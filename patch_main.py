import re

with open("app/src/main/java/com/vicky/astitva/MainActivity.kt", "r") as f:
    code = f.read()

# Add imports
imports = """
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.io.OutputStreamWriter
import org.json.JSONArray
"""
code = code.replace("import kotlin.concurrent.thread", "import kotlin.concurrent.thread\n" + imports)

# Add class variables
vars = """
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
"""
code = code.replace("private lateinit var prefs: SharedPreferences", vars)

# Add TTS init in onCreate
init_tts = """
        prefs = getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
            }
        }
        setupSpeechRecognizer()
"""
code = code.replace('prefs = getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)', init_tts)

# Add Voice Button UI
btn_ui = """
        val voiceBtn = Button(this).apply {
            text = "🎤 VOICE"
            setBackgroundColor(Color.parseColor("#E64A19"))
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(40, 30, 40, 30)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                bottomMargin = 200
                leftMargin = 40
            }
            setOnClickListener { startListening() }
        }

        val visionBtn = Button(this).apply {
"""
code = code.replace("val visionBtn = Button(this).apply {", btn_ui)
code = code.replace("root.addView(visionBtn)", "root.addView(voiceBtn)\n        root.addView(visionBtn)")

# Replace handleCommand else branch with callAIModel
old_else = """            } else {
                addSystemThought("Intent recognized: General inquiry. Routing to Neural Engine...")
                addTerminalBox("node /system/brain/neural.js \\"$cmd\\"", "Awaiting AI response...")
                Thread.sleep(1000)
                addMessage("ASTITVA", "I have received your command. The neural link is processing your request: '$cmd'.", false)
            }"""

new_else = """            } else {
                addSystemThought("Intent recognized: Neural Generation. Routing via Active Provider...")
                val activeProvider = prefs.getString("ACTIVE_PROVIDER", "")
                val activeModel = prefs.getString("SELECTED_MODEL", "")
                addTerminalBox("POST /v1/chat/completions", "Provider: $activeProvider\\nModel: $activeModel\\nAwaiting AI response...")
                callAIModel(cmd, activeProvider, activeModel)
            }"""
code = code.replace(old_else, new_else)

# Add missing methods
new_methods = """
    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Voice Error: $error", Toast.LENGTH_SHORT).show() }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        promptInput.setText(text)
                        if (text.lowercase().contains("astitva")) {
                            handleCommand(text)
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.startListening(intent)
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun callAIModel(prompt: String, provider: String?, model: String?) {
        if (provider.isNullOrEmpty() || model.isNullOrEmpty()) {
            addMessage("ASTITVA", "Error: No Active Provider or Model selected in Config.", false)
            speak("Error. No brain configuration selected.")
            return
        }
        val apiKey = prefs.getString("${provider}_KEY", "") ?: ""
        if (apiKey.isEmpty()) {
            addMessage("ASTITVA", "Error: API key for $provider is missing.", false)
            return
        }

        thread {
            try {
                val (urlStr, authHeader, jsonBody) = when (provider) {
                    "OpenRouter", "OpenAI", "Groq" -> {
                        val url = if (provider == "OpenRouter") "https://openrouter.ai/api/v1/chat/completions"
                                  else if (provider == "Groq") "https://api.groq.com/openai/v1/chat/completions"
                                  else "https://api.openai.com/v1/chat/completions"
                        val body = JSONObject().apply {
                            put("model", model)
                            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                        }
                        Triple(url, "Bearer $apiKey", body.toString())
                    }
                    "Gemini" -> {
                        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                        val body = JSONObject().apply {
                            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                        }
                        Triple(url, "", body.toString())
                    }
                    else -> Triple("", "", "")
                }

                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                if (authHeader.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", authHeader)
                }
                if (provider == "OpenRouter") {
                    conn.setRequestProperty("HTTP-Referer", "https://github.com/astitva")
                    conn.setRequestProperty("X-Title", "Astitva OS")
                }
                conn.doOutput = true

                OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

                val responseCode = conn.responseCode
                val reader = if (responseCode in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream))
                } else {
                    BufferedReader(InputStreamReader(conn.errorStream))
                }
                
                val response = reader.readText()
                reader.close()

                if (responseCode in 200..299) {
                    val json = JSONObject(response)
                    var reply = ""
                    if (provider == "Gemini") {
                        reply = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    } else {
                        reply = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    }
                    addMessage("ASTITVA", reply, false)
                    speak(reply)
                } else {
                    addMessage("ASTITVA", "API Error ($responseCode): $response", false)
                }
            } catch (e: Exception) {
                addMessage("ASTITVA", "Network Error: ${e.message}", false)
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
"""
code = code.replace("    override fun onActivityResult", new_methods + "\n    override fun onActivityResult")

with open("app/src/main/java/com/vicky/astitva/MainActivity.kt", "w") as f:
    f.write(code)
