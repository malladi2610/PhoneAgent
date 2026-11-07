package com.malladi2610.phoneagent.tools

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.SmsManager
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRisk
import com.malladi2610.phoneagent.tools.util.DateTimes
import com.malladi2610.phoneagent.tools.util.PhoneNumbers
import com.malladi2610.phoneagent.tools.util.WhatsAppLink
import com.malladi2610.phoneagent.tools.util.str
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PREFILLED_NOTE = "The screen is open and pre-filled; the user reviews it and taps Send/Save."

internal class FindContactTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "find_contact",
      "Look up contacts by name and return up to 5 matches with their phone numbers. Use this " +
        "before call, send_sms or send_whatsapp when the user names a person.",
      ToolRisk.SENSITIVE_READ,
    ) {
      string("name", "the contact's name, e.g. 'Mom' or 'Priya'", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    ensurePermission(Manifest.permission.READ_CONTACTS, ctx)?.let { return it }
    val name = args.str("name")!!.trim()
    var contacts = query(name)
    if (contacts.isEmpty() && name.contains(' ')) contacts = query(name.substringBefore(' '))
    return ToolOutcome.Ok(
      buildJsonObject {
        put(
          "contacts",
          buildJsonArray {
            for ((display, numbers) in contacts) {
              add(
                buildJsonObject {
                  put("name", display)
                  put("numbers", JsonArray(numbers.map(::JsonPrimitive)))
                }
              )
            }
          },
        )
        if (contacts.isEmpty()) put("note", "No contact with a phone number matches '$name'.")
      }
    )
  }

  /** Contacts whose display name contains [name], as (name, distinct numbers), max 5. */
  private fun query(name: String): List<Pair<String, List<String>>> {
    val escaped = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    val byContact = linkedMapOf<Long, Pair<String, LinkedHashMap<String, String>>>()
    context.contentResolver
      .query(
        Phone.CONTENT_URI,
        arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER),
        "${Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
        arrayOf("%$escaped%"),
        "${Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
      )
      ?.use { c ->
        while (c.moveToNext()) {
          val id = c.getLong(0)
          val display = c.getString(1) ?: continue
          val number = c.getString(2) ?: continue
          if (id !in byContact && byContact.size >= MAX_CONTACTS) continue
          val entry = byContact.getOrPut(id) { display to linkedMapOf() }
          // Same number stored twice with different formatting → keep the first spelling.
          entry.second.putIfAbsent(PhoneNumbers.normalize(number), number)
        }
      }
    return byContact.values.map { (display, numbers) -> display to numbers.values.toList() }
  }

  private companion object {
    const val MAX_CONTACTS = 5
  }
}

internal class CallTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "call",
      "Start a phone call right away (the user is asked to confirm first). Needs a phone " +
        "number; use find_contact to get one from a name.",
      ToolRisk.OUTWARD_INSTANT,
    ) {
      string("number", "phone number to call, e.g. '+49 151 2345678'", required = true)
    }

  override fun confirmSummary(args: JsonObject) = "Call ${args.str("number")}"

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val raw = args.str("number")!!
    if (!PhoneNumbers.isDialable(raw)) return ToolOutcome.Error("'$raw' is not a phone number")
    ensurePermission(Manifest.permission.CALL_PHONE, ctx)?.let { return it }
    val number = PhoneNumbers.normalize(raw)
    launch(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)), "the phone app")
      ?.let { return it }
    return ToolOutcome.Ok(buildJsonObject { put("calling", number) })
  }
}

internal class SendSmsTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "send_sms",
      "Send a text message (SMS) right away (the user is asked to confirm first). Needs a " +
        "phone number; use find_contact to get one from a name.",
      ToolRisk.OUTWARD_INSTANT,
    ) {
      string("number", "recipient phone number", required = true)
      string("text", "the message text", required = true)
    }

  override fun confirmSummary(args: JsonObject) =
    "Send SMS to ${args.str("number")}: '${args.str("text")}'"

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val raw = args.str("number")!!
    val text = args.str("text")!!
    if (!PhoneNumbers.isDialable(raw)) return ToolOutcome.Error("'$raw' is not a phone number")
    if (text.isBlank()) return ToolOutcome.Error("the message text is empty")
    ensurePermission(Manifest.permission.SEND_SMS, ctx)?.let { return it }
    val number = PhoneNumbers.normalize(raw)
    val sms =
      context.getSystemService(SmsManager::class.java)
        ?: return ToolOutcome.Error("this phone can't send SMS")
    val parts = sms.divideMessage(text)

    val action = "com.malladi2610.phoneagent.SMS_SENT." + UUID.randomUUID()
    val sentIntents =
      ArrayList(
        parts.indices.map { i ->
          PendingIntent.getBroadcast(
            context,
            i,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
          )
        }
      )
    val results =
      awaitSentResults(action, parts.size) {
        if (parts.size == 1) sms.sendTextMessage(number, null, text, sentIntents[0], null)
        else sms.sendMultipartTextMessage(number, null, parts, sentIntents, null)
      }
    return when {
      results == null ->
        ToolOutcome.Ok(
          buildJsonObject {
            put("to", number)
            put("sent", "pending")
            put("note", "The SMS was handed to the phone but not yet confirmed as sent.")
          }
        )
      results.all { it == Activity.RESULT_OK } ->
        ToolOutcome.Ok(
          buildJsonObject {
            put("to", number)
            put("sent", true)
            put("parts", parts.size)
          }
        )
      else -> ToolOutcome.Error("the SMS failed to send (code ${results.first { it != Activity.RESULT_OK }})")
    }
  }

  /** Runs [send] and collects one result code per part, or null after the timeout. */
  private suspend fun awaitSentResults(action: String, count: Int, send: () -> Unit): List<Int>? {
    var receiver: BroadcastReceiver? = null
    try {
      return withTimeoutOrNull(SENT_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
          val codes = mutableListOf<Int>()
          receiver =
            object : BroadcastReceiver() {
              override fun onReceive(c: Context, intent: Intent) {
                synchronized(codes) {
                  codes += resultCode
                  if (codes.size == count && cont.isActive) cont.resume(codes.toList())
                }
              }
            }
          context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
          send()
        }
      }
    } finally {
      receiver?.let { runCatching { context.unregisterReceiver(it) } }
    }
  }

  private companion object {
    const val SENT_TIMEOUT_MS = 20_000L
  }
}

internal class SendWhatsAppTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "send_whatsapp",
      "Open WhatsApp on a chat with the message pre-filled; the user taps Send. Needs a phone " +
        "number with country code; use find_contact to get one from a name.",
      ToolRisk.OUTWARD_PREFILLED,
    ) {
      string("number", "phone number with country code, e.g. '+49 151 2345678'", required = true)
      string("text", "the message text", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val number = args.str("number")!!
    if (!PhoneNumbers.isDialable(number)) return ToolOutcome.Error("'$number' is not a phone number")
    try {
      context.packageManager.getPackageInfo(WHATSAPP, 0)
    } catch (e: PackageManager.NameNotFoundException) {
      return ToolOutcome.Error("WhatsApp isn't installed")
    }
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(WhatsAppLink.build(number, args.str("text")!!)))
        .setPackage(WHATSAPP)
    launch(intent, "WhatsApp")?.let { return it }
    return ToolOutcome.Ok(
      buildJsonObject {
        put("opened", "whatsapp")
        put("note", PREFILLED_NOTE)
      }
    )
  }

  private companion object {
    const val WHATSAPP = "com.whatsapp"
  }
}

internal class SendEmailTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "send_email",
      "Open the email app with a pre-filled email; the user taps Send.",
      ToolRisk.OUTWARD_PREFILLED,
    ) {
      string("to", "recipient email address", required = true)
      string("subject", "email subject", required = true)
      string("body", "email body", required = true)
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val intent =
      Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(args.str("to")!!))
        .putExtra(Intent.EXTRA_SUBJECT, args.str("subject")!!)
        .putExtra(Intent.EXTRA_TEXT, args.str("body")!!)
    launch(intent, "an email app")?.let { return it }
    return ToolOutcome.Ok(
      buildJsonObject {
        put("opened", "email")
        put("note", PREFILLED_NOTE)
      }
    )
  }
}

internal class CreateContactTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "create_contact",
      "Open the Contacts app with a new pre-filled contact; the user taps Save.",
      ToolRisk.OUTWARD_PREFILLED,
    ) {
      string("name", "the contact's name", required = true)
      string("phone", "optional phone number")
      string("email", "optional email address")
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val intent =
      Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.NAME, args.str("name")!!)
    args.str("phone")?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
    args.str("email")?.let { intent.putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
    launch(intent, "the Contacts app")?.let { return it }
    return ToolOutcome.Ok(
      buildJsonObject {
        put("opened", "contacts")
        put("note", PREFILLED_NOTE)
      }
    )
  }
}

internal class CreateCalendarEventTool(context: Context) : BasePhoneTool(context) {
  override val spec =
    toolSpec(
      "create_calendar_event",
      "Open the calendar with a new pre-filled event; the user taps Save. Times are ISO-8601 " +
        "local date-times like '2026-11-08T09:30'.",
      ToolRisk.OUTWARD_PREFILLED,
    ) {
      string("title", "event title", required = true)
      string("start", "start, ISO-8601 local date-time, e.g. '2026-11-08T09:30'", required = true)
      string("end", "optional end, ISO-8601 local date-time; defaults to one hour after start")
    }

  override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolOutcome {
    val startText = args.str("start")!!
    val start =
      DateTimes.parseToEpochMillis(startText)
        ?: return ToolOutcome.Error("start '$startText' is not an ISO-8601 date-time")
    val endText = args.str("end")
    val end =
      if (endText == null) start + HOUR_MS
      else
        DateTimes.parseToEpochMillis(endText)
          ?: return ToolOutcome.Error("end '$endText' is not an ISO-8601 date-time")
    if (end <= start) return ToolOutcome.Error("end must be after start")
    val intent =
      Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, args.str("title")!!)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
    launch(intent, "the calendar")?.let { return it }
    return ToolOutcome.Ok(
      buildJsonObject {
        put("opened", "calendar")
        put("note", PREFILLED_NOTE)
      }
    )
  }

  private companion object {
    const val HOUR_MS = 3_600_000L
  }
}
