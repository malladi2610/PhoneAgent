package com.malladi2610.phoneagent.tools

import android.content.ContextWrapper
import com.malladi2610.phoneagent.core.tool.ToolRisk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tool table must match spec M1 §5.3 exactly (names, parameters, risks). */
class PhoneToolsTest {
  private val tools = createPhoneTools(ContextWrapper(null))
  private val byName = tools.associateBy { it.spec.name }

  private fun required(name: String): List<String> =
    (byName.getValue(name).spec.parameters["required"] as JsonArray).map {
      (it as JsonPrimitive).content
    }

  private fun params(name: String): Set<String> =
    (byName.getValue(name).spec.parameters["properties"] as JsonObject).keys

  @Test
  fun seventeenUniqueTools_withSpecRisks() {
    val expected =
      mapOf(
        "get_device_status" to ToolRisk.SAFE,
        "set_flashlight" to ToolRisk.SAFE,
        "set_volume" to ToolRisk.SAFE,
        "set_brightness" to ToolRisk.SAFE,
        "set_do_not_disturb" to ToolRisk.SAFE,
        "open_settings_panel" to ToolRisk.SAFE,
        "open_app" to ToolRisk.SAFE,
        "set_alarm" to ToolRisk.SAFE,
        "start_timer" to ToolRisk.SAFE,
        "show_location_on_map" to ToolRisk.SAFE,
        "find_contact" to ToolRisk.SENSITIVE_READ,
        "send_whatsapp" to ToolRisk.OUTWARD_PREFILLED,
        "send_email" to ToolRisk.OUTWARD_PREFILLED,
        "create_contact" to ToolRisk.OUTWARD_PREFILLED,
        "create_calendar_event" to ToolRisk.OUTWARD_PREFILLED,
        "call" to ToolRisk.OUTWARD_INSTANT,
        "send_sms" to ToolRisk.OUTWARD_INSTANT,
      )
    assertEquals(17, tools.size)
    assertEquals(expected, tools.associate { it.spec.name to it.spec.risk })
  }

  @Test
  fun parameters_matchSpecTable() {
    val expected =
      mapOf(
        "get_device_status" to (emptySet<String>() to emptyList()),
        "set_flashlight" to (setOf("on") to listOf("on")),
        "set_volume" to (setOf("stream", "percent") to listOf("stream", "percent")),
        "set_brightness" to (setOf("percent") to listOf("percent")),
        "set_do_not_disturb" to (setOf("on") to listOf("on")),
        "open_settings_panel" to (setOf("panel") to listOf("panel")),
        "open_app" to (setOf("name") to listOf("name")),
        "set_alarm" to (setOf("hour", "minute", "label", "days") to listOf("hour", "minute")),
        "start_timer" to (setOf("seconds", "label") to listOf("seconds")),
        "show_location_on_map" to (setOf("query") to listOf("query")),
        "find_contact" to (setOf("name") to listOf("name")),
        "call" to (setOf("number") to listOf("number")),
        "send_sms" to (setOf("number", "text") to listOf("number", "text")),
        "send_whatsapp" to (setOf("number", "text") to listOf("number", "text")),
        "send_email" to (setOf("to", "subject", "body") to listOf("to", "subject", "body")),
        "create_contact" to (setOf("name", "phone", "email") to listOf("name")),
        "create_calendar_event" to (setOf("title", "start", "end") to listOf("title", "start")),
      )
    for ((name, pair) in expected) {
      assertEquals("$name params", pair.first, params(name))
      assertEquals("$name required", pair.second, required(name))
      assertEquals(JsonPrimitive("object"), byName.getValue(name).spec.parameters["type"])
      assertTrue("$name description", byName.getValue(name).spec.description.isNotBlank())
    }
  }

  @Test
  fun enums_forStreamAndPanel() {
    fun enumOf(tool: String, prop: String) =
      ((byName.getValue(tool).spec.parameters["properties"] as JsonObject)[prop] as JsonObject)[
          "enum"]
        .let { (it as JsonArray).map { e -> (e as JsonPrimitive).content } }
    assertEquals(listOf("media", "ring", "alarm", "notification"), enumOf("set_volume", "stream"))
    assertEquals(
      listOf("wifi", "bluetooth", "internet", "nfc", "volume"),
      enumOf("open_settings_panel", "panel"),
    )
  }

  @Test
  fun confirmSummary_showsNumberAndText() {
    val sms = buildJsonObject {
      put("number", "+49 151 2345678")
      put("text", "Running 10 min late")
    }
    assertEquals(
      "Send SMS to +49 151 2345678: 'Running 10 min late'",
      byName.getValue("send_sms").confirmSummary(sms),
    )
    assertEquals(
      "Call +49 151 2345678",
      byName.getValue("call").confirmSummary(buildJsonObject { put("number", "+49 151 2345678") }),
    )
  }
}
