package com.malladi2610.phoneagent.voice.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.malladi2610.phoneagent.voice.MicUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** A cold stream of fixed-size PCM16 mono chunks; collecting starts the mic, cancelling stops it. */
interface PcmSource {
  fun chunks(): Flow<ByteArray>
}

/** 16 kHz mono PCM16 from the VOICE_RECOGNITION source, in 100 ms (3200-byte) chunks. */
class MicRecorder(
  private val context: Context,
  private val sampleRate: Int = 16_000,
  private val chunkBytes: Int = 3_200,
) : PcmSource {

  @SuppressLint("MissingPermission") // checked below
  override fun chunks(): Flow<ByteArray> =
    flow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
          throw MicUnavailableException("RECORD_AUDIO not granted")
        }
        val minBuf =
          AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
          try {
            AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              sampleRate,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              maxOf(minBuf, chunkBytes * 4),
            )
          } catch (e: Exception) {
            throw MicUnavailableException("AudioRecord: ${e.message}")
          }
        try {
          if (record.state != AudioRecord.STATE_INITIALIZED) throw MicUnavailableException("AudioRecord not initialized")
          record.startRecording()
          if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw MicUnavailableException("mic busy")
          }
          while (currentCoroutineContext().isActive) {
            val buf = ByteArray(chunkBytes)
            var filled = 0
            while (filled < chunkBytes) {
              val n = record.read(buf, filled, chunkBytes - filled)
              if (n < 0) throw MicUnavailableException("AudioRecord.read=$n")
              filled += n
            }
            emit(buf)
          }
        } finally {
          runCatching { record.stop() }
          record.release()
        }
      }
      .flowOn(Dispatchers.IO)
}
