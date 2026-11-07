package com.malladi2610.phoneagent.tools

import com.malladi2610.phoneagent.tools.util.BatteryState
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryStateTest {
  // Constants mirror android.os.BatteryManager (unit tests run without the Android framework).
  private val charging = 2
  private val discharging = 3
  private val full = 5
  private val pluggedAc = 1

  @Test
  fun oneUi_acPluggedAndStatusCharging_isCharging_evenWhenIsChargingApiSaysFalse() {
    val s = BatteryState.from(status = charging, plugged = pluggedAc, isChargingApi = false)
    assertEquals(true, s.charging)
    assertEquals("ac", s.powerSource)
  }

  @Test
  fun fullWhilePlugged_isCharging() {
    assertEquals(true, BatteryState.from(full, pluggedAc, isChargingApi = false).charging)
  }

  @Test
  fun unplugged_isNotCharging() {
    val s = BatteryState.from(status = discharging, plugged = 0, isChargingApi = false)
    assertEquals(false, s.charging)
    assertEquals("none", s.powerSource)
  }
}
