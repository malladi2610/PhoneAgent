package com.malladi2610.phoneagent.tools.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Parses the ISO-8601 date-times models emit for calendar events. */
object DateTimes {
  /**
   * Accepts `2026-11-08T09:30[:00]` (local, in [zone]), the same with a space instead of `T`, an
   * explicit offset (`Z`, `+01:00`), or a bare date (start of day). Returns null otherwise.
   */
  fun parseToEpochMillis(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val s = text.trim()
    runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
    runCatching {
      return LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()
    }
    runCatching { return LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli() }
    return null
  }
}
