package com.malladi2610.phoneagent.tools

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One-stop screen for the phone tools' permissions: each runtime permission with its status and a
 * Grant button, plus shortcuts to the two special-access pages (system settings, Do Not Disturb).
 * Opened by class name from "PhoneAgent setup". Plain Views, no Compose.
 */
class PermissionsActivity : Activity() {
  private data class Runtime(val permission: String, val label: String, val usedBy: String)

  private val runtimePermissions =
    listOf(
      Runtime(Manifest.permission.READ_CONTACTS, "Contacts", "find_contact"),
      Runtime(Manifest.permission.CALL_PHONE, "Phone calls", "call"),
      Runtime(Manifest.permission.SEND_SMS, "Send SMS", "send_sms"),
    )

  private lateinit var list: LinearLayout

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    list = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val pad = dp(16)
      setPadding(pad, pad, pad, pad)
    }
    setContentView(ScrollView(this).apply { addView(list, MATCH_PARENT, WRAP_CONTENT) })
  }

  override fun onResume() {
    super.onResume()
    render()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    render()
  }

  private fun render() {
    list.removeAllViews()
    list.addView(text("Phone permissions", 22f))
    list.addView(
      text("PhoneAgent asks before calling or texting. These permissions let it act once you allow.", 14f)
    )
    for (p in runtimePermissions) {
      val granted = checkSelfPermission(p.permission) == PackageManager.PERMISSION_GRANTED
      row(
        "${p.label} (${p.usedBy})",
        granted,
        if (granted) null else ({ requestPermissions(arrayOf(p.permission), REQUEST_CODE) }),
      )
    }
    list.addView(text("Special access", 18f))
    row("Modify system settings (brightness)", Settings.System.canWrite(this)) {
      startActivity(writeSettingsIntent(this))
    }
    val dnd = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
    row("Do Not Disturb access", dnd) { startActivity(dndAccessSettingsIntent()) }
  }

  private fun row(label: String, granted: Boolean, onClick: (() -> Unit)?) {
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding(0, dp(8), 0, dp(8))
    }
    row.addView(
      text("$label\n${if (granted) "Granted" else "Not granted"}", 16f),
      LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
    )
    if (onClick != null) {
      row.addView(
        Button(this).apply {
          text = if (granted) "Open" else "Grant"
          setOnClickListener { runCatching { onClick() } }
        }
      )
    }
    list.addView(row)
  }

  private fun text(value: String, sizeSp: Float) =
    TextView(this).apply {
      text = value
      setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
      setPadding(0, dp(6), 0, dp(6))
    }

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  private companion object {
    const val REQUEST_CODE = 42
  }
}
