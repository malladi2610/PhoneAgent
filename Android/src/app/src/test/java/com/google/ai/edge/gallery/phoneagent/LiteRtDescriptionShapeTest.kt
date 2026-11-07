package com.google.ai.edge.gallery.phoneagent

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.malladi2610.phoneagent.core.ChatTurnInfo
import com.malladi2610.phoneagent.core.ollama.LiteRtToolSpecs
import com.malladi2610.phoneagent.core.tool.Surface
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolUi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the JSON shape of LiteRT-LM 0.18.1 `ToolManager.getToolsDescription()`, which
 * `LiteRtToolSpecs.parse` (pa-core) consumes.
 *
 * Observed on 2026-11-07 (LiteRT-LM 0.18.1, JVM unit test; ToolManager loads without JNI):
 * ```
 * [{"type":"function","function":{"name":"set_flashlight","description":"Turns the flashlight on or off",
 *    "parameters":{"type":"object","properties":{"on":{"type":"boolean","description":"true to turn on"}},
 *                  "required":["on"]}}},
 *  {"type":"function","function":{"name":"set_volume","description":"Sets a volume stream to a percentage",
 *    "parameters":{"type":"object","properties":{"stream":{"type":"string","description":"..."},
 *                  "percent":{"type":"integer","description":"0-100"}},"required":["stream","percent"]}}},
 *  {"type":"function","function":{"name":"get_device_status","description":"Battery and network",
 *    "parameters":{"type":"object","properties":{}}}}]
 * ```
 * - It is a JSON array, already in Ollama's `{"type":"function","function":{...}}` format.
 * - `@Tool` (ReflectionTool) names are camelCase → snake_case; params become JSON Schema.
 * - `OpenApiTool` descriptions are passed through verbatim inside the same wrapper.
 */
class LiteRtDescriptionShapeTest {
  class SampleToolSet : ToolSet {
    @Tool(description = "Turns the flashlight on or off")
    fun setFlashlight(@ToolParam(description = "true to turn on") on: Boolean): Map<String, Any> =
      mapOf("status" to "ok", "on" to on)

    @Tool(description = "Sets a volume stream to a percentage")
    fun setVolume(
      @ToolParam(description = "media, ring, alarm or notification") stream: String,
      @ToolParam(description = "0-100") percent: Int,
    ): Map<String, Any> = mapOf("status" to "ok", "stream" to stream, "percent" to percent)
  }

  class SampleOpenApiTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String =
      """{"name":"get_device_status","description":"Battery and network",""" +
        """"parameters":{"type":"object","properties":{}}}"""

    override fun execute(paramsJsonString: String): String = """{"status":"ok","battery":81}"""
  }

  /** Like the phone-tool bridge: returns a ToolOutcome JSON string chosen by the "case" arg. */
  class OutcomeOpenApiTool : OpenApiTool {
    override fun getToolDescriptionJsonString(): String =
      """{"name":"outcome","description":"d","parameters":{"type":"object","properties":{}}}"""

    override fun execute(paramsJsonString: String): String {
      val case = Json.parseToJsonElement(paramsJsonString).jsonObject["case"]!!.jsonPrimitive.content
      return when (case) {
        "denied" -> ToolOutcome.DeniedByUser
        "unlock" -> ToolOutcome.NeedsUnlock
        "perm" -> ToolOutcome.PermissionNeeded("android.permission.SEND_SMS", false)
        "error" -> ToolOutcome.Error("nope")
        "throw" -> throw IllegalStateException("boom")
        else -> ToolOutcome.Ok(JsonObject(mapOf("n" to JsonPrimitive(1))))
      }.toJson().toString()
    }
  }

  private object NoUi : ToolUi {
    override suspend fun confirm(summary: String) = true

    override suspend fun requestRuntimePermission(permission: String) = true

    override suspend fun requestUnlock() = true
  }

  private fun ctx(step: Int = 3) =
    ToolContext(Surface.CHAT, "turn-42", "turn on the torch", "kimi-k3", step, NoUi)

  private val toolManager = ToolManager(listOf(tool(SampleToolSet()), tool(SampleOpenApiTool())))

  @Test
  fun description_isArrayOfOllamaFunctionTools() {
    val json = Json.parseToJsonElement(toolManager.getToolsDescription().toString()) as JsonArray
    assertEquals(3, json.size)
    val first = json[0].jsonObject
    assertEquals("function", first["type"]!!.jsonPrimitive.content)
    val fn = first["function"]!!.jsonObject
    assertEquals("set_flashlight", fn["name"]!!.jsonPrimitive.content)
    assertEquals("Turns the flashlight on or off", fn["description"]!!.jsonPrimitive.content)
    val params = fn["parameters"]!!.jsonObject
    assertEquals("object", params["type"]!!.jsonPrimitive.content)
    assertEquals(
      "boolean",
      params["properties"]!!.jsonObject["on"]!!.jsonObject["type"]!!.jsonPrimitive.content,
    )
    assertEquals("on", params["required"]!!.jsonArray[0].jsonPrimitive.content)

    val volume = json[1].jsonObject["function"]!!.jsonObject
    assertEquals("set_volume", volume["name"]!!.jsonPrimitive.content)
    assertEquals(
      "integer",
      volume["parameters"]!!
        .jsonObject["properties"]!!
        .jsonObject["percent"]!!
        .jsonObject["type"]!!
        .jsonPrimitive
        .content,
    )

    val openApi = json[2].jsonObject
    assertEquals("function", openApi["type"]!!.jsonPrimitive.content)
    assertEquals(
      "get_device_status",
      openApi["function"]!!.jsonObject["name"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun liteRtToolSpecs_parsesObservedShape() {
    val specs = LiteRtToolSpecs.parse(toolManager.getToolsDescription().toString())
    assertEquals(listOf("set_flashlight", "set_volume", "get_device_status"), specs.map { it.name })
  }

  @Test
  fun registry_executesThroughToolManager_andSetsChatTurnInfo() = runBlocking {
    val registry = LiteRtToolRegistry(toolManager)
    assertEquals(listOf("set_flashlight", "set_volume", "get_device_status"), registry.specs().map { it.name })

    val outcome =
      registry.execute("set_flashlight", JsonObject(mapOf("on" to JsonPrimitive(true))), ctx())

    assertEquals(ToolOutcome.Ok(JsonObject(mapOf("on" to JsonPrimitive(true)))), outcome)
    assertEquals("turn-42", ChatTurnInfo.turnId)
    assertEquals("turn on the torch", ChatTurnInfo.userRequest)
    assertEquals("kimi-k3", ChatTurnInfo.model)
    assertEquals(3, ChatTurnInfo.step)

    val volume =
      registry.execute(
        "set_volume",
        JsonObject(mapOf("stream" to JsonPrimitive("media"), "percent" to JsonPrimitive(30))),
        ctx(),
      )
    assertEquals(
      ToolOutcome.Ok(JsonObject(mapOf("stream" to JsonPrimitive("media"), "percent" to JsonPrimitive(30)))),
      volume,
    )
  }

  @Test
  fun registry_openApiStringResult_isParsedAsJson() = runBlocking {
    val outcome = LiteRtToolRegistry(toolManager).execute("get_device_status", JsonObject(emptyMap()), ctx())
    assertEquals(ToolOutcome.Ok(JsonObject(mapOf("battery" to JsonPrimitive(81)))), outcome)
  }

  @Test
  fun registry_mapsStatusJsonBackToOutcomes() = runBlocking {
    val registry = LiteRtToolRegistry(ToolManager(listOf(tool(OutcomeOpenApiTool()))))
    fun run(case: String) = runBlocking {
      registry.execute("outcome", JsonObject(mapOf("case" to JsonPrimitive(case))), ctx())
    }
    assertEquals(ToolOutcome.DeniedByUser, run("denied"))
    assertEquals(ToolOutcome.NeedsUnlock, run("unlock"))
    assertEquals(ToolOutcome.PermissionNeeded("android.permission.SEND_SMS", false), run("perm"))
    assertEquals(ToolOutcome.Error("nope"), run("error"))
    assertEquals(ToolOutcome.Ok(JsonObject(mapOf("n" to JsonPrimitive(1)))), run("ok"))
    val thrown = run("throw")
    assertTrue(thrown.toString(), thrown is ToolOutcome.Error && thrown.reason.contains("boom"))
  }

  @Test
  fun registry_unknownTool_returnsError() = runBlocking {
    val outcome = LiteRtToolRegistry(toolManager).execute("launch_rockets", JsonObject(emptyMap()), ctx())
    assertTrue(outcome.toString(), outcome is ToolOutcome.Error && outcome.reason.contains("launch_rockets"))
  }
}
