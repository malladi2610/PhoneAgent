package com.malladi2610.phoneagent.tools

import com.malladi2610.phoneagent.core.log.ActionLog
import com.malladi2610.phoneagent.core.log.ActionLogEntry
import com.malladi2610.phoneagent.core.tool.Gate
import com.malladi2610.phoneagent.core.tool.PhoneTool
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolSpec
import com.malladi2610.phoneagent.core.tool.gateFor
import com.malladi2610.phoneagent.tools.util.ArgValidator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The gated, logged registry of phone tools. For every call it:
 * 1. validates the arguments against the tool's schema (invalid → error, no prompts),
 * 2. applies [gateFor] (unlock and/or confirm through [ToolContext.ui]),
 * 3. runs the tool, turning any exception into [ToolOutcome.Error],
 * 4. writes exactly one [ActionLogEntry].
 *
 * Coroutine cancellation is rethrown (after logging) so a stopped turn really stops.
 */
class PhoneToolRunner(
  tools: List<PhoneTool>,
  private val log: ActionLog,
  private val clock: () -> Long = System::currentTimeMillis,
) : ToolRegistry {
  private val byName: Map<String, PhoneTool> = tools.associateBy { it.spec.name }

  override fun specs(): List<ToolSpec> = byName.values.map { it.spec }

  override suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolOutcome {
    val start = clock()
    var confirmation = CONFIRMATION_NA
    var outcome: ToolOutcome = ToolOutcome.Error("internal error")
    try {
      outcome = run {
        val tool = byName[name] ?: return@run ToolOutcome.Error("unknown tool: $name")
        ArgValidator.validate(tool.spec.parameters, args)?.let {
          return@run ToolOutcome.Error(it)
        }
        val gate = gateFor(tool.spec.risk, ctx.surface)
        if (gate == Gate.UNLOCK_THEN_RUN || gate == Gate.UNLOCK_THEN_CONFIRM) {
          if (!ctx.ui.requestUnlock()) return@run ToolOutcome.NeedsUnlock
        }
        if (gate == Gate.CONFIRM || gate == Gate.UNLOCK_THEN_CONFIRM) {
          val allowed = ctx.ui.confirm(tool.confirmSummary(args))
          confirmation = if (allowed) CONFIRMATION_ALLOWED else CONFIRMATION_DENIED
          if (!allowed) return@run ToolOutcome.DeniedByUser
        }
        tool.run(args, ctx)
      }
    } catch (e: CancellationException) {
      outcome = ToolOutcome.Error("cancelled")
      throw e
    } catch (t: Throwable) {
      outcome = ToolOutcome.Error(t.message ?: t.javaClass.simpleName)
    } finally {
      writeLog(start, name, args, ctx, outcome, confirmation)
    }
    return outcome
  }

  private fun writeLog(
    start: Long,
    name: String,
    args: JsonObject,
    ctx: ToolContext,
    outcome: ToolOutcome,
    confirmation: String,
  ) {
    val json = outcome.toJson()
    val entry =
      ActionLogEntry(
        ts = start,
        chatId = ctx.turnId,
        surface = ctx.surface.name,
        userRequest = ctx.userRequest,
        model = ctx.model,
        step = ctx.step,
        tool = name,
        args = args,
        resultStatus = (json["status"] as? JsonPrimitive)?.content ?: "unknown",
        result = json.toString().take(MAX_RESULT_CHARS),
        confirmation = confirmation,
        latencyMs = (clock() - start).coerceAtLeast(0),
      )
    // Logging must never break a tool call.
    runCatching { log.record(entry) }
  }

  companion object {
    const val CONFIRMATION_ALLOWED = "allowed"
    const val CONFIRMATION_DENIED = "denied"
    const val CONFIRMATION_NA = "n/a"
    const val MAX_RESULT_CHARS = 2048
  }
}
