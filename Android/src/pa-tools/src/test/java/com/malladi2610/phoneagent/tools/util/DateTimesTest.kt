package com.malladi2610.phoneagent.tools.util

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateTimesTest {
  private val berlin = ZoneId.of("Europe/Berlin")

  @Test
  fun parsesLocalDateTime_inGivenZone() {
    val expected = LocalDateTime.of(2026, 11, 8, 9, 30).atZone(berlin).toInstant().toEpochMilli()
    assertEquals(expected, DateTimes.parseToEpochMillis("2026-11-08T09:30", berlin))
    assertEquals(expected, DateTimes.parseToEpochMillis("2026-11-08T09:30:00", berlin))
    assertEquals(expected, DateTimes.parseToEpochMillis("2026-11-08 09:30", berlin))
  }

  @Test
  fun honoursExplicitOffset() {
    val expected = LocalDateTime.of(2026, 11, 8, 9, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
    assertEquals(expected, DateTimes.parseToEpochMillis("2026-11-08T09:30:00Z", berlin))
  }

  @Test
  fun dateOnly_isStartOfDay() {
    val expected = LocalDateTime.of(2026, 11, 8, 0, 0).atZone(berlin).toInstant().toEpochMilli()
    assertEquals(expected, DateTimes.parseToEpochMillis("2026-11-08", berlin))
  }

  @Test
  fun garbage_isNull() {
    assertNull(DateTimes.parseToEpochMillis("tomorrow at 9", berlin))
  }
}
