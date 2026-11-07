package com.malladi2610.phoneagent.wakeword

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable

/** The native wake-word engine could not be loaded (missing .so for this ABI, missing assets...). */
class WakeWordUnavailableException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/**
 * sherpa-onnx keyword spotter (zh-en 3M phone model, chunk-16, 1 thread) plus the CAM++ speaker
 * embedding extractor, loaded from `assets/wakeword/` (see scripts/fetch-wakeword-assets.sh).
 *
 * Native objects are not thread-safe: use one engine from one thread at a time, and [close] it when
 * done (it holds ~50 MB of native memory).
 */
class WakeWordEngine
private constructor(
  private val kws: KeywordSpotter,
  private val extractor: SpeakerEmbeddingExtractor,
) : SpeakerEmbedder, Closeable {

  /**
   * A detector for one encoded keyword line (from [WakePhraseEncoder]).
   *
   * @throws IllegalArgumentException if sherpa-onnx rejects the keyword line.
   */
  fun newDetector(keywordLine: String): KeywordDetector = SherpaDetector(kws, kws.createStream(keywordLine))

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

  override fun close() {
    kws.release()
    extractor.release()
  }

  private class SherpaDetector(private val kws: KeywordSpotter, private val stream: OnlineStream) :
    KeywordDetector {
    override fun accept(samples: FloatArray): Boolean {
      stream.acceptWaveform(samples, WakePipeline.SAMPLE_RATE)
      var hit = false
      while (kws.isReady(stream)) {
        kws.decode(stream)
        if (kws.getResult(stream).keyword.isNotBlank()) {
          hit = true
          kws.reset(stream) // must reset right after a detection
        }
      }
      return hit
    }

    override fun reset() = kws.reset(stream)

    override fun close() = stream.release()
  }

  companion object {
    private const val DIR = "wakeword"
    const val ENCODER = "$DIR/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER = "$DIR/decoder-epoch-13-avg-2-chunk-16-left-64.onnx"
    const val JOINER = "$DIR/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx"
    const val TOKENS = "$DIR/tokens.txt"
    /** Default keyword file (Hey Jarvis); the constructor needs a valid one. */
    const val KEYWORDS = "$DIR/keywords.txt"
    const val SPEAKER_MODEL = "$DIR/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

    /** Loads both models. Slow (~1 s); call off the main thread. */
    @Throws(WakeWordUnavailableException::class)
    fun create(context: Context): WakeWordEngine {
      val assets = context.applicationContext.assets
      val missing =
        listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS, SPEAKER_MODEL).filterNot { path ->
          runCatching { assets.open(path).close() }.isSuccess
        }
      if (missing.isNotEmpty()) {
        throw WakeWordUnavailableException("Wake-word models missing from the APK: $missing")
      }
      var kws: KeywordSpotter? = null
      try {
        kws =
          KeywordSpotter(
            assetManager = assets,
            config =
              KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = WakePipeline.SAMPLE_RATE, featureDim = 80),
                modelConfig =
                  OnlineModelConfig(
                    transducer =
                      OnlineTransducerModelConfig(encoder = ENCODER, decoder = DECODER, joiner = JOINER),
                    tokens = TOKENS,
                    numThreads = 1,
                    provider = "cpu",
                    modelType = "zipformer2",
                  ),
                keywordsFile = KEYWORDS,
                keywordsScore = WakePhraseEncoder.DEFAULT_BOOST_SCORE,
                keywordsThreshold = WakePhraseEncoder.DEFAULT_TRIGGER_THRESHOLD,
              ),
          )
        val extractor =
          SpeakerEmbeddingExtractor(
            assetManager = assets,
            config = SpeakerEmbeddingExtractorConfig(model = SPEAKER_MODEL, numThreads = 1, provider = "cpu"),
          )
        return WakeWordEngine(kws, extractor)
      } catch (e: LinkageError) {
        // UnsatisfiedLinkError (no arm64 .so on this device), ExceptionInInitializerError...
        kws?.release()
        throw WakeWordUnavailableException("sherpa-onnx native library unavailable on this device", e)
      } catch (e: RuntimeException) {
        kws?.release()
        throw WakeWordUnavailableException("Could not load the wake-word models", e)
      }
    }
  }
}
