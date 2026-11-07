package com.malladi2610.phoneagent.tools.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsAppLinkTest {
  @Test
  fun build_stripsFormatting_andPercentEncodesSpaces() {
    assertEquals(
      "https://wa.me/491512345678?text=see%20you%20at%207",
      WhatsAppLink.build("+49 151-234 5678", "see you at 7"),
    )
  }

  @Test
  fun build_encodesSpecialCharacters() {
    assertEquals(
      "https://wa.me/15551234?text=a%26b%3Fc%2B%C3%A9",
      WhatsAppLink.build("+1 (555) 1234", "a&b?c+é"),
    )
  }

  @Test
  fun build_emptyText_omitsQuery() {
    assertEquals("https://wa.me/4915123", WhatsAppLink.build("+49 151 23", ""))
  }
}
