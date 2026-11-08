package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.core.tool.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Converts LiteRT-LM's `ToolManager.getToolsDescription()` JSON into [ToolSpec]s.
 *
 * Observed shape (LiteRT-LM 0.18.1, see app `LiteRtDescriptionShapeTest`): a JSON array that is
 * already Ollama's tool format,
 * `[{"type":"function","function":{"name":..,"description":..,"parameters":<JSON Schema>}}, ...]`.
 * Bare `{"name",...}` entries are accepted too. Entries without a name and duplicate names are
 * dropped; missing parameters become an empty object schema.
 *
 * Risk is always [ToolRisk.SAFE] here: the phone tools gate themselves inside the phone-tool
 * bridge, and Gallery's own tools (skills, MCP) have their own permission flow.
 */
object LiteRtToolSpecs {
  private val EMPTY_SCHEMA =
    JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))

  fun parse(descriptionJson: String): List<ToolSpec> {
    val root = runCatching { Json.parseToJsonElement(descriptionJson) }.getOrNull() as? JsonArray
      ?: return emptyList()
    val seen = mutableSetOf<String>()
    return root.mapNotNull { entry ->
      val obj = entry as? JsonObject ?: return@mapNotNull null
      val fn = obj["function"] as? JsonObject ?: obj
      val name = fn.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      if (!seen.add(name)) return@mapNotNull null
      ToolSpec(
        name = name,
        description = fn.string("description") ?: "",
        parameters = fn["parameters"] as? JsonObject ?: EMPTY_SCHEMA,
        risk = ToolRisk.SAFE,
      )
    }
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
