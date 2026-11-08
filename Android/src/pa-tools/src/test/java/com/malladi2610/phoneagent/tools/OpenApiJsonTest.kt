package com.malladi2610.phoneagent.tools

import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.core.tool.ToolSpec
import com.malladi2610.phoneagent.core.tool.ToolUi
import com.malladi2610.phoneagent.tools.util.ToolSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenApiJsonTest {
  private val spec =
    ToolSpec(
      name = "set_flashlight",
      description = "Turn the torch on or off.",
      parameters = ToolSchema.obj { boolean("on", "true = on", required = true) },
      risk = ToolRisk.SAFE,
    )

  private object NoUi : ToolUi {
    override suspend fun confirm(summary: String) = false

    override suspend fun requestRuntimePermission(permission: String) = false

    override suspend fun requestUnlock() = false
  }

  private val ctx = ToolContext(Surface.CHAT, "t", "u", "m", 1, NoUi)

  private class RecordingRegistry(private val result: () -> ToolOutcome) : ToolRegistry {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override fun specs() = emptyList<ToolSpec>()

    override suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolOutcome {
      calls += name to args
      return result()
    }
  }

  @Test
  fun description_hasNameDescriptionParameters() {
    val obj = Json.parseToJsonElement(OpenApiJson.description(spec)).jsonObject
    assertEquals(setOf("name", "description", "parameters"), obj.keys)
    assertEquals(JsonPrimitive("set_flashlight"), obj["name"])
    assertEquals(spec.parameters, obj["parameters"])
  }

  @Test
  fun execute_parsesArgs_andReturnsOutcomeJson() = runTest {
    val registry = RecordingRegistry { ToolOutcome.Ok(buildJsonObject { put("on", true) }) }
    val out = OpenApiJson.execute(registry, "set_flashlight", """{"on":true}""", ctx)
    assertEquals(listOf("set_flashlight" to buildJsonObject { put("on", true) }), registry.calls)
    assertEquals("""{"status":"ok","on":true}""", out)
  }

  @Test
  fun execute_blankArgs_isEmptyObject() = runTest {
    val registry = RecordingRegistry { ToolOutcome.Ok() }
    OpenApiJson.execute(registry, "get_device_status", "", ctx)
    assertEquals(JsonObject(emptyMap()), registry.calls.single().second)
  }

  @Test
  fun execute_malformedArgs_returnsErrorWithoutCalling() = runTest {
    val registry = RecordingRegistry { ToolOutcome.Ok() }
    val out =
      Json.parseToJsonElement(OpenApiJson.execute(registry, "x", "{not json", ctx)).jsonObject
    assertEquals(JsonPrimitive("error"), out["status"])
    assertEquals(0, registry.calls.size)
  }

  @Test
  fun execute_registryThrows_returnsError() = runTest {
    val registry = RecordingRegistry { error("kaput") }
    val out = Json.parseToJsonElement(OpenApiJson.execute(registry, "x", "{}", ctx)).jsonObject
    assertEquals(JsonPrimitive("error"), out["status"])
    assertEquals(JsonPrimitive("kaput"), out["reason"])
  }
}
