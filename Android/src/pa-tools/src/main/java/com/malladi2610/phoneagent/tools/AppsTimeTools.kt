package com.malladi2610.phoneagent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.tools.util.AppMatcher
import com.malladi2610.phoneagent.tools.util.int
import com.malladi2610.phoneagent.tools.util.str
import com.malladi2610.phoneagent.tools.util.strList
import java.util.Calendar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class OpenAppTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "open_app",
      "Open an installed app by its name (fuzzy match). If several apps match, returns " +
        "candidates; ask the user which one.",
      ToolRisk.SAFE,
    ) {
      string("name", "the app's name as the user said it, e.g. 'Spotify'", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val query = args.str("name")!!
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps =
      pm.queryIntentActivities(launcher, 0).map {
        it.loadLabel(pm).toString() to it.activityInfo.packageName
      }
    return when (val m = AppMatcher.match(query, apps.map { it.first })) {
      is AppMatcher.Result.Match -> {
        val pkg = apps.first { it.first == m.label }.second
        val intent =
          pm.getLaunchIntentForPackage(pkg) ?: return ToolOutcome.Error("${m.label} can't be opened")
        launch(intent, m.label) ?: ToolOutcome.Ok(buildJsonObject { put("opened", m.label) })
      }
      is AppMatcher.Result.Candidates ->
        ToolOutcome.Ok(
          buildJsonObject {
            put("opened", false)
            put("candidates", JsonArray(m.labels.take(MAX_CANDIDATES).map(::JsonPrimitive)))
            put("note", "Several apps match '$query'; ask the user which one.")
          }
        )
      AppMatcher.Result.NoMatch -> ToolOutcome.Error("no installed app matches '$query'")
    }
  }

  private companion object {
    const val MAX_CANDIDATES = 5
  }
}

internal class SetAlarmTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "set_alarm",
      "Set an alarm in the Clock app at hour:minute (24-hour). Optionally repeating on days.",
      ToolRisk.SAFE,
    ) {
      integer("hour", "hour 0-23", required = true)
      integer("minute", "minute 0-59", required = true)
      string("label", "optional alarm label")
      stringArray("days", "optional repeat days, e.g. [\"mon\",\"fri\"]; omit for a one-time alarm")
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val hour = args.int("hour")!!
    val minute = args.int("minute")!!
    if (hour !in 0..23 || minute !in 0..59) return ToolOutcome.Error("hour must be 0-23, minute 0-59")
    val dayNames = args.strList("days").orEmpty()
    val days = dayNames.map { DAYS[it.trim().lowercase().take(3)] }
    if (days.any { it == null }) {
      return ToolOutcome.Error("days must be weekday names like 'mon' or 'monday'")
    }
    val label = args.str("label")
    val intent =
      Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
    if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.filterNotNull()))
    launch(intent, "the Clock app")?.let { return it }
    return ToolOutcome.Ok(
      buildJsonObject {
        put("time", "%02d:%02d".format(hour, minute))
        if (label != null) put("label", label)
        if (dayNames.isNotEmpty()) put("days", JsonArray(dayNames.map(::JsonPrimitive)))
      }
    )
  }

  private companion object {
    val DAYS =
      mapOf(
        "mon" to Calendar.MONDAY,
        "tue" to Calendar.TUESDAY,
        "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY,
        "sat" to Calendar.SATURDAY,
        "sun" to Calendar.SUNDAY,
      )
  }
}

internal class StartTimerTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec("start_timer", "Start a countdown timer in the Clock app.", ToolRisk.SAFE) {
      integer("seconds", "length in seconds (1-86400)", required = true)
      string("label", "optional timer label")
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val seconds = args.int("seconds")!!
    if (seconds !in 1..86_400) return ToolOutcome.Error("seconds must be 1-86400")
    val label = args.str("label")
    val intent =
      Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
    launch(intent, "the Clock app")?.let { return it }
    return ToolOutcome.Ok(buildJsonObject { put("seconds", seconds) })
  }
}

internal class ShowLocationOnMapTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec("show_location_on_map", "Show a place or address in the maps app.", ToolRisk.SAFE) {
      string("query", "place name or address", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val query = args.str("query")!!
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
    launch(intent, "a maps app")?.let { return it }
    return ToolOutcome.Ok(buildJsonObject { put("query", query) })
  }
}
