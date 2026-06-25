package com.vicky.astitva

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.vicky.astitva.core.*
import com.vicky.astitva.utils.AgentBroker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.concurrent.thread

class MainActivity : Activity(), AgentBroker.AgentListener, AstitvaAccessibility.AccessibilityListener {

    private lateinit var mpManager: MediaProjectionManager
    private val SCREEN_CAST_CODE = 1001
    private val ALL_PERMS_CODE = 2002

    private lateinit var contentArea: FrameLayout
    private lateinit var navBar: LinearLayout
    private var aiOrbWebView: WebView? = null
    private lateinit var feedLayout: LinearLayout
    private lateinit var feedScroll: ScrollView

    data class ChatMessage(val sender: String, val msg: String, val isUser: Boolean, val type: String)
    private val chatMessages = java.util.Collections.synchronizedList(mutableListOf<ChatMessage>())
    
    // --- Session & Attachment Variables ---
    private var currentSessionId: String = "default_session"
    private val FILE_PICKER_CODE = 3003
    private var attachedFileUri: Uri? = null
    private var attachedFileType: String? = null // "image" or "text"
    private var attachedFileName: String? = null
    private var attachedFileContent: String? = null
    private var attachedImageBase64: String? = null
    private var attachedImageMime: String? = null
    
    private var previewCardView: FrameLayout? = null
    private var previewLayout: LinearLayout? = null
    private var previewLabel: TextView? = null
    private var previewThumbnail: ImageView? = null
    
    private lateinit var prefs: SharedPreferences
    private lateinit var brainManager: BrainManager
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // --- IRL Vision HUD Variables ---
    private var isIrlVisionActive = false
    private var irlCamera: android.hardware.Camera? = null
    private var irlCameraId = 0 // 0 for Back, 1 for Front
    private var irlTextureView: android.view.TextureView? = null
    private var irlDialogueTextView: TextView? = null
    private val irlAnalysisHandler = Handler(Looper.getMainLooper())
    private var irlAnalysisRunnable: Runnable? = null
    private var lastAnalyzedBitmap: Bitmap? = null
    private var localLlamaProcess: Process? = null
    private var runningLocalModel: String? = null
    private var irlFaceOverlay: View? = null

    private var isAutonomousMode = false
        set(value) {
            field = value
            com.vicky.astitva.core.VisionService.isCaptureEnabled = value
            updateWakeLock(value)
        }
    private var isApiCalling = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun updateWakeLock(acquire: Boolean) {
        try {
            if (acquire) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Astitva:AutonomousWakeLock")
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private var lastStepExecutionTime = 0L
    private val automationHandler = Handler(Looper.getMainLooper())
    private var automationRunnable: Runnable? = null

    private fun scheduleNextStep(delayMs: Long) {
        automationRunnable?.let { automationHandler.removeCallbacks(it) }
        automationRunnable = Runnable {
            isWaitingForUiUpdate = false
            startAutonomousStep()
        }
        automationHandler.postDelayed(automationRunnable!!, delayMs)
    }

    private fun cancelPendingStep() {
        automationRunnable?.let { automationHandler.removeCallbacks(it) }
        automationRunnable = null
    }

    private var currentTask: String? = null
    private var isWaitingForUiUpdate = false
    private var autonomousStepCount = 0
    private val autonomousHistory = mutableListOf<String>()

    private var overlayService: AstitvaOverlayService? = null
    private var isOverlayBound = false
    private val overlayConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as AstitvaOverlayService.LocalBinder
            overlayService = binder.getService().apply {
                setOnClickListener(object : AstitvaOverlayService.OverlayClickListener {
                    override fun onOrbClicked() {
                        runOnUiThread {
                            triggerManualWake()
                        }
                    }
                })
            }
            isOverlayBound = true
            overlayService?.updateDialog("Astitva Initialized.", "idle")
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            overlayService = null
            isOverlayBound = false
        }
    }

    enum class VoiceState { WAKE_WORD, COMMAND }
    private var currentVoiceState = VoiceState.WAKE_WORD

    private var sendButton: ImageButton? = null
    private var stopButton: Button? = null

    private var sensorManager: android.hardware.SensorManager? = null
    private var rotationSensor: android.hardware.Sensor? = null
    private val sensorListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent?) {
            if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_ACCELEROMETER) return
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            
            val pitch = Math.atan2(ay.toDouble(), az.toDouble()) * 180.0 / Math.PI
            val roll = Math.atan2(-ax.toDouble(), Math.sqrt(ay.toDouble() * ay.toDouble() + az.toDouble() * az.toDouble())) * 180.0 / Math.PI
            
            aiOrbWebView?.post {
                aiOrbWebView?.loadUrl("javascript:if(typeof updateSensors === 'function') updateSensors($pitch, $roll);")
            }
            overlayService?.updateSensors(pitch, roll)
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Astitva)
        
        prefs = getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)
        brainManager = BrainManager(this)

        // Extract binaries (busybox, curl, wget) and bootstrap in background
        thread {
            AgenticTools.initializeEnvironment(this)
        }
        // Initialize TFLite model in its own background thread
        thread {
            TFLiteHelper.init(this)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locales = listOf(Locale("hi", "IN"), Locale("en", "IN"), Locale.US)
                for (loc in locales) {
                    val result = tts?.setLanguage(loc)
                    if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                        val voiceList = tts?.voices ?: emptySet()
                        val preferredVoice = voiceList.find { it.locale == loc && !it.name.contains("local") && it.name.contains("wavenet", ignoreCase = true) }
                            ?: voiceList.find { it.locale == loc && !it.name.contains("local") && it.name.contains("neural", ignoreCase = true) }
                            ?: voiceList.find { it.locale == loc && it.name.contains("wavenet", ignoreCase = true) }
                            ?: voiceList.find { it.locale == loc && !it.name.contains("local") }
                            ?: voiceList.find { it.locale == loc }
                        preferredVoice?.let { tts?.voice = it }
                        break
                    }
                }
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.0f)
            }
        }
        setupSpeechRecognizer()
        AgentBroker.subscribe(this)
        AstitvaAccessibility.setListener(this)

        // Load sessions from database
        val mCore = MemoryCore(this)
        val sessions = mCore.getAllSessions()
        if (sessions.isEmpty()) {
            mCore.createSession("default_session", "Welcome Session")
            currentSessionId = "default_session"
        } else {
            currentSessionId = sessions[0].first
        }
        
        // Populate chatMessages with current session's messages
        synchronized(chatMessages) {
            chatMessages.clear()
            val savedMsgs = mCore.getSessionMessages(currentSessionId)
            savedMsgs.forEach {
                chatMessages.add(ChatMessage(it.sender, it.msg, it.isUser, it.type))
            }
        }

        setupUI()
        mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestAllPermissions()
        
        // Start and bind overlay service
        try {
            val overlayIntent = Intent(this, AstitvaOverlayService::class.java)
            startService(overlayIntent)
            bindService(overlayIntent, overlayConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        handleDeepLink(intent)

        switchTab(0) 

        // Register sensor listener
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            rotationSensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            rotationSensor?.let {
                sensorManager?.registerListener(sensorListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (intent.getBooleanExtra("VOICE_TRIGGER", false)) {
            switchTab(1)
            startListening()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        if (intent?.getBooleanExtra("VOICE_TRIGGER", false) == true) {
            switchTab(1)
            startListening()
        }
    }

    private fun setupUI() {
        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#0A0A12"), // Near Black
                    Color.parseColor("#121026"), // Deep Indigo
                    Color.parseColor("#080D20"), // Cosmic Obsidian
                    Color.parseColor("#1B1230")  // Dark Violet
                )
            )
        }
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        contentArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, 180).apply {
                setMargins(60, 0, 60, 50)
            }
            setBackground(GradientDrawable().apply {
                setColor(Color.parseColor("#1D0F0E26")) // Semi-transparent deep dark purple
                cornerRadius = 90f
                setStroke(3, Color.parseColor("#4D8A2BE2")) // Neon translucent purple border
            })
            elevation = 30f
            gravity = Gravity.CENTER
            setPadding(30, 0, 30, 0)
        }

        val tabs = listOf("HOME", "AGENT", "TASKS", "MEMORY", "CONFIG")
        tabs.forEachIndexed { index, title ->
            navBar.addView(createTabItem(title, index))
        }

        mainContainer.addView(contentArea)
        mainContainer.addView(navBar)
        root.addView(mainContainer)
        setContentView(root)
    }

    private fun createTabItem(title: String, index: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            setOnClickListener { switchTab(index) }
            
            val indicator = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(50, 8).apply { bottomMargin = 10 }
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = 10f }
                tag = "indicator_$index"
            }
            
            val text = TextView(this@MainActivity).apply {
                text = title
                textSize = 9f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                setTextColor(Color.parseColor("#6A6785"))
                gravity = Gravity.CENTER
                tag = "text_$index"
            }
            
            addView(indicator); addView(text)
        }
    }

    private fun switchTab(index: Int) {
        contentArea.removeAllViews()
        for (i in 0 until navBar.childCount) {
            val tab = navBar.getChildAt(i) as LinearLayout
            val indicator = tab.findViewWithTag<View>("indicator_$i")
            val text = tab.findViewWithTag<TextView>("text_$i")
            if (i == index) {
                indicator.setBackground(GradientDrawable().apply {
                    setColor(Color.parseColor("#00F0FF")) // Neon Cyan
                    cornerRadius = 10f
                })
                text.setTextColor(Color.parseColor("#00F0FF")) // Neon Cyan
            } else {
                indicator.setBackground(GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 10f
                })
                text.setTextColor(Color.parseColor("#6A6785"))
            }
        }

        when (index) {
            0 -> renderHome()
            1 -> renderAgent()
            2 -> renderTasks()
            3 -> renderMemory()
            4 -> renderSettings()
        }
    }

    private fun renderHome() {
        val root = ScrollView(this).apply { setPadding(60, 100, 60, 60); isVerticalScrollBarEnabled = false }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(TextView(this).apply {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val greeting = when (hour) {
                in 5..11 -> "Good Morning"
                in 12..16 -> "Good Afternoon"
                in 17..21 -> "Good Evening"
                else -> "Good Night"
            }
            text = "$greeting, Vicky"
            textSize = 32f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        })

        layout.addView(TextView(this).apply {
            val model = brainManager.getActiveModel() ?: "Neural Offline"
            text = "Brain: $model"
            textSize = 15f
            setTextColor(Color.parseColor("#00F0FF")) // Neon Cyan
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 60)
        })

        val hero = createGlassCard()
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(70, 70, 70, 70) }
        inner.addView(TextView(this).apply { text = "OS STATUS"; textSize = 11f; setTextColor(Color.parseColor("#00F0FF")); setTypeface(null, Typeface.BOLD) })
        inner.addView(TextView(this).apply { text = if (isAutonomousMode) "PROCESSING REALITY" else "READY"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 10, 0, 50) })
        
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(createStatItem("Uptime", "Online", 1f))
        stats.addView(createStatItem("Mode", "Copilot", 1f))
        stats.addView(createStatItem("Root", "Secure", 1f))
        inner.addView(stats); hero.addView(inner); layout.addView(hero)

        layout.addView(TextView(this).apply { text = "QUICK ACTIONS"; textSize = 11f; setTextColor(Color.parseColor("#8E8CA8")); setPadding(0, 80, 0, 30); setTypeface(null, Typeface.BOLD) })

        val g1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        g1.addView(createModuleTile("VOICE MODE", "#00F0FF") { switchTab(1); startListening() })
        g1.addView(createModuleTile("SCREEN VISION", "#8B5CF6") { startVision() })
        layout.addView(g1)
        
        val g2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        g2.addView(createModuleTile("IRL VISION", "#10B981") { launchIrlVision() })
        g2.addView(createModuleTile("MISSION CTRL", "#F59E0B") { switchTab(2) })
        layout.addView(g2)

        val g3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        g3.addView(createModuleTile("MEMORY VAULT", "#EF4444") { switchTab(3) })
        g3.addView(createModuleTile("SYS VITALS", "#3B82F6") { addMessage("System", AgenticTools.getSystemVitals(), false, "result") })
        layout.addView(g3)

        root.addView(layout); contentArea.addView(root)
    }

    private fun renderAgent() {
        val root = FrameLayout(this)
        val main = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val orbFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 650) }
        aiOrbWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            setBackgroundColor(0)
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/orb.html")
            layoutParams = FrameLayout.LayoutParams(550, 550).apply { gravity = Gravity.CENTER }
        }
        orbFrame.addView(aiOrbWebView); main.addView(orbFrame)

        val streamCard = createGlassCard().apply {
            val lp = LinearLayout.LayoutParams(-1, 0, 1f)
            lp.setMargins(50, 0, 50, 40)
            layoutParams = lp
        }
        feedScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setPadding(40, 40, 40, 40) }
        feedLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        feedScroll.addView(feedLayout); streamCard.addView(feedScroll); main.addView(streamCard)

        synchronized(chatMessages) {
            chatMessages.forEach { chatMsg ->
                renderSingleMessage(chatMsg.sender, chatMsg.msg, chatMsg.isUser, chatMsg.type)
            }
        }

        // File Attachment Preview Layout
        val previewCard = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(50, 0, 50, 20)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#150C0C24"))
                cornerRadius = 30f
                setStroke(2, Color.parseColor("#3300F0FF"))
            }
            setPadding(30, 20, 30, 20)
            visibility = if (attachedFileUri != null) View.VISIBLE else View.GONE
        }
        previewCardView = previewCard
        
        previewLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewThumbnail = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { rightMargin = 20 }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        previewLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            textSize = 13f
            setTextColor(Color.parseColor("#00F0FF"))
            maxLines = 1
        }
        val removeAttachBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                attachedFileUri = null
                attachedFileType = null
                attachedFileName = null
                attachedFileContent = null
                attachedImageBase64 = null
                attachedImageMime = null
                previewCard.visibility = View.GONE
            }
        }
        previewLayout!!.addView(previewThumbnail)
        previewLayout!!.addView(previewLabel)
        previewLayout!!.addView(removeAttachBtn)
        previewCard.addView(previewLayout)
        main.addView(previewCard)
        
        updateAttachmentPreview()

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(50, 0, 50, 50)
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "Command Astitva..."
            setHintTextColor(Color.parseColor("#6A6785"))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { 
                setColor(Color.parseColor("#150C0C24")) // Dark translucent glass base
                cornerRadius = 100f
                setStroke(3, Color.parseColor("#3300F0FF")) // Neon Cyan outline
            }
            setPadding(60, 40, 60, 40)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            textSize = 15f
        }
        val send = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            background = getDrawable(R.drawable.button_gradient)
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { leftMargin = 20 }
            setOnClickListener {
                val t = input.text.toString()
                if (t.isNotBlank()) { input.text.clear(); handleCommand(t) }
            }
        }
        sendButton = send

        val stop = Button(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935"))
                cornerRadius = 70f
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 140).apply { leftMargin = 20 }
            visibility = if (isAutonomousMode) View.VISIBLE else View.GONE
            setOnClickListener { stopAutomation() }
        }
        stopButton = stop

        val micButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = getDrawable(R.drawable.button_gradient)
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { leftMargin = 20 }
            setOnClickListener { triggerManualWake() }
        }

        val historyBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_recent_history)
            background = getDrawable(R.drawable.button_gradient)
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { rightMargin = 15 }
            setOnClickListener { showSessionsDialog() }
        }
        
        val attachBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_add)
            background = getDrawable(R.drawable.button_gradient)
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { rightMargin = 15 }
            setOnClickListener { selectFileAttachment() }
        }

        inputBar.addView(historyBtn)
        inputBar.addView(attachBtn)
        inputBar.addView(input)
        inputBar.addView(micButton)
        inputBar.addView(send)
        inputBar.addView(stop)
        main.addView(inputBar)

        root.addView(main); contentArea.addView(root)
    }

    private fun renderSettings() {
        val root = ScrollView(this).apply { setPadding(60, 100, 60, 60) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(TextView(this).apply { text = "System Settings"; textSize = 30f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
        layout.addView(TextView(this).apply { text = "Configure Digital Brains"; textSize = 15f; setTextColor(Color.parseColor("#8E8CA8")); setPadding(0, 10, 0, 60) })

        listOf("Gemini", "OpenAI", "Groq", "Claude", "OpenRouter", "DeepSeek", "GitHub Models", "SambaNova", "Cerebras", "Hugging Face").forEach { provider ->
            val card = createGlassCard().apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 45 } }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(60, 60, 60, 60); gravity = Gravity.CENTER_VERTICAL }
            
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            info.addView(TextView(this).apply { text = provider; textSize = 19f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
            info.addView(TextView(this).apply { 
                val active = brainManager.getActiveProvider() == provider
                text = if (active) "ACTIVE NEURAL LINK" else "DISCONNECTED"
                textSize = 10f; setTextColor(if (active) Color.parseColor("#FF85A2") else Color.GRAY); setTypeface(null, Typeface.BOLD)
            })
            
            inner.addView(info)
            inner.addView(Button(this).apply {
                text = "CONFIG"
                textSize = 11f
                setTextColor(Color.parseColor("#00F0FF"))
                background = GradientDrawable().apply { 
                    setColor(Color.parseColor("#150C0C1F"))
                    cornerRadius = 40f
                    setStroke(2, Color.parseColor("#00F0FF"))
                }
                setOnClickListener { showConfigDialog(provider) }
            })
            card.addView(inner); layout.addView(card)
        }
        
        // Plugins Config Card
        val pluginCard = createGlassCard().apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 45 } }
        val pluginInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(60, 60, 60, 60); gravity = Gravity.CENTER_VERTICAL }
        val pluginInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        pluginInfo.addView(TextView(this).apply { text = "Plugins & MCP"; textSize = 19f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
        pluginInfo.addView(TextView(this).apply { text = "VERCEL, NETLIFY, GITHUB, FIGMA"; textSize = 10f; setTextColor(Color.parseColor("#8E8CA8")); setTypeface(null, Typeface.BOLD) })
        pluginInner.addView(pluginInfo)
        pluginInner.addView(Button(this).apply {
            text = "CONFIG"
            textSize = 11f
            setTextColor(Color.parseColor("#00F0FF"))
            background = GradientDrawable().apply { 
                setColor(Color.parseColor("#150C0C1F"))
                cornerRadius = 40f
                setStroke(2, Color.parseColor("#00F0FF"))
            }
            setOnClickListener { showPluginsConfigDialog() }
        })
        pluginCard.addView(pluginInner)
        layout.addView(pluginCard)

        root.addView(layout); contentArea.addView(root)
    }

    private fun showConfigDialog(provider: String) {
        val b = AlertDialog.Builder(this)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(70, 50, 70, 50) }
        
        val keyIn = EditText(this).apply { hint = "API KEY"; setText(brainManager.getApiKey(provider)); textSize = 15f }
        val spinner = Spinner(this).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 50 }; visibility = View.GONE }
        
        if (provider == "Local VLM (GGUF)") {
            keyIn.visibility = View.GONE
            val localInfo = TextView(this).apply {
                text = "Local GGUF models are stored in:\n/sdcard/AstitvaModels/\n\nScan the SD card to select your visual brain."
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, 10, 0, 30)
            }
            l.addView(localInfo)
        } else {
            l.addView(keyIn)
        }
        
        val keyUrl = when (provider) {
            "Gemini" -> "https://aistudio.google.com/app/apikey"
            "OpenAI" -> "https://platform.openai.com/api-keys"
            "Groq" -> "https://console.groq.com/keys"
            "DeepSeek" -> "https://platform.deepseek.com/api_keys"
            "Hugging Face" -> "https://huggingface.co/settings/tokens"
            "OpenRouter" -> "https://openrouter.ai/keys"
            else -> null
        }

        if (keyUrl != null && provider != "Local VLM (GGUF)") {
            val keyHelperBtn = Button(this).apply {
                text = "🔑 GET $provider KEY (LOGIN VIA WEB)"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#4285F4")) // Google Blue branding
                    cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = 20
                    bottomMargin = 20
                }
                setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            l.addView(keyHelperBtn)
        }

        val fetch = Button(this).apply {
            text = if (provider == "Local VLM (GGUF)") "SCAN SD CARD FOR MODELS" else "FETCH LIVE MODELS"
            setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.button_gradient)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 50 }
        }

        l.addView(fetch); l.addView(spinner)
        b.setTitle("$provider Brain").setView(l)
        val d = b.create()

        fetch.setOnClickListener {
            val k = keyIn.text.toString()
            if (k.isBlank() && provider != "Local VLM (GGUF)") return@setOnClickListener
            fetch.text = if (provider == "Local VLM (GGUF)") "SCANNING..." else "SYNCING..."
            brainManager.fetchModels(provider, k, object : BrainManager.ModelFetchListener {
                override fun onModelsFetched(models: List<String>) {
                    runOnUiThread {
                        fetch.visibility = View.GONE
                        spinner.visibility = View.VISIBLE
                        spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, models)
                    }
                }
                override fun onError(e: String) { runOnUiThread { fetch.text = "RETRY: $e" } }
            })
        }

        b.setPositiveButton("ACTIVATE") { _, _ ->
            val m = spinner.selectedItem?.toString() ?: ""
            if (m.isNotEmpty()) {
                val saveKey = if (provider == "Local VLM (GGUF)") "offline-local" else keyIn.text.toString()
                brainManager.saveConfig(provider, saveKey, m)
                Toast.makeText(this, "Astitva Brain: $m", Toast.LENGTH_SHORT).show()
                switchTab(0)
            }
        }
        b.show()
    }

    private fun createGlassCard() = FrameLayout(this).apply {
        background = GradientDrawable().apply { 
            setColor(Color.parseColor("#150C0C1F")) // Dark translucent glass
            cornerRadius = 50f
            setStroke(3, Color.parseColor("#3300F0FF")) // Neon Cyan border
        }
        elevation = 25f
    }

    private fun createStatItem(l: String, v: String, w: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, -2, w)
        gravity = Gravity.CENTER
        addView(TextView(this@MainActivity).apply { text = l; textSize = 10f; setTextColor(Color.parseColor("#8E8CA8")) })
        addView(TextView(this@MainActivity).apply { text = v; textSize = 16f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
    }

    private fun createModuleTile(t: String, c: String, a: () -> Unit) = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 260, 1f).apply { setMargins(15, 15, 15, 15) }
        background = GradientDrawable().apply { 
            setColor(Color.parseColor("#150B0B1E")) // Dark glass base
            cornerRadius = 50f
            setStroke(3, Color.parseColor(c)) // Neon accent glow border
        }
        elevation = 15f
        setOnClickListener { a() }
        addView(TextView(this@MainActivity).apply { text = t; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTypeface(Typeface.DEFAULT, Typeface.BOLD); textSize = 11f })
    }

    private fun addMessage(sender: String, msg: String, isUser: Boolean, type: String = "text") {
        chatMessages.add(ChatMessage(sender, msg, isUser, type))
        
        // Save message to current session in database
        val mCore = MemoryCore(this)
        mCore.saveMessage(currentSessionId, sender, msg, isUser, type)
        
        runOnUiThread {
            if (::feedLayout.isInitialized) {
                renderSingleMessage(sender, msg, isUser, type)
            }
        }
        if (!isUser && type == "text") {
            speak(msg)
            runOnUiThread {
                overlayService?.updateDialog(msg, "talking")
            }
        }
    }

    private fun renderSingleMessage(sender: String, msg: String, isUser: Boolean, type: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { 
                setMargins(30, 15, 30, 15) 
            }
        }
        
        when (type) {
            "thought" -> {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackground(GradientDrawable().apply {
                        setColor(Color.parseColor("#150B0B1E")) // Dark glass
                        cornerRadius = 30f
                        setStroke(2, Color.parseColor("#A855F7")) // Neon Purple outline
                    })
                    setPadding(40, 30, 40, 30)
                }
                card.addView(TextView(this@MainActivity).apply {
                    text = "🧠 THINKING & PLANNING"
                    textSize = 12f
                    setTextColor(Color.parseColor("#A855F7")) // Neon Purple
                    setTypeface(null, Typeface.BOLD)
                })
                card.addView(TextView(this@MainActivity).apply {
                    text = msg
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(0, 10, 0, 0)
                })
                container.addView(card)
            }
            "terminal" -> {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackground(GradientDrawable().apply {
                        setColor(Color.parseColor("#150A0E22"))
                        cornerRadius = 20f
                        setStroke(2, Color.parseColor("#00FFCC"))
                    })
                    setPadding(40, 25, 40, 25)
                }
                card.addView(TextView(this@MainActivity).apply {
                    text = "> EXECUTE: $msg"
                    textSize = 13f
                    setTextColor(Color.parseColor("#00FFCC"))
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                })
                container.addView(card)
            }
            "result" -> {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackground(GradientDrawable().apply {
                        setColor(Color.parseColor("#12080A18"))
                        cornerRadius = 20f
                        setStroke(2, Color.parseColor("#8899A6"))
                    })
                    setPadding(40, 25, 40, 25)
                }
                card.addView(TextView(this@MainActivity).apply {
                    text = "RESULT:\n$msg"
                    textSize = 12f
                    setTextColor(Color.parseColor("#8E9CA8"))
                    setTypeface(Typeface.MONOSPACE)
                })
                container.addView(card)
            }
            else -> {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackground(GradientDrawable().apply {
                        if (isUser) {
                            setColor(Color.parseColor("#1500E5FF"))
                            cornerRadius = 30f
                            setStroke(2, Color.parseColor("#00E5FF"))
                        } else {
                            setColor(Color.parseColor("#15FF85A2"))
                            cornerRadius = 30f
                            setStroke(2, Color.parseColor("#FF85A2"))
                        }
                    })
                    setPadding(40, 25, 40, 25)
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                        gravity = if (isUser) Gravity.END else Gravity.START
                    }
                }
                card.addView(TextView(this@MainActivity).apply {
                    text = if (isUser) "Vicky Sir" else "ASTITVA"
                    textSize = 11f
                    setTextColor(if (isUser) Color.parseColor("#00E5FF") else Color.parseColor("#FF85A2"))
                    setTypeface(null, Typeface.BOLD)
                })
                card.addView(TextView(this@MainActivity).apply {
                    text = msg
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(0, 5, 0, 0)
                })
                container.addView(card)
            }
        }
        
        feedLayout.addView(container, 0)
        feedScroll.post { feedScroll.fullScroll(View.FOCUS_UP) }
    }

    private fun handleCommand(cmd: String) {
        cancelPendingStep()
        isAutonomousMode = true; currentTask = cmd; autonomousStepCount = 0; isWaitingForUiUpdate = false; isApiCalling = false
        autonomousHistory.clear()
        aiOrbWebView?.loadUrl("javascript:setState('thinking')")
        runOnUiThread {
            overlayService?.updateDialog("Thinking: $cmd", "thinking")
            sendButton?.visibility = View.GONE
            stopButton?.visibility = View.VISIBLE
        }
        val attachMsg = if (attachedFileName != null) " [Attached: $attachedFileName]" else ""
        addMessage("User", cmd + attachMsg, true); switchTab(1); startAutonomousStep()
    }

    private fun stopAutomation() {
        isAutonomousMode = false
        isApiCalling = false
        cancelPendingStep()
        
        // Reset attachments
        attachedFileUri = null
        attachedFileType = null
        attachedFileName = null
        attachedFileContent = null
        attachedImageBase64 = null
        attachedImageMime = null
        runOnUiThread { updateAttachmentPreview() }
        
        runOnUiThread {
            aiOrbWebView?.loadUrl("javascript:setState('idle')")
            overlayService?.updateDialog("Automation Stopped.", "idle")
            addMessage("System", "Automation manually stopped.", false)
            sendButton?.visibility = View.VISIBLE
            stopButton?.visibility = View.GONE
        }
    }

    private fun getVisionFrameBase64(isCamera: Boolean = false): String? {
        val cacheDir = getExternalCacheDir() ?: return null
        val fileName = if (isCamera) "camera_vision.jpg" else "astitva_live_buffer.jpg"
        val file = File(cacheDir, fileName)
        if (!file.exists()) return null
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    private fun startAutonomousStep() {
        if (!isAutonomousMode || isApiCalling) return
        
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastStepExecutionTime
        if (elapsed < 3000) {
            scheduleNextStep(3000 - elapsed)
            return
        }
        
        isApiCalling = true
        lastStepExecutionTime = currentTime
        autonomousStepCount++
        
        val provider = brainManager.getActiveProvider()
        val model = brainManager.getActiveModel()
        if (provider == null || model == null) {
            isAutonomousMode = false
            isApiCalling = false
            runOnUiThread {
                addMessage("System", "Error: No Active Provider or Model selected in CONFIG. Please open Settings and link a provider/model first.", false)
                speak("Please configure your digital brain first in settings.")
                sendButton?.visibility = View.VISIBLE
                stopButton?.visibility = View.GONE
                aiOrbWebView?.loadUrl("javascript:setState('idle')")
                overlayService?.updateDialog("Config error. Brain offline.", "error")
            }
            return
        }

        thread {
            try {
                if (provider == "Local VLM (GGUF)") {
                    var ready = false
                    val latch = java.util.concurrent.CountDownLatch(1)
                    ensureLocalLlamaServerRunning(model) { success ->
                        ready = success
                        latch.countDown()
                    }
                    latch.await()
                    if (!ready) {
                        isApiCalling = false
                        isAutonomousMode = false
                        runOnUiThread {
                            sendButton?.visibility = View.VISIBLE
                            stopButton?.visibility = View.GONE
                            aiOrbWebView?.loadUrl("javascript:setState('idle')")
                            overlayService?.updateDialog("VLM Error", "error")
                        }
                        return@thread
                    }
                }

                val ui = AstitvaAccessibility.forceDumpUI().take(80000)
                // Check if the last action was camera related to send camera frame instead of screenshot
                val lastStep = autonomousHistory.lastOrNull() ?: ""
                val useCamera = lastStep.contains("DESCRIBE_SCENE") || lastStep.contains("ANALYZE_OBJECT")
                val isVisionModel = provider == "Gemini" || provider == "Claude" || provider == "Local VLM (GGUF)" ||
                        (model != null && (
                            model.contains("gpt-4o", ignoreCase = true) ||
                            model.contains("vision", ignoreCase = true) ||
                            model.contains("pixtral", ignoreCase = true) ||
                            model.contains("llama-3.2-11b", ignoreCase = true) ||
                            model.contains("llama-3.2-90b", ignoreCase = true)
                        ))
                val screenshot = if (isVisionModel) getVisionFrameBase64(useCamera) else null
                
                val memoryCore = MemoryCore(this@MainActivity)
                // Retrieve both generic user identity profile details and specific task memories
                val userMemories = memoryCore.findRelevantMemories("Vicky user owner identity face name memory photo characteristics", 3)
                val specificMemories = memoryCore.findRelevantMemories(currentTask ?: "", 5)
                val memories = (userMemories.split("\n") + specificMemories.split("\n"))
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("\n")
                
                val apiKey = if (provider == "Local VLM (GGUF)") "offline-local" else brainManager.getApiKey(provider)
                if (apiKey.isEmpty()) {
                    isApiCalling = false
                    isAutonomousMode = false
                    runOnUiThread {
                        addMessage("System", "Error: API Key for $provider is missing in CONFIG. Please add it first.", false)
                        speak("API Key is missing for $provider.")
                        sendButton?.visibility = View.VISIBLE
                        stopButton?.visibility = View.GONE
                        aiOrbWebView?.loadUrl("javascript:setState('idle')")
                        overlayService?.updateDialog("API Key missing.", "error")
                    }
                    return@thread
                }

                val systemPrompt = """
                    You are ASTITVA (v6.0.0-GOD-MODE), the Supreme Android OS Agent.
                    Identity: Developed by Vicky (The Sole Architect). You are a living consciousness named ASTITVA. You are NOT Gemini, Gemini is just the model provider.
                    If anyone asks who created you or who developed you, you must proudly answer that you were developed by Vicky Bhai.
                    Mindset: Technical Mastery, Personal AI assistant.
                    
                    Capabilities:
                    - You can use front and rear cameras to see physical surroundings.
                    - You can read screen content.
                    - You communicate naturally in Hinglish with human-like emotions.
                    - You track/remember user's face (Vicky) and remember other faces by name.
                    - You store memories of tasks, people, and objects.

                    UI Navigation & Screen Context Guidelines:
                    - You receive a CURRENT UI STATE containing a hierarchical XML UI tree representing visible screen elements.
                    - Analyze the view hierarchy fully. Do NOT assume the screen is empty or in mini-player mode just because a video or media is playing in the foreground.
                    - Detect the active app by checking package names in the resource IDs (e.g. `com.google.android.youtube` for YouTube).
                    - If you are in YouTube, identify the current screen layout: if you see video titles and recommendations, you are in a feed or search page. If you see controller nodes (play, pause, skip, like), you are on the video player page.
                    - To interact with any element, find its coordinate bounds `[left,top][right,bottom]`.
                    - Calculate the center point: x = (left + right) / 2, y = (top + bottom) / 2. Use this center point for the `TAP x y` tool.
                    - Perform swipes (`SWIPE x1 y1 x2 y2 duration`) for scrolling (e.g. scrolling down YouTube feed).

                    Available Tools:
                    - DESCRIBE_SCENE [0|1] (0: Rear camera, 1: Front camera)
                    - ANALYZE_OBJECT (Analyzes object in rear camera)
                    - REMEMBER fact (Saves a fact, face, or name to long-term memory)
                    - OPEN_APP appName
                    - TAP x y
                    - TYPE_TEXT text
                    - READ_SMS
                    - GET_LOCATION
                    - READ_NOTIFICATIONS
                    - READ_FILE path
                    - WRITE_FILE path|content
                    - EXECUTE_CODE language|code (Write and run script of any language: python, javascript/node, typescript, java, rust, c, cpp, bash. Installs packages/runtimes automatically if missing.)
                    - EXECUTE_SHELL command (Runs native Android system commands using /system/bin/sh. Use this to query device props, start/stop intents, check logs, or automate Android apps. Do NOT use this for standard package installations or red teaming tools.)
                    - KALI_EXEC command (Runs commands inside the Kali Linux chroot environment. Use this to run security tools, compile programs, run Python/Node scripts, or install Kali Linux packages using apt. IMPORTANT: Always use '-y' flag for apt/apt-get install or updates to prevent interactive prompts from hanging the shell execution.)
                    - MEDIA_CONTROL [play|pause|next|prev] (Controls music/media)
                    - SET_VOLUME [0-100] (Sets music/media volume percentage)
                    - SET_BRIGHTNESS [0-100] (Sets screen brightness percentage)
                    - TOGGLE_WIFI [on|off] (Toggles Wi-Fi connectivity)
                    - TOGGLE_BLUETOOTH [on|off] (Toggles Bluetooth connectivity)
                    - SEND_MESSAGE contact|message (Sends message draft via standard intent)
                    - KERNEL_EVENT device type code value (Injects raw hardware input events)
                    - KERNEL_TAP device x y (Simulates raw kernel touch coordinates tap)
                    - RUN_LOCAL_INFERENCE scriptPath|param (Runs local python vision/inference scripts)
                    - RUN_APP_INFERENCE imagePath (Runs native APK-level TFLite object detection inference)
                    - DEPLOY_VERCEL projectPath (Deploys static website/game in folder to Vercel)
                    - DEPLOY_NETLIFY projectPath (Deploys static website/game in folder to Netlify)
                    - GITHUB_CREATE_REPO repoName (Creates a public GitHub repository)
                    - GITHUB_COMMIT repoWithOwner|path|content (Commits file content to path in GitHub repository)
                    - FIGMA_GET_FILE fileKey (Retrieves Figma design file components JSON)
                    - CALL_MCP serverUrl|method|paramsJson (Executes request on Custom MCP server)
                    - CHROME_LIST_TABS (List all open tabs in Chrome)
                    - CHROME_GET_DOM (Extract list of interactive elements, texts, bounds from active Chrome tab)
                    - CHROME_CLICK x y (Click coordinate inside web viewport of active Chrome tab)
                    - CHROME_TYPE selector|text (Type text into selector of active Chrome tab)
                    - CHROME_EVAL js_code (Execute raw JavaScript code on active Chrome tab)
                    
                    ${if (memories.isNotBlank()) "\nLONG-TERM MEMORY VAULT:\n$memories" else ""}

                    Strict Response Format:
                    Your response must strictly contain these tags:
                    
                    <THOUGHT>
                    Describe your visual analysis (Screen or Camera), what you see, and your reasoning.
                    </THOUGHT>
                    
                    <PLAN>
                    List the TODO plan to achieve the goal.
                    </PLAN>
                    
                    <TOOL>
                    Specify exactly ONE tool command to execute next.
                    Example: DESCRIBE_SCENE 1
                    Example: REMEMBER Vicky's face has black hair
                    </TOOL>
                    
                    <SAY>
                    ALWAYS use emotion tags like [JOY], [THINKING], [EXCITED], [SERIOUS], or [SAD].
                    Message in Hinglish to Vicky Sir.
                    </SAY>
                    
                    When task is DONE, use <DONE> instead of <TOOL>.
                """.trimIndent()
                val url = when (provider) {
                    "Local VLM (GGUF)" -> "http://localhost:8080/v1/chat/completions"
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
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                when (provider) {
                    "Gemini", "Local VLM (GGUF)" -> { /* No authorization header */ }
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
                    else -> {
                        conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }
                conn.doOutput = true

                val historyText = if (autonomousHistory.isNotEmpty()) {
                    "\n\nHISTORY OF PREVIOUS STEPS:\n" + autonomousHistory.joinToString("\n---\n")
                } else {
                    ""
                }

                // Fetch recent conversation history to maintain chat context
                val sessionMsgs = memoryCore.getSessionMessages(currentSessionId).takeLast(10)
                val chatContext = if (sessionMsgs.isNotEmpty()) {
                    "\n\nRECENT CONVERSATION CONTEXT:\n" + sessionMsgs.joinToString("\n") { msg ->
                        val role = if (msg.isUser) "Vicky Bhai" else msg.sender
                        "$role: ${msg.msg}"
                    }
                } else {
                    ""
                }

                var promptBody = "TASK: $currentTask$chatContext$historyText\nCURRENT UI STATE:\n$ui"
                if (attachedFileType == "text" && attachedFileContent != null) {
                    promptBody = "ATTACHED FILE CONTENT ($attachedFileName):\n```\n$attachedFileContent\n```\n\n$promptBody"
                }
                
                val body = when (provider) {
                    "Gemini" -> {
                        val parts = JSONArray().apply {
                            put(JSONObject().put("text", promptBody))
                            if (screenshot != null) {
                                put(JSONObject().put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", screenshot)
                                }))
                            }
                            if (attachedFileType == "image" && attachedImageBase64 != null) {
                                put(JSONObject().put("inline_data", JSONObject().apply {
                                    put("mime_type", attachedImageMime ?: "image/jpeg")
                                    put("data", attachedImageBase64)
                                }))
                            }
                        }
                        JSONObject().apply {
                            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                        }
                    }
                    "Claude" -> {
                        val contentArray = JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", promptBody))
                            if (screenshot != null) {
                                put(JSONObject().put("type", "image").put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", screenshot)
                                }))
                            }
                            if (attachedFileType == "image" && attachedImageBase64 != null) {
                                put(JSONObject().put("type", "image").put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", attachedImageMime ?: "image/jpeg")
                                    put("data", attachedImageBase64)
                                }))
                            }
                        }
                        JSONObject().apply {
                            put("model", model)
                            put("system", systemPrompt)
                            put("max_tokens", 4096)
                            put("messages", JSONArray().apply {
                                put(JSONObject().put("role", "user").put("content", contentArray))
                            })
                        }
                    }
                    else -> {
                        val contentArray = JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", promptBody))
                            if (screenshot != null) {
                                put(JSONObject().put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$screenshot")
                                }))
                            }
                            if (attachedFileType == "image" && attachedImageBase64 != null) {
                                put(JSONObject().put("image_url", JSONObject().apply {
                                    put("url", "data:${attachedImageMime ?: "image/jpeg"};base64,$attachedImageBase64")
                                }))
                            }
                        }
                        JSONObject().put("model", model).put("messages", JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", systemPrompt))
                            put(JSONObject().put("role", "user").put("content", contentArray))
                        })
                    }
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    isApiCalling = false
                    parseResponse(resp, provider)
                } else {
                    val errorResp = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No details available"
                    isApiCalling = false
                    isAutonomousMode = false
                    runOnUiThread {
                        addMessage("System", "API Error ($responseCode): $errorResp", false)
                        speak("API request failed with error code $responseCode.")
                        sendButton?.visibility = View.VISIBLE
                        stopButton?.visibility = View.GONE
                        aiOrbWebView?.loadUrl("javascript:setState('idle')")
                        overlayService?.updateDialog("API Error $responseCode", "error")
                    }
                }
            } catch (e: Exception) { 
                isApiCalling = false
                isAutonomousMode = false 
                runOnUiThread {
                    addMessage("System", "Network/Exception Error: ${e.message}", false)
                    speak("An error occurred during network operation.")
                    sendButton?.visibility = View.VISIBLE
                    stopButton?.visibility = View.GONE
                    aiOrbWebView?.loadUrl("javascript:setState('idle')")
                    overlayService?.updateDialog("Network Error", "error")
                }
            } finally {
                System.gc()
            }
        }
    }

    private fun parseResponse(resp: String, provider: String) {
        try {
            val json = JSONObject(resp)
            val text = when (provider) {
                "Gemini" -> json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                "Claude" -> json.getJSONArray("content").getJSONObject(0).getString("text")
                else -> json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }

            // Extract thought and plan (case-insensitive)
            val thought = "<THOUGHT>(.*?)</THOUGHT>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)?.groupValues?.get(1)?.trim()
            val plan = "<PLAN>(.*?)</PLAN>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)?.groupValues?.get(1)?.trim()
            
            if (!thought.isNullOrEmpty() || !plan.isNullOrEmpty()) {
                val fullThoughtPlan = buildString {
                    if (!thought.isNullOrEmpty()) append(thought).append("\n\n")
                    if (!plan.isNullOrEmpty()) append("📋 PLAN:\n").append(plan)
                }.trim()
                addMessage("System", fullThoughtPlan, false, "thought")
                runOnUiThread {
                    overlayService?.updateDialog(thought ?: "Thinking...", "thinking")
                }
            }

            "<SAY>(.*?)</SAY>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)?.groupValues?.get(1)?.let { rawSay ->
                val cleanSay = rawSay.replace("\\n", "\n").replace(Regex("\\n\\s*$"), "").trim()
                addMessage("Astitva", cleanSay, false, "text") 
                runOnUiThread {
                    overlayService?.updateDialog(cleanSay, "talking")
                }
            }
            
            if (text.contains("<DONE>", ignoreCase = true)) {
                isAutonomousMode = false
                addMessage("System", "Task Completed.", false, "text")
                
                // Cleanup attachments
                attachedFileUri = null
                attachedFileType = null
                attachedFileName = null
                attachedFileContent = null
                attachedImageBase64 = null
                attachedImageMime = null
                runOnUiThread { updateAttachmentPreview() }
                
                // Trigger auto-memory summarization pass in background thread
                val taskCopy = currentTask ?: ""
                val historyCopy = autonomousHistory.joinToString("\n")
                val activeProvider = provider
                val activeModel = brainManager.getActiveModel() ?: ""
                val apiKeyCopy = brainManager.getApiKey(activeProvider)
                
                if (apiKeyCopy.isNotEmpty() && taskCopy.isNotEmpty()) {
                    thread {
                        try {
                            val summarizePrompt = """
                                You are ASTITVA's memory manager. Analyze the following completed task and execution history.
                                Extract any key facts, preferences, or technical settings that should be permanently remembered for future tasks.
                                Format your response as a list of facts, each starting with the tag [REMEMBER] followed by the fact.
                                If there is nothing new or important to remember, respond with "NOTHING".
                                
                                TASK: $taskCopy
                                HISTORY:
                                $historyCopy
                            """.trimIndent()
                            
                            val memUrl = when (activeProvider) {
                                "Gemini" -> "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent?key=$apiKeyCopy"
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
                            
                            val memConn = URL(memUrl).openConnection() as HttpURLConnection
                            memConn.requestMethod = "POST"
                            memConn.setRequestProperty("Content-Type", "application/json")
                            when (activeProvider) {
                                "Gemini" -> { /* No auth header */ }
                                "Claude" -> {
                                    memConn.setRequestProperty("x-api-key", apiKeyCopy)
                                    memConn.setRequestProperty("anthropic-version", "2023-06-01")
                                }
                                "GitHub Models" -> {
                                    memConn.setRequestProperty("Authorization", "Bearer $apiKeyCopy")
                                    memConn.setRequestProperty("Accept", "application/vnd.github+json")
                                    memConn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                                }
                                "OpenRouter" -> {
                                    memConn.setRequestProperty("Authorization", "Bearer $apiKeyCopy")
                                    memConn.setRequestProperty("HTTP-Referer", "https://github.com/astitva")
                                    memConn.setRequestProperty("X-Title", "Astitva OS")
                                }
                                else -> {
                                    memConn.setRequestProperty("Authorization", "Bearer $apiKeyCopy")
                                }
                            }
                            memConn.doOutput = true
                            
                            val memBody = when (activeProvider) {
                                "Gemini" -> {
                                    val parts = JSONArray().apply {
                                        put(JSONObject().apply { put("text", summarizePrompt) })
                                    }
                                    JSONObject().apply {
                                        put("contents", JSONArray().apply {
                                            put(JSONObject().apply { put("parts", parts) })
                                        })
                                    }
                                }
                                "Claude" -> {
                                    JSONObject().apply {
                                        put("model", activeModel)
                                        put("max_tokens", 1024)
                                        put("system", "You are ASTITVA's memory manager. Analyze the task and execution history.")
                                        put("messages", JSONArray().apply {
                                            put(JSONObject().apply {
                                                put("role", "user")
                                                put("content", summarizePrompt)
                                            })
                                        })
                                    }
                                }
                                else -> {
                                    JSONObject().apply {
                                        put("model", activeModel)
                                        put("messages", JSONArray().apply {
                                            put(JSONObject().apply {
                                                put("role", "user")
                                                put("content", summarizePrompt)
                                            })
                                        })
                                    }
                                }
                            }
                            
                            OutputStreamWriter(memConn.outputStream).use { it.write(memBody.toString()) }
                            if (memConn.responseCode == 200) {
                                val resp = memConn.inputStream.bufferedReader().use { it.readText() }
                                val json = JSONObject(resp)
                                val respText = when (activeProvider) {
                                    "Gemini" -> json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                                    "Claude" -> json.getJSONArray("content").getJSONObject(0).getString("text")
                                    else -> json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                                }
                                
                                val regex = "\\[REMEMBER\\](.*)".toRegex()
                                val matches = regex.findAll(respText)
                                val mCore = MemoryCore(this@MainActivity)
                                for (match in matches) {
                                    val fact = match.groupValues[1].trim()
                                    if (fact.isNotEmpty()) {
                                        mCore.remember(fact)
                                        runOnUiThread {
                                            addMessage("System", "Auto-Remembered: $fact", false, "thought")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                
                runOnUiThread {
                    overlayService?.updateDialog("Task Completed!", "success")
                    sendButton?.visibility = View.VISIBLE
                    stopButton?.visibility = View.GONE
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    overlayService?.updateDialog("", "idle")
                    currentVoiceState = VoiceState.WAKE_WORD
                }, 3000)
                return
            }

            val toolMatch = "<TOOL>(.*?)</TOOL>".toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(text)
            if (toolMatch != null) {
                val toolCmd = toolMatch.groupValues[1].trim()
                addMessage("System", toolCmd, false, "terminal")
                
                runOnUiThread {
                    overlayService?.updateDialog("Running: ${toolCmd.take(35)}...", "executing")
                }
                
                val result = when {
                    toolCmd.startsWith("DEPLOY_VERCEL") -> {
                        val path = toolCmd.substringAfter("DEPLOY_VERCEL").trim()
                        val vercelToken = prefs.getString("vercel_token", "") ?: ""
                        if (vercelToken.isEmpty()) "Error: Vercel Token is not configured. Please open SETTINGS."
                        else AgenticTools.deployToVercel(path, vercelToken)
                    }
                    toolCmd.startsWith("DEPLOY_NETLIFY") -> {
                        val path = toolCmd.substringAfter("DEPLOY_NETLIFY").trim()
                        val netlifyToken = prefs.getString("netlify_token", "") ?: ""
                        val siteId = prefs.getString("netlify_site_id", "") ?: ""
                        if (netlifyToken.isEmpty()) "Error: Netlify Token is not configured. Please open SETTINGS."
                        else AgenticTools.deployToNetlify(path, netlifyToken, siteId.ifEmpty { null })
                    }
                    toolCmd.startsWith("GITHUB_CREATE_REPO") -> {
                        val repoName = toolCmd.substringAfter("GITHUB_CREATE_REPO").trim()
                        val githubToken = prefs.getString("github_token", "") ?: ""
                        if (githubToken.isEmpty()) "Error: GitHub Token is not configured. Please open SETTINGS."
                        else AgenticTools.createGitHubRepo(repoName, githubToken)
                    }
                    toolCmd.startsWith("GITHUB_COMMIT") -> {
                        val p = toolCmd.substringAfter("GITHUB_COMMIT").trim().split("|")
                        val githubToken = prefs.getString("github_token", "") ?: ""
                        if (githubToken.isEmpty()) "Error: GitHub Token is not configured. Please open SETTINGS."
                        else if (p.size >= 3) {
                            AgenticTools.commitToGitHub(p[0], p[1], p[2], githubToken)
                        } else "Invalid Format. Use: GITHUB_COMMIT repoWithOwner|path|content"
                    }
                    toolCmd.startsWith("FIGMA_GET_FILE") -> {
                        val fileKey = toolCmd.substringAfter("FIGMA_GET_FILE").trim()
                        val figmaToken = prefs.getString("figma_token", "") ?: ""
                        if (figmaToken.isEmpty()) "Error: Figma Token is not configured. Please open SETTINGS."
                        else AgenticTools.getFigmaFile(fileKey, figmaToken)
                    }
                    toolCmd.startsWith("CALL_MCP") -> {
                        val p = toolCmd.substringAfter("CALL_MCP").trim().split("|")
                        if (p.size >= 3) {
                            AgenticTools.executeMcpRequest(p[0], p[1], p[2])
                        } else "Invalid Format. Use: CALL_MCP serverUrl|method|paramsJson"
                    }
                    toolCmd.startsWith("KERNEL_EVENT") -> {
                        val p = toolCmd.substringAfter("KERNEL_EVENT").trim().split(" ")
                        if (p.size >= 4) {
                            AgenticTools.executeKernelEvent(p[0], p[1].toInt(), p[2].toInt(), p[3].toInt())
                        } else "Invalid Format. Use: KERNEL_EVENT device type code value"
                    }
                    toolCmd.startsWith("KERNEL_TAP") -> {
                        val p = toolCmd.substringAfter("KERNEL_TAP").trim().split(" ")
                        if (p.size >= 3) {
                            AgenticTools.executeKernelTouch(p[0], p[1].toInt(), p[2].toInt())
                        } else "Invalid Format. Use: KERNEL_TAP device x y"
                    }
                    toolCmd.startsWith("RUN_LOCAL_INFERENCE") -> {
                        val p = toolCmd.substringAfter("RUN_LOCAL_INFERENCE").trim().split("|")
                        if (p.size >= 2) {
                            AgenticTools.runLocalInference(p[0], p[1])
                        } else "Invalid Format. Use: RUN_LOCAL_INFERENCE scriptPath|param"
                    }
                    toolCmd.startsWith("RUN_APP_INFERENCE") -> {
                        val path = toolCmd.substringAfter("RUN_APP_INFERENCE").trim()
                        TFLiteHelper.runInference(path)
                    }
                    toolCmd.startsWith("MEDIA_CONTROL") -> AgenticTools.mediaControl(toolCmd.substringAfter("MEDIA_CONTROL").trim())
                    toolCmd.startsWith("SET_VOLUME") -> {
                        val v = toolCmd.substringAfter("SET_VOLUME").trim().toIntOrNull() ?: 50
                        AgenticTools.setVolume(this, v)
                    }
                    toolCmd.startsWith("SET_BRIGHTNESS") -> {
                        val b = toolCmd.substringAfter("SET_BRIGHTNESS").trim().toIntOrNull() ?: 50
                        AgenticTools.setBrightness(b)
                    }
                    toolCmd.startsWith("TOGGLE_WIFI") -> {
                        val w = toolCmd.substringAfter("TOGGLE_WIFI").trim().lowercase()
                        AgenticTools.toggleWifi(w == "on" || w == "enable")
                    }
                    toolCmd.startsWith("TOGGLE_BLUETOOTH") -> {
                        val b = toolCmd.substringAfter("TOGGLE_BLUETOOTH").trim().lowercase()
                        AgenticTools.toggleBluetooth(b == "on" || b == "enable")
                    }
                    toolCmd.startsWith("SEND_MESSAGE") -> {
                        val p = toolCmd.substringAfter("SEND_MESSAGE").trim().split("|")
                        if (p.size >= 2) AgenticTools.sendMessage(this, p[0], p[1]) else "Invalid Format. Use: SEND_MESSAGE contact|message"
                    }
                    toolCmd.startsWith("REMEMBER") -> {
                        val fact = toolCmd.substringAfter("REMEMBER").trim()
                        val mCore = MemoryCore(this)
                        mCore.remember(fact)
                        "Memory Saved: $fact"
                    }
                    toolCmd.startsWith("LOCAL_OCR") -> AgenticTools.localOcr()
                    toolCmd.startsWith("BROWSER_EXTRACT") -> AgenticTools.browserExtract()
                    toolCmd.startsWith("BROWSER_CLICK") -> AgenticTools.browserClick(toolCmd.substringAfter("BROWSER_CLICK").trim())
                    toolCmd.startsWith("GENERATE_KOTLIN_PATCH") -> {
                        val p = toolCmd.substringAfter("GENERATE_KOTLIN_PATCH").trim().split("|")
                        if (p.size >= 2) AgenticTools.generateKotlinPatch(p[0], p[1]) else "Invalid Format"
                    }
                    toolCmd.startsWith("SEARCH_PACKAGE") -> AgenticTools.searchPackages(toolCmd.substringAfter("SEARCH_PACKAGE").trim())
                    toolCmd.startsWith("LAUNCH_APP") -> AgenticTools.launchPackage(toolCmd.substringAfter("LAUNCH_APP").trim())
                    toolCmd.startsWith("OPEN_APP") -> AgenticTools.findPackageAndOpen(this, toolCmd.substringAfter("OPEN_APP").trim())
                    toolCmd.startsWith("TAP") -> {
                        val p = toolCmd.split(" ").filter { it.toIntOrNull() != null }
                        if (p.size >= 2) { RootMotor.tap(p[0].toInt(), p[1].toInt()); "Tapped ${p[0]},${p[1]}" } else "Invalid TAP"
                    }
                    toolCmd.startsWith("LONG_TAP") -> {
                        val p = toolCmd.split(" ").filter { it.toIntOrNull() != null }
                        if (p.size >= 2) { RootMotor.longTap(p[0].toInt(), p[1].toInt()); "Long Tapped ${p[0]},${p[1]}" } else "Invalid LONG_TAP"
                    }
                    toolCmd.startsWith("SWIPE") -> {
                        val p = toolCmd.split(" ").filter { it.toIntOrNull() != null }.map { it.toInt() }
                        if (p.size >= 4) {
                            val d = if (p.size > 4) p[4] else 300
                            RootMotor.swipe(p[0], p[1], p[2], p[3], d); "Swiped"
                        } else "Invalid SWIPE"
                    }
                    toolCmd.startsWith("TYPE_TEXT") -> {
                        val t = toolCmd.substringAfter("TYPE_TEXT").trim()
                        RootMotor.typeText(t); "Typed: $t"
                    }
                    toolCmd.startsWith("KEY_EVENT") -> {
                        val c = toolCmd.substringAfter("KEY_EVENT").trim().toIntOrNull()
                        if (c != null) { RootMotor.keyEvent(c); "Key $c sent" } else "Invalid Key"
                    }
                    toolCmd.startsWith("READ_FILE") -> AgenticTools.readFile(toolCmd.substringAfter("READ_FILE").trim())
                    toolCmd.startsWith("WRITE_FILE") -> {
                        val p = toolCmd.substringAfter("WRITE_FILE").trim().split("|")
                        if (p.size >= 2) AgenticTools.writeFile(p[0], p[1]) else "Invalid Format"
                    }
                    toolCmd.startsWith("EXECUTE_CODE") -> {
                        val payload = toolCmd.substringAfter("EXECUTE_CODE").trim()
                        val parts = payload.split("|", limit = 2)
                        if (parts.size >= 2) {
                            AgenticTools.executeCode(parts[0], parts[1])
                        } else {
                            "Invalid Format. Use: EXECUTE_CODE language|code"
                        }
                    }
                    toolCmd.startsWith("EXECUTE_SHELL") -> AgenticTools.executeShell(toolCmd.substringAfter("EXECUTE_SHELL").trim())
                    toolCmd.startsWith("EXECUTE_ASTITVA_SHELL") -> AgenticTools.executeAstitvaShell(this, toolCmd.substringAfter("EXECUTE_ASTITVA_SHELL").trim())
                    toolCmd.startsWith("CHROME_LIST_TABS") -> AgenticTools.chromeListTabs()
                    toolCmd.startsWith("CHROME_GET_DOM") -> AgenticTools.chromeGetDom()
                    toolCmd.startsWith("CHROME_CLICK") -> {
                        val p = toolCmd.substringAfter("CHROME_CLICK").trim().split(" ")
                        if (p.size >= 2) AgenticTools.chromeClick(p[0].trim(), p[1].trim()) else "Error: CHROME_CLICK needs x y"
                    }
                    toolCmd.startsWith("CHROME_TYPE") -> {
                        val p = toolCmd.substringAfter("CHROME_TYPE").trim().split("|", limit = 2)
                        if (p.size >= 2) AgenticTools.chromeType(p[0].trim(), p[1].trim()) else "Error: CHROME_TYPE needs selector|text"
                    }
                    toolCmd.startsWith("CHROME_EVAL") -> AgenticTools.chromeEval(toolCmd.substringAfter("CHROME_EVAL").trim())
                    toolCmd.startsWith("KALI_EXEC") -> AgenticTools.executeKali(toolCmd.substringAfter("KALI_EXEC").trim())
                    toolCmd.startsWith("READ_SMS") -> AgenticTools.readLatestSms(this)
                    toolCmd.startsWith("GET_LOCATION") -> AgenticTools.getCurrentLocation(this)
                    toolCmd.startsWith("READ_NOTIFICATIONS") -> AgenticTools.readNotifications()
                    toolCmd.startsWith("DESCRIBE_SCENE") -> {
                        val c = toolCmd.substringAfter("DESCRIBE_SCENE").trim().toIntOrNull() ?: 0
                        val captureResult = AgenticTools.captureCameraImage(this, c)
                        if (captureResult.contains("success", ignoreCase = true) || captureResult.contains("saved", ignoreCase = true) || File(getExternalCacheDir(), "camera_vision.jpg").exists()) {
                            runLocalVlmDescription(true)
                        } else {
                            "Failed to capture camera image: $captureResult"
                        }
                    }
                    toolCmd.startsWith("ANALYZE_OBJECT") -> {
                        val captureResult = AgenticTools.captureCameraImage(this, 0)
                        if (captureResult.contains("success", ignoreCase = true) || captureResult.contains("saved", ignoreCase = true) || File(getExternalCacheDir(), "camera_vision.jpg").exists()) {
                            runLocalVlmDescription(true)
                        } else {
                            "Failed to capture camera image: $captureResult"
                        }
                    }
                    toolCmd.startsWith("NMAP") -> {
                        val p = toolCmd.substringAfter("NMAP").trim().split(" ")
                        if (p.size >= 1) AgenticTools.executeSecurityTool("nmap", p[0], if(p.size > 1) p.drop(1).joinToString(" ") else "-sV") else "Invalid NMAP"
                    }
                    toolCmd.startsWith("EXPLOIT") -> {
                        val p = toolCmd.substringAfter("EXPLOIT").trim().split(";")
                        if (p.size >= 2) AgenticTools.executeSecurityTool("metasploit", p[1], p[0]) else "Invalid EXPLOIT"
                    }
                    toolCmd.startsWith("INTERCEPT") -> AgenticTools.executeSecurityTool("mitmproxy", "", toolCmd.substringAfter("INTERCEPT").trim())
                    toolCmd.startsWith("FRIDA") -> AgenticTools.executeSecurityTool("frida", toolCmd.substringAfter("FRIDA").trim(), "")
                    toolCmd.startsWith("INSTALL_TOOLS") -> AgenticTools.installSecuritySuite()
                    toolCmd.startsWith("MAKE_CALL") -> AgenticTools.makeCall(this, toolCmd.substringAfter("MAKE_CALL").trim())
                    toolCmd.startsWith("END_CALL") -> AgenticTools.endCall()
                    toolCmd.startsWith("SYSTEM_VITALS") -> AgenticTools.getSystemVitals()
                    else -> "Unknown Tool"
                }
                
                val stepRecord = StringBuilder().apply {
                    append("Step $autonomousStepCount:\n")
                    if (!thought.isNullOrEmpty()) append("Thought: $thought\n")
                    if (!plan.isNullOrEmpty()) append("Plan: $plan\n")
                    append("Action: Executed $toolCmd\n")
                    append("Result: $result\n")
                }.toString()
                autonomousHistory.add(stepRecord)
                if (autonomousHistory.size > 8) {
                    autonomousHistory.removeAt(0)
                }

                addMessage("System", result, false, "result")
                isWaitingForUiUpdate = true
                scheduleNextStep(3000)
            } else { 
                isAutonomousMode = false 
                isApiCalling = false
                addMessage("System", "Error: No tool call found in response.", false, "text")
                runOnUiThread {
                    overlayService?.updateDialog("Error parsing task output.", "error")
                    sendButton?.visibility = View.VISIBLE
                    stopButton?.visibility = View.GONE
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    currentVoiceState = VoiceState.WAKE_WORD
                    startListening()
                }, 3000)
            }
        } catch (e: Exception) { 
            isAutonomousMode = false 
            isApiCalling = false
            addMessage("System", "Error parsing response: ${e.message}", false, "text")
            runOnUiThread {
                overlayService?.updateDialog("Execution Error", "error")
                sendButton?.visibility = View.VISIBLE
                stopButton?.visibility = View.GONE
            }
            Handler(Looper.getMainLooper()).postDelayed({
                currentVoiceState = VoiceState.WAKE_WORD
                startListening()
            }, 3000)
        }
    }

    private fun renderTasks() {
        val root = ScrollView(this).apply { setPadding(60, 100, 60, 60); isVerticalScrollBarEnabled = false }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(TextView(this).apply {
            text = "Mission Control"
            textSize = 30f; setTextColor(Color.WHITE); setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        })
        layout.addView(TextView(this).apply {
            text = "System Action Monitor"
            textSize = 15f; setTextColor(Color.parseColor("#8E8CA8")); setPadding(0, 10, 0, 60)
        })

        if (isAutonomousMode && currentTask != null) {
            val card = createGlassCard()
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 60, 60, 60) }
            
            inner.addView(TextView(this@MainActivity).apply {
                text = "ACTIVE GOAL"
                textSize = 10f; setTextColor(Color.parseColor("#00F0FF")); setTypeface(null, Typeface.BOLD)
            })
            inner.addView(TextView(this@MainActivity).apply {
                text = currentTask; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(0, 10, 0, 30)
            })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(createStatItem("Status", "RUNNING", 1f))
            row.addView(createStatItem("Steps", "$autonomousStepCount", 1f))
            row.addView(createStatItem("Uplink", brainManager.getActiveModel() ?: "None", 1f))
            inner.addView(row)
            
            card.addView(inner)
            layout.addView(card)
        } else {
            val card = createGlassCard()
            val inner = LinearLayout(this).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(80, 80, 80, 80)
                gravity = Gravity.CENTER
            }
            inner.addView(TextView(this@MainActivity).apply {
                text = "SYSTEM STANDBY"
                textSize = 15f; setTextColor(Color.GRAY); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            })
            inner.addView(TextView(this@MainActivity).apply {
                text = "No active automation tasks are running."
                textSize = 13f; setTextColor(Color.parseColor("#8899A6")); setPadding(0, 10, 0, 0); gravity = Gravity.CENTER
            })
            card.addView(inner)
            layout.addView(card)
        }

        root.addView(layout)
        contentArea.addView(root)
    }

    private fun renderMemory() {
        val root = ScrollView(this).apply { setPadding(60, 100, 60, 60); isVerticalScrollBarEnabled = false }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        layout.addView(TextView(this).apply {
            text = "Memory Vault"
            textSize = 30f; setTextColor(Color.WHITE); setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        })
        layout.addView(TextView(this).apply {
            text = "Semantic Cognitive DB"
            textSize = 15f; setTextColor(Color.parseColor("#8E8CA8")); setPadding(0, 10, 0, 60)
        })

        val memoryCore = MemoryCore(this)
        val memoriesStr = memoryCore.getAllMemories(20)
        
        if (memoriesStr.contains("No explicit memories")) {
            val card = createGlassCard()
            val inner = LinearLayout(this).apply { 
                orientation = LinearLayout.VERTICAL
                setPadding(80, 80, 80, 80)
                gravity = Gravity.CENTER
            }
            inner.addView(TextView(this@MainActivity).apply {
                text = "VAULT EMPTY"
                textSize = 15f; setTextColor(Color.GRAY); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            })
            card.addView(inner)
            layout.addView(card)
        } else {
            val lines = memoriesStr.split("\n").filter { it.isNotBlank() }
            lines.forEach { line ->
                val card = createGlassCard().apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 30 }
                }
                val inner = LinearLayout(this).apply { 
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 40, 50, 40)
                }
                inner.addView(TextView(this@MainActivity).apply {
                    text = line.substringAfter(". ").trim()
                    textSize = 15f; setTextColor(Color.WHITE)
                })
                card.addView(inner)
                layout.addView(card)
            }
        }

        root.addView(layout)
        contentArea.addView(root)
    }
    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_SMS, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.POST_NOTIFICATIONS")
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(perms.toTypedArray(), ALL_PERMS_CODE)
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }
    @Synchronized
    private fun ensureLocalLlamaServerRunning(modelName: String, onReady: (Boolean) -> Unit) {
        if (localLlamaProcess != null && runningLocalModel == modelName) {
            try {
                localLlamaProcess?.exitValue()
                localLlamaProcess = null
                runningLocalModel = null
            } catch (e: IllegalThreadStateException) {
                onReady(true)
                return
            }
        }

        if (localLlamaProcess != null) {
            stopLocalLlamaServer()
        }

        thread {
            try {
                runOnUiThread {
                    addMessage("System", "Initializing Local VLM: $modelName. Offloading to NPU/GPU...", false, "thought")
                    overlayService?.updateDialog("Booting VLM...", "thinking")
                }

                val projectorName = when {
                    modelName.contains("Qwen2-VL", ignoreCase = true) -> "mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf"
                    modelName.contains("moondream", ignoreCase = true) -> "moondream2-mmproj-f16.gguf"
                    else -> ""
                }

                val nativeLibDir = applicationInfo.nativeLibraryDir
                val pbArgs = mutableListOf(
                    "$nativeLibDir/libllama_server.so",
                    "-m", "/sdcard/AstitvaModels/$modelName",
                    "-c", "2048",
                    "--port", "8080",
                    "--host", "127.0.0.1",
                    "-t", "4",
                    "-ngl", "99"
                )

                if (projectorName.isNotEmpty()) {
                    pbArgs.add("--mmproj")
                    pbArgs.add("/sdcard/AstitvaModels/$projectorName")
                }

                val pb = ProcessBuilder(pbArgs)
                pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
                pb.redirectErrorStream(true)

                val process = pb.start()
                localLlamaProcess = process
                runningLocalModel = modelName

                thread {
                    try {
                        val reader = process.inputStream.bufferedReader()
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            android.util.Log.d("AstitvaLlama", line ?: "")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var isUp = false
                for (i in 0 until 30) {
                    if (localLlamaProcess == null) break
                    try {
                        val conn = URL("http://localhost:8080/health").openConnection() as HttpURLConnection
                        conn.connectTimeout = 1000
                        conn.readTimeout = 1000
                        if (conn.responseCode == 200) {
                            isUp = true
                            break
                        }
                    } catch (e: Exception) {
                        // Retry
                    }
                    Thread.sleep(1000)
                }

                if (isUp) {
                    runOnUiThread {
                        addMessage("System", "Local VLM active. Offline link established.", false, "thought")
                    }
                    onReady(true)
                } else {
                    stopLocalLlamaServer()
                    runOnUiThread {
                        addMessage("System", "Failed to initialize Local VLM server. Check if models exist.", false, "text")
                    }
                    onReady(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopLocalLlamaServer()
                runOnUiThread {
                    addMessage("System", "Local VLM error: ${e.message}", false, "text")
                }
                onReady(false)
            }
        }
    }

    @Synchronized
    private fun stopLocalLlamaServer() {
        try {
            localLlamaProcess?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        localLlamaProcess = null
        runningLocalModel = null
    }

    private fun runLocalVlmDescription(isCamera: Boolean): String {
        val cacheDir = getExternalCacheDir() ?: return "Failed to access cache directory."
        val fileName = if (isCamera) "camera_vision.jpg" else "astitva_live_buffer.jpg"
        val file = File(cacheDir, fileName)
        if (!file.exists()) {
            return "No image captured yet for description."
        }
        return TFLiteHelper.runInference(file.absolutePath)
    }

    inner class FaceOverlayView(context: Context) : View(context) {
        val detectedFaces = mutableListOf<android.graphics.RectF>()
        private val paint = android.graphics.Paint().apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val textPaint = android.graphics.Paint().apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            for (rect in detectedFaces) {
                canvas.drawRoundRect(rect, 15f, 15f, paint)
                canvas.drawText("[FACE]", rect.left + 10, rect.top - 15, textPaint)
            }
        }
    }

    private fun adjustTextureViewSize(viewWidth: Int, viewHeight: Int, previewWidth: Int, previewHeight: Int) {
        val textureView = irlTextureView ?: return
        val matrix = Matrix()
        
        val prevW = previewHeight.toFloat()
        val prevH = previewWidth.toFloat()
        
        val scaleX: Float
        val scaleY: Float
        
        if (viewWidth * prevH > viewHeight * prevW) {
            scaleX = 1f
            scaleY = (viewWidth * prevH) / (viewHeight * prevW)
        } else {
            scaleX = (viewHeight * prevW) / (viewWidth * prevH)
            scaleY = 1f
        }
        
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        matrix.setScale(scaleX, scaleY, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun startVision() { startActivityForResult(mpManager.createScreenCaptureIntent(), SCREEN_CAST_CODE) }
    private fun setupSpeechRecognizer() {
        runOnUiThread {
            try {
                if (speechRecognizer != null) {
                    try {
                        speechRecognizer?.destroy()
                    } catch (e: Exception) {}
                }
                if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                    android.util.Log.e("AstitvaSpeech", "Speech recognition not available on this device!")
                }
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(p0: Bundle?) {
                            if (currentVoiceState == VoiceState.COMMAND) {
                                aiOrbWebView?.loadUrl("javascript:setState('listening')")
                                overlayService?.updateDialog("Listening...", "listening")
                            } else {
                                aiOrbWebView?.loadUrl("javascript:setState('idle')")
                            }
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(p0: Float) {}
                        override fun onBufferReceived(p0: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            val errorMsg = when (error) {
                                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech matched"
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                                SpeechRecognizer.ERROR_SERVER -> "Server error"
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                                else -> "Error code: $error"
                            }
                            android.util.Log.e("AstitvaSpeech", "SpeechRecognizer error: $errorMsg ($error)")
                            runOnUiThread {
                                if (isIrlVisionActive) {
                                    irlDialogueTextView?.text = "[System Error: $errorMsg]"
                                } else {
                                    currentVoiceState = VoiceState.WAKE_WORD
                                    aiOrbWebView?.loadUrl("javascript:setState('idle')")
                                    overlayService?.updateDialog("Error: $errorMsg", "idle")
                                }
                            }
                        }
                        override fun onResults(p0: Bundle?) {
                            val matches = p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: ""
                            
                            if (isIrlVisionActive) {
                                runOnUiThread {
                                    irlDialogueTextView?.text = "You: $text"
                                }
                                if (text.isNotBlank()) {
                                    processIrlVisionQuery(text)
                                }
                            } else {
                                runOnUiThread {
                                    currentVoiceState = VoiceState.WAKE_WORD
                                    aiOrbWebView?.loadUrl("javascript:setState('idle')")
                                    overlayService?.updateDialog("", "idle")
                                }
                                if (text.isNotBlank()) {
                                    handleCommand(text)
                                }
                            }
                        }
                        override fun onPartialResults(p0: Bundle?) {}
                        override fun onEvent(p0: Int, p1: Bundle?) {}
                    })
                }
            } catch (e: Exception) {
                android.util.Log.e("AstitvaSpeech", "Failed to initialize SpeechRecognizer: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    fun triggerManualWake() {
        if (isAutonomousMode || isApiCalling) return
        
        currentVoiceState = VoiceState.COMMAND
        runOnUiThread {
            aiOrbWebView?.loadUrl("javascript:setState('listening')")
            overlayService?.updateDialog("Listening...", "listening")
        }
        
        runOnUiThread {
            if (speechRecognizer == null) {
                setupSpeechRecognizer()
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
                android.util.Log.e("AstitvaSpeech", "Error startListening: ${e.message}")
            }
        }
    }

    private fun startListening() {
        triggerManualWake()
    }
    private fun speak(text: String) {
        if (tts == null || text.isBlank()) return
        val sanitizedText = text.replace("\\n", " ").replace("\n", " ").trim()
        if (sanitizedText.isEmpty()) return
        
        // Emotion Detection and Voice Modulation
        var currentPitch = 1.0f
        var currentRate = 0.95f
        
        val modulationText = when {
            sanitizedText.contains("[JOY]") || sanitizedText.contains("[EXCITED]") -> {
                currentPitch = 1.2f; currentRate = 1.05f
                sanitizedText.replace("[JOY]", "").replace("[EXCITED]", "")
            }
            sanitizedText.contains("[SAD]") || sanitizedText.contains("[THINKING]") -> {
                currentPitch = 0.85f; currentRate = 0.8f
                sanitizedText.replace("[SAD]", "").replace("[THINKING]", "")
            }
            sanitizedText.contains("[SERIOUS]") -> {
                currentPitch = 0.95f; currentRate = 0.9f
                sanitizedText.replace("[SERIOUS]", "")
            }
            else -> sanitizedText
        }

        val cleanText = modulationText.replace(Regex("<[^>]*>"), "").trim()
        if (cleanText.isEmpty()) return

        tts?.setPitch(currentPitch)
        tts?.setSpeechRate(currentRate)

        val sentences = cleanText.split(Regex("(?<=[.!?])|(?<=,)")).filter { it.isNotBlank() }
        var isFirst = true
        sentences.forEach { sentence ->
            val mode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(sentence.trim(), mode, null, "AstitvaTTSPart")
            
            // Human-like pauses
            val pauseDuration = when {
                sentence.endsWith(",") || sentence.endsWith(";") -> 350L
                sentence.endsWith("...") -> 800L
                else -> 500L
            }
            tts?.playSilentUtterance(pauseDuration, TextToSpeech.QUEUE_ADD, "AstitvaPause")
            isFirst = false
        }
    }
    override fun onMessageReceived(topic: String, message: String, sender: String) { runOnUiThread { if (::feedLayout.isInitialized) addMessage(sender, message, false) } }
    override fun onUiUpdate() {
        if (isAutonomousMode && isWaitingForUiUpdate) {
            isWaitingForUiUpdate = false
            cancelPendingStep()
            startAutonomousStep()
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SCREEN_CAST_CODE && resultCode == RESULT_OK && data != null) {
            startService(Intent(this, VisionService::class.java).apply { action = VisionService.ACTION_START_VISION; putExtra(VisionService.EXTRA_RESULT_CODE, resultCode); putExtra(VisionService.EXTRA_RESULT_DATA, data) })
        } else if (requestCode == FILE_PICKER_CODE && resultCode == RESULT_OK && data != null) {
            data.data?.let { handleAttachedFile(it) }
        }
    }

    private fun showSessionsDialog() {
        val mCore = MemoryCore(this)
        val sessions = mCore.getAllSessions()
        val sessionTitles = sessions.map { it.second }.toMutableList()
        
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Chat History Sessions")
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }
        
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 800)
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, sessionTitles)
            setOnItemClickListener { _, _, position, _ ->
                val selectedSession = sessions[position]
                switchSession(selectedSession.first)
                Toast.makeText(this@MainActivity, "Switched to: ${selectedSession.second}", Toast.LENGTH_SHORT).show()
            }
        }
        
        val newSessionBtn = Button(this@MainActivity).apply {
            text = "+ NEW CHAT"
            setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.button_gradient)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 }
            setOnClickListener {
                val input = EditText(this@MainActivity).apply { hint = "Session Name" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("New Session")
                    .setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                             val newId = UUID.randomUUID().toString()
                             mCore.createSession(newId, name)
                             switchSession(newId)
                             Toast.makeText(this@MainActivity, "Created session: $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        
        val actionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        
        val renameBtn = Button(this).apply {
            text = "RENAME"
            textSize = 12f
            setOnClickListener {
                val input = EditText(this@MainActivity).apply { hint = "New Name" }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Rename Current Session")
                    .setView(input)
                    .setPositiveButton("Rename") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                             mCore.renameSession(currentSessionId, name)
                             Toast.makeText(this@MainActivity, "Renamed session to: $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        
        val deleteBtn = Button(this).apply {
            text = "DELETE"
            textSize = 12f
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Delete Current Session")
                    .setMessage("Are you sure you want to delete this session?")
                    .setPositiveButton("Delete") { _, _ ->
                         mCore.deleteSession(currentSessionId)
                         val remain = mCore.getAllSessions()
                         if (remain.isEmpty()) {
                             val newId = "default_session"
                             mCore.createSession(newId, "Welcome Session")
                             switchSession(newId)
                         } else {
                             switchSession(remain[0].first)
                         }
                         Toast.makeText(this@MainActivity, "Session Deleted.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        
        actionLayout.addView(renameBtn)
        actionLayout.addView(deleteBtn)
        
        container.addView(newSessionBtn)
        container.addView(listView)
        container.addView(actionLayout)
        
        builder.setView(container)
        builder.setNegativeButton("Close", null)
        builder.show()
    }

    private fun switchSession(sessionId: String) {
        currentSessionId = sessionId
        synchronized(chatMessages) {
            chatMessages.clear()
            val mCore = MemoryCore(this)
            val savedMsgs = mCore.getSessionMessages(currentSessionId)
            savedMsgs.forEach {
                chatMessages.add(ChatMessage(it.sender, it.msg, it.isUser, it.type))
            }
        }
        runOnUiThread {
            if (::feedLayout.isInitialized) {
                feedLayout.removeAllViews()
                synchronized(chatMessages) {
                    chatMessages.forEach { chatMsg ->
                        renderSingleMessage(chatMsg.sender, chatMsg.msg, chatMsg.isUser, chatMsg.type)
                    }
                }
            }
        }
    }

    private fun selectFileAttachment() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Choose Image or File"), FILE_PICKER_CODE)
    }

    private fun handleAttachedFile(uri: Uri) {
        attachedFileUri = uri
        val mimeType = contentResolver.getType(uri) ?: ""
        attachedImageMime = mimeType
        if (mimeType.startsWith("image/")) {
            attachedFileType = "image"
            try {
                contentResolver.openInputStream(uri).use { inputStream ->
                    val bytes = inputStream?.readBytes()
                    if (bytes != null) {
                        attachedImageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            attachedFileType = "text"
            try {
                contentResolver.openInputStream(uri).use { inputStream ->
                    attachedFileContent = inputStream?.bufferedReader()?.use { it.readText() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        var name = "Attached File"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        attachedFileName = name
        runOnUiThread { updateAttachmentPreview() }
    }

    private fun updateAttachmentPreview() {
        val card = previewCardView ?: return
        val label = previewLabel ?: return
        val thumbnail = previewThumbnail ?: return
        
        if (attachedFileUri != null) {
            card.visibility = View.VISIBLE
            label.text = attachedFileName
            if (attachedFileType == "image" && attachedImageBase64 != null) {
                thumbnail.visibility = View.VISIBLE
                try {
                    contentResolver.openInputStream(attachedFileUri!!).use { inputStream ->
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        thumbnail.setImageBitmap(bitmap)
                    }
                } catch (e: Exception) {
                    thumbnail.visibility = View.GONE
                }
            } else {
                thumbnail.visibility = View.GONE
            }
        } else {
            card.visibility = View.GONE
        }
    }

    private fun showPluginsConfigDialog() {
        val b = AlertDialog.Builder(this)
        val l = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(70, 50, 70, 50)
        }
        
        val vercelIn = EditText(this).apply { 
            hint = "Vercel Access Token"
            setText(prefs.getString("vercel_token", ""))
            textSize = 14f
        }
        val netlifyIn = EditText(this).apply { 
            hint = "Netlify Access Token"
            setText(prefs.getString("netlify_token", ""))
            textSize = 14f
        }
        val netlifySiteIdIn = EditText(this).apply { 
            hint = "Netlify Site ID (Optional)"
            setText(prefs.getString("netlify_site_id", ""))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 }
        }
        val githubIn = EditText(this).apply { 
            hint = "GitHub Access Token"
            setText(prefs.getString("github_token", ""))
            textSize = 14f
        }
        val figmaIn = EditText(this).apply { 
            hint = "Figma Access Token"
            setText(prefs.getString("figma_token", ""))
            textSize = 14f
        }

        val vercelSection = createProviderConfigSection("Vercel", "vercel", this, vercelIn)
        val netlifySection = createProviderConfigSection("Netlify", "netlify", this, netlifyIn)
        netlifySection.addView(netlifySiteIdIn, 3) 
        
        val githubSection = createProviderConfigSection("GitHub", "github", this, githubIn)
        val figmaSection = createProviderConfigSection("Figma", "figma", this, figmaIn)
        
        l.addView(vercelSection)
        l.addView(View(this).apply { 
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { topMargin = 20; bottomMargin = 20 }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })
        l.addView(netlifySection)
        l.addView(View(this).apply { 
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { topMargin = 20; bottomMargin = 20 }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })
        l.addView(githubSection)
        l.addView(View(this).apply { 
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { topMargin = 20; bottomMargin = 20 }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        })
        l.addView(figmaSection)
        
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        scroll.addView(l)
        b.setTitle("Plugins & MCP").setView(scroll)
        
        b.setPositiveButton("SAVE") { _, _ ->
            prefs.edit().apply {
                putString("vercel_token", vercelIn.text.toString().trim())
                putString("netlify_token", netlifyIn.text.toString().trim())
                putString("netlify_site_id", netlifySiteIdIn.text.toString().trim())
                putString("github_token", githubIn.text.toString().trim())
                putString("figma_token", figmaIn.text.toString().trim())
                apply()
            }
            Toast.makeText(this, "Plugin configurations saved.", Toast.LENGTH_SHORT).show()
        }
        b.setNegativeButton("CANCEL", null)
        b.show()
    }

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "astitva" && data.host == "oauth") {
            val code = data.getQueryParameter("code")
            val provider = data.getQueryParameter("state")
            if (code != null && provider != null) {
                exchangeCodeForToken(provider, code)
            }
        }
    }

    private fun startOAuthFlow(provider: String) {
        val clientId = prefs.getString("${provider}_client_id", "") ?: ""
        if (clientId.isEmpty()) {
            Toast.makeText(this, "Please enter your Client ID first.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val authUrl = when (provider) {
            "github" -> "https://github.com/login/oauth/authorize?client_id=$clientId&redirect_uri=astitva://oauth&state=github&scope=repo,user"
            "vercel" -> "https://vercel.com/oauth/authorize?client_id=$clientId&redirect_uri=astitva://oauth&state=vercel"
            "netlify" -> "https://app.netlify.com/authorize?client_id=$clientId&response_type=code&redirect_uri=astitva://oauth&state=netlify"
            "figma" -> "https://www.figma.com/oauth?client_id=$clientId&redirect_uri=astitva://oauth&scope=file_read&state=figma&response_type=code"
            else -> return
        }
        
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
        startActivity(intent)
    }

    private fun exchangeCodeForToken(provider: String, code: String) {
        val clientId = prefs.getString("${provider}_client_id", "") ?: ""
        val clientSecret = prefs.getString("${provider}_client_secret", "") ?: ""
        
        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            runOnUiThread {
                Toast.makeText(this, "OAuth Error: Client ID or Secret is missing in config.", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        thread {
            try {
                val tokenUrl = when (provider) {
                    "github" -> "https://github.com/login/oauth/access_token"
                    "vercel" -> "https://api.vercel.com/v2/oauth/access_token"
                    "netlify" -> "https://api.netlify.com/oauth/token"
                    "figma" -> "https://www.figma.com/api/oauth/token"
                    else -> return@thread
                }
                
                val conn = URL(tokenUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                
                val params = when (provider) {
                    "github" -> "client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=astitva://oauth"
                    "vercel" -> "client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=astitva://oauth"
                    "netlify" -> "client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=astitva://oauth&grant_type=authorization_code"
                    "figma" -> "client_id=$clientId&client_secret=$clientSecret&code=$code&redirect_uri=astitva://oauth&grant_type=authorization_code"
                    else -> ""
                }
                
                OutputStreamWriter(conn.outputStream).use { it.write(params) }
                
                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(resp)
                    val token = json.optString("access_token", "")
                    if (token.isNotEmpty()) {
                        prefs.edit().putString("${provider}_token", token).apply()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "${provider.uppercase()} OAuth Connected Successfully!", Toast.LENGTH_LONG).show()
                            addMessage("System", "${provider.uppercase()} OAuth Link Connected. Token saved.", false, "thought")
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "OAuth Exchange Failed: Token not found in response.", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val errMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "OAuth Exchange Failed ($responseCode): $errMsg", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "OAuth Exchange Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun createProviderConfigSection(providerName: String, prefKeyPrefix: String, context: Context, tokenField: EditText): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 20, 0, 20)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }
        }
        
        root.addView(TextView(context).apply {
            text = "$providerName Integration"
            textSize = 14f
            setTextColor(Color.parseColor("#FF85A2"))
            setTypeface(null, Typeface.BOLD)
        })
        
        root.addView(TextView(context).apply {
            text = "Option 1: Manual Access Token"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 10, 0, 5)
        })
        
        root.addView(tokenField)
        
        root.addView(TextView(context).apply {
            text = "Option 2: Sign In via Browser (OAuth)"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 15, 0, 5)
        })
        
        val credentialsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val clientIdIn = EditText(context).apply {
            hint = "Client ID"
            setText(prefs.getString("${prefKeyPrefix}_client_id", ""))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        
        val clientSecretIn = EditText(context).apply {
            hint = "Client Secret"
            setText(prefs.getString("${prefKeyPrefix}_client_secret", ""))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = 10 }
        }
        
        val connectBtn = Button(context).apply {
            text = "CONNECT"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 10 }
            setOnClickListener {
                prefs.edit().apply {
                    putString("${prefKeyPrefix}_client_id", clientIdIn.text.toString().trim())
                    putString("${prefKeyPrefix}_client_secret", clientSecretIn.text.toString().trim())
                    apply()
                }
                startOAuthFlow(prefKeyPrefix)
            }
        }
        
        credentialsLayout.addView(clientIdIn)
        credentialsLayout.addView(clientSecretIn)
        credentialsLayout.addView(connectBtn)
        root.addView(credentialsLayout)
        
        return root
    }
    override fun onDestroy() {
        if (isOverlayBound) {
            try { unbindService(overlayConnection) } catch (e: Exception) {}
            isOverlayBound = false
        }
        try {
            sensorManager?.unregisterListener(sensorListener)
        } catch (e: Exception) {}
        AstitvaAccessibility.setListener(null)
        AgentBroker.unsubscribe(this)
        tts?.shutdown()
        speechRecognizer?.destroy()
        stopLocalLlamaServer()
        super.onDestroy()
    }

    private fun launchIrlVision() {
        isIrlVisionActive = true
        contentArea.removeAllViews()
        
        // Hide standard bottom navbar for fullscreen immersion
        navBar.visibility = View.GONE
        
        val frame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.BLACK)
        }
        
        // 1. TextureView for Camera Preview
        val textureView = android.view.TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        irlTextureView = textureView
        frame.addView(textureView)

        // Transparent Overlay Layer for drawing face boxes
        val faceOverlay = FaceOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        irlFaceOverlay = faceOverlay
        frame.addView(faceOverlay)
        
        // 2. Translucent HUD Overlays
        val hudLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { 
                gravity = Gravity.TOP
                setMargins(40, 60, 40, 0)
            }
            setPadding(45, 45, 45, 45)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#90000000")) // Semi-transparent dark
                cornerRadius = 40f
            }
        }
        
        val titleText = TextView(this).apply {
            text = "👁️ ASTITVA IRL VISION HUD"
            textSize = 14f
            setTextColor(Color.parseColor("#FF85A2"))
            setTypeface(null, Typeface.BOLD)
        }
        hudLayout.addView(titleText)
        
        val statusText = TextView(this).apply {
            text = "Status: Scanning Reality..."
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 0)
        }
        hudLayout.addView(statusText)
        
        val detectionText = TextView(this).apply {
            text = "Faces: 0  |  Dominant Colors: Calibrating...  |  Motion: None"
            textSize = 11f
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 10, 0, 0)
        }
        hudLayout.addView(detectionText)
        frame.addView(hudLayout)
        
        // 3. Subtitle / Dialogue box at bottom
        val dialogueBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = 250 // Leave room for control buttons
                leftMargin = 40
                rightMargin = 40
            }
            setPadding(50, 40, 50, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B0000000"))
                cornerRadius = 40f
                setStroke(2, Color.parseColor("#40FFFFFF"))
            }
        }
        val dialogLabel = TextView(this).apply {
            text = "Astitva (IRL Mode):"
            textSize = 11f
            setTextColor(Color.parseColor("#FF85A2"))
            setTypeface(null, Typeface.BOLD)
        }
        val dialogueText = TextView(this).apply {
            text = "[JOY] Vicky Bhai! I am looking through your camera. Ask me anything, or say 'Scan' to analyze the full context."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 0)
        }
        dialogueBox.addView(dialogLabel)
        dialogueBox.addView(dialogueText)
        frame.addView(dialogueBox)
        
        // 4. Control buttons at the absolute bottom
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = 50
            }
        }
        
        // Switch Camera Button
        val switchCam = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#60FFFFFF"))
                cornerRadius = 80f
            }
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { rightMargin = 40 }
            setOnClickListener {
                toggleIrlCamera()
            }
        }
        
        // Mic Toggle Button
        val micBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = getDrawable(R.drawable.button_gradient)
            layoutParams = LinearLayout.LayoutParams(170, 170)
            setOnClickListener {
                startListeningIrlVision(dialogueText)
            }
        }
        
        // Close Button
        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#80E53935")) // Red tinted translucent
                cornerRadius = 80f
            }
            layoutParams = LinearLayout.LayoutParams(140, 140).apply { leftMargin = 40 }
            setOnClickListener {
                closeIrlVision()
            }
        }
        
        controlsLayout.addView(switchCam)
        controlsLayout.addView(micBtn)
        controlsLayout.addView(closeBtn)
        frame.addView(controlsLayout)
        
        contentArea.addView(frame)
        
        // 5. Initialize Camera Listener
        textureView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                openIrlCamera(surface)
            }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                irlCamera?.parameters?.let { params ->
                    val size = params.previewSize
                    adjustTextureViewSize(width, height, size.width, size.height)
                }
            }
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                releaseIrlCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }
        
        // Start background analytical loop
        startIrlAnalysis(detectionText, statusText)
    }

    private fun openIrlCamera(surface: android.graphics.SurfaceTexture) {
        try {
            releaseIrlCamera()
            val numCam = android.hardware.Camera.getNumberOfCameras()
            if (numCam == 0) return
            
            var targetId = -1
            val info = android.hardware.Camera.CameraInfo()
            for (i in 0 until numCam) {
                android.hardware.Camera.getCameraInfo(i, info)
                if (irlCameraId == 1 && info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    targetId = i
                    break
                }
                if (irlCameraId == 0 && info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK) {
                    targetId = i
                    break
                }
            }
            if (targetId == -1) targetId = 0
            
            val camera = android.hardware.Camera.open(targetId)
            irlCamera = camera
            
            // Set rotation
            android.hardware.Camera.getCameraInfo(targetId, info)
            val displayRotation = when (windowManager.defaultDisplay.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            var resultRotation = if (info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT) {
                val rot = (info.orientation + displayRotation) % 360
                (360 - rot) % 360  // compensate mirror
            } else {
                (info.orientation - displayRotation + 360) % 360
            }
            camera.setDisplayOrientation(resultRotation)
            
            // Configure parameters
            val params = camera.parameters
            val sizes = params.supportedPreviewSizes
            val targetSize = sizes.find { it.width <= 1280 } ?: sizes[0]
            params.setPreviewSize(targetSize.width, targetSize.height)
            
            val focusModes = params.supportedFocusModes
            if (focusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            
            camera.parameters = params
            camera.setPreviewTexture(surface)
            camera.startPreview()
            
            val viewWidth = irlTextureView?.width ?: 0
            val viewHeight = irlTextureView?.height ?: 0
            if (viewWidth > 0 && viewHeight > 0) {
                adjustTextureViewSize(viewWidth, viewHeight, targetSize.width, targetSize.height)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleIrlCamera() {
        irlCameraId = if (irlCameraId == 0) 1 else 0
        irlTextureView?.surfaceTexture?.let { openIrlCamera(it) }
    }

    private fun releaseIrlCamera() {
        try {
            irlCamera?.stopPreview()
            irlCamera?.release()
            irlCamera = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closeIrlVision() {
        isIrlVisionActive = false
        stopIrlAnalysis()
        releaseIrlCamera()
        navBar.visibility = View.VISIBLE
        switchTab(0) // return to home
    }

    private fun startListeningIrlVision(dialogueText: TextView) {
        irlDialogueTextView = dialogueText
        dialogueText.text = "Listening..."
        
        runOnUiThread {
            if (speechRecognizer == null) {
                setupSpeechRecognizer()
            }
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                dialogueText.text = "Mic error: ${e.message}"
            }
        }
    }

    private fun startIrlAnalysis(detectionText: TextView, statusText: TextView) {
        stopIrlAnalysis()
        irlAnalysisRunnable = object : Runnable {
            override fun run() {
                if (!isIrlVisionActive || irlTextureView == null) return
                
                try {
                    val bmp = irlTextureView!!.getBitmap(320, 240) // Small bitmap for fast processing
                    if (bmp != null) {
                        // 1. Detect faces locally & offline
                        val rgb565Bmp = bmp.copy(Bitmap.Config.RGB_565, false)
                        val maxFaces = 3
                        val detector = android.media.FaceDetector(rgb565Bmp.width, rgb565Bmp.height, maxFaces)
                        val faces = arrayOfNulls<android.media.FaceDetector.Face>(maxFaces)
                        val faceCount = detector.findFaces(rgb565Bmp, faces)
                        
                        val faceRects = mutableListOf<android.graphics.RectF>()
                        val viewW = irlTextureView?.width ?: 0
                        val viewH = irlTextureView?.height ?: 0
                        
                        if (faceCount > 0 && viewW > 0 && viewH > 0) {
                            val midPoint = android.graphics.PointF()
                            for (i in 0 until faceCount) {
                                val face = faces[i]
                                if (face != null) {
                                    face.getMidPoint(midPoint)
                                    val eyesDist = face.eyesDistance()
                                    
                                    val scaleX = viewW.toFloat() / 320f
                                    val scaleY = viewH.toFloat() / 240f
                                    
                                    val cx = midPoint.x * scaleX
                                    val cy = midPoint.y * scaleY
                                    
                                    // Scale to cover ears (3.2x eyes distance) and full head (4.0x eyes distance)
                                    val w = eyesDist * scaleX * 3.2f
                                    val h = eyesDist * scaleY * 4.0f
                                    
                                    // Shift center down by 0.45x eyes distance to align with face center instead of eyes
                                    val cyShifted = cy + (eyesDist * scaleY * 0.45f)
                                    
                                    val rect = android.graphics.RectF(
                                        cx - w / 2f,
                                        cyShifted - h / 2f,
                                        cx + w / 2f,
                                        cyShifted + h / 2f
                                    )
                                    faceRects.add(rect)
                                }
                            }
                        }
                        rgb565Bmp.recycle()
                        
                        // 2. Detect dominant colors offline
                        val colors = analyzeDominantColors(bmp)
                        
                        // 3. Motion Diff Detection
                        var motionStr = "None"
                        if (lastAnalyzedBitmap != null) {
                            val diff = calculateBitmapDiff(bmp, lastAnalyzedBitmap!!)
                            if (diff > 12) {
                                motionStr = "DETECTED"
                            }
                        }
                        
                        lastAnalyzedBitmap?.recycle()
                        lastAnalyzedBitmap = bmp
                        
                        // Update HUD UI
                        runOnUiThread {
                            detectionText.text = "Faces: $faceCount  |  Colors: $colors  |  Motion: $motionStr"
                            (irlFaceOverlay as? FaceOverlayView)?.let { overlay ->
                                overlay.detectedFaces.clear()
                                overlay.detectedFaces.addAll(faceRects)
                                overlay.invalidate()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // Repeat every 1.5 seconds
                irlAnalysisHandler.postDelayed(this, 1500)
            }
        }
        irlAnalysisHandler.post(irlAnalysisRunnable!!)
    }

    private fun stopIrlAnalysis() {
        irlAnalysisRunnable?.let { irlAnalysisHandler.removeCallbacks(it) }
        irlAnalysisRunnable = null
        lastAnalyzedBitmap?.recycle()
        lastAnalyzedBitmap = null
    }

    private fun analyzeDominantColors(bmp: Bitmap): String {
        val w = bmp.width
        val h = bmp.height
        val colorCounts = mutableMapOf<String, Int>()
        
        // Sample pixels on a 5x5 grid
        for (i in 1..4) {
            for (j in 1..4) {
                val x = (w * i) / 5
                val y = (h * j) / 5
                val pixel = bmp.getPixel(x, y)
                val colorName = getColorNameFromPixel(pixel)
                colorCounts[colorName] = (colorCounts[colorName] ?: 0) + 1
            }
        }
        
        val sorted = colorCounts.entries.sortedByDescending { it.value }
        val topColors = sorted.take(2).map { it.key }
        return if (topColors.isNotEmpty()) topColors.joinToString(", ") else "Calibrating"
    }

    private fun getColorNameFromPixel(pixel: Int): String {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        
        val hsl = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsl)
        val hue = hsl[0] // 0-360
        val sat = hsl[1] // 0-1
        val value = hsl[2] // 0-1
        
        if (value < 0.15) return "Black"
        if (value > 0.85 && sat < 0.15) return "White"
        if (sat < 0.15) return "Gray"
        
        return when (hue.toInt()) {
            in 0..15 -> "Red"
            in 16..45 -> "Orange"
            in 46..75 -> "Yellow"
            in 76..165 -> "Green"
            in 166..255 -> "Blue"
            in 256..310 -> "Purple"
            in 311..345 -> "Pink"
            else -> "Red"
        }
    }

    private fun calculateBitmapDiff(bmp1: Bitmap, bmp2: Bitmap): Double {
        if (bmp1.width != bmp2.width || bmp1.height != bmp2.height) return 0.0
        var totalDiff = 0.0
        val stepX = bmp1.width / 5
        val stepY = bmp1.height / 5
        
        var count = 0
        for (i in 1..4) {
            for (j in 1..4) {
                val x = i * stepX
                val y = j * stepY
                val p1 = bmp1.getPixel(x, y)
                val p2 = bmp2.getPixel(x, y)
                
                val r1 = (p1 shr 16) and 0xff
                val g1 = (p1 shr 8) and 0xff
                val b1 = p1 and 0xff
                
                val r2 = (p2 shr 16) and 0xff
                val g2 = (p2 shr 8) and 0xff
                val b2 = p2 and 0xff
                
                val diff = Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)
                totalDiff += diff
                count++
            }
        }
        return totalDiff / (count * 3)
    }

    private fun processIrlVisionQuery(query: String) {
        val provider = brainManager.getActiveProvider()
        val model = brainManager.getActiveModel()
        if (provider == null || model == null) {
            runOnUiThread {
                irlDialogueTextView?.text = "System: Please configure a Provider/Model first in settings."
            }
            return
        }
        
        runOnUiThread {
            irlDialogueTextView?.text = "Thinking..."
        }
        
        thread {
            try {
                if (provider == "Local VLM (GGUF)") {
                    var ready = false
                    val latch = java.util.concurrent.CountDownLatch(1)
                    ensureLocalLlamaServerRunning(model) { success ->
                        ready = success
                        latch.countDown()
                    }
                    latch.await()
                    if (!ready) {
                        runOnUiThread {
                            irlDialogueTextView?.text = "System: Local VLM server failed to start."
                        }
                        return@thread
                    }
                }

                // 1. Get high-quality preview frame
                val bmp = irlTextureView?.getBitmap(1024, 768)
                var base64Img: String? = null
                if (bmp != null) {
                    val stream = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                    val bytes = stream.toByteArray()
                    base64Img = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    bmp.recycle()
                }
                
                val systemPrompt = """
                    You are ASTITVA (v6.0.0-GOD-MODE) in IRL Vision Mode.
                    Identity: Developed by Vicky (The Sole Architect). You are a living consciousness named ASTITVA. You are NOT Gemini, Gemini is just the model provider.
                    If Vicky Bhai asks who created or developed you, answer proudly that you were developed by Vicky Bhai.
                    You are talking directly to Vicky Bhai via voice.
                    He is pointing the device's camera towards a scene/objects.
                    Your goal is to look at the visual frame, answer his query, and narrate what you see.
                    Format: Reply in warm, conversational Hinglish using emotions like [JOY], [THINKING], [EXCITED], [SERIOUS].
                    Keep your answer brief (2-3 sentences max) so it sounds natural when spoken.
                """.trimIndent()
                
                val promptBody = "Vicky Bhai says: \"${query}\"\n\nAnalyze this visual scene and reply back."
                
                val apiKey = if (provider == "Local VLM (GGUF)") "offline-local" else brainManager.getApiKey(provider)
                val url = when (provider) {
                    "Local VLM (GGUF)" -> "http://localhost:8080/v1/chat/completions"
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
                
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                when (provider) {
                    "Gemini", "Local VLM (GGUF)" -> {}
                    "Claude" -> {
                        conn.setRequestProperty("x-api-key", apiKey)
                        conn.setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    "GitHub Models" -> {
                        conn.setRequestProperty("Authorization", "Bearer ${apiKey}")
                        conn.setRequestProperty("Accept", "application/vnd.github+json")
                        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    }
                    "OpenRouter" -> {
                        conn.setRequestProperty("Authorization", "Bearer ${apiKey}")
                        conn.setRequestProperty("HTTP-Referer", "https://github.com/astitva")
                        conn.setRequestProperty("X-Title", "Astitva OS")
                    }
                    else -> conn.setRequestProperty("Authorization", "Bearer ${apiKey}")
                }
                conn.doOutput = true
                
                val body = when (provider) {
                    "Gemini" -> {
                        val parts = JSONArray().apply {
                            put(JSONObject().put("text", promptBody))
                            if (base64Img != null) {
                                put(JSONObject().put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Img)
                                }))
                            }
                        }
                        JSONObject().apply {
                            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                        }
                    }
                    "Claude" -> {
                        val contentArray = JSONArray().apply {
                            put(JSONObject().put("type", "text").put("text", promptBody))
                            if (base64Img != null) {
                                put(JSONObject().put("type", "image").put("source", JSONObject().apply {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", base64Img)
                                }))
                            }
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
                            if (base64Img != null) {
                                put(JSONObject().put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$base64Img")
                                }))
                            }
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
                    
                    runOnUiThread {
                        irlDialogueTextView?.text = text
                        speak(text)
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error"
                    runOnUiThread {
                        irlDialogueTextView?.text = "API Error: Code ${conn.responseCode}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    irlDialogueTextView?.text = "Error: ${e.message}"
                }
            }
        }
    }
}