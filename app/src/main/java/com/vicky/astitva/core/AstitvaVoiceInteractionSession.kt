package com.vicky.astitva.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession

class AstitvaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        val intent = Intent(context, AstitvaOverlayService::class.java).apply {
            putExtra("SHOW_BOTTOM_OVERLAY", true)
        }
        context.startService(intent)
        finish()
    }
}
