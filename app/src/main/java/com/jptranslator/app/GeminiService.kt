package com.jptranslator.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GeminiService {
    private const val TAG = "GeminiService"
    // 硬編碼你的 API Key 以跳過設定頁面
    private const val API_KEY = "你的_API_KEY_放在這裡"
    private const val MODEL_NAME = "gemini-2.0-flash"

    fun translateAudio(context: Context, audioData: ByteArray): String? {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${MODEL_NAME}:generateContent?key=${API_KEY}")
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
                return root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()
            } else {
                Log.e(TAG, "Gemini API error: ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request failed: ${e.message}")
        }
        return null
    }
}
