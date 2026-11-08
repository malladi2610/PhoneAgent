package com.malladi2610.phoneagent.core.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPolicyTest {
  @Test
  fun safeToolsRunEverywhere() {
    for (surface in Surface.entries) {
      assertEquals(Gate.RUN, gateFor(ToolRisk.SAFE, surface))
    }
  }

  @Test
  fun unlockedSurfacesFollowTheConfirmationPolicy() {
    for (surface in listOf(Surface.CHAT, Surface.VOICE_UNLOCKED)) {
      assertEquals(Gate.RUN, gateFor(ToolRisk.SENSITIVE_READ, surface))
      // The pre-filled screen (WhatsApp/email/contact/calendar) is the confirmation.
      assertEquals(Gate.RUN, gateFor(ToolRisk.OUTWARD_PREFILLED, surface))
      assertEquals(Gate.CONFIRM, gateFor(ToolRisk.OUTWARD_INSTANT, surface))
    }
  }

  @Test
  fun lockScreenRequiresUnlockForAnythingPersonalOrOutward() {
    assertEquals(Gate.UNLOCK_THEN_RUN, gateFor(ToolRisk.SENSITIVE_READ, Surface.VOICE_LOCKED))
    assertEquals(Gate.UNLOCK_THEN_RUN, gateFor(ToolRisk.OUTWARD_PREFILLED, Surface.VOICE_LOCKED))
    assertEquals(Gate.UNLOCK_THEN_CONFIRM, gateFor(ToolRisk.OUTWARD_INSTANT, Surface.VOICE_LOCKED))
  }
}
