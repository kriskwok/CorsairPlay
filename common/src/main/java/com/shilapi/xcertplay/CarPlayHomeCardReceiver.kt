package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CarPlayHomeCardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CarPlayHomeCard.ACTION_QUERY) {
            CarPlayHomeCard.respond(context)
        }
    }
}
