package com.malladi2610.phoneagent.core.ollama

import com.malladi2610.phoneagent.core.tool.ToolRisk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtToolSpecsTest {
  /** Exactly what LiteRT-LM 0.18.1 `ToolManager.getToolsDescription()` returned (A1). */
  private val observed =
    """[{"type":"function","function":{"name":"set_flashlight","description":"Turns the flashlight on or off","parameters":{"type":"object","properties":{"on":{"type":"boolean","description":"true to turn on"}},"required":["on"]}}},{"type":"function","function":{"name":"set_volume","description":"Sets a volume stream to a percentage","parameters":{"type":"object","properties":{"stream":{"type":"string","description":"media, ring, alarm or notification"},"percent":{"type":"integer","description":"0-100"}},"required":["stream","percent"]}}},{"type":"function","function":{"name":"get_device_status","description":"Battery and network","parameters":{"type":"object","properties":{}}}}]"""

  @Test
  fun parsesObservedLiteRtShape() {
    val specs = LiteRtToolSpecs.parse(observed)
    assertEquals(listOf("set_flashlight", "set_volume", "get_device_status"), specs.map { it.name })
    assertEquals("Turns the flashlight on or off", specs[0].description)
    val expectedParams =
      Json.parseToJsonElement(observed)
        .let { (it as kotlinx.serialization.json.JsonArray)[0] }
        .jsonObject["function"]!!
        .jsonObject["parameters"]!!
        .jsonObject
    assertEquals(expectedParams, specs[0].parameters)
    assertTrue(specs.all { it.risk == ToolRisk.SAFE })
  }

  @Test
  fun roundTripsToOllamaToolFormat() {
    val wire = LiteRtToolSpecs.parse(observed).map(OllamaWire::toWire)
    val encoded = Json.parseToJsonElement(OllamaWire.json.encodeToString(
      kotlinx.serialization.builtins.ListSerializer(WireTool.serializer()), wire))
    assertEquals(Json.parseToJsonElement(observed), encoded)
  }

  @Test
  fun acceptsBareFunctionObjects_andFillsMissingParameters() {
    val specs =
      LiteRtToolSpecs.parse(
        """[{"name":"a","description":"A"},{"name":"b","parameters":{"type":"object","properties":{"x":{"type":"string"}}}}]"""
      )
    assertEquals(listOf("a", "b"), specs.map { it.name })
    assertEquals("", specs[1].description)
    assertEquals(
      Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
      specs[0].parameters,
    )
  }

  @Test
  fun skipsEntriesWithoutName_andDuplicates() {
    val specs =
      LiteRtToolSpecs.parse(
        """[{"type":"function","function":{"description":"no name"}},"junk",""" +
          """{"type":"function","function":{"name":"a","description":"first"}},""" +
          """{"type":"function","function":{"name":"a","description":"second"}}]"""
      )
    assertEquals(listOf("a"), specs.map { it.name })
    assertEquals("first", specs[0].description)
  }

  @Test
  fun invalidJson_returnsEmpty() {
    assertEquals(emptyList<Any>(), LiteRtToolSpecs.parse("not json"))
    assertEquals(emptyList<Any>(), LiteRtToolSpecs.parse("{}"))
  }
}
