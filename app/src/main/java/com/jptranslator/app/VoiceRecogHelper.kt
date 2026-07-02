package com.jptranslator.app

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.speech.SpeechRecognition
import com.google.mlkit.nl.speech.SpeechRecognitionModel
import com.google.mlkit.nl.speech.SpeechRecognizer
import java.nio.ByteBuffer

object VoiceRecogHelper {

    private val TAG = "VoiceRecogHelper"
    private var recognizer: SpeechRecognizer? = null
    private var isModelDownloaded = false
    private var isInitializing = false

    fun init() {
        if (recognizer != null) return
        if (isInitializing) return

        isInitializing = true

        val model = SpeechRecognitionModel.builder("ja-JP").build()
        recognizer = SpeechRecognition.getClient(model)
    }

    fun downloadModel() {
        if (isModelDownloaded) return

        val model = SpeechRecognitionModel.builder("ja-JP").build()
        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        model.download(conditions)
            .addOnSuccessListener {
                Log.d(TAG, "日語語音模型下載完成")
                isModelDownloaded = true
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "語音模型下載失敗: ${exception.message}")
            }
    }

    fun recognize(audioData: ByteArray, sampleRate: Int, callback: (String) -> Unit) {
        if (recognizer == null) {
            Log.w(TAG, "語音識別器未初始化")
            callback("")
            return
        }

        if (!isModelDownloaded) {
            Log.w(TAG, "語音模型未下載完成")
            callback("")
            return
        }

        val byteBuffer = ByteBuffer.wrap(audioData)

        recognizer?.recognize(byteBuffer, sampleRate)
            ?.addOnSuccessListener { result ->
                val text = result.text
                callback(text ?: "")
            }
            ?.addOnFailureListener { exception ->
                Log.e(TAG, "語音識別失敗: ${exception.message}")
                callback("")
            }
    }
}
