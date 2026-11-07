package com.malladi2610.phoneagent.wakeword

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.io.IOException

/**
 * 16 kHz mono PCM16 microphone capture (VOICE_RECOGNITION source), read in 100 ms chunks and
 * converted to floats in [-1, 1]. The caller must hold RECORD_AUDIO.
 */
internal class MicCapture private constructor(private val record: AudioRecord) : Closeable {
  private val shorts = ShortArray(CHUNK_SAMPLES)

  /** Blocks for up to one chunk; returns null on a read error. */
  fun read(): FloatArray? {
    var filled = 0
    while (filled < CHUNK_SAMPLES) {
      val n = record.read(shorts, filled, CHUNK_SAMPLES - filled)
      if (n < 0) return null
      if (n == 0) break
      filled += n
    }
    return FloatArray(filled) { shorts[it] / 32768f }
  }

  override fun close() {
    runCatching { record.stop() }
    record.release()
  }

  companion object {
    const val CHUNK_SAMPLES = WakePipeline.SAMPLE_RATE / 10 // 100 ms

    /**
     * Opens and starts the mic. The buffer holds 2 s so the listener can spend a moment embedding
     * a hit without losing audio.
     *
     * @throws SecurityException without RECORD_AUDIO
     * @throws IOException if the mic cannot be opened (e.g. in use)
     */
    @SuppressLint("MissingPermission")
    fun open(): MicCapture {
      val min =
        AudioRecord.getMinBufferSize(
          WakePipeline.SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
      val bytes = maxOf(min, WakePipeline.SAMPLE_RATE * 2 * 2)
      val record =
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          WakePipeline.SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          bytes,
        )
      if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IOException("Microphone could not be initialised")
      }
      record.startRecording()
      if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
        record.release()
        throw IOException("Microphone could not start recording")
      }
      return MicCapture(record)
    }
  }
}
