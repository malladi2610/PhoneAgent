package com.malladi2610.phoneagent.core.secrets

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeysTest {
  @Test
  fun normalize_removesInternalAndSurroundingWhitespace() {
    assertEquals("abc123.def456", ApiKeys.normalize("  abc123\n.def 456\t"))
  }

  @Test
  fun normalize_removesZeroWidthAndNonBreakingSpaces() {
    assertEquals("abc.def", ApiKeys.normalize("abc​. def﻿"))
  }

  @Test
  fun normalize_leavesCleanKeyUntouched() {
    assertEquals("0123abcd.ZyX_-9", ApiKeys.normalize("0123abcd.ZyX_-9"))
  }
}
