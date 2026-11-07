package com.malladi2610.phoneagent.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How risky a tool is. Together with the [Surface], this decides whether the tool runs straight
 * away, needs a confirmation, or needs the phone unlocked first ([gateFor]).
 */
enum class ToolRisk {
  /** Harmless or read-only device actions: torch, volume, timer, battery status. */
  SAFE,
  /** Reads personal data (contacts). Runs without asking, but never from the lock screen. */
  SENSITIVE_READ,
  /** Opens another app pre-filled (WhatsApp, email, contact, calendar); the user presses Send/Save. */
  OUTWARD_PREFILLED,
  /** Acts on the outside world instantly (call, SMS). Always needs an explicit confirmation. */
  OUTWARD_INSTANT,
}

/** Where the request came from. */
enum class Surface {
  /** Typed into Agent Chat (phone is unlocked by definition). */
  CHAT,
  /** Voice turn while the phone is unlocked. */
  VOICE_UNLOCKED,
  /** Voice turn on the lock screen. */
  VOICE_LOCKED,
}

/** Model-facing description of a tool. [parameters] is a JSON Schema object. */
data class ToolSpec(
  val name: String,
  val description: String,
  val parameters: JsonObject,
  val risk: ToolRisk,
)

/** Result of a tool call. [toJson] is what the model sees as the tool result. */
sealed interface ToolOutcome {
  fun toJson(): JsonObject

  data class Ok(val data: JsonObject = JsonObject(emptyMap())) : ToolOutcome {
    override fun toJson() = JsonObject(mapOf("status" to JsonPrimitive("ok")) + data)
  }

  data class Error(val reason: String) : ToolOutcome {
    override fun toJson() = buildJsonObject {
      put("status", "error")
      put("reason", reason)
    }
  }

  /**
   * A permission is missing. [openedSettings] is true when the tool opened the matching settings
   * page (special permissions like WRITE_SETTINGS or DND access).
   */
  data class PermissionNeeded(val permission: String, val openedSettings: Boolean) : ToolOutcome {
    override fun toJson() = buildJsonObject {
      put("status", "permission_needed")
      put("permission", permission)
      put("action", if (openedSettings) "opened_settings" else "asked_user")
    }
  }

  data object DeniedByUser : ToolOutcome {
    override fun toJson() = buildJsonObject { put("status", "denied_by_user") }
  }

  /** The user didn't unlock the phone, so a lock-screen-gated tool did not run. */
  data object NeedsUnlock : ToolOutcome {
    override fun toJson() = buildJsonObject {
      put("status", "needs_unlock")
      put("reason", "The phone is locked; ask the user to unlock and try again.")
    }
  }
}

/** UI hooks a tool run may need. Each surface (chat, voice, lock screen) provides its own. */
interface ToolUi {
  /** Shows a confirm card with [summary]; returns true on Allow. */
  suspend fun confirm(summary: String): Boolean

  /** Shows the system prompt for a runtime [permission] (e.g. android.permission.SEND_SMS). */
  suspend fun requestRuntimePermission(permission: String): Boolean

  /** Asks the user to unlock the phone; returns true once unlocked. */
  suspend fun requestUnlock(): Boolean
}

/** Per-call context handed to a tool. */
data class ToolContext(
  val surface: Surface,
  val turnId: String,
  val userRequest: String,
  val model: String,
  val step: Int,
  val ui: ToolUi,
)

/** One phone capability. Implementations must not throw; return [ToolOutcome.Error] instead. */
interface PhoneTool {
  val spec: ToolSpec

  suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome

  /** Human-readable text for the confirm card, e.g. "Send SMS to +49 151 …: 'Running late'". */
  fun confirmSummary(args: JsonObject): String = "${spec.name} $args"
}

/**
 * The set of tools an agent turn can use. The phone-tools implementation applies [gateFor],
 * shows confirm/unlock prompts via [ToolContext.ui], runs the tool and writes the ActionLog.
 * The chat path also wraps Gallery's own tools (skills, MCP, intents) in this interface.
 */
interface ToolRegistry {
  fun specs(): List<ToolSpec>

  suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolOutcome
}
