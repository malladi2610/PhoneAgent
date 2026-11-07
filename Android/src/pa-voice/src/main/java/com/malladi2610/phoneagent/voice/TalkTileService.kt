package com.malladi2610.phoneagent.voice

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.malladi2610.phoneagent.core.voice.PhoneAgentBus
import com.malladi2610.phoneagent.core.voice.VoiceTrigger

/** Quick Settings tile: tap to talk, also from the lock screen (collapses the shade first). */
class TalkTileService : TileService() {
  override fun onStartListening() {
    qsTile?.apply {
      state = Tile.STATE_INACTIVE
      label = getString(R.string.pa_talk_label)
      icon = Icon.createWithResource(this@TalkTileService, R.drawable.pa_ic_mic)
      updateTile()
    }
  }

  override fun onClick() {
    if (isLocked) {
      // Over the keyguard an app activity would need an unlock first; the assistant session
      // shows over the lock screen by itself (same path as the side button).
      Log.d(TAG, "tile tapped on the lock screen: starting the session directly")
      PhoneAgentBus.voiceTurnLauncher?.startVoiceTurn(VoiceTrigger.ASSISTANT_BUTTON)
      return
    }
    Log.d(TAG, "tile tapped")
    val intent =
      Intent(this, TalkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    startActivityAndCollapse(
      PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    )
  }

  private companion object {
    const val TAG = "PaTalk"
  }
}
