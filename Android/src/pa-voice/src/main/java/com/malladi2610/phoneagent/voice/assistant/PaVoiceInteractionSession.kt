package com.malladi2610.phoneagent.voice.assistant

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.malladi2610.phoneagent.core.voice.VoiceTrigger
import com.malladi2610.phoneagent.voice.PaVoice
import com.malladi2610.phoneagent.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The assistant overlay: a small card at the bottom of the screen showing Listening… / your words /
 * the answer. The voice-interaction window sits above the lock screen; touches outside the card
 * pass through, so the user can still unlock the phone while it's showing. Closes 3 s after the
 * reply has been spoken. Tapping the card stops the turn.
 */
class PaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var hideJob: Job? = null
  private var lastState: VoiceUiState? = null

  private lateinit var card: LinearLayout
  private lateinit var status: TextView
  private lateinit var heard: TextView
  private lateinit var answer: TextView

  override fun onCreate() {
    super.onCreate()
    window.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    scope.launch { PaVoice.ui.collect { render(it) } }
  }

  override fun onCreateContentView(): View {
    val ctx = context
    val dark =
      (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val fg = if (dark) Color.WHITE else Color.parseColor("#1B1B1F")
    val muted = if (dark) Color.parseColor("#C7C6CA") else Color.parseColor("#46464F")
    fun text(sizeSp: Float, color: Int, style: Int = Typeface.NORMAL) =
      TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setTypeface(typeface, style)
        visibility = View.GONE
      }
    status = text(18f, fg, Typeface.BOLD)
    heard = text(16f, muted, Typeface.ITALIC)
    answer = text(18f, fg)
    card =
      LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(20)
        setPadding(pad, pad, pad, pad)
        background =
          GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(if (dark) Color.parseColor("#2B2930") else Color.parseColor("#F3EDF7"))
          }
        elevation = dp(6).toFloat()
        addView(status)
        addView(heard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(answer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        contentDescription = "PhoneAgent. Tap to stop."
        setOnClickListener {
          PaVoice.cancelTurn()
          hide()
        }
      }
    val root =
      FrameLayout(ctx).apply {
        addView(
          card,
          FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            val m = dp(12)
            setMargins(m, m, m, dp(32))
          },
        )
      }
    lastState?.let { render(it) } // a state that arrived before the views existed
    return root
  }

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    hideJob?.cancel()
    val trigger =
      args?.getString(PaVoice.EXTRA_TRIGGER)?.let { runCatching { VoiceTrigger.valueOf(it) }.getOrNull() }
        ?: VoiceTrigger.ASSISTANT_BUTTON // side button / system assist gesture
    if (!PaVoice.startTurn(trigger)) {
      // A turn is already running (e.g. wake phrase then side button): just show it.
      lastState?.let { render(it) }
    }
  }

  override fun onHide() {
    // The turn keeps running (e.g. a tool opened another app); it finishes headless.
    hideJob?.cancel()
    super.onHide()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun onComputeInsets(outInsets: Insets) {
    super.onComputeInsets(outInsets)
    if (!::card.isInitialized || card.height == 0) return
    val loc = IntArray(2)
    card.getLocationInWindow(loc)
    outInsets.contentInsets.top = loc[1]
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
    outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
  }

  private fun render(state: VoiceUiState) {
    if (!::card.isInitialized) {
      lastState = state
      return
    }
    val newTurn = state == VoiceUiState.Listening && (lastState == null || lastState is VoiceUiState.Done || lastState is VoiceUiState.Error)
    lastState = state
    if (newTurn) {
      set(heard, null)
      set(answer, null)
    }
    when (state) {
      VoiceUiState.Listening -> set(status, "Listening…")
      is VoiceUiState.Heard -> {
        set(status, null)
        set(heard, "“${state.text}”")
      }
      VoiceUiState.Thinking -> set(status, "Thinking…")
      is VoiceUiState.Speaking -> {
        set(status, null)
        set(answer, state.text)
      }
      VoiceUiState.Done -> scheduleHide(3_000)
      is VoiceUiState.Error -> {
        set(status, null)
        set(answer, state.message)
        scheduleHide(6_000)
      }
    }
    if (state == VoiceUiState.Listening || state == VoiceUiState.Thinking) hideJob?.cancel()
  }

  private fun scheduleHide(ms: Long) {
    hideJob?.cancel()
    hideJob =
      scope.launch {
        delay(ms)
        hide()
      }
  }

  private fun set(view: TextView, text: String?) {
    view.text = text.orEmpty()
    view.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
  }

  private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
