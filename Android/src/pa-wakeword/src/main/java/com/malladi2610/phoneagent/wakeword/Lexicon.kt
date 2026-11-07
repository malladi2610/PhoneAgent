package com.malladi2610.phoneagent.wakeword

import java.io.InputStream

/**
 * Word → ARPAbet phones, read from the KWS model's `en.phone` file (lines like
 * `JARVIS JH AA1 R V AH0 S`). When a word has several lines, the first one wins.
 *
 * The full file has ~126k words (3.3 MB); callers that only need a phrase pass [parse]'s `only` so
 * the map stays tiny.
 */
class Lexicon private constructor(private val entries: Map<String, String>) {
  /** Space-separated phones for an upper-case [word], or null if the word is not in the lexicon. */
  fun phones(word: String): String? = entries[word]

  val size: Int
    get() = entries.size

  companion object {
    /** Asset path of the lexicon inside the module's assets. */
    const val ASSET_PATH = "wakeword/en.phone"

    fun parse(lines: Sequence<String>, only: Set<String>? = null): Lexicon {
      val map = HashMap<String, String>()
      for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val split = line.indexOfFirst { it.isWhitespace() }
        if (split <= 0) continue
        val word = line.substring(0, split)
        if (only != null && word !in only) continue
        if (word in map) continue
        val phones = line.substring(split + 1).trim().split(WHITESPACE).joinToString(" ")
        if (phones.isNotEmpty()) map[word] = phones
      }
      return Lexicon(map)
    }

    /** Reads [input] (closed afterwards), keeping only [only] when given. */
    fun load(input: InputStream, only: Set<String>? = null): Lexicon =
      input.bufferedReader(Charsets.UTF_8).useLines { parse(it, only) }

    private val WHITESPACE = Regex("\\s+")
  }
}
