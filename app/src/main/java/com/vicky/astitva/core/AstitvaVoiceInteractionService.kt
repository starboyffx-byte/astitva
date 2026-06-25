package com.vicky.astitva.core

import android.service.voice.VoiceInteractionService
import android.util.Log

class AstitvaVoiceInteractionService : VoiceInteractionService() {
    companion object {
        private const val TAG = "VoiceInteractionService"
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Astitva Voice Assistant is ready.")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i(TAG, "Astitva Voice Assistant is shutting down.")
    }
}
