package com.jptranslator.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiService {
    private const val TAG = "GeminiService"
    private const val PREFS = "jptranslator_prefs"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_MODEL_NAME = "gemini_model_name"
    private const val DEFAULT_MODEL = "gemini-2.0-flash"

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, key).apply()
    }

    fun getModelName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModelName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL_NAME, name).apply()
    }

    fun translateAudio(context: Context, audioData: ByteArray): String? {
        val apiKey = getApiKey(context)
        val modelName = getModelName(context).ifBlank { DEFAULT_MODEL }
        if (apiKey.isBlank()) {
            Log.w(TAG, "尚未設定 Gemini API Key")
            return null
        }

        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent?key=${apiKey}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

            val jsonBody = JSONObject()
            val contents = JSONObject()
            val parts = org.json.JSONArray()

            val textPart = JSONObject()
            textPart.put("text", "請將這段日文音訊直接翻譯成繁體中文，不需要解釋，只輸出翻譯內容：")
            parts.put(textPart)

            val audioPart = JSONObject()
            val inlineData = JSONObject()
            inlineData.put("mime_type", "audio/wav")
            inlineData.put("data", base64Audio)
            audioPart.put("inline_data", inlineData)
            parts.put(audioPart)

            contents.put("parts", parts)
            val contentsArray = org.json.JSONArray()
            contentsArray.put(contents)
            jsonBody.put("contents", contentsArray)

            conn.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(response)
                val text = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
                return text
            } else {
                Log.e(TAG, "Gemini API error: ${conn.responseCode}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request failed: ${e.message}")
            return null
        }
    }
}
