package com.vicky.astitva.core

import android.content.Context
import android.content.SharedPreferences
import com.vicky.astitva.utils.SecurityUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class BrainManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("AstitvaConfig", Context.MODE_PRIVATE)

    interface ModelFetchListener {
        fun onModelsFetched(models: List<String>)
        fun onError(error: String)
    }

    fun getActiveProvider(): String? {
        val provider = prefs.getString("ACTIVE_PROVIDER", null)
        if (provider == "Local VLM (GGUF)") {
            return "Gemini"
        }
        return provider
    }
    fun getActiveModel(): String? = prefs.getString("SELECTED_MODEL", null)

    fun getApiKey(provider: String): String {
        val encryptedKey = prefs.getString("${provider}_KEY", "") ?: ""
        return if (encryptedKey.isNotEmpty()) SecurityUtils.decrypt(encryptedKey) else ""
    }

    fun fetchModels(provider: String, apiKey: String, listener: ModelFetchListener) {
        thread {
            try {
                if (provider == "Local VLM (GGUF)") {
                    val folder = java.io.File("/sdcard/AstitvaModels")
                    val modelsList = mutableListOf<String>()
                    if (folder.exists() && folder.isDirectory) {
                        val files = folder.listFiles()
                        if (files != null) {
                            for (file in files) {
                                if (file.isFile && file.name.endsWith(".gguf") && !file.name.contains("mmproj", ignoreCase = true)) {
                                    modelsList.add(file.name)
                                }
                            }
                        }
                    }
                    if (modelsList.isEmpty()) {
                        modelsList.add("No GGUF models found in /sdcard/AstitvaModels")
                    }
                    listener.onModelsFetched(modelsList)
                    return@thread
                }

                if (provider == "Hugging Face") {
                    val modelsList = listOf(
                        "meta-llama/Llama-3.2-3B-Instruct",
                        "Qwen/Qwen2.5-72B-Instruct",
                        "meta-llama/Meta-Llama-3-8B-Instruct",
                        "mistralai/Mistral-7B-Instruct-v0.3",
                        "google/gemma-2-9b-it",
                        "microsoft/Phi-3-mini-4k-instruct"
                    )
                    listener.onModelsFetched(modelsList)
                    return@thread
                }

                val (urlStr, headers) = when (provider) {
                    "Gemini" -> Pair("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey", mapOf())
                    "OpenAI" -> Pair("https://api.openai.com/v1/models", mapOf("Authorization" to "Bearer $apiKey"))
                    "Groq" -> Pair("https://api.groq.com/openai/v1/models", mapOf("Authorization" to "Bearer $apiKey"))
                    "Claude" -> Pair("https://api.anthropic.com/v1/models", mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"))
                    "OpenRouter" -> Pair("https://openrouter.ai/api/v1/models", mapOf("Authorization" to "Bearer $apiKey"))
                    "DeepSeek" -> Pair("https://api.deepseek.com/models", mapOf("Authorization" to "Bearer $apiKey"))
                    "GitHub Models" -> Pair("https://models.github.ai/catalog/models", mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"))
                    "SambaNova" -> Pair("https://api.sambanova.ai/v1/models", mapOf("Authorization" to "Bearer $apiKey"))
                    "Cerebras" -> Pair("https://api.cerebras.ai/v1/models", mapOf("Authorization" to "Bearer $apiKey"))
                    else -> Pair("", mapOf())
                }

                if (urlStr.isEmpty()) {
                    listener.onError("Unsupported provider")
                    return@thread
                }

                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()
                    
                    val modelsList = mutableListOf<String>()
                    if (provider == "GitHub Models") {
                        val arr = JSONArray(response)
                        for (i in 0 until arr.length()) modelsList.add(arr.getJSONObject(i).getString("id"))
                    } else {
                        val json = JSONObject(response)
                        when (provider) {
                            "Gemini" -> {
                                val arr = json.getJSONArray("models")
                                for (i in 0 until arr.length()) modelsList.add(arr.getJSONObject(i).getString("name").replace("models/", ""))
                            }
                            "OpenAI", "Groq", "OpenRouter", "DeepSeek", "SambaNova", "Cerebras" -> {
                                val arr = json.getJSONArray("data")
                                for (i in 0 until arr.length()) modelsList.add(arr.getJSONObject(i).getString("id"))
                            }
                            "Claude" -> {
                                val arr = json.optJSONArray("data")
                                if (arr != null) {
                                    for (i in 0 until arr.length()) modelsList.add(arr.getJSONObject(i).getString("id"))
                                } else {
                                    modelsList.addAll(listOf("claude-3-opus-20240229", "claude-3-sonnet-20240229", "claude-3-haiku-20240307", "claude-3-5-sonnet-20240620"))
                                }
                            }
                        }
                    }
                    listener.onModelsFetched(modelsList)
                } else {
                    listener.onError("Failed: ${conn.responseCode}")
                }
            } catch (e: Exception) {
                listener.onError("Error: ${e.message}")
            }
        }
    }

    fun saveConfig(provider: String, apiKey: String, model: String) {
        val encryptedKey = SecurityUtils.encrypt(apiKey)
        prefs.edit().apply {
            putString("${provider}_KEY", encryptedKey)
            putString("ACTIVE_PROVIDER", provider)
            putString("SELECTED_MODEL", model)
            apply()
        }
    }
}
