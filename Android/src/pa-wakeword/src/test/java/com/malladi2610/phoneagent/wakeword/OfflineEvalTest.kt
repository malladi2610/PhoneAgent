package com.malladi2610.phoneagent.wakeword

import java.io.File
import java.util.Locale
import java.util.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Offline evaluation of enrollment + detection on real recordings, using the production code
 * paths ([EnrollmentLogic.TakeRecorder], [EnrollmentLogic.features], [PhraseCalibration],
 * [UtteranceCutter], [PhraseMatcher]) with the ONNX copies of the feature models. Skipped unless
 * `PA_WAKE_EVAL_DIR` points at a directory with:
 * - `user.wav`: the owner saying the phrase several times, with pauses (16 kHz mono PCM16);
 * - `neg/` *.wav: other phrases / voices; `pos/` *.wav: the phrase in other voices;
 *   `long/` *.wav: longer stretches of unrelated speech.
 *
 * Every 4-take subset of the user's takes is enrolled as the app does; the held-out takes are
 * then looked for in the continuous recording. Results are printed (summarised in
 * docs/research/wakeword-query-by-example.md).
 */
class OfflineEvalTest {
  private val sr = WakePipeline.SAMPLE_RATE
  private val rng = Random(0)

  private fun padded(audio: FloatArray, before: Float = 1f, after: Float = 0.5f): FloatArray {
    val a = (before * sr).toInt()
    val out = FloatArray(a + audio.size + (after * sr).toInt())
    System.arraycopy(audio, 0, out, a, audio.size)
    val sigma = Math.pow(10.0, -65.0 / 20) // a quiet noise floor instead of digital silence
    for (i in out.indices) out[i] += (rng.nextGaussian() * sigma).toFloat()
    return out
  }

  private fun wavs(dir: File) = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()

  /** Utterances the live detector would evaluate, fed in 80 ms chunks like MicCapture. */
  private fun utterances(models: FeatureModels, audio: FloatArray): List<UtteranceCutter.Utterance> {
    val stream = SpeechEmbeddingStream(models)
    val cutter = UtteranceCutter()
    val out = mutableListOf<UtteranceCutter.Utterance>()
    var i = 0
    while (i < audio.size) {
      val chunk = audio.copyOfRange(i, minOf(audio.size, i + MicCapture.CHUNK_SAMPLES))
      out += cutter.accept(chunk, stream.accept(chunk))
      i += MicCapture.CHUNK_SAMPLES
    }
    return out
  }

  private fun f(v: Float) = String.format(Locale.ROOT, "%.3f", v)

  private fun pct(xs: List<Float>, vararg ps: Int): String {
    val s = xs.sorted()
    return ps.joinToString(" ") { p -> "p$p=" + f(s[((s.size - 1) * p / 100.0).toInt()]) }
  }

  @Test
  fun evaluate() {
    val root = System.getenv("PA_WAKE_EVAL_DIR")
    assumeTrue("PA_WAKE_EVAL_DIR not set", root != null)
    val dir = File(root!!)
    TestAudio.onnxModels().use { models ->
      val (user, rate) = TestAudio.readWav(File(dir, "user.wav"))
      check(rate == sr)

      // Enrollment-style takes (as the app records them).
      val recorder = EnrollmentLogic.TakeRecorder()
      val results = mutableListOf<Pair<Long, EnrollmentLogic.TakeResult>>()
      var pos = 0
      while (pos < user.size) {
        val chunk = user.copyOfRange(pos, minOf(user.size, pos + MicCapture.CHUNK_SAMPLES))
        pos += chunk.size
        recorder.accept(chunk).forEach { results += pos.toLong() to it }
      }
      println(
        "takes: " +
          results.joinToString { (at, r) ->
            when (r) {
              is EnrollmentLogic.TakeResult.Accepted -> "ok@${f(at / 16000f)}s" + (r.hint?.let { "(loud)" } ?: "")
              is EnrollmentLogic.TakeResult.Rejected -> "rejected@${f(at / 16000f)}s(${r.message.substringBefore('.')})"
              EnrollmentLogic.TakeResult.Ignored -> "ignored@${f(at / 16000f)}s"
            }
          }
      )
      val accepted = results.mapNotNull { (at, r) -> (r as? EnrollmentLogic.TakeResult.Accepted)?.let { at to it.take } }
      val takes = accepted.map { (_, t) -> requireNotNull(EnrollmentLogic.features(t, models)) }
      // Absolute speech start of each take, to find it again in the continuous stream.
      val takeStarts = accepted.map { (at, t) -> at - t.clip.size + t.speechStart }
      println("template lengths: " + takes.map { it.template.size })

      val cont = utterances(models, user)
      println("continuous utterances: ${cont.size} at " + cont.joinToString { f(it.start / 16000f) })
      val heldIndex = takeStarts.map { s -> cont.indexOfFirst { kotlin.math.abs(it.start - s) < sr / 10 } }
      check(heldIndex.all { it >= 0 }) { "take not found in the continuous stream: $heldIndex" }

      val negs = wavs(File(dir, "neg")).map { it.name to utterances(models, padded(TestAudio.readWav(it).first)) }
      val poss = wavs(File(dir, "pos")).map { it.name to utterances(models, padded(TestAudio.readWav(it).first)) }
      val longs = wavs(File(dir, "long")).map { utterances(models, padded(TestAudio.readWav(it).first, 1f, 1f)) }
      val longMinutes = wavs(File(dir, "long")).sumOf { TestAudio.readWav(it).first.size } / 16000.0 / 60
      println(
        "negatives ${negs.size} files (${negs.sumOf { it.second.size }} utterances), other-voice positives ${poss.size}, " +
          "long ${longs.size} files (${"%.1f".format(longMinutes)} min, ${longs.sumOf { it.size }} utterances)"
      )

      println("pairwise cost (row = take, col = template):")
      for (i in takes.indices) {
        println(
          takes.indices.joinToString(" ") { j ->
            if (i == j) "  -  " else f(PhraseMatcher(listOf(takes[j].template)).match(takes[i].template))
          }
        )
      }

      class Run(
        val combo: List<Int>,
        val loo: FloatArray,
        val held: List<Pair<Int, Float>>,
        val neg: List<Float>, // min per file
        val pos: List<Float>,
        val long: List<Float>, // every utterance
        val userOther: List<Float>, // utterances in user.wav that are not takes
      )

      val n = takes.size
      val combos = mutableListOf<List<Int>>()
      fun rec(start: Int, acc: List<Int>) {
        if (acc.size == EnrollmentLogic.TAKES) {
          combos += acc
          return
        }
        for (i in start until n) rec(i + 1, acc + i)
      }
      rec(0, emptyList())
      val runs =
        combos.map { combo ->
          val sub = combo.map { takes[it] }
          val matcher = PhraseMatcher(sub.map { it.template })
          fun best(us: List<UtteranceCutter.Utterance>) = us.minOfOrNull { matcher.match(it.frames) } ?: Float.POSITIVE_INFINITY
          Run(
            combo,
            PhraseCalibration.looCosts(sub),
            (0 until n).filter { it !in combo }.map { i -> i to matcher.match(cont[heldIndex[i]].frames) },
            negs.map { best(it.second) },
            poss.map { best(it.second) },
            longs.flatten().map { matcher.match(it.frames) },
            cont.indices.filter { it !in heldIndex }.map { matcher.match(cont[it].frames) },
          )
        }
      println("${runs.size} enrollments of ${EnrollmentLogic.TAKES} takes")
      println("held-out user takes: ${pct(runs.flatMap { r -> r.held.map { it.second } }, 0, 50, 90, 100)}")
      println("LOO costs:           ${pct(runs.flatMap { it.loo.toList() }, 0, 50, 90, 100)}")
      println("max LOO per enrollment: ${pct(runs.map { it.loo.max() }, 0, 50, 90, 100)}")
      println("negatives (min/file): ${pct(runs.flatMap { it.neg }, 0, 1, 5, 50)}")
      println("long-speech utterances: ${pct(runs.flatMap { it.long }, 0, 1, 5, 50)}")
      println("other voices 'Hey Friday': ${pct(runs.flatMap { it.pos }, 0, 50, 100)}")
      val hardest = negs.indices.map { k -> negs[k].first to runs.minOf { it.neg[k] } }.sortedBy { it.second }.take(10)
      println("hardest negatives: " + hardest.joinToString { "${it.first}=${f(it.second)}" })
      for (i in 0 until n) {
        val c = runs.flatMap { r -> r.held.filter { it.first == i }.map { it.second } }
        println("take $i held-out: ${pct(c, 0, 50, 100)}")
      }

      fun report(label: String, thrOf: (FloatArray) -> Float) {
        var hit = 0
        var tot = 0
        var fa = 0
        var longFa = 0
        var userFa = 0
        var otherVoice = 0
        val thrs = mutableListOf<Float>()
        for (r in runs) {
          val thr = thrOf(r.loo)
          thrs += thr
          hit += r.held.count { it.second <= thr }
          tot += r.held.size
          fa += r.neg.count { it <= thr }
          longFa += r.long.count { it <= thr }
          userFa += r.userOther.count { it <= thr }
          otherVoice += r.pos.count { it <= thr }
        }
        println(
          "$label: thr ${pct(thrs, 0, 50, 100)} | recall $hit/$tot (${"%.1f".format(100.0 * hit / tot)}%) | " +
            "neg files $fa/${runs.size * negs.size} | long-speech $longFa/${runs.size * runs[0].long.size} | " +
            "rejected takes (also the phrase) $userFa/${runs.size * runs[0].userOther.size} | other-voice 'Hey Friday' $otherVoice/${runs.size * poss.size}"
        )
      }
      for (fixed in listOf(0.07f, 0.08f, 0.09f, 0.1f, 0.11f, 0.12f)) report("fixed ${f(fixed)}") { fixed }
      for (margin in listOf(0f, 0.01f, 0.02f, 0.03f)) {
        for ((lo, hi) in listOf(0.08f to 0.11f, 0.08f to 0.1f, 0.09f to 0.11f, 0.07f to 0.12f)) {
          report("max(LOO)+${f(margin)} in [${f(lo)}, ${f(hi)}]") { loo -> (loo.max() + margin).coerceIn(lo, hi) }
        }
      }
      report("PhraseCalibration.threshold") { PhraseCalibration.threshold(it) }
      val flagged = runs.mapNotNull { r -> PhraseCalibration.outlier(r.loo)?.let { r.combo[it] } }
      println("outlier flags: ${flagged.size}/${runs.size} enrollments; flagged takes ${flagged.groupingBy { it }.eachCount()}")
      // A flagged take is re-recorded in the app, so also report enrollments made only of takes that
      // are never flagged, scored on the held-out takes that are never flagged either.
      val bad = flagged.toSet()
      val clean = runs.filter { r -> r.combo.none { it in bad } }
      val cleanHeld = clean.flatMap { r -> r.held.filter { it.first !in bad }.map { r to it.second } }
      val cleanHit = cleanHeld.count { (r, c) -> c <= PhraseCalibration.threshold(r.loo) }
      println("enrollments without flagged takes: ${clean.size}, recall on unflagged held-out takes $cleanHit/${cleanHeld.size}")
    }
  }
}
