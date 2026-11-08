package com.malladi2610.phoneagent.tools.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppMatcherTest {
  private val labels =
    listOf("Instagram", "Spotify", "Camera", "CamScanner", "Settings", "Samsung Notes", "Gmail")

  @Test
  fun exact_isCaseInsensitive() {
    assertEquals(AppMatcher.Result.Match("Spotify"), AppMatcher.match("spotify", labels))
  }

  @Test
  fun exact_beatsPrefix() {
    // "Camera" is also a prefix candidate next to CamScanner's "cam"; exact must win.
    assertEquals(AppMatcher.Result.Match("Camera"), AppMatcher.match("Camera", labels))
  }

  @Test
  fun prefix_insta_matchesInstagram() {
    assertEquals(AppMatcher.Result.Match("Instagram"), AppMatcher.match("insta", labels))
  }

  @Test
  fun ambiguousPrefix_returnsCandidates() {
    assertEquals(
      AppMatcher.Result.Candidates(listOf("Camera", "CamScanner")),
      AppMatcher.match("cam", labels),
    )
  }

  @Test
  fun contains_matchesInnerWord() {
    assertEquals(AppMatcher.Result.Match("Samsung Notes"), AppMatcher.match("notes", labels))
  }

  @Test
  fun fuzzy_withinEditDistanceTwo() {
    assertEquals(AppMatcher.Result.Match("Spotify"), AppMatcher.match("spotfy", labels))
    assertEquals(AppMatcher.Result.Match("Gmail"), AppMatcher.match("gmial", labels))
  }

  @Test
  fun tooFar_isNoMatch() {
    assertEquals(AppMatcher.Result.NoMatch, AppMatcher.match("whatsapp", labels))
  }

  @Test
  fun blankQuery_isNoMatch() {
    assertEquals(AppMatcher.Result.NoMatch, AppMatcher.match("  ", labels))
  }

  @Test
  fun duplicateLabels_collapseToOneMatch() {
    assertEquals(
      AppMatcher.Result.Match("Camera"),
      AppMatcher.match("camera", listOf("Camera", "Camera")),
    )
  }

  @Test
  fun editDistance_basic() {
    assertEquals(0, AppMatcher.editDistance("abc", "abc"))
    assertEquals(1, AppMatcher.editDistance("abc", "abd"))
    assertEquals(3, AppMatcher.editDistance("", "abc"))
  }
}
