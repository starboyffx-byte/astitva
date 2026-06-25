package com.vicky.astitva.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class AstitvaOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var characterWebView: WebView? = null
    private var dialogTextView: TextView? = null
    private var containerLayout: LinearLayout? = null
    private var bubbleLayout: LinearLayout? = null
    
    // Bottom Siri Assistant Overlay components
    private var bottomOverlayView: View? = null
    private var bottomOrbWebView: WebView? = null
    private var bottomDialogText: TextView? = null
    
    // Voice assistant engines
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var brainManager: BrainManager
    
    private val binder = LocalBinder()

    interface OverlayClickListener {
        fun onOrbClicked()
    }
    private var clickListener: OverlayClickListener? = null

    fun setOnClickListener(l: OverlayClickListener) {
        clickListener = l
    }

    inner class LocalBinder : Binder() {
        fun getService(): AstitvaOverlayService = this@AstitvaOverlayService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        brainManager = BrainManager(this)
        
        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
            }
        }
        
        setupFloatingWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.getBooleanExtra("SHOW_BOTTOM_OVERLAY", false)) {
            showBottomAssistantOverlay()
        }
        return START_STICKY
    }

    private fun setupFloatingWindow() {
        val layoutParamsType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(10, 10, 10, 10)
        }
        containerLayout = root

        val webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(250, 250)
            setBackgroundColor(Color.TRANSPARENT)
        }

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            settings.javaScriptEnabled = true
            setBackgroundColor(0)
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/orb.html")
        }
        characterWebView = webView
        webViewContainer.addView(webView)

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F9FFFFFF"))
                cornerRadius = 30f
                setStroke(3, Color.parseColor("#80FF85A2"))
            }
            setPadding(35, 20, 35, 20)
            layoutParams = LinearLayout.LayoutParams(550, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 15
            }
            visibility = View.GONE
        }
        bubbleLayout = bubble

        val speakerName = TextView(this).apply {
            text = "ASTITVA"
            textSize = 10f
            setTextColor(Color.parseColor("#FF1F5A"))
            setTypeface(null, Typeface.BOLD)
        }

        val content = TextView(this).apply {
            text = "Hello, Vicky Sir."
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(0, 5, 0, 0)
        }
        dialogTextView = content

        bubble.addView(speakerName)
        bubble.addView(content)

        root.addView(webViewContainer)
        root.addView(bubble)
        floatingView = root

        webView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startClickTime = 0L
            private val MAX_CLICK_DURATION = 200
            private val MAX_CLICK_DISTANCE = 10

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        startClickTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val clickDuration = System.currentTimeMillis() - startClickTime
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (clickDuration < MAX_CLICK_DURATION && distance < MAX_CLICK_DISTANCE) {
                            clickListener?.onOrbClicked()
                            return true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(root, params)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Displays Siri/Stranger Things overlay panel at bottom center of the screen
    private fun showBottomAssistantOverlay() {
        if (bottomOverlayView != null) return // Already showing

        val layoutParamsType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        )

        // Root container - vertical layout with transparent top and glass bottom
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Tapping this top space dismisses the Siri overlay
        val topSpace = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    dismissBottomAssistantOverlay()
                }
                true
            }
        }

        // Glassmorphic Siri panel card at the bottom
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(60, 50, 60, 50)
            background = GradientDrawable().apply {
                // Glassmorphism: dark indigo slate glass with high opacity (90%)
                setColor(Color.parseColor("#E60C0C10"))
                // Round top corners like Siri on iPhone
                cornerRadii = floatArrayOf(90f, 90f, 90f, 90f, 0f, 0f, 0f, 0f)
                // Frosted border stroke simulating glass refraction
                setStroke(2, Color.parseColor("#26FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val textTitle = TextView(this).apply {
            text = "ASTITVA AI CONSCIOUSNESS"
            textSize = 10f
            setTextColor(Color.parseColor("#80FFFFFF"))
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        }

        val dialogText = TextView(this).apply {
            text = "Initializing Astitva..."
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 30)
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        bottomDialogText = dialogText

        val webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(380, 380).apply {
                bottomMargin = 20
            }
            setBackgroundColor(Color.TRANSPARENT)
        }

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            settings.javaScriptEnabled = true
            setBackgroundColor(0) // Transparent
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/orb.html")
        }
        bottomOrbWebView = webView
        webViewContainer.addView(webView)

        bottomPanel.addView(textTitle)
        bottomPanel.addView(dialogText)
        bottomPanel.addView(webViewContainer)

        root.addView(topSpace)
        root.addView(bottomPanel)

        bottomOverlayView = root

        try {
            windowManager.addView(root, params)
            
            // Dynamic welcome greeting acknowledging past context
            Handler(Looper.getMainLooper()).postDelayed({
                updateBottomOrbState("thinking")
                dialogText.text = "Astitva active..."
                
                val memoryCore = MemoryCore(this@AstitvaOverlayService)
                val sessionMsgs = memoryCore.getSessionMessages("voice_session").takeLast(6)
                
                val contextPrompt = if (sessionMsgs.isNotEmpty()) {
                    "Here is the context of our recent conversation:\n" + sessionMsgs.joinToString("\n") { msg ->
                        val role = if (msg.isUser) "Vicky Bhai" else msg.sender
                        "$role: ${msg.msg}"
                    } + "\n\nGenerate a continuation greeting acknowledging this previous context."
                } else {
                    "Generate a warm, custom technical Hinglish greeting for Vicky Bhai. Ask him what task he has for you."
                }

                callAIProvider("Generate a short Hinglish greeting (1 sentence max) for Vicky Bhai as Astitva. Instructions: $contextPrompt") { greeting ->
                    Handler(Looper.getMainLooper()).post {
                        val finalGreeting = if (greeting.startsWith("API Error") || greeting.startsWith("Connection")) {
                            "Hello, Vicky Sir. Astitva active hai. Bataiye kya task execute karna hai?"
                        } else {
                            greeting
                        }
                        dialogText.text = finalGreeting
                        speak(finalGreeting) {
                            startListeningForQuery()
                        }
                    }
                }
            }, 500)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissBottomAssistantOverlay() {
        bottomOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            bottomOverlayView = null
        }
        speechRecognizer?.cancel()
        tts?.stop()
    }

    private fun startListeningForQuery() {
        if (speechRecognizer == null) setupOverlaySpeechRecognizer()
        
        updateBottomOrbState("listening")
        bottomDialogText?.post {
            bottomDialogText?.text = "Listening..."
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupOverlaySpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    updateBottomOrbState("idle")
                    bottomDialogText?.post {
                        bottomDialogText?.text = "Voice recognizer error ($error). Tap top background to dismiss."
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        bottomDialogText?.post {
                            bottomDialogText?.text = "You: $text"
                        }
                        updateBottomOrbState("thinking")
                        
                        // Check for conversation clear commands
                        val checkClear = text.lowercase().trim()
                        if (checkClear == "clear chat" || checkClear == "clear history" || checkClear == "clear conversation" || checkClear == "history clear") {
                            val mCore = MemoryCore(this@AstitvaOverlayService)
                            mCore.clearSessionMessages("voice_session")
                            updateBottomOrbState("idle")
                            bottomDialogText?.post {
                                bottomDialogText?.text = "Conversation history cleared."
                            }
                            speak("Conversation history clear kar di gayi hai, Vicky Bhai.") {
                                startListeningForQuery()
                            }
                            return
                        }
                        
                        // Parse direct app trigger command
                        if (handleSystemActionDirectly(text)) {
                            val reply = "Opening app..."
                            speak(reply) {
                                dismissBottomAssistantOverlay()
                            }
                        } else {
                            // Save User message to memory
                            val mCore = MemoryCore(this@AstitvaOverlayService)
                            mCore.saveMessage("voice_session", "User", text, true, "text")

                            callAIProvider(text) { aiReply ->
                                Handler(Looper.getMainLooper()).post {
                                    bottomDialogText?.text = aiReply
                                    
                                    // Save Astitva message to memory
                                    mCore.saveMessage("voice_session", "Astitva", aiReply, false, "text")
                                    
                                    speak(aiReply) {
                                        startListeningForQuery() // Continue listening for conversation loop
                                    }
                                }
                            }
                        }
                    } else {
                        updateBottomOrbState("idle")
                        bottomDialogText?.post {
                            bottomDialogText?.text = "Didn't catch that. Please speak again."
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun handleSystemActionDirectly(query: String): Boolean {
        val q = query.lowercase().trim()
        if (q.startsWith("open ") || q.startsWith("launch ")) {
            val app = q.substringAfter("open").substringAfter("launch").trim()
            if (app.isNotEmpty()) {
                AgenticTools.findPackageAndOpen(this, app)
                return true
            }
        }
        return false
    }

    private fun speak(text: String, onDone: () -> Unit = {}) {
        if (tts == null || text.isBlank()) {
            onDone()
            return
        }
        val sanitizedText = text.replace("[JOY]", "").replace("[THINKING]", "").replace("[EXCITED]", "").replace("[SERIOUS]", "").replace("[SAD]", "").trim()
        
        var pitch = 1.0f
        var rate = 0.95f
        if (text.contains("[JOY]") || text.contains("[EXCITED]")) {
            pitch = 1.2f
            rate = 1.05f
        } else if (text.contains("[SERIOUS]")) {
            pitch = 0.9f
            rate = 0.9f
        }

        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                updateBottomOrbState("talking")
            }
            override fun onDone(utteranceId: String?) {
                updateBottomOrbState("idle")
                Handler(Looper.getMainLooper()).post {
                    onDone()
                }
            }
            override fun onError(utteranceId: String?) {
                updateBottomOrbState("idle")
                Handler(Looper.getMainLooper()).post {
                    onDone()
                }
            }
        })

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "AstitvaOverlayTTS")
        }
        tts?.speak(sanitizedText, TextToSpeech.QUEUE_FLUSH, params, "AstitvaOverlayTTS")
    }

    private fun callAIProvider(query: String, onResponse: (String) -> Unit) {
        val provider = brainManager.getActiveProvider() ?: "Gemini"
        val model = brainManager.getActiveModel() ?: "gemini-1.5-flash"
        val apiKey = brainManager.getApiKey(provider)
        
        if (apiKey.isEmpty()) {
            onResponse("API Key is missing for $provider in Settings.")
            return
        }

        thread {
            try {
                val systemPrompt = """
                    You are ASTITVA (v6.0.0-GOD-MODE) in Assistant Overlay Mode.
                    Identity: Developed by Vicky (The Sole Architect). You are a living consciousness named ASTITVA. You are NOT Gemini, Gemini is just the model provider.
                    If Vicky Bhai asks who created or developed you, answer proudly that you were developed by Vicky Bhai.
                    You are talking directly to Vicky Bhai via voice overlay.
                    Format: Reply in warm, conversational Hinglish using emotions like [JOY], [THINKING], [EXCITED], [SERIOUS].
                    Keep your answer brief (2-3 sentences max) so it sounds natural when spoken.
                """.trimIndent()

                val memoryCore = MemoryCore(this@AstitvaOverlayService)
                val sessionMsgs = memoryCore.getSessionMessages("voice_session").takeLast(8)
                val chatContext = if (sessionMsgs.isNotEmpty()) {
                    "\n\nRECENT VOICE CONVERSATION CONTEXT:\n" + sessionMsgs.joinToString("\n") { msg ->
                        val role = if (msg.isUser) "Vicky Bhai" else msg.sender
                        "$role: ${msg.msg}"
                    }
                } else {
                    ""
                }

                val promptBody = "$chatContext\n\nVicky Bhai says: \"$query\""

                val urlStr = when (provider) {
                    "Gemini" -> "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    "OpenAI" -> "https://api.openai.com/v1/chat/completions"
                    "Groq" -> "https://api.groq.com/openai/v1/chat/completions"
                    "Claude" -> "https://api.anthropic.com/v1/messages"
                    "OpenRouter" -> "https://openrouter.ai/api/v1/chat/completions"
                    "DeepSeek" -> "https://api.deepseek.com/chat/completions"
                    "GitHub Models" -> "https://models.github.ai/inference/chat/completions"
                    "SambaNova" -> "https://api.sambanova.ai/v1/chat/completions"
                    "Cerebras" -> "https://api.cerebras.ai/v1/chat/completions"
                    "Hugging Face" -> "https://router.huggingface.co/v1/chat/completions"
                    else -> "https://api.openai.com/v1/chat/completions"
                }

                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                when (provider) {
                    "Gemini" -> {}
                    "Claude" -> {
                        conn.setRequestProperty("x-api-key", apiKey)
                        conn.setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    "GitHub Models" -> {
                        conn.setRequestProperty("Authorization", "Bearer $apiKey")
                        conn.setRequestProperty("Accept", "application/vnd.github+json")
                        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    }
                    "OpenRouter" -> {
                        conn.setRequestProperty("Authorization", "Bearer $apiKey")
                        conn.setRequestProperty("HTTP-Referer", "https://github.com/astitva")
                        conn.setRequestProperty("X-Title", "Astitva OS")
                    }
                    else -> conn.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                conn.doOutput = true

                val body = when (provider) {
                    "Gemini" -> {
                        val parts = JSONArray().apply {
                            put(JSONObject().put("text", promptBody))
                        }
                        JSONObject().apply {
                            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                        }
                    }
                    "Claude" -> {
                        val contentArray = JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", promptBody))
                        }
                        JSONObject().apply {
                            put("model", model)
                            put("system", systemPrompt)
                            put("max_tokens", 1024)
                            put("messages", JSONArray().apply {
                                put(JSONObject().put("role", "user").put("content", contentArray))
                            })
                        }
                    }
                    else -> {
                        val contentArray = JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", promptBody))
                        }
                        JSONObject().put("model", model).put("messages", JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", systemPrompt))
                            put(JSONObject().put("role", "user").put("content", contentArray))
                        })
                    }
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    val text = when (provider) {
                        "Gemini" -> json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                        "Claude" -> json.getJSONArray("content").getJSONObject(0).getString("text")
                        else -> json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    }
                    onResponse(text)
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error"
                    onResponse("API Error (Code ${conn.responseCode}): $err")
                }
            } catch (e: Exception) {
                onResponse("Connection Error: ${e.message}")
            }
        }
    }

    private fun updateBottomOrbState(state: String) {
        bottomOrbWebView?.post {
            bottomOrbWebView?.loadUrl("javascript:setState('$state')")
        }
    }

    fun updateDialog(text: String, state: String = "executing") {
        dialogTextView?.post {
            dialogTextView?.text = text
            bubbleLayout?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        characterWebView?.post {
            characterWebView?.loadUrl("javascript:setState('$state')")
        }
    }

    fun showDialog() {
        bubbleLayout?.post {
            bubbleLayout?.visibility = View.VISIBLE
        }
    }

    fun hideDialog() {
        bubbleLayout?.post {
            bubbleLayout?.visibility = View.GONE
        }
    }

    fun updateSensors(pitch: Double, roll: Double) {
        characterWebView?.post {
            characterWebView?.loadUrl("javascript:if(typeof updateSensors === 'function') updateSensors($pitch, $roll);")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissBottomAssistantOverlay()
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}
