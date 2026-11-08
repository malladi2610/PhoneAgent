package com.malladi2610.phoneagent.wakeword

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordStoreTest {
  private val dir: File = Files.createTempDirectory("pa-wakeword").toFile()

  private fun frames(n: Int, seed: Float) = List(n) { k -> FloatArray(SpeechEmbeddingStream.DIM) { seed + k + it * 0.01f } }

  private fun enrollment() =
    Enrollment(
      "Hey Friday",
      listOf(frames(12, 1f), frames(14, 2f), frames(11, 3f)),
      0.093f,
      VoiceProfile(listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0.9f, 0.1f, 0f))),
    )

  /** What the keyword-spotter build saved: phrase.txt (phrase + keyword line) and profile.bin. */
  private fun writeLegacy() {
    dir.mkdirs()
    File(dir, "phrase.txt").writeText("Hey Friday\nHH EY1 F R AY1 D EY2 :1.5 #0.08 @HEY_FRIDAY\n")
    VoiceProfile(listOf(floatArrayOf(1f, 0f))).save(File(dir, "profile.bin"))
  }

  @Test
  fun roundTrip() {
    val e = enrollment()
    WakeWordStore.save(dir, e)
    val back = WakeWordStore.load(dir)!!
    assertEquals("Hey Friday", back.phrase)
    assertEquals(0.093f, back.threshold, 0f)
    assertEquals(listOf(12, 14, 11), back.templates.map { it.size })
    for (t in e.templates.indices) for (k in e.templates[t].indices) {
      assertArrayEquals(e.templates[t][k], back.templates[t][k], 0f)
    }
    assertEquals(2, back.profile.size)
    assertEquals(e.profile.threshold(), back.profile.threshold(), 1e-6f)
  }

  @Test
  fun nothingSaved_isNotEnrolled() = assertNull(WakeWordStore.load(dir))

  @Test
  fun legacyKeywordSpotterEnrollment_isNotEnrolled_andIsRemovedOnSave() {
    writeLegacy()
    assertNull(WakeWordStore.load(dir))
    WakeWordStore.save(dir, enrollment())
    assertFalse(File(dir, "phrase.txt").exists())
    assertFalse(File(dir, "profile.bin").exists())
    assertNotNull(WakeWordStore.load(dir))
  }

  @Test
  fun corruptOrTruncated_isNotEnrolled() {
    WakeWordStore.save(dir, enrollment())
    val file = File(dir, "enrollment.bin")
    val bytes = file.readBytes()
    file.writeBytes(bytes.copyOf(bytes.size - 5))
    assertNull(WakeWordStore.load(dir))
    file.writeBytes(bytes + byteArrayOf(1))
    assertNull(WakeWordStore.load(dir))
    file.writeBytes(bytes.copyOf().also { it[7] = 9 }) // version 9
    assertNull(WakeWordStore.load(dir))
    file.writeBytes(ByteArray(0))
    assertNull(WakeWordStore.load(dir))
    file.writeBytes(bytes)
    assertTrue(WakeWordStore.load(dir) != null)
  }

  @Test
  fun invalidContent_isNotEnrolled() {
    // A threshold outside (0, 1) is rejected by Enrollment's checks at load time.
    WakeWordStore.save(dir, enrollment())
    val file = File(dir, "enrollment.bin")
    val bytes = file.readBytes()
    // Layout: magic(4) version(4) UTF(2 + 10) threshold(4)…
    val bad = bytes.copyOf()
    java.nio.ByteBuffer.wrap(bad).putFloat(20, 5f)
    file.writeBytes(bad)
    assertNull(WakeWordStore.load(dir))
  }
}
