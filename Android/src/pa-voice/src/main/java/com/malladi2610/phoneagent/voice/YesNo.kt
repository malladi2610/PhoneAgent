package com.malladi2610.phoneagent.voice

/**
 * Classifies a spoken confirmation. Only a clear yes counts; anything negated, unclear or empty is
 * no (the safe default for outward actions).
 */
object YesNo {
  private val yesWords = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "haan", "ha", "ji", "हाँ", "हां", "जी")
  private val yesPhrases = listOf(listOf("go", "ahead"), listOf("do", "it"), listOf("send", "it"))
  private val negWords =
    setOf("no", "not", "don't", "dont", "nope", "nah", "na", "nahi", "nahin", "cancel", "stop", "wait", "never", "नहीं", "नही", "मत")

  fun isYes(utterance: String): Boolean {
    val tokens = tokenize(utterance)
    if (tokens.isEmpty() || tokens.any { it in negWords }) return false
    if (tokens.any { it in yesWords }) return true
    return yesPhrases.any { phrase -> tokens.windowed(phrase.size).any { it == phrase } }
  }

  private fun tokenize(s: String): List<String> =
    Regex("[\\p{L}\\p{M}']+")
      .findAll(s.lowercase().replace('’', '\''))
      .map { it.value.trim('\'') }
      .filter { it.isNotEmpty() }
      .toList()
}
