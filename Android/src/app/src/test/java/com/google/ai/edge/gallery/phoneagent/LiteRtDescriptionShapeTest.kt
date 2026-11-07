package com.google.ai.edge.gallery.phoneagent

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
}
