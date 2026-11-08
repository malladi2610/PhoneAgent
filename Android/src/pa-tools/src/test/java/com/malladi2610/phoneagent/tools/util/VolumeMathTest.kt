package com.malladi2610.phoneagent.tools.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMathTest {
  @Test
  fun percentToIndex_rounds() {
    assertEquals(5, VolumeMath.percentToIndex(30, min = 0, max = 15))
    assertEquals(15, VolumeMath.percentToIndex(100, min = 0, max = 15))
    assertEquals(0, VolumeMath.percentToIndex(0, min = 0, max = 15))
  }

  @Test
  fun percentToIndex_clampsPercent() {
    assertEquals(15, VolumeMath.percentToIndex(250, min = 0, max = 15))
    assertEquals(1, VolumeMath.percentToIndex(-10, min = 1, max = 7))
  }

  @Test
  fun percentToIndex_respectsMinimum() {
    assertEquals(4, VolumeMath.percentToIndex(50, min = 1, max = 7))
  }

  @Test
  fun indexToPercent_roundTrips() {
    assertEquals(33, VolumeMath.indexToPercent(5, min = 0, max = 15))
    assertEquals(0, VolumeMath.indexToPercent(3, min = 3, max = 3))
  }
}
