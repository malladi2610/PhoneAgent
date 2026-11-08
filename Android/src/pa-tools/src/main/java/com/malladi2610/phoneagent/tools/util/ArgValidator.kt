package com.malladi2610.phoneagent.tools.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Checks tool-call arguments against the tool's JSON Schema: required keys, primitive types and
 * string enums. Unknown extra keys are ignored. Returns a model-readable error or null when valid.
 */
object ArgValidator {
  fun validate(schema: JsonObject, args: JsonObject): String? {
    val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required =
      (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    val missing = required.filter { args[it] == null || args[it] is JsonNull }
    if (missing.isNotEmpty()) return "missing required argument(s): ${missing.joinToString(", ")}"

    for ((key, value) in args) {
      if (value is JsonNull) continue
      val prop = properties[key] as? JsonObject ?: continue
      val type = (prop["type"] as? JsonPrimitive)?.content ?: continue
      if (!matchesType(type, value)) return "argument '$key' must be of type $type"
      val enum = (prop["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
      if (enum != null && value is JsonPrimitive && value.content !in enum) {
        return "argument '$key' must be one of: ${enum.joinToString(", ")}"
      }
    }
    return null
  }

  private fun matchesType(type: String, value: JsonElement): Boolean =
    when (type) {
      "string" -> value is JsonPrimitive && value.isString
      "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
      "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
      "integer" ->
        value is JsonPrimitive &&
          !value.isString &&
          value.doubleOrNull?.let { it == Math.floor(it) && !it.isInfinite() } == true
      "array" -> value is JsonArray
      "object" -> value is JsonObject
      else -> true
    }
}

/** Builds the JSON Schema `parameters` object of a [com.malladi2610.phoneagent.core.tool.ToolSpec]. */
class ToolSchema private constructor() {
  private val properties = linkedMapOf<String, JsonObject>()
  private val required = mutableListOf<String>()

  fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
    add(name, required) {
      put("type", "string")
      put("description", description)
      if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
    }

  fun integer(name: String, description: String, required: Boolean = false) =
    add(name, required) {
      put("type", "integer")
      put("description", description)
    }

  fun number(name: String, description: String, required: Boolean = false) =
    add(name, required) {
      put("type", "number")
      put("description", description)
    }

  fun boolean(name: String, description: String, required: Boolean = false) =
    add(name, required) {
      put("type", "boolean")
      put("description", description)
    }

  fun stringArray(name: String, description: String, required: Boolean = false) =
    add(name, required) {
      put("type", "array")
      put("description", description)
      put("items", buildJsonObject { put("type", "string") })
    }

  private fun add(
    name: String,
    isRequired: Boolean,
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
  ) {
    properties[name] = buildJsonObject(block)
    if (isRequired) required += name
  }

  private fun build(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    put("required", JsonArray(required.map(::JsonPrimitive)))
  }

  companion object {
    fun obj(block: ToolSchema.() -> Unit = {}): JsonObject = ToolSchema().apply(block).build()
  }
}

/** Typed reads of validated arguments. Missing or null values return null. */
fun JsonObject.str(key: String): String? =
  (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun JsonObject.int(key: String): Int? =
  (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull?.toInt()

fun JsonObject.bool(key: String): Boolean? =
  (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

fun JsonObject.strList(key: String): List<String>? =
  (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
