package com.vicky.astitva.core

import android.content.Intent
import android.speech.RecognitionService

class AstitvaSpeechRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
