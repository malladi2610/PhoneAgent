package com.malladi2610.phoneagent.voice.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavTest {
  private fun wav(
    pcm: ByteArray,
    sampleRate: Int = 24_000,
    channels: Int = 1,
    extraChunk: Boolean = false,
    dataSize: Int = pcm.size,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    out.write("RIFF".toByteArray()); out.write(le32(36 + pcm.size)); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); out.write(le32(16)); out.write(le16(1)); out.write(le16(channels))
    out.write(le32(sampleRate)); out.write(le32(sampleRate * channels * 2)); out.write(le16(channels * 2)); out.write(le16(16))
    if (extraChunk) {
      out.write("LIST".toByteArray()); out.write(le32(4)); out.write("INFO".toByteArray())
    }
    out.write("data".toByteArray()); out.write(le32(dataSize)); out.write(pcm)
    return out.toByteArray()
  }

  @Test
  fun parsesCanonicalHeader() {
    val pcm = ByteArray(480) { it.toByte() }

    val parsed = Wav.parse(wav(pcm))!!

    assertEquals(24_000, parsed.sampleRate)
    assertEquals(1, parsed.channels)
    assertEquals(16, parsed.bitsPerSample)
    assertArrayEquals(pcm, parsed.pcm)
  }

  @Test
  fun skipsExtraChunksBeforeData() {
    val pcm = ByteArray(10) { 7 }

    val parsed = Wav.parse(wav(pcm, sampleRate = 22_050, channels = 2, extraChunk = true))!!

    assertEquals(22_050, parsed.sampleRate)
    assertEquals(2, parsed.channels)
    assertArrayEquals(pcm, parsed.pcm)
  }

  @Test
  fun streamingDataSize_isClampedToAvailableBytes() {
    val pcm = ByteArray(100) { 3 }

    val parsed = Wav.parse(wav(pcm, dataSize = -1))!! // 0xFFFFFFFF

    assertArrayEquals(pcm, parsed.pcm)
  }

  @Test
  fun notAWav_returnsNull() {
    assertNull(Wav.parse(byteArrayOf(1, 2, 3)))
    assertNull(Wav.parse("ID3 not a wav file at all, padding padding padding".toByteArray()))
  }
}
