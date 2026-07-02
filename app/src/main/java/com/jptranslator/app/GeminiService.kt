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

    /**
     * AudioRecord 抓出來的是裸 PCM 資料，沒有 WAV 檔頭。
     * Gemini 需要一份「真正的」WAV 檔（含 44 bytes RIFF/WAVE 表頭）才認得出來，
     * 直接把裸 PCM 標成 audio/wav 送過去，Gemini 會打不開而回覆「無法處理音訊檔案」。
     */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size (PCM)
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)

        return header.array() + pcmData
    }

    fun translateAudio(context: Context, audioData: ByteArray, sampleRate: Int = 16000): String? {
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

            val wavData = pcmToWav(audioData, sampleRate)
            val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

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
