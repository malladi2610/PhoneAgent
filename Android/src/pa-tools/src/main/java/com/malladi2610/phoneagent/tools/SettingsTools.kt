package com.malladi2610.phoneagent.tools

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.tools.util.VolumeMath
import com.malladi2610.phoneagent.tools.util.bool
import com.malladi2610.phoneagent.tools.util.int
import com.malladi2610.phoneagent.tools.util.str
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val PERMISSION_WRITE_SETTINGS = Manifest.permission.WRITE_SETTINGS
internal const val PERMISSION_DND_ACCESS = Manifest.permission.ACCESS_NOTIFICATION_POLICY

internal fun dndAccessSettingsIntent() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

internal fun writeSettingsIntent(context: Context) =
  Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

internal class SetFlashlightTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec("set_flashlight", "Turn the flashlight (torch) on or off.", ToolRisk.SAFE) {
      boolean("on", "true to turn the torch on, false to turn it off", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val on = args.bool("on")!!
    val cm = context.getSystemService(CameraManager::class.java)
    val ids = cm.cameraIdList.filter {
      cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    val id =
      ids.firstOrNull {
        cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
          CameraCharacteristics.LENS_FACING_BACK
      } ?: ids.firstOrNull() ?: return ToolOutcome.Error("this phone has no flashlight")
    cm.setTorchMode(id, on)
    return ToolOutcome.Ok(buildJsonObject { put("on", on) })
  }
}

internal class SetVolumeTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec("set_volume", "Set a volume level in percent (0-100).", ToolRisk.SAFE) {
      string(
        "stream",
        "which volume to change",
        required = true,
        enum = STREAMS.keys.toList(),
      )
      integer("percent", "volume level 0-100", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val name = args.str("stream")!!
    val stream = STREAMS[name] ?: return ToolOutcome.Error("unknown stream '$name'")
    val am = context.getSystemService(AudioManager::class.java)
    val min = am.getStreamMinVolume(stream)
    val max = am.getStreamMaxVolume(stream)
    val index = VolumeMath.percentToIndex(args.int("percent")!!, min, max)
    try {
      am.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)
    } catch (e: SecurityException) {
      // Changing ring/notification volume while DND is on needs notification-policy access.
      val nm = context.getSystemService(NotificationManager::class.java)
      if (!nm.isNotificationPolicyAccessGranted) {
        return openSettingsFor(PERMISSION_DND_ACCESS, dndAccessSettingsIntent())
      }
      throw e
    }
    val actual = am.getStreamVolume(stream)
    return ToolOutcome.Ok(
      buildJsonObject {
        put("stream", name)
        put("percent", VolumeMath.indexToPercent(actual, min, max))
      }
    )
  }
}

internal class SetBrightnessTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "set_brightness",
      "Set screen brightness in percent (0-100); turns off auto-brightness.",
      ToolRisk.SAFE,
    ) {
      integer("percent", "brightness 0-100", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    if (!Settings.System.canWrite(context)) {
      return openSettingsFor(PERMISSION_WRITE_SETTINGS, writeSettingsIntent(context))
    }
    val percent = args.int("percent")!!.coerceIn(0, 100)
    val value = (percent * 255 / 100.0).roundToInt().coerceIn(1, 255)
    val cr = context.contentResolver
    Settings.System.putInt(
      cr,
      Settings.System.SCREEN_BRIGHTNESS_MODE,
      Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    )
    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, value)
    return ToolOutcome.Ok(buildJsonObject { put("percent", percent) })
  }
}

internal class SetDoNotDisturbTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec("set_do_not_disturb", "Turn Do Not Disturb on or off.", ToolRisk.SAFE) {
      boolean("on", "true to turn Do Not Disturb on", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (!nm.isNotificationPolicyAccessGranted) {
      return openSettingsFor(PERMISSION_DND_ACCESS, dndAccessSettingsIntent())
    }
    val on = args.bool("on")!!
    nm.setInterruptionFilter(
      if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
      else NotificationManager.INTERRUPTION_FILTER_ALL
    )
    return ToolOutcome.Ok(buildJsonObject { put("on", on) })
  }
}

internal class OpenSettingsPanelTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "open_settings_panel",
      "Open a quick settings panel so the user can change it (apps can't toggle these directly).",
      ToolRisk.SAFE,
    ) {
      string("panel", "which panel", required = true, enum = PANELS.keys.toList())
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val panel = args.str("panel")!!
    val actions = PANELS[panel] ?: return ToolOutcome.Error("unknown panel '$panel'")
    // Try the floating panel first, then the full settings screen.
    var lastError: ToolOutcome.Error? = null
    for (action in actions) {
      lastError = launch(Intent(action), "the $panel settings") ?: return ToolOutcome.Ok(
        buildJsonObject { put("opened", panel) }
      )
    }
    return lastError ?: ToolOutcome.Error("couldn't open the $panel settings")
  }

  private companion object {
    @Suppress("DEPRECATION")
    val PANELS =
      linkedMapOf(
        "wifi" to listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS),
        "bluetooth" to listOf(Settings.ACTION_BLUETOOTH_SETTINGS),
        "internet" to
          listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS),
        "nfc" to listOf(Settings.Panel.ACTION_NFC, Settings.ACTION_NFC_SETTINGS),
        "volume" to listOf(Settings.Panel.ACTION_VOLUME, Settings.ACTION_SOUND_SETTINGS),
      )
  }
}
