package com.malladi2610.phoneagent.tools

import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * String-in/string-out glue for LiteRT-LM's `OpenApiTool` (used by the app's Gallery bridge), kept
 * here so it is unit-testable without LiteRT on the classpath.
 */
object OpenApiJson {
  /** `{name, description, parameters}` — what `OpenApiTool.getToolDescriptionJsonString` returns. */
  fun description(spec: ToolSpec): String =
    buildJsonObject {
        put("name", spec.name)
        put("description", spec.description)
        put("parameters", spec.parameters)
      }
      .toString()

  /** Parses [paramsJson], runs [name] through [registry], returns the outcome JSON. Never throws. */
  suspend fun execute(
    registry: ToolRegistry,
    name: String,
    paramsJson: String,
    ctx: ToolContext,
  ): String {
    val args =
      if (paramsJson.isBlank()) JsonObject(emptyMap())
      else
        runCatching { Json.parseToJsonElement(paramsJson) as JsonObject }.getOrNull()
          ?: return ToolOutcome.Error("arguments must be a JSON object").toJson().toString()
    val outcome =
      try {
        registry.execute(name, args, ctx)
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        ToolOutcome.Error(t.message ?: t.javaClass.simpleName)
      }
    return outcome.toJson().toString()
  }
}
