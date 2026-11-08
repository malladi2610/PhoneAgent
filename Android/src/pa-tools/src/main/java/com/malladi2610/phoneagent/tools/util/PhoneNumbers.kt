package com.malladi2610.phoneagent.tools.util

object PhoneNumbers {
  /** Keeps a leading `+` (after trimming) and the digits; drops everything else. */
  fun normalize(raw: String): String {
    val trimmed = raw.trim()
    val digits = trimmed.filter { it.isDigit() }
    if (digits.isEmpty()) return ""
    return if (trimmed.startsWith("+")) "+$digits" else digits
  }

  /** True when [raw] has at least 3 digits (enough for short codes like 112). */
  fun isDialable(raw: String): Boolean = normalize(raw).count { it.isDigit() } >= 3
}
