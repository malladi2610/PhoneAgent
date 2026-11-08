package com.malladi2610.phoneagent.tools.util

/**
 * Charging state from the ACTION_BATTERY_CHANGED status/plug extras. `BatteryManager.isCharging`
 * alone is unreliable on One UI: it returned false on the S23 while on AC power and gaining charge.
 */
data class BatteryState(val charging: Boolean, val powerSource: String) {
  companion object {
    // android.os.BatteryManager values.
    private const val STATUS_CHARGING = 2
    private const val STATUS_FULL = 5
    private const val PLUGGED_AC = 1
    private const val PLUGGED_USB = 2
    private const val PLUGGED_WIRELESS = 4
    private const val PLUGGED_DOCK = 8

    fun from(status: Int, plugged: Int, isChargingApi: Boolean): BatteryState {
      val source =
        when {
          plugged and PLUGGED_AC != 0 -> "ac"
          plugged and PLUGGED_USB != 0 -> "usb"
          plugged and PLUGGED_WIRELESS != 0 -> "wireless"
          plugged and PLUGGED_DOCK != 0 -> "dock"
          else -> "none"
        }
      val charging =
        isChargingApi || status == STATUS_CHARGING || (status == STATUS_FULL && plugged != 0)
      return BatteryState(charging, source)
    }
  }
}
