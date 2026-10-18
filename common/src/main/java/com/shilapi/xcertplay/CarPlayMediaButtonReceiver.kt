package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

class CarPlayMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CarPlayHomeCard.ACTION_TOGGLE) {
            CarPlayMediaKeys.onHomeCardToggle()
            return
        }
        if (intent.action == CarPlayHomeCard.ACTION_NEXT) {
            CarPlayMediaKeys.onHomeCardNext()
            return
        }
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        CarPlayMediaKeys.onMediaButtonBroadcast(event)
    }
}
