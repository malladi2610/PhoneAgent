package com.google.ai.edge.gallery.phoneagent

import com.google.ai.edge.litertlm.ToolManager
import com.google.gson.JsonParser
import com.malladi2610.phoneagent.core.ChatTurnInfo
import com.malladi2610.phoneagent.core.ollama.LiteRtToolSpecs
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Exposes every tool Gallery hands the runtime (Agent Chat's skills/MCP/intent tools plus the
 * phone-tool bridge) as a pa-core [ToolRegistry], executing through LiteRT-LM's synchronous
 * [ToolManager].
 *
 * [ToolManager] carries no per-call context, so [ChatTurnInfo] is set before each call; the
 * phone-tool bridge reads it to build its ToolContext and ActionLog entry. Gating (confirm, unlock)
 * happens inside the bridge, not here.
 */
class LiteRtToolRegistry(private val toolManager: ToolManager) : ToolRegistry {
  private val specs: List<ToolSpec> by lazy {
    LiteRtToolSpecs.parse(toolManager.getToolsDescription().toString())
  }

  override fun specs(): List<ToolSpec> = specs

  override suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolOutcome {
    ChatTurnInfo.turnId = ctx.turnId
    ChatTurnInfo.userRequest = ctx.userRequest
    ChatTurnInfo.model = ctx.model
    ChatTurnInfo.step = ctx.step
    return try {
      val gsonArgs = JsonParser.parseString(args.toString()).asJsonObject
      // Tools block (the bridge uses runBlocking, MCP does network I/O), so keep them off the
      // caller's dispatcher. A running tool is not interrupted; cancellation applies after it.
      val result = withContext(Dispatchers.IO) { toolManager.execute(name, gsonArgs) }
      toOutcome(Json.parseToJsonElement(result.toString()))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Includes LiteRT's IllegalArgumentException("Tool not found: <name>").
      ToolOutcome.Error(e.message ?: e::class.java.simpleName)
    }
  }

  internal companion object {
    /** LiteRT-LM 0.18.1 returns this string (not JSON) when a tool throws. */
    private const val LITERT_ERROR_PREFIX = "Error occured."

    fun toOutcome(result: JsonElement): ToolOutcome =
      when (result) {
        is JsonObject -> fromStatusObject(result)
        is JsonPrimitive ->
          if (result.isString) {
            val text = result.content
            if (text.startsWith(LITERT_ERROR_PREFIX)) {
              ToolOutcome.Error(text)
            } else {
              val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
              if (parsed is JsonObject) fromStatusObject(parsed)
              else ToolOutcome.Ok(JsonObject(mapOf("result" to result)))
            }
          } else {
            ToolOutcome.Ok(JsonObject(mapOf("result" to result)))
          }
        else -> ToolOutcome.Ok(JsonObject(mapOf("result" to result)))
      }

    /** Maps the `{"status": ...}` JSON produced by [ToolOutcome.toJson] back to the outcome. */
    private fun fromStatusObject(obj: JsonObject): ToolOutcome {
      fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
      return when (str("status")) {
        "ok" -> ToolOutcome.Ok(JsonObject(obj - "status"))
        "error" -> ToolOutcome.Error(str("reason") ?: obj.toString())
        "denied_by_user" -> ToolOutcome.DeniedByUser
        "needs_unlock" -> ToolOutcome.NeedsUnlock
        "permission_needed" ->
          ToolOutcome.PermissionNeeded(str("permission") ?: "", str("action") == "opened_settings")
        // Gallery's own tools (skills, MCP) return arbitrary JSON; pass it through untouched.
        else -> ToolOutcome.Ok(obj)
      }
    }
  }
}
