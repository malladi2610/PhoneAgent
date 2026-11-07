package com.malladi2610.phoneagent.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Test helpers: fixtures, WAV reading, and the ONNX Runtime (JVM) [FeatureModels]. */
object TestAudio {
  fun resource(name: String): ByteArray =
    requireNotNull(TestAudio::class.java.getResourceAsStream("/wakeword/$name")) { "missing fixture $name" }
      .use { it.readBytes() }

  /** Little-endian float32 rows of [dim]. */
  fun floatRows(bytes: ByteArray, dim: Int = SpeechEmbeddingStream.DIM): List<FloatArray> {
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    require(fb.remaining() % dim == 0)
    return List(fb.remaining() / dim) { FloatArray(dim).also { r -> fb.get(r) } }
  }

  /** 16-bit PCM mono WAV → floats (`short / 32768f`, like MicCapture). Averages stereo. */
  fun readWav(bytes: ByteArray): Pair<FloatArray, Int> {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV" }
    var pos = 12
    var channels = 1
    var rate = 16000
    var bits = 16
    while (pos + 8 <= bytes.size) {
      val id = String(bytes, pos, 4)
      val size = b.getInt(pos + 4)
      val body = pos + 8
      when (id) {
        "fmt " -> {
          channels = b.getShort(body + 2).toInt()
          rate = b.getInt(body + 4)
          bits = b.getShort(body + 14).toInt()
        }
        "data" -> {
          require(bits == 16) { "only PCM16" }
          val n = minOf(size, bytes.size - body) / (2 * channels)
          val out = FloatArray(n) { i ->
            var s = 0f
            for (c in 0 until channels) s += b.getShort(body + 2 * (i * channels + c)) / 32768f
            s / channels
          }
          return out to rate
        }
      }
      pos = body + size + (size and 1)
    }
    error("no data chunk")
  }

  fun readWav(file: File) = readWav(file.readBytes())

  private fun modelDir(): File {
    // Gradle runs unit tests with the module directory as the working directory.
    val dir = File("testmodels")
    check(File(dir, "melspectrogram.onnx").isFile && File(dir, "embedding_model.onnx").isFile) {
      "Test models missing in ${dir.absolutePath}: run ./scripts/fetch-wakeword-assets.sh"
    }
    return dir
  }

  fun onnxModels(): FeatureModels = OnnxFeatureModels(modelDir())
}

/** The ONNX copies of the openWakeWord feature models, run with ONNX Runtime on the JVM. */
class OnnxFeatureModels(dir: File) : FeatureModels {
  private val env = OrtEnvironment.getEnvironment()
  private val options =
    OrtSession.SessionOptions().apply {
      setIntraOpNumThreads(1)
      setInterOpNumThreads(1)
    }
  private val mel = env.createSession(File(dir, "melspectrogram.onnx").path, options)
  private val emb = env.createSession(File(dir, "embedding_model.onnx").path, options)

  override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
    OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
      mel.run(mapOf("input" to input)).use { result ->
        val t = result[0] as OnnxTensor
        val shape = t.info.shape // [1, 1, frames, 32]
        val fb = t.floatBuffer
        val frames = shape[2].toInt()
        return Array(frames) { FloatArray(SpeechEmbeddingStream.MEL_BINS).also { r -> fb.get(r) } }
      }
    }
  }

  override fun embed(window: Array<FloatArray>): FloatArray {
    val flat = FloatArray(SpeechEmbeddingStream.WINDOW * SpeechEmbeddingStream.MEL_BINS)
    for (i in window.indices) System.arraycopy(window[i], 0, flat, i * SpeechEmbeddingStream.MEL_BINS, SpeechEmbeddingStream.MEL_BINS)
    val shape = longArrayOf(1, SpeechEmbeddingStream.WINDOW.toLong(), SpeechEmbeddingStream.MEL_BINS.toLong(), 1)
    OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { input ->
      emb.run(mapOf("input_1" to input)).use { result ->
        val fb = (result[0] as OnnxTensor).floatBuffer
        return FloatArray(SpeechEmbeddingStream.DIM).also { fb.get(it) }
      }
    }
  }

  override fun close() {
    mel.close()
    emb.close()
    options.close()
  }
}
