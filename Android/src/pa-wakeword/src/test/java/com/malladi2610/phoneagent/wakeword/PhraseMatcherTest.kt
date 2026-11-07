package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhraseMatcherTest {
  private fun onehot(i: Int, dim: Int = 8) = FloatArray(dim).also { it[i] = 1f }

  /** A "phrase" of distinct frames a, b, c, d (one-hot, so 1 − cos is 0 or 1). */
  private val phrase = listOf(onehot(1), onehot(2), onehot(3), onehot(4))

  @Test
  fun exactMatch_costsZero() {
    assertEquals(0f, PhraseMatcher(listOf(phrase)).match(phrase), 1e-6f)
  }

  @Test
  fun slowerOrFasterSpeech_stillMatches() {
    val slow = phrase.flatMap { listOf(it, it) } // 2× longer: the limit
    assertEquals(0f, PhraseMatcher(listOf(phrase)).match(slow), 1e-6f)
    assertEquals(0f, PhraseMatcher(listOf(slow)).match(phrase), 1e-6f)
  }

  @Test
  fun lengthsTooDifferent_doNotMatch() {
    val tooSlow = phrase.flatMap { listOf(it, it, it) }
    assertEquals(Float.POSITIVE_INFINITY, PhraseMatcher(listOf(phrase)).match(tooSlow))
    assertEquals(Float.POSITIVE_INFINITY, PhraseMatcher(listOf(phrase)).match(emptyList()))
  }

  @Test
  fun bothEndsAreAnchored() {
    // The phrase followed by something else: the extra frames must be paid for.
    val m = PhraseMatcher(listOf(phrase))
    val c = m.match(phrase + listOf(onehot(5), onehot(6)))
    // Best path: a-b-c-d diagonal, then two live-only steps on d vs e, f (cost 1 each): 2 / (4 + 6).
    assertEquals(0.2f, c, 1e-6f)
    // Only the tail of the phrase ("…c d") against the whole template costs even more.
    assertTrue(m.match(listOf(onehot(3), onehot(4))) > 0.2f)
  }

  @Test
  fun wrongOrder_costsMore() {
    assertTrue(PhraseMatcher(listOf(phrase)).match(phrase.reversed()) > 0.4f)
  }

  @Test
  fun costIsWeightedMean_ofOneSubstitution() {
    val one = listOf(onehot(1), onehot(2), onehot(5), onehot(4))
    // Diagonal path, one mismatch with weight 2, normalised by n + m = 8.
    assertEquals(2f / 8f, PhraseMatcher(listOf(phrase)).match(one), 1e-6f)
  }

  @Test
  fun bestTemplateWins_andScaleDoesNotMatter() {
    val other = listOf(onehot(6), onehot(7), onehot(6), onehot(7))
    val m = PhraseMatcher(listOf(other, phrase.map { v -> FloatArray(v.size) { v[it] * 7f } }))
    assertEquals(0f, m.match(phrase.map { v -> FloatArray(v.size) { v[it] * 0.01f } }), 1e-6f)
  }

  /** Same costs as the Python port used for the offline eval (fixture from TTS audio). */
  @Test
  fun matchesPythonReference() {
    val templates = (0..2).map { TestAudio.floatRows(TestAudio.resource("matcher_t$it.f32")) }
    val lines = String(TestAudio.resource("matcher_ref.txt")).lines()
    for (line in lines.filter { it.startsWith("template") }) {
      val (_, i, len) = line.split(' ')
      assertEquals(len.toInt(), templates[i.toInt()].size)
    }
    val queries = lines.filter { it.startsWith("query") }
    assertEquals(3, queries.size)
    val matcher = PhraseMatcher(templates)
    for (line in queries) {
      val (_, q, len, expected) = line.split(' ')
      val frames = TestAudio.floatRows(TestAudio.resource("matcher_q$q.f32"))
      assertEquals(len.toInt(), frames.size)
      val actual = matcher.match(frames)
      assertTrue("query $q: $expected vs $actual", abs(expected.toFloat() - actual) < 1e-4f)
    }
  }
}
