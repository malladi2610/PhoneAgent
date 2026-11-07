package com.malladi2610.phoneagent.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseEncoderTest {
  // Same line format as the model's en.phone: "<WORD> <PHONE> <PHONE> ...".
  private val lexicon =
    Lexicon.parse(
      """
      HEY HH EY1
      JARVIS JH AA1 R V AH0 S
      READ R IY1 D
      READ R EH1 D
      WHAT'S W AH1 T S
      UP AH1 P
      """
        .trimIndent()
        .lineSequence()
    )
  private val encoder = WakePhraseEncoder(lexicon)

  @Test
  fun encodesHeyJarvis() {
    assertEquals(
      "HH EY1 JH AA1 R V AH0 S :1.5 #0.25 @HEY_JARVIS",
      encoder.encode("Hey Jarvis").getOrThrow(),
    )
  }

  @Test
  fun caseAndPunctuationInsensitive() {
    val expected = "HH EY1 JH AA1 R V AH0 S :1.5 #0.25 @HEY_JARVIS"
    assertEquals(expected, encoder.encode("hey, JARVIS!").getOrThrow())
    assertEquals(expected, encoder.encode("  Hey   jarvis.  ").getOrThrow())
    assertEquals(expected, encoder.encode("\"Hey-Jarvis\"").getOrThrow())
  }

  @Test
  fun apostropheInsideWord_isKept() {
    assertEquals("W AH1 T S AH1 P :1.5 #0.25 @WHAT'S_UP", encoder.encode("What's up?").getOrThrow())
  }

  @Test
  fun encoder_unknownWord_returnsError() {
    val result = encoder.encode("Hey Friday")
    assertTrue(result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(error is UnknownWordsException)
    assertEquals(listOf("FRIDAY"), (error as UnknownWordsException).words)
    assertTrue(error.message!!, error.message!!.contains("FRIDAY"))
  }

  @Test
  fun encoder_severalUnknownWords_namesAll() {
    val error = encoder.encode("Okay Friday hey").exceptionOrNull() as UnknownWordsException
    assertEquals(listOf("OKAY", "FRIDAY"), error.words)
  }

  @Test
  fun multiplePronunciations_usesFirst() {
    assertEquals("R IY1 D :1.5 #0.25 @READ", encoder.encode("read").getOrThrow())
  }

  @Test
  fun emptyPhrase_returnsError() {
    for (phrase in listOf("", "   ", "!!! ...")) {
      val result = encoder.encode(phrase)
      assertTrue("'$phrase' should fail", result.isFailure)
      assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
  }

  @Test
  fun customScoreAndThreshold_areWritten() {
    val custom = WakePhraseEncoder(lexicon, boostScore = 2.0f, triggerThreshold = 0.1f)
    assertEquals("HH EY1 :2.0 #0.1 @HEY", custom.encode("hey").getOrThrow())
  }

  @Test
  fun words_normalizesPhrase() {
    assertEquals(listOf("HEY", "JARVIS"), WakePhraseEncoder.words(" hey, Jarvis! "))
  }

  /** Runs against the real model lexicon when scripts/fetch-wakeword-assets.sh has been run. */
  @Test
  fun realLexicon_encodesHeyJarvis_ifAssetsPresent() {
    val file = java.io.File("src/main/assets/${Lexicon.ASSET_PATH}")
    org.junit.Assume.assumeTrue("wake-word assets not fetched", file.isFile)
    val phrase = "Hey Jarvis"
    val real = Lexicon.load(file.inputStream(), only = WakePhraseEncoder.words(phrase).toSet())
    assertEquals(
      "HH EY1 JH AA1 R V AH0 S :1.5 #0.25 @HEY_JARVIS",
      WakePhraseEncoder(real).encode(phrase).getOrThrow(),
    )
    // The generated default keywords file must hold the same line.
    assertEquals(
      encoder.encode(phrase).getOrThrow(),
      java.io.File("src/main/assets/wakeword/keywords.txt").readText().trim(),
    )
  }

  @Test
  fun lexiconParse_onlyKeepsRequestedWords_andSkipsBlankLines() {
    val small = Lexicon.parse(sequenceOf("", "HEY HH EY1", "UP AH1 P", "BAD"), only = setOf("HEY"))
    assertEquals("HH EY1", small.phones("HEY"))
    assertNull(small.phones("UP"))
    assertNull(small.phones("BAD"))
  }
}
