package com.malladi2610.phoneagent.tools

import android.util.Log
import com.malladi2610.phoneagent.core.log.ActionLog
import com.malladi2610.phoneagent.core.log.ActionLogEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Appends one JSON line per tool call to `dir/YYYY-MM-DD.jsonl` (spec M1 §5.6), using the entry's
 * timestamp in [zone] to pick the day. [redact] runs over the whole serialized line before it is
 * written (use [redactValues] with the API keys). Never throws: logging must not break a tool call.
 *
 * Production location: `File(context.filesDir, "action_log")`.
 */
class JsonlActionLog(
  private val dir: File,
  private val redact: (String) -> String,
  private val zone: ZoneId = ZoneId.systemDefault(),
) : ActionLog {
  private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  override fun record(entry: ActionLogEntry) {
    try {
      val line = redact(toJsonLine(entry))
      val day = dayFormat.format(Instant.ofEpochMilli(entry.ts).atZone(zone))
      synchronized(this) {
        if (!dir.isDirectory && !dir.mkdirs()) error("can't create ${dir.name}")
        File(dir, "$day.jsonl").appendText(line + "\n")
      }
    } catch (t: Throwable) {
      // Message only: the entry itself could carry personal data.
      runCatching { Log.w(TAG, "ActionLog write failed: ${t.javaClass.simpleName}") }
    }
  }

  private fun toJsonLine(e: ActionLogEntry): String =
    buildJsonObject {
        put("ts", e.ts)
        put("chat_id", e.chatId)
        put("surface", e.surface)
        put("user_request", e.userRequest)
        put("model", e.model)
        put("step", e.step)
        put("tool", e.tool)
        put("args", e.args)
        put("result_status", e.resultStatus)
        put("result", e.result.take(MAX_RESULT_CHARS))
        put("confirmation", e.confirmation)
        put("latency_ms", e.latencyMs)
      }
      .toString()

  companion object {
    private const val TAG = "PaActionLog"
    const val MAX_RESULT_CHARS = 2048
    private const val MIN_SECRET_LENGTH = 6

    /**
     * A redactor that replaces every current secret value (read on each call, so a changed key is
     * covered) with `[REDACTED]`. Null, blank and very short values are skipped so they can't
     * mangle the JSON line.
     */
    fun redactValues(secrets: () -> List<String?>): (String) -> String = { line ->
      secrets()
        .filterNotNull()
        .filter { it.length >= MIN_SECRET_LENGTH }
        .fold(line) { acc, secret -> acc.replace(secret, "[REDACTED]") }
    }
  }
}
