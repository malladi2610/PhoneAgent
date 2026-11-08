package com.malladi2610.phoneagent.tools.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgValidatorTest {
  private val schema =
    ToolSchema.obj {
      string("stream", "which stream", required = true, enum = listOf("media", "ring"))
      integer("percent", "0-100", required = true)
      boolean("on", "torch on")
      number("ratio", "a ratio")
      stringArray("days", "weekdays")
    }

  private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    buildJsonObject(block)

  @Test
  fun validArgs_pass() {
    val a = args {
      put("stream", "media")
      put("percent", 30)
      put("on", true)
      put("ratio", 0.5)
      putJsonArray("days") { add(JsonPrimitive("mon")) }
    }
    assertNull(ArgValidator.validate(schema, a))
  }

  @Test
  fun missingRequired_namesTheKey() {
    val error = ArgValidator.validate(schema, args { put("stream", "media") })
    assertNotNull(error)
    assertTrue(error!!.contains("percent"))
  }

  @Test
  fun nullRequired_countsAsMissing() {
    val a = buildJsonObject {
      put("stream", "media")
      put("percent", JsonNull)
    }
    assertTrue(ArgValidator.validate(schema, a)!!.contains("percent"))
  }

  @Test
  fun wrongTypes_areRejected() {
    assertNotNull(ArgValidator.validate(schema, args { put("stream", 3); put("percent", 3) }))
    assertNotNull(ArgValidator.validate(schema, args { put("stream", "media"); put("percent", "30") }))
    assertNotNull(ArgValidator.validate(schema, args { put("stream", "media"); put("percent", 30.5) }))
    assertNotNull(
      ArgValidator.validate(schema, args { put("stream", "media"); put("percent", 3); put("on", "yes") })
    )
    assertNotNull(
      ArgValidator.validate(schema, args { put("stream", "media"); put("percent", 3); put("days", "mon") })
    )
  }

  @Test
  fun wholeNumberFloat_acceptedAsInteger() {
    assertNull(ArgValidator.validate(schema, args { put("stream", "media"); put("percent", 30.0) }))
  }

  @Test
  fun enumViolation_listsAllowedValues() {
    val error = ArgValidator.validate(schema, args { put("stream", "loud"); put("percent", 3) })
    assertTrue(error!!.contains("media"))
  }

  @Test
  fun unknownExtraKeys_areIgnored() {
    assertNull(
      ArgValidator.validate(schema, args { put("stream", "ring"); put("percent", 3); put("x", 1) })
    )
  }

  @Test
  fun schemaBuilder_producesJsonSchemaObject() {
    assertEquals(JsonPrimitive("object"), schema["type"])
    val props = schema["properties"] as JsonObject
    assertEquals(JsonPrimitive("integer"), (props["percent"] as JsonObject)["type"])
    assertEquals(
      listOf("stream", "percent"),
      (schema["required"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content },
    )
  }
}
