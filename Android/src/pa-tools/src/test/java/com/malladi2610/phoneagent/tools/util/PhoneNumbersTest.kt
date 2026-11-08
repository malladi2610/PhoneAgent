package com.malladi2610.phoneagent.tools.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
  @Test
  fun normalize_keepsLeadingPlusAndDigits() {
    assertEquals("+491512345678", PhoneNumbers.normalize(" +49 (151) 234-5678 "))
  }

  @Test
  fun normalize_dropsInnerPlus() {
    assertEquals("0151234", PhoneNumbers.normalize("0151+234"))
  }

  @Test
  fun normalize_noDigits_isEmpty() {
    assertEquals("", PhoneNumbers.normalize("mom"))
  }

  @Test
  fun isDialable_needsThreeDigits() {
    assertTrue(PhoneNumbers.isDialable("112"))
    assertTrue(PhoneNumbers.isDialable("+49 151 2345"))
    assertFalse(PhoneNumbers.isDialable("12"))
    assertFalse(PhoneNumbers.isDialable("Mom"))
  }
}
