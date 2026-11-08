package com.malladi2610.phoneagent.wakeword

/**
 * Enrollment steps that don't touch Android (unit-tested): phrase validation, take capture from
 * the endpointer, per-take checks, and turning the takes into an [Enrollment].
 */
internal object EnrollmentLogic {
  const val TAKES = 4
  const val MAX_REDOS = 2
  const val MIN_PHRASE_CHARS = 2
  const val MAX_PHRASE_CHARS = 40

  /** Audio kept before the speech start: the feature stream needs ~0.8 s to warm up. */
  const val PRE_ROLL_SECONDS = 1.0f
  /** A take is too quiet when its loudest 20 ms frame is below this (dBFS). */
  const val QUIET_PEAK_DB = -35f
  /** Share of clipped samples above which a take is rejected, and above which we warn. */
  const val REJECT_CLIPPED = 0.02f
  const val WARN_CLIPPED = 0.002f
  /** Shorter than this, a "segment" is a click or breath: ignore it without a message. */
  const val IGNORE_SECONDS = 0.15f

  /** null if [typed] can be used as a wake phrase, else the reason. */
  fun phraseError(typed: String): String? {
    val p = typed.trim()
    return when {
      p.isEmpty() -> "Type the phrase you want to say."
      p.length < MIN_PHRASE_CHARS -> "That's too short."
      p.length > MAX_PHRASE_CHARS -> "Keep it under $MAX_PHRASE_CHARS characters."
      p.none { it.isLetterOrDigit() } -> "Use words, e.g. “Hey Friday”."
      else -> null
    }
  }

  /** One recorded take: [clip] holds pre-roll + speech `[speechStart, speechEnd)` + a short tail. */
  class TakeAudio(val clip: FloatArray, val speechStart: Int, val speechEnd: Int, val peakDb: Float) {
    val clippedFraction: Float = Endpointer.clippedFraction(clip, speechStart, speechEnd)

    /** The window voice match sees at runtime: 2.5 s ending just after the phrase, trimmed. */
    fun voiceWindow(sampleRate: Int = WakePipeline.SAMPLE_RATE): FloatArray {
      val end = minOf(clip.size, speechEnd + (PhraseCalibration.TEMPLATE_END_SECONDS * sampleRate).toInt())
      val start = maxOf(0, end - (WakePipeline.WINDOW_SECONDS * sampleRate).toInt())
      return SpeechTrim.trim(clip.copyOfRange(start, end), sampleRate)
    }
  }

  sealed interface TakeResult {
    /** Nothing worth mentioning (a click, a cough). */
    data object Ignored : TakeResult

    data class Rejected(val message: String) : TakeResult

    /** [hint] is a non-blocking warning (e.g. slightly clipped). */
    data class Accepted(val take: TakeAudio, val hint: String?) : TakeResult
  }

  /**
   * Feeds the endpointer and keeps enough audio to cut out each take. Positions are counted from
   * the first sample fed in.
   */
  class TakeRecorder(private val sampleRate: Int = WakePipeline.SAMPLE_RATE) {
    val endpointer = Endpointer(sampleRate)
    private val ring =
      RingBuffer(((PRE_ROLL_SECONDS + Endpointer.MAX_SPEECH_SECONDS + 2.5f) * sampleRate).toInt())
    private var total = 0L

    fun accept(chunk: FloatArray): List<TakeResult> {
      ring.write(chunk)
      total += chunk.size
      val segments = endpointer.accept(chunk)
      if (segments.isEmpty()) return emptyList()
      val audio = ring.snapshot()
      val audioStart = total - audio.size
      return segments.map { judge(it, audio, audioStart) }
    }

    private fun judge(s: Endpointer.Segment, audio: FloatArray, audioStart: Long): TakeResult {
      if (s.seconds < IGNORE_SECONDS) return TakeResult.Ignored
      when (s.kind) {
        Endpointer.Kind.TOO_SHORT -> return TakeResult.Rejected("Too short. Say the whole phrase.")
        Endpointer.Kind.TOO_LONG -> return TakeResult.Rejected("Too long. Say just the phrase, then pause.")
        Endpointer.Kind.OK -> Unit
      }
      val clipStart = maxOf(audioStart, s.start - (PRE_ROLL_SECONDS * sampleRate).toLong())
      if (s.start < audioStart) return TakeResult.Rejected("Too long. Say just the phrase, then pause.")
      val clip = audio.copyOfRange((clipStart - audioStart).toInt(), audio.size)
      val take = TakeAudio(clip, (s.start - clipStart).toInt(), (s.end - clipStart).toInt(), s.peakDb)
      return check(take)
    }

    fun reset() {
      endpointer.reset()
      ring.clear()
      total = 0
    }
  }

  fun check(take: TakeAudio): TakeResult =
    when {
      take.peakDb < QUIET_PEAK_DB -> TakeResult.Rejected("Too quiet. Speak up or hold the phone closer.")
      take.clippedFraction > REJECT_CLIPPED -> TakeResult.Rejected("Too loud. Move the phone a bit away.")
      take.clippedFraction > WARN_CLIPPED -> TakeResult.Accepted(take, "A bit loud: move the phone a bit away.")
      else -> TakeResult.Accepted(take, null)
    }

  /** Embeddings of a take's clip and its template range (null if the clip is unusable). */
  fun features(take: TakeAudio, models: FeatureModels): PhraseCalibration.Take? {
    val frames = SpeechEmbeddingStream(models).accept(take.clip)
    val range =
      PhraseCalibration.templateRange(take.speechStart.toLong(), take.speechEnd.toLong(), frames.size.toLong())
        ?: return null
    return PhraseCalibration.Take(frames, range.first.toInt(), range.last.toInt())
  }

  sealed interface Outcome {
    /** Take [index] doesn't sound like the others: record it again. */
    data class Redo(val index: Int, val loo: FloatArray) : Outcome

    data class Done(val enrollment: Enrollment, val loo: FloatArray, val keptTakes: Int) : Outcome

    data class Failed(val message: String) : Outcome
  }

  /**
   * Phrase templates + threshold from the takes, the consistency check, then the voice profile.
   * With [allowRedo] false an outlier take is dropped instead (as long as 3 takes remain).
   */
  fun build(
    phrase: String,
    takes: List<TakeAudio>,
    models: FeatureModels,
    speaker: SpeakerEmbedder,
    allowRedo: Boolean,
  ): Outcome {
    val feats = takes.map { features(it, models) }
    feats.indexOfFirst { it == null }.takeIf { it >= 0 }?.let { bad ->
      return if (allowRedo) Outcome.Redo(bad, FloatArray(0)) else Outcome.Failed("A take could not be analysed. Try again.")
    }
    var kept = feats.map { it!! }.withIndex().toList()
    var loo = PhraseCalibration.looCosts(kept.map { it.value })
    val outlier = PhraseCalibration.outlier(loo)
    if (outlier != null) {
      if (allowRedo) return Outcome.Redo(kept[outlier].index, loo)
      if (kept.size > 3) {
        kept = kept.filterIndexed { i, _ -> i != outlier }
        loo = PhraseCalibration.looCosts(kept.map { it.value })
      }
    }
    val threshold = PhraseCalibration.threshold(loo)
    val voices = kept.mapNotNull { speaker.embed(takes[it.index].voiceWindow()) }
    if (voices.size < 2) return Outcome.Failed("Couldn't learn your voice from these takes. Try again.")
    val profile = VoiceProfile(voices).withoutOutliers()
    return Outcome.Done(Enrollment(phrase.trim(), kept.map { it.value.template }, threshold, profile), loo, kept.size)
  }
}
