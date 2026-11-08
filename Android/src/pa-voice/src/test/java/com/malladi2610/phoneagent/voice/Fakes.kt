package com.malladi2610.phoneagent.voice

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.malladi2610.phoneagent.core.PhoneAgentServices
import com.malladi2610.phoneagent.core.agent.AgentEngine
import com.malladi2610.phoneagent.core.agent.AgentTurnResult
import com.malladi2610.phoneagent.core.agent.StopReason
import com.malladi2610.phoneagent.core.agent.TurnRequest
import com.malladi2610.phoneagent.core.log.ActionLog
import com.malladi2610.phoneagent.core.log.ActionLogEntry
import com.malladi2610.phoneagent.core.secrets.SecretKey
import com.malladi2610.phoneagent.core.secrets.SecretStore
import com.malladi2610.phoneagent.core.settings.PaSettings
import com.malladi2610.phoneagent.core.tool.ToolContext
import com.malladi2610.phoneagent.core.tool.ToolOutcome
import com.malladi2610.phoneagent.core.tool.ToolRegistry
import com.malladi2610.phoneagent.core.tool.ToolSpec
import kotlinx.serialization.json.JsonObject

/** In-memory SharedPreferences so the real [PaSettings] works on the JVM. */
class MemoryPrefs : SharedPreferences {
  val map = HashMap<String, Any?>()

  override fun getAll(): MutableMap<String, *> = map

  override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue

  @Suppress("UNCHECKED_CAST")
  override fun getStringSet(key: String, defValues: MutableSet<String>?) =
    map[key] as MutableSet<String>? ?: defValues

  override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue

  override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue

  override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue

  override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue

  override fun contains(key: String) = map.containsKey(key)

  override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

  override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

  override fun edit(): SharedPreferences.Editor =
    object : SharedPreferences.Editor {
      override fun putString(key: String, value: String?) = apply { map[key] = value }

      override fun putStringSet(key: String, values: MutableSet<String>?) = apply { map[key] = values }

      override fun putInt(key: String, value: Int) = apply { map[key] = value }

      override fun putLong(key: String, value: Long) = apply { map[key] = value }

      override fun putFloat(key: String, value: Float) = apply { map[key] = value }

      override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }

      override fun remove(key: String) = apply { map.remove(key) }

      override fun clear() = apply { map.clear() }

      override fun commit() = true

      override fun apply() {}
    }
}

fun testSettings(): PaSettings {
  val prefs = MemoryPrefs()
  return PaSettings(
    object : ContextWrapper(null) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }
  )
}

class FakeSecrets : SecretStore {
  val values = HashMap<SecretKey, String>()

  override fun get(key: SecretKey) = values[key]

  override fun put(key: SecretKey, value: String) {
    values[key] = value
  }

  override fun clear(key: SecretKey) {
    values.remove(key)
  }
}

object NoTools : ToolRegistry {
  override fun specs(): List<ToolSpec> = emptyList()

  override suspend fun execute(name: String, args: JsonObject, ctx: ToolContext): ToolOutcome =
    ToolOutcome.Error("no tools")
}

class FakeEngine(var reply: suspend (TurnRequest) -> AgentTurnResult = { ok("Done.") }) : AgentEngine {
  val requests = mutableListOf<TurnRequest>()

  override suspend fun runTurn(request: TurnRequest): AgentTurnResult {
    requests += request
    return reply(request)
  }

  companion object {
    fun ok(text: String) = AgentTurnResult(text, 0, StopReason.COMPLETED)
  }
}

fun services(engine: AgentEngine, settings: PaSettings = testSettings()) =
  PhoneAgentServices.Installed(
    agentEngine = engine,
    phoneTools = NoTools,
    secrets = FakeSecrets(),
    settings = settings,
    actionLog =
      object : ActionLog {
        override fun record(entry: ActionLogEntry) {}
      },
  )

class FakeInput(vararg replies: SttResult) : SpeechInput {
  val queue = ArrayDeque(replies.toList())
  val languages = mutableListOf<String>()
  var onListen: suspend () -> Unit = {}

  override suspend fun listenOnce(language: String): SttResult {
    languages += language
    onListen()
    return queue.removeFirstOrNull() ?: SttResult("", null)
  }
}

class FakeOutput : SpeechOutput {
  val spoken = mutableListOf<Pair<String, String>>()
  var failWith: Exception? = null

  override suspend fun speak(text: String, language: String) {
    failWith?.let { throw it }
    spoken += text to language
  }
}
