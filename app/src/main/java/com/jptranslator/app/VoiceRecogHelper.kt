package com.jptranslator.app

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VoiceRecogHelper {

    private const val TAG = "VoiceRecogHelper"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-ja-0.22"

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    @Volatile
    private var isModelReady = false

    @Volatile
    private var isDownloading = false

    /**
     * 嘗試載入已經存在於本機的模型（不下載）
     */
    fun init(context: Context) {
        if (model != null) return
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (modelDir.exists() && !modelDir.list().isNullOrEmpty()) {
            loadModel(modelDir)
        }
    }

    /**
     * 下載（如果需要）並載入語音模型，背景執行緒執行
     */
    fun downloadModel(context: Context, onDone: (() -> Unit)? = null) {
        if (isModelReady || isDownloading) return
        isDownloading = true

        Thread {
            try {
                val modelDir = File(context.filesDir, MODEL_DIR_NAME)
                if (!modelDir.exists() || modelDir.list().isNullOrEmpty()) {
                    Log.d(TAG, "開始下載日語語音模型...")
                    val zipFile = File(context.filesDir, "vosk-model.zip")
                    downloadFile(MODEL_URL, zipFile)
                    unzip(zipFile, context.filesDir)
                    zipFile.delete()
                    Log.d(TAG, "日語語音模型下載完成")
                }
                loadModel(modelDir)
            } catch (e: Exception) {
                Log.e(TAG, "語音模型下載/載入失敗: ${e.message}", e)
            } finally {
                isDownloading = false
                onDone?.invoke()
            }
        }.start()
    }

    private fun loadModel(modelDir: File) {
        try {
            val loadedModel = Model(modelDir.absolutePath)
            model = loadedModel
            recognizer = Recognizer(loadedModel, 16000.0f)
            isModelReady = true
            Log.d(TAG, "Vosk 模型載入完成")
        } catch (e: Exception) {
            Log.e(TAG, "模型載入失敗: ${e.message}", e)
        }
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.connect()
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 辨識一段 PCM 16bit 單聲道音訊，回傳辨識出的日文文字
     */
    @Synchronized
    fun recognize(audioData: ByteArray, sampleRate: Int, callback: (String) -> Unit) {
        val rec = recognizer
        if (rec == null || !isModelReady) {
            Log.w(TAG, "語音識別器未就緒，跳過")
            callback("")
            return
        }

        try {
            rec.acceptWaveForm(audioData, audioData.size)
            val text = extractText(rec.finalResult)
            callback(text)
        } catch (e: Exception) {
            Log.e(TAG, "語音識別失敗: ${e.message}", e)
            callback("")
        }
    }

    private fun extractText(json: String): String {
        return try {
            val regex = "\"text\"\\s*:\\s*\"(.*?)\"".toRegex()
            regex.find(json)?.groupValues?.get(1)?.replace("\\u0020", " ") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun release() {
        recognizer?.close()
        model?.close()
        recognizer = null
        model = null
        isModelReady = false
    }
}