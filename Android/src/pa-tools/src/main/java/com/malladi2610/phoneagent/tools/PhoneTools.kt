package com.malladi2610.phoneagent.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.malladi2610.phoneagent.core.tool.PhoneTool
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.core.tool.ToolSpec
import com.malladi2610.phoneagent.tools.util.ToolSchema
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * All M1 phone tools (spec §5.3). Wrap them in a [PhoneToolRunner] for gating and logging:
 * `PhoneToolRunner(createPhoneTools(context), JsonlActionLog(File(filesDir, "action_log"), …))`.
 */
fun createPhoneTools(context: Context): List<PhoneTool> {
  val app = context.applicationContext ?: context
  return listOf(
    // Status
    GetDeviceStatusTool(app),
    // Settings
    SetFlashlightTool(app),
    SetVolumeTool(app),
    SetBrightnessTool(app),
    SetDoNotDisturbTool(app),
    OpenSettingsPanelTool(app),
    // Apps & time
    OpenAppTool(app),
    SetAlarmTool(app),
    StartTimerTool(app),
    ShowLocationOnMapTool(app),
    // Communication
    FindContactTool(app),
    CallTool(app),
    SendSmsTool(app),
    SendWhatsAppTool(app),
    SendEmailTool(app),
    CreateContactTool(app),
    CreateCalendarEventTool(app),
  )
}

/** Base for the phone tools: [run] never throws (cancellation excepted). */
internal abstract class BasePhoneTool(protected val context: Context) : PhoneTool {
  final override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome =
    try {
      execute(args, ctx)
    } catch (e: CancellationException) {
      throw e
    } catch (e: SecurityException) {
      ToolOutcome.Error("not allowed: ${e.message ?: "security exception"}")
    } catch (t: Throwable) {
      ToolOutcome.Error(t.message ?: t.javaClass.simpleName)
    }

  protected abstract suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome

  /**
   * Starts an activity from a tool. Returns null on success, or an error the model can relay.
   * Note: Android may silently drop a launch from the background (no exception); the chat and
   * voice-session surfaces are foreground, so this should only bite headless voice turns.
   */
  protected fun launch(intent: Intent, what: String): ToolOutcome.Error? =
    try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      null
    } catch (e: ActivityNotFoundException) {
      ToolOutcome.Error("no app on this phone can open $what")
    } catch (e: Exception) {
      ToolOutcome.Error("couldn't open $what from the background")
    }

  /**
   * Ensures a runtime [permission], asking via [ToolContext.ui] when missing. Returns null when
   * granted, or [ToolOutcome.PermissionNeeded] (not opened settings) when still denied.
   */
  protected suspend fun ensurePermission(
    permission: String,
    ctx: ToolContext,
  ): ToolOutcome.PermissionNeeded? {
    if (isGranted(permission)) return null
    val granted = runCatching { ctx.ui.requestRuntimePermission(permission) }.getOrDefault(false)
    return if (granted && isGranted(permission)) null
    else ToolOutcome.PermissionNeeded(permission, openedSettings = false)
  }

  /** Opens a special-permission settings page and reports [permission] as needed. */
  protected fun openSettingsFor(permission: String, intent: Intent): ToolOutcome {
    val opened = launch(intent, "the settings page") == null
    return ToolOutcome.PermissionNeeded(permission, openedSettings = opened)
  }

  private fun isGranted(permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

internal fun toolSpec(
  name: String,
  description: String,
  risk: ToolRisk,
  schema: ToolSchema.() -> Unit = {},
) = ToolSpec(name, description, ToolSchema.obj(schema), risk)
