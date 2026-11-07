package com.malladi2610.phoneagent.tools

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.tools.util.VolumeMath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

internal class GetDeviceStatusTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      name = "get_device_status",
      description =
        "Read the phone's current status: battery level and charging, network (wifi/cellular), " +
          "ringer mode, volume levels in percent, Do Not Disturb, and screen brightness.",
      risk = ToolRisk.SAFE,
    )

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome =
    ToolOutcome.Ok(
      buildJsonObject {
        // Each section is independent: one failing reading must not hide the others.
        section("battery") {
          val bm = context.getSystemService(BatteryManager::class.java)
          put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
          put("charging", bm.isCharging)
        }
        section("network") {
          val cm = context.getSystemService(ConnectivityManager::class.java)
          val caps = cm.getNetworkCapabilities(cm.activeNetwork)
          put(
            "type",
            when {
              caps == null -> "none"
              caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
              caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
              caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
              else -> "other"
            },
          )
          put(
            "internet",
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
          )
        }
        section("sound") {
          val am = context.getSystemService(AudioManager::class.java)
          put(
            "ringer_mode",
            when (am.ringerMode) {
              AudioManager.RINGER_MODE_SILENT -> "silent"
              AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
              else -> "normal"
            },
          )
          putJsonObject("volume_percent") {
            for ((name, stream) in STREAMS) {
              put(
                name,
                VolumeMath.indexToPercent(
                  am.getStreamVolume(stream),
                  am.getStreamMinVolume(stream),
                  am.getStreamMaxVolume(stream),
                ),
              )
            }
          }
        }
        section("do_not_disturb") {
          val nm = context.getSystemService(NotificationManager::class.java)
          put("on", nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        section("screen") {
          val cr = context.contentResolver
          val raw = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS)
          put("brightness_percent", (raw * 100.0 / 255).roundToInt().coerceIn(0, 100))
          put(
            "auto_brightness",
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
              Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
          )
        }
      }
    )

  private inline fun JsonObjectBuilder.section(name: String, block: JsonObjectBuilder.() -> Unit) {
    val part = runCatching { buildJsonObject(block) }
    put(name, part.getOrElse { buildJsonObject { put("error", "unavailable") } })
  }
}

/** Model-facing stream names → AudioManager streams (shared with set_volume). */
internal val STREAMS =
  linkedMapOf(
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "alarm" to AudioManager.STREAM_ALARM,
    "notification" to AudioManager.STREAM_NOTIFICATION,
  )
