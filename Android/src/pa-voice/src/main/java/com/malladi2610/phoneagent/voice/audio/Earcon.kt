package com.malladi2610.phoneagent.voice.audio

import kotlin.math.PI
import kotlin.math.sin

/** The short two-tone "listening" chime, synthesised (no asset). */
object Earcon {
  const val SAMPLE_RATE = 24_000

  /** ~180 ms: 880 Hz then 1320 Hz, with a short fade to avoid clicks. PCM16 mono LE. */
  val listening: ByteArray by lazy { tones(listOf(880.0 to 90, 1320.0 to 90)) }

  private fun tones(parts: List<Pair<Double, Int>>): ByteArray {
    val samples = parts.sumOf { it.second * SAMPLE_RATE / 1000 }
    val out = ByteArray(samples * 2)
    var i = 0
    for ((freq, ms) in parts) {
      val n = ms * SAMPLE_RATE / 1000
      val fade = SAMPLE_RATE / 200 // 5 ms
      for (k in 0 until n) {
        val env = minOf(1.0, k / fade.toDouble(), (n - k) / fade.toDouble())
        val v = (sin(2 * PI * freq * k / SAMPLE_RATE) * env * 0.35 * Short.MAX_VALUE).toInt()
        out[2 * i] = (v and 0xFF).toByte()
        out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        i++
      }
    }
    return out
  }

  suspend fun play(player: WavPlayer) = player.playPcm16(listening, SAMPLE_RATE, 1)
}
