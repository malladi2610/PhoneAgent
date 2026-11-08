package com.malladi2610.phoneagent.core.log

import kotlinx.serialization.json.JsonObject

/**
 * One line of the ActionLog (spec M1 §5.6) — the future fine-tuning dataset.
 * [confirmation] is "allowed", "denied" or "n/a". Must never contain an API key.
 */
data class ActionLogEntry(
  val ts: Long,
  val chatId: String,
  val surface: String,
  val userRequest: String,
  val model: String,
  val step: Int,
  val tool: String,
  val args: JsonObject,
  val resultStatus: String,
  /** Tool result JSON, truncated to 2 KB. */
  val result: String,
  val confirmation: String,
  val latencyMs: Long,
)

interface ActionLog {
  fun record(entry: ActionLogEntry)
}
