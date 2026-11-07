package com.malladi2610.phoneagent.core.secrets

/** API keys never contain whitespace; copy-paste often adds line breaks or invisible characters. */
object ApiKeys {
  private const val INVISIBLE = "​‌‍⁠﻿"

  fun normalize(raw: String): String = raw.filterNot { it.isWhitespace() || it in INVISIBLE }
}
