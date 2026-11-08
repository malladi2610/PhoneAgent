package com.google.ai.edge.gallery.phoneagent

import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {
  private val gallery = "Step 1: find a relevant skill. Step 4: if none, output \"No relevant skill\"."
  private val preamble = "You are PhoneAgent."

  @Test
  fun phoneInstructionsComeBeforeGalleryPrompt_andTakePriority() {
    val prompt = OllamaCloudModelHelper.composeSystemPrompt(gallery, preamble)
    val phoneAt = prompt.indexOf(preamble)
    val galleryAt = prompt.indexOf(gallery)
    assertTrue(phoneAt in 0 until galleryAt)
    assertTrue(prompt.contains("take priority over the skill instructions"))
  }

  @Test
  fun emptyGalleryPrompt_isJustPhoneInstructions() {
    val prompt = OllamaCloudModelHelper.composeSystemPrompt("  ", preamble)
    assertTrue(prompt.startsWith(preamble))
    assertTrue(!prompt.contains("skill instructions below"))
  }
}
