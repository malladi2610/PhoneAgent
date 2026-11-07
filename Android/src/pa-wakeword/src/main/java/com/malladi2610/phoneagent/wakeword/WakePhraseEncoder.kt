package com.malladi2610.phoneagent.wakeword

import java.util.Locale

/** Some words of the wake phrase are not in the pronunciation lexicon. */
class UnknownWordsException(val words: List<String>) :
  IllegalArgumentException(
    "Not in the wake-word dictionary: ${words.joinToString(", ")}. " +
      "Try a different word or a common spelling."
  )

/**
 * Turns a typed wake phrase into a sherpa-onnx keyword line for the phone-based zh-en KWS model,
 * e.g. "Hey Jarvis" → `HH EY1 JH AA1 R V AH0 S :1.5 #0.25 @HEY_JARVIS`
 * (`:` boosting score, `#` trigger threshold, `@` the phrase reported on detection).
 */
class WakePhraseEncoder(
  private val lexicon: Lexicon,
  private val boostScore: Float = DEFAULT_BOOST_SCORE,
  private val triggerThreshold: Float = DEFAULT_TRIGGER_THRESHOLD,
) {
  fun encode(phrase: String): Result<String> {
    val words = words(phrase)
    if (words.isEmpty()) {
      return Result.failure(IllegalArgumentException("Enter a wake phrase, for example \"Hey Jarvis\"."))
    }
    val unknown = words.filter { lexicon.phones(it) == null }.distinct()
    if (unknown.isNotEmpty()) return Result.failure(UnknownWordsException(unknown))
    val phones = words.joinToString(" ") { lexicon.phones(it)!! }
    return Result.success("$phones :$boostScore #$triggerThreshold @${words.joinToString("_")}")
  }

  companion object {
    const val DEFAULT_BOOST_SCORE = 1.5f
    const val DEFAULT_TRIGGER_THRESHOLD = 0.25f

    private val NON_WORD = Regex("[^A-Z0-9']+")

    /**
     * Upper-case lexicon words of [phrase]: case-insensitive, punctuation and hyphens split words,
     * apostrophes are kept inside words ("what's") but trimmed from the ends.
     */
    fun words(phrase: String): List<String> =
      phrase
        .uppercase(Locale.ROOT)
        .split(NON_WORD)
        .map { it.trim('\'') }
        .filter { it.isNotEmpty() }
  }
}
