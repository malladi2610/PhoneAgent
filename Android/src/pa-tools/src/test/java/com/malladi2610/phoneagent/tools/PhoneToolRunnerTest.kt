package com.malladi2610.phoneagent.tools

import com.malladi2610.phoneagent.core.log.ActionLog
import com.malladi2610.phoneagent.core.log.ActionLogEntry
import com.malladi2610.phoneagent.core.tool.PhoneTool
import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.core.tool.ToolSpec
import com.malladi2610.phoneagent.core.tool.ToolUi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolRunnerTest {

  private class FakeTool(
    name: String,
    risk: ToolRisk,
    private val throwOnRun: Boolean = false,
    required: List<String> = emptyList(),
  ) : PhoneTool {
    val runs = mutableListOf<JsonObject>()

    override val spec =
      ToolSpec(
        name = name,
        description = "fake $name",
        parameters =
          buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
              putJsonObject("number") { put("type", "string") }
              putJsonObject("text") { put("type", "string") }
            }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
          },
        risk = risk,
      )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
      if (throwOnRun) error("boom")
      runs += args
      return ToolOutcome.Ok(buildJsonObject { put("done", true) })
    }

    override fun confirmSummary(args: JsonObject) = "Send SMS to ${args["number"]}"
  }

  private class FakeUi(
    private val confirmResult: Boolean = true,
    private val unlockResult: Boolean = true,
  ) : ToolUi {
    val confirms = mutableListOf<String>()
    var unlockRequests = 0

    override suspend fun confirm(summary: String): Boolean {
      confirms += summary
      return confirmResult
    }

    override suspend fun requestRuntimePermission(permission: String) = false

    override suspend fun requestUnlock(): Boolean {
      unlockRequests++
      return unlockResult
    }
  }

  private class MemoryLog : ActionLog {
    val entries = mutableListOf<ActionLogEntry>()

    override fun record(entry: ActionLogEntry) {
      entries += entry
    }
  }

  private val smsArgs = buildJsonObject {
    put("number", "+491511234567")
    put("text", "running late")
  }

  private fun ctx(surface: Surface, ui: ToolUi) =
    ToolContext(
      surface = surface,
      turnId = "turn-1",
      userRequest = "text mom",
      model = "m",
      step = 2,
      ui = ui,
    )

  @Test
  fun safe_runsWithoutPrompt() = runTest {
    val tool = FakeTool("set_flashlight", ToolRisk.SAFE)
    val ui = FakeUi()
    val runner = PhoneToolRunner(listOf(tool), MemoryLog())

    val outcome = runner.execute("set_flashlight", JsonObject(emptyMap()), ctx(Surface.VOICE_LOCKED, ui))

    assertTrue(outcome is ToolOutcome.Ok)
    assertEquals(1, tool.runs.size)
    assertTrue(ui.confirms.isEmpty())
    assertEquals(0, ui.unlockRequests)
  }

  @Test
  fun outwardInstant_unlocked_confirmAllowed_runs_logsAllowed() = runTest {
    val tool = FakeTool("send_sms", ToolRisk.OUTWARD_INSTANT)
    val ui = FakeUi(confirmResult = true)
    val log = MemoryLog()
    val runner = PhoneToolRunner(listOf(tool), log)

    val outcome = runner.execute("send_sms", smsArgs, ctx(Surface.CHAT, ui))

    assertTrue(outcome is ToolOutcome.Ok)
    assertEquals(1, tool.runs.size)
    assertEquals(listOf("Send SMS to \"+491511234567\""), ui.confirms)
    assertEquals(0, ui.unlockRequests)
    assertEquals("allowed", log.entries.single().confirmation)
  }

  @Test
  fun outwardInstant_unlocked_confirmDenied_returnsDenied_doesNotRun_logsDenied() = runTest {
    val tool = FakeTool("send_sms", ToolRisk.OUTWARD_INSTANT)
    val log = MemoryLog()
    val runner = PhoneToolRunner(listOf(tool), log)

    val outcome = runner.execute("send_sms", smsArgs, ctx(Surface.VOICE_UNLOCKED, FakeUi(confirmResult = false)))

    assertEquals(ToolOutcome.DeniedByUser, outcome)
    assertTrue(tool.runs.isEmpty())
    assertEquals("denied", log.entries.single().confirmation)
    assertEquals("denied_by_user", log.entries.single().resultStatus)
  }

  @Test
  fun lockedOutwardInstant_requiresUnlockThenConfirm_neverRunsOnDenial() = runTest {
    val tool = FakeTool("send_sms", ToolRisk.OUTWARD_INSTANT)
    val runner = PhoneToolRunner(listOf(tool), MemoryLog())

    // Unlock refused: confirm is never asked, nothing runs.
    val noUnlockUi = FakeUi(confirmResult = true, unlockResult = false)
    val first = runner.execute("send_sms", smsArgs, ctx(Surface.VOICE_LOCKED, noUnlockUi))
    assertEquals(ToolOutcome.NeedsUnlock, first)
    assertEquals(1, noUnlockUi.unlockRequests)
    assertTrue(noUnlockUi.confirms.isEmpty())
    assertTrue(tool.runs.isEmpty())

    // Unlocked, but the user says no: still nothing runs.
    val denyUi = FakeUi(confirmResult = false, unlockResult = true)
    val second = runner.execute("send_sms", smsArgs, ctx(Surface.VOICE_LOCKED, denyUi))
    assertEquals(ToolOutcome.DeniedByUser, second)
    assertEquals(1, denyUi.unlockRequests)
    assertEquals(1, denyUi.confirms.size)
    assertTrue(tool.runs.isEmpty())

    // Unlocked and confirmed: runs exactly once.
    val allowUi = FakeUi(confirmResult = true, unlockResult = true)
    val third = runner.execute("send_sms", smsArgs, ctx(Surface.VOICE_LOCKED, allowUi))
    assertTrue(third is ToolOutcome.Ok)
    assertEquals(1, tool.runs.size)
  }

  @Test
  fun lockedSensitiveRead_unlockRefused_returnsNeedsUnlock() = runTest {
    val tool = FakeTool("find_contact", ToolRisk.SENSITIVE_READ)
    val ui = FakeUi(unlockResult = false)
    val runner = PhoneToolRunner(listOf(tool), MemoryLog())

    val outcome = runner.execute("find_contact", JsonObject(emptyMap()), ctx(Surface.VOICE_LOCKED, ui))

    assertEquals(ToolOutcome.NeedsUnlock, outcome)
    assertTrue(tool.runs.isEmpty())
    assertTrue(ui.confirms.isEmpty())
  }

  @Test
  fun unknownTool_returnsError() = runTest {
    val runner = PhoneToolRunner(listOf(FakeTool("set_flashlight", ToolRisk.SAFE)), MemoryLog())

    val outcome = runner.execute("launch_rocket", JsonObject(emptyMap()), ctx(Surface.CHAT, FakeUi()))

    assertTrue(outcome is ToolOutcome.Error)
    assertTrue((outcome as ToolOutcome.Error).reason.contains("launch_rocket"))
  }

  @Test
  fun missingRequiredArg_returnsError_withoutRunning() = runTest {
    val tool = FakeTool("send_sms", ToolRisk.OUTWARD_INSTANT, required = listOf("number", "text"))
    val ui = FakeUi()
    val runner = PhoneToolRunner(listOf(tool), MemoryLog())

    val outcome =
      runner.execute("send_sms", buildJsonObject { put("number", "+49") }, ctx(Surface.CHAT, ui))

    assertTrue(outcome is ToolOutcome.Error)
    assertTrue((outcome as ToolOutcome.Error).reason.contains("text"))
    assertTrue(tool.runs.isEmpty())
    assertTrue("no confirm card for invalid args", ui.confirms.isEmpty())
  }

  @Test
  fun toolThrows_returnsError() = runTest {
    val runner = PhoneToolRunner(listOf(FakeTool("set_flashlight", ToolRisk.SAFE, throwOnRun = true)), MemoryLog())

    val outcome = runner.execute("set_flashlight", JsonObject(emptyMap()), ctx(Surface.CHAT, FakeUi()))

    assertTrue(outcome is ToolOutcome.Error)
    assertTrue((outcome as ToolOutcome.Error).reason.contains("boom"))
  }

  @Test
  fun everyCall_writesOneLogEntry_withLatencyAndStatus() = runTest {
    var now = 1_000L
    val log = MemoryLog()
    val tools =
      listOf(
        object : PhoneTool {
          override val spec = FakeTool("slow", ToolRisk.SAFE).spec

          override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
            now += 250
            return ToolOutcome.Ok()
          }
        },
        FakeTool("send_sms", ToolRisk.OUTWARD_INSTANT),
        FakeTool("crash", ToolRisk.SAFE, throwOnRun = true),
      )
    val runner = PhoneToolRunner(tools, log, clock = { now })
    val c = ctx(Surface.VOICE_LOCKED, FakeUi(unlockResult = false))

    runner.execute("slow", smsArgs, c)
    runner.execute("send_sms", smsArgs, c)
    runner.execute("crash", JsonObject(emptyMap()), c)
    runner.execute("nope", JsonObject(emptyMap()), c)

    assertEquals(4, log.entries.size)
    val slow = log.entries[0]
    assertEquals(1_000L, slow.ts)
    assertEquals(250L, slow.latencyMs)
    assertEquals("ok", slow.resultStatus)
    assertEquals("n/a", slow.confirmation)
    assertEquals("turn-1", slow.chatId)
    assertEquals("VOICE_LOCKED", slow.surface)
    assertEquals("text mom", slow.userRequest)
    assertEquals("m", slow.model)
    assertEquals(2, slow.step)
    assertEquals("slow", slow.tool)
    assertEquals(smsArgs, slow.args)
    assertTrue(slow.result.contains("\"status\":\"ok\""))
    assertEquals("needs_unlock", log.entries[1].resultStatus)
    assertEquals("error", log.entries[2].resultStatus)
    assertEquals("error", log.entries[3].resultStatus)
    assertEquals("nope", log.entries[3].tool)
    assertFalse(log.entries.any { it.latencyMs < 0 })
  }
}
