package com.malladi2610.phoneagent.tools

import com.malladi2610.phoneagent.core.log.ActionLogEntry
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlActionLogTest {
  private val dir: File = Files.createTempDirectory("actionlog").toFile()
  private val utc = ZoneId.of("UTC")

  @After
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun millis(y: Int, m: Int, d: Int, h: Int = 12) =
    LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

  private fun entry(
    ts: Long = millis(2026, 11, 7),
    args: JsonObject = buildJsonObject { put("on", true) },
    result: String = """{"status":"ok"}""",
  ) =
    ActionLogEntry(
      ts = ts,
      chatId = "turn-1",
      surface = "CHAT",
      userRequest = "torch on",
      model = "deepseek-v4.1-flash",
      step = 1,
      tool = "set_flashlight",
      args = args,
      resultStatus = "ok",
      result = result,
      confirmation = "n/a",
      latencyMs = 42,
    )

  @Test
  fun writesOneJsonLinePerEntry_snakeCaseKeys() {
    val log = JsonlActionLog(dir, redact = { it }, zone = utc)
    log.record(entry())
    log.record(entry())

    val lines = File(dir, "2026-11-07.jsonl").readLines()
    assertEquals(2, lines.size)
    val obj = Json.parseToJsonElement(lines[0]).jsonObject
    assertEquals(
      setOf(
        "ts", "chat_id", "surface", "user_request", "model", "step", "tool", "args",
        "result_status", "result", "confirmation", "latency_ms",
      ),
      obj.keys,
    )
    assertEquals(JsonPrimitive("turn-1"), obj["chat_id"])
    assertEquals(JsonPrimitive(42L), obj["latency_ms"])
    assertEquals(buildJsonObject { put("on", true) }, obj["args"])
    assertEquals(JsonPrimitive("""{"status":"ok"}"""), obj["result"])
  }

  @Test
  fun truncatesResultTo2048Chars() {
    val log = JsonlActionLog(dir, redact = { it }, zone = utc)
    log.record(entry(result = "x".repeat(5000)))

    val obj = Json.parseToJsonElement(File(dir, "2026-11-07.jsonl").readLines()[0]).jsonObject
    assertEquals(2048, (obj["result"] as JsonPrimitive).content.length)
  }

  @Test
  fun redactsSecrets() {
    val key = "sk-ollama-SECRET-123"
    val log = JsonlActionLog(dir, redact = JsonlActionLog.redactValues { listOf(key, null, "") }, zone = utc)
    log.record(
      entry(
        args = buildJsonObject { put("text", "my key is $key") },
        result = """{"status":"ok","echo":"$key"}""",
      )
    )

    val text = File(dir, "2026-11-07.jsonl").readText()
    assertFalse(text.contains(key))
    assertTrue(text.contains("[REDACTED]"))
    // Still one valid JSON line after redaction.
    Json.parseToJsonElement(text.trim())
  }

  @Test
  fun rollsFilePerDay() {
    val log = JsonlActionLog(dir, redact = { it }, zone = utc)
    log.record(entry(ts = millis(2026, 11, 7, h = 23)))
    log.record(entry(ts = millis(2026, 11, 8, h = 0)))

    assertEquals(1, File(dir, "2026-11-07.jsonl").readLines().size)
    assertEquals(1, File(dir, "2026-11-08.jsonl").readLines().size)
  }

  @Test
  fun unwritableDir_doesNotThrow() {
    val notADir = File(dir, "file").apply { writeText("x") }
    JsonlActionLog(notADir, redact = { it }, zone = utc).record(entry())
  }
}
