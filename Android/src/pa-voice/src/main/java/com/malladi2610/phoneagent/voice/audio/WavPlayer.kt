package com.malladi2610.phoneagent.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Minimal RIFF/WAVE parser (PCM16 only is played). */
object Wav {
  class Parsed(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val pcm: ByteArray)

  fun parse(bytes: ByteArray): Parsed? {
    if (bytes.size < 12) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") return null
    var pos = 12
    var sampleRate = 0
    var channels = 0
    var bits = 0
    while (pos + 8 <= bytes.size) {
      val id = ascii(bytes, pos)
      val size = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL
      val body = pos + 8
      when (id) {
        "fmt " -> {
          if (body + 16 > bytes.size) return null
          channels = buf.getShort(body + 2).toInt()
          sampleRate = buf.getInt(body + 4)
          bits = buf.getShort(body + 14).toInt()
        }
        "data" -> {
          if (sampleRate == 0) return null
          val end = minOf(bytes.size.toLong(), body + size).toInt()
          return Parsed(sampleRate, channels, bits, bytes.copyOfRange(body, end))
        }
      }
      pos = (body + size + (size and 1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return null
  }

  private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)
}

/** Plays PCM16 WAV bytes through AudioTrack (assistant usage); suspends until playback ends. */
class WavPlayer(context: Context) {
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val attributes =
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ASSISTANT)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

  suspend fun play(wavBytes: ByteArray) {
    val wav = Wav.parse(wavBytes) ?: error("TTS audio is not a WAV file")
    require(wav.bitsPerSample == 16) { "unsupported WAV bits=${wav.bitsPerSample}" }
    playPcm16(wav.pcm, wav.sampleRate, wav.channels)
  }

  suspend fun playPcm16(pcm: ByteArray, sampleRate: Int, channels: Int = 1) =
    withContext(Dispatchers.IO) {
      val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
      val format =
        AudioFormat.Builder()
          .setSampleRate(sampleRate)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(channelMask)
          .build()
      val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
      val track =
        AudioTrack.Builder()
          .setAudioAttributes(attributes)
          .setAudioFormat(format)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .setBufferSizeInBytes(maxOf(minBuf, 8192))
          .build()
      val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
      audioManager?.requestAudioFocus(focus)
      try {
        track.play()
        var off = 0
        while (off < pcm.size) {
          ensureActive()
          val n = track.write(pcm, off, minOf(4096, pcm.size - off))
          if (n < 0) break
          off += n
        }
        val totalFrames = pcm.size / (2 * channels)
        val maxWaitMs = totalFrames * 1000L / sampleRate + 1_000
        var waited = 0L
        while (track.playbackHeadPosition < totalFrames && waited < maxWaitMs) {
          delay(20)
          waited += 20
        }
      } finally {
        runCatching { track.stop() }
        track.release()
        audioManager?.abandonAudioFocusRequest(focus)
      }
    }
}
