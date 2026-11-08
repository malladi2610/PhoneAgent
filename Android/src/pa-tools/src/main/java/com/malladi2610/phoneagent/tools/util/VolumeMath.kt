package com.malladi2610.phoneagent.tools.util

import kotlin.math.roundToInt

/** Maps between a 0–100 percent and an AudioManager stream's [min, max] index range. */
object VolumeMath {
  fun percentToIndex(percent: Int, min: Int, max: Int): Int {
    val p = percent.coerceIn(0, 100)
    return min + ((max - min) * p / 100.0).roundToInt()
  }

  fun indexToPercent(index: Int, min: Int, max: Int): Int {
    if (max <= min) return 0
    return ((index - min).coerceIn(0, max - min) * 100.0 / (max - min)).roundToInt()
  }
}
