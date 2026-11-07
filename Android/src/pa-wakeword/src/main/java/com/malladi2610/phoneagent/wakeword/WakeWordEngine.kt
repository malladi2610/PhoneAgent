package com.malladi2610.phoneagent.wakeword

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/** A native wake-phrase model could not be loaded (missing .so for this ABI, missing assets...). */
class WakeWordUnavailableException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/** Asset paths under `assets/wakeword/` (see scripts/fetch-wakeword-assets.sh). */
object WakeAssets {
  private const val DIR = "wakeword"
  const val MELSPECTROGRAM = "$DIR/melspectrogram.tflite"
  const val EMBEDDING = "$DIR/embedding_model.tflite"
  const val SPEAKER_MODEL = "$DIR/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
}

/**
 * openWakeWord's feature models (.tflite) on LiteRT, one thread each. ~2.4 MB of model; one
 * embedding (mel + CNN) per 80 ms of audio. Not thread-safe.
 */
class TfliteFeatureModels private constructor(private val mel: Interpreter, private val emb: Interpreter) :
  FeatureModels {
  private var melInputSize = -1
  private var melIn: ByteBuffer = ByteBuffer.allocateDirect(0)
  private var melOut: ByteBuffer = ByteBuffer.allocateDirect(0)
  private var melFrames = 0
  private val embIn = direct(SpeechEmbeddingStream.WINDOW * SpeechEmbeddingStream.MEL_BINS)
  private val embOut = direct(SpeechEmbeddingStream.DIM)

  override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
    if (samples.size != melInputSize) {
      mel.resizeInput(0, intArrayOf(1, samples.size))
      mel.allocateTensors()
      melInputSize = samples.size
      melIn = direct(samples.size)
      val shape = mel.getOutputTensor(0).shape() // [1, 1, frames, 32]
      melFrames = shape[shape.size - 2]
      melOut = direct(melFrames * SpeechEmbeddingStream.MEL_BINS)
    }
    melIn.rewind()
    melIn.asFloatBuffer().put(samples)
    melOut.rewind()
    mel.run(melIn, melOut)
    melOut.rewind()
    val fb = melOut.asFloatBuffer()
    return Array(melFrames) { FloatArray(SpeechEmbeddingStream.MEL_BINS).also { fb.get(it) } }
  }

  override fun embed(window: Array<FloatArray>): FloatArray {
    embIn.rewind()
    val fb = embIn.asFloatBuffer()
    for (row in window) fb.put(row)
    embOut.rewind()
    emb.run(embIn, embOut)
    embOut.rewind()
    return FloatArray(SpeechEmbeddingStream.DIM).also { embOut.asFloatBuffer().get(it) }
  }

  override fun close() {
    mel.close()
    emb.close()
  }

  companion object {
    private fun direct(floats: Int): ByteBuffer =
      ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    private fun loadAsset(context: Context, path: String): ByteBuffer {
      val bytes = context.assets.open(path).use { it.readBytes() }
      return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }

    /** Loads both models (~100 ms). Call off the main thread. */
    @Throws(WakeWordUnavailableException::class)
    fun create(context: Context): TfliteFeatureModels {
      val app = context.applicationContext
      var mel: Interpreter? = null
      try {
        val options = { Interpreter.Options().setNumThreads(1) }
        mel = Interpreter(loadAsset(app, WakeAssets.MELSPECTROGRAM), options())
        val emb = Interpreter(loadAsset(app, WakeAssets.EMBEDDING), options())
        return TfliteFeatureModels(mel, emb)
      } catch (e: IOException) {
        mel?.close()
        throw WakeWordUnavailableException("Wake-phrase models missing from the APK", e)
      } catch (e: LinkageError) {
        mel?.close()
        throw WakeWordUnavailableException("LiteRT native library unavailable on this device", e)
      } catch (e: RuntimeException) {
        mel?.close()
        throw WakeWordUnavailableException("Could not load the wake-phrase models", e)
      }
    }
  }
}

/**
 * CAM++ speaker embeddings (sherpa-onnx) for voice match. ~30 MB model, ~1 s to load. Native
 * objects are not thread-safe: use from one thread at a time and [close] when done.
 */
class SpeakerEngine private constructor(private val extractor: SpeakerEmbeddingExtractor) :
  SpeakerEmbedder, Closeable {

  override fun embed(samples: FloatArray): FloatArray? {
    if (samples.isEmpty()) return null
    val stream = extractor.createStream()
    try {
      stream.acceptWaveform(samples, WakePipeline.SAMPLE_RATE)
      stream.inputFinished()
      if (!extractor.isReady(stream)) return null
      return extractor.compute(stream)
    } finally {
      stream.release()
    }
  }

  override fun close() = extractor.release()

  companion object {
    @Throws(WakeWordUnavailableException::class)
    fun create(context: Context): SpeakerEngine {
      val assets = context.applicationContext.assets
      if (runCatching { assets.open(WakeAssets.SPEAKER_MODEL).close() }.isFailure) {
        throw WakeWordUnavailableException("Voice-match model missing from the APK")
      }
      try {
        return SpeakerEngine(
          SpeakerEmbeddingExtractor(
            assetManager = assets,
            config =
              SpeakerEmbeddingExtractorConfig(model = WakeAssets.SPEAKER_MODEL, numThreads = 1, provider = "cpu"),
          )
        )
      } catch (e: LinkageError) {
        throw WakeWordUnavailableException("sherpa-onnx native library unavailable on this device", e)
      } catch (e: RuntimeException) {
        throw WakeWordUnavailableException("Could not load the voice-match model", e)
      }
    }
  }
}
