package com.jptranslator.app

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.github.houbb.opencc4j.util.ZhConverterUtil

object TranslateHelper {

    private val TAG = "TranslateHelper"
    private var translator: Translator? = null
    private var isInitialized = false
    private var isModelDownloaded = false

    fun init(context: Context, onDone: ((success: Boolean) -> Unit)? = null) {
        if (isInitialized) {
            onDone?.invoke(isModelDownloaded)
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()

        translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        translator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                Log.d(TAG, "翻譯模型下載完成")
                isModelDownloaded = true
                isInitialized = true
                onDone?.invoke(true)
            }
            ?.addOnFailureListener { exception ->
                Log.e(TAG, "翻譯模型下載失敗: ${exception.message}")
                isInitialized = true
                onDone?.invoke(false)
            }
    }

    fun translate(text: String, callback: (String) -> Unit) {
        if (translator == null || !isModelDownloaded) {
            Log.w(TAG, "翻譯器未就緒，跳過翻譯")
            callback(text)
            return
        }

        translator?.translate(text)
            ?.addOnSuccessListener { translatedText ->
                callback(toTraditional(translatedText))
            }
            ?.addOnFailureListener { exception ->
                Log.e(TAG, "翻譯失敗: ${exception.message}")
                callback(text)
            }
    }

    /** MLKit 只輸出簡體，這裡轉成繁體（台灣/香港適用）。轉換失敗就回原字串。 */
    private fun toTraditional(simplified: String): String {
        return try {
            ZhConverterUtil.toTraditional(simplified)
        } catch (e: Exception) {
            Log.e(TAG, "簡繁轉換失敗: ${e.message}")
            simplified
        }
    }
}