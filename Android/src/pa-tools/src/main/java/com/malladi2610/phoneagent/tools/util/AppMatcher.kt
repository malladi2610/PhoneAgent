package com.malladi2610.phoneagent.tools.util

/**
 * Matches a spoken/typed app name against launcher labels, case-insensitively, in tiers:
 * exact > prefix > contains > fuzzy (edit distance ≤ 2, queries of 4+ chars). The first tier with
 * any hit decides: one distinct label → [Result.Match], several → [Result.Candidates].
 */
object AppMatcher {
  sealed interface Result {
    data class Match(val label: String) : Result

    data class Candidates(val labels: List<String>) : Result

    data object NoMatch : Result
  }

  private const val MAX_FUZZY_DISTANCE = 2
  private const val MIN_FUZZY_QUERY = 4

  fun match(query: String, labels: List<String>): Result {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return Result.NoMatch
    val distinct = labels.distinct()
    val lower = distinct.map { it.trim().lowercase() }

    val tiers: List<(String) -> Boolean> =
      listOf({ it == q }, { it.startsWith(q) }, { it.contains(q) })
    for (tier in tiers) {
      val hits = distinct.filterIndexed { i, _ -> tier(lower[i]) }
      if (hits.isNotEmpty()) return toResult(hits)
    }

    if (q.length < MIN_FUZZY_QUERY) return Result.NoMatch
    val distances = lower.map { editDistance(q, it) }
    val best = distances.minOrNull() ?: return Result.NoMatch
    if (best > MAX_FUZZY_DISTANCE) return Result.NoMatch
    return toResult(distinct.filterIndexed { i, _ -> distances[i] == best })
  }

  private fun toResult(hits: List<String>): Result =
    if (hits.size == 1) Result.Match(hits[0]) else Result.Candidates(hits)

  /** Levenshtein distance. */
  fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
      cur[0] = i
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
      }
      val t = prev
      prev = cur
      cur = t
    }
    return prev[b.length]
  }
}
