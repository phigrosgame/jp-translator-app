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

    fun init(context: Context) {
        if (model != null) return
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (modelDir.exists() && !modelDir.list().isNullOrEmpty()) {
            loadModel(modelDir)
        }
    }

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
     * 持續餵一小塊音訊進辨識器（串流模式）。
     * 回傳 true 代表 Vosk 偵測到自然停頓，一句話已經講完，
     * 這時候呼叫 getFinalText() 拿最終結果。
     * 回傳 false 代表還在講話中，可以呼叫 getPartialText() 拿暫時字幕。
     */
    @Synchronized
    fun acceptAudioChunk(chunk: ByteArray, len: Int): Boolean {
        val rec = recognizer
        if (rec == null || !isModelReady) return false
        return try {
            rec.acceptWaveForm(chunk, len)
        } catch (e: Exception) {
            Log.e(TAG, "串流辨識失敗: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun getFinalText(): String {
        val rec = recognizer ?: return ""
        return try {
            extractField(rec.result, "text")
        } catch (e: Exception) {
            Log.e(TAG, "取得最終結果失敗: ${e.message}")
            ""
        }
    }

    @Synchronized
    fun getPartialText(): String {
        val rec = recognizer ?: return ""
        return try {
            extractField(rec.partialResult, "partial")
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractField(json: String, field: String): String {
        return try {
            val regex = "\"$field\"\\s*:\\s*\"(.*?)\"".toRegex()
            regex.find(json)?.groupValues?.get(1) ?: ""
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