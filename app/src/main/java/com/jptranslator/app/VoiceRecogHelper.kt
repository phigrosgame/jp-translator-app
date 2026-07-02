package com.jptranslator.app

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object VoiceRecogHelper {

    private const val TAG = "VoiceRecogHelper"
    private const val PREFS = "jptranslator_prefs"
    private const val KEY_MODEL = "voice_model_choice"

    // 兩種模型：small（預設，快、~50MB）、large（高精度、~1GB）
    const val MODEL_SMALL = "small"
    const val MODEL_LARGE = "large"

    private data class ModelSpec(val dirName: String, val url: String)

    private val SPECS = mapOf(
        MODEL_SMALL to ModelSpec(
            "vosk-model-small-ja-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
        ),
        MODEL_LARGE to ModelSpec(
            "vosk-model-ja-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip"
        )
    )

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    // 目前實際載入的是哪個模型（small / large / null）
    @Volatile
    var loadedModel: String? = null
        private set

    @Volatile
    private var isModelReady = false

    @Volatile
    private var isDownloading = false

    /**
     * 模型都存在「App 外部專屬資料夾」：
     *   Android/data/com.jptranslator.app/files/models/<模型資料夾>
     * 好處：
     *   - adb install -r / Android Studio 直接更新 App 時，模型會保留，不用重下
     *   - 可在 PC 解壓大模型後，用 USB 直接丟進這個資料夾，App 開機自動偵測載入
     * 若取不到外部空間才退回內部 filesDir。
     */
    private fun modelsRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun modelDir(context: Context, choice: String): File {
        val spec = SPECS[choice] ?: SPECS.getValue(MODEL_SMALL)
        return File(modelsRoot(context), spec.dirName)
    }

    private fun isPresent(dir: File): Boolean = dir.exists() && !dir.list().isNullOrEmpty()

    /** 讀取使用者偏好的模型（預設 small） */
    fun getPreferredModel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODEL, MODEL_SMALL) ?: MODEL_SMALL
    }

    fun setPreferredModel(context: Context, choice: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, choice).apply()
    }

    /** 指定的模型是否已在本機（可直接載入、免下載） */
    fun isModelPresent(context: Context, choice: String): Boolean =
        isPresent(modelDir(context, choice))

    /**
     * 啟動時呼叫：載入使用者偏好的模型（若已存在）。
     * 若偏好的是 large 但沒下載、而 small 在，會退回載入 small 讓使用者至少能用。
     */
    fun init(context: Context) {
        if (model != null) return
        val preferred = getPreferredModel(context)
        val preferredDir = modelDir(context, preferred)
        when {
            isPresent(preferredDir) -> loadModel(preferredDir, preferred)
            isModelPresent(context, MODEL_SMALL) -> loadModel(modelDir(context, MODEL_SMALL), MODEL_SMALL)
            else -> Log.d(TAG, "尚無任何本機模型，等待下載")
        }
    }

    /**
     * 下載並載入指定模型。若該模型資料夾已存在（例如手動匯入），就直接載入不下載。
     * 切換模型時會先釋放舊的再載入新的。
     */
    fun downloadModel(
        context: Context,
        choice: String = getPreferredModel(context),
        onProgress: ((Int) -> Unit)? = null,
        onDone: ((success: Boolean) -> Unit)? = null
    ) {
        if (isDownloading) return
        // 已經載入同一個模型，直接完成
        if (isModelReady && loadedModel == choice) {
            onDone?.invoke(true)
            return
        }
        isDownloading = true

        Thread {
            var success = false
            try {
                val spec = SPECS[choice] ?: SPECS.getValue(MODEL_SMALL)
                val dir = modelDir(context, choice)
                if (!isPresent(dir)) {
                    Log.d(TAG, "開始下載語音模型：$choice")
                    installModel(context, spec, choice, onProgress)
                    Log.d(TAG, "語音模型下載完成：$choice")
                } else {
                    Log.d(TAG, "模型已存在，直接載入：$choice")
                    onProgress?.invoke(100)
                }
                loadModel(dir, choice)
                success = isModelReady && loadedModel == choice
            } catch (e: Exception) {
                Log.e(TAG, "語音模型下載/載入失敗($choice): ${e.message}", e)
            } finally {
                isDownloading = false
                onDone?.invoke(success)
            }
        }.start()
    }

    @Synchronized
    private fun loadModel(dir: File, choice: String) {
        try {
            // 切換模型：釋放舊的
            recognizer?.close()
            model?.close()
            recognizer = null
            model = null
            isModelReady = false

            val loadedM = Model(dir.absolutePath)
            model = loadedM
            recognizer = Recognizer(loadedM, 16000.0f)
            loadedModel = choice
            isModelReady = true
            Log.d(TAG, "Vosk 模型載入完成：$choice")
        } catch (e: Exception) {
            Log.e(TAG, "模型載入失敗($choice): ${e.message}", e)
        }
    }

    /**
     * 下載並安裝模型（含斷點續傳、重試、安全解壓）。
     * 流程：續傳下載到 .part → 改名為 .zip → 解壓到暫存資料夾 → 原子換名到最終資料夾。
     * 這樣即使中途斷網 / App 被關掉，最終資料夾都不會出現「半套」的壞模型。
     */
    private fun installModel(
        context: Context,
        spec: ModelSpec,
        choice: String,
        onProgress: ((Int) -> Unit)?
    ) {
        val root = modelsRoot(context)
        val partFile = File(root, "vosk-model-$choice.zip.part")
        val zipFile = File(root, "vosk-model-$choice.zip")

        try {
            downloadResumable(spec.url, partFile, onProgress)
        } catch (e: Exception) {
            // 續傳資料保留在 .part，下次再開 App 會接著下，不從 0 開始
            throw e
        }

        // 下載完成 → 換名 → 解壓（解壓失敗代表檔案壞了，砍掉讓下次重下）
        if (zipFile.exists()) zipFile.delete()
        if (!partFile.renameTo(zipFile)) {
            partFile.copyTo(zipFile, overwrite = true)
            partFile.delete()
        }

        val staging = File(root, ".staging-$choice")
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(zipFile, staging)

            // zip 內含頂層資料夾（例如 vosk-model-ja-0.22），把它換名到最終位置
            val extracted = File(staging, spec.dirName)
            val src = if (extracted.exists()) extracted else staging
            val finalDir = File(root, spec.dirName)
            finalDir.deleteRecursively()
            if (!src.renameTo(finalDir)) {
                src.copyRecursively(finalDir, overwrite = true)
            }
        } catch (e: Exception) {
            zipFile.delete() // 壞檔，砍掉下次重下
            throw IOException("解壓模型失敗，已清除損壞檔案：${e.message}", e)
        } finally {
            staging.deleteRecursively()
            zipFile.delete()
        }
    }

    /**
     * 斷點續傳下載：斷線 / 讀取中斷就從已下載的位元組數用 HTTP Range 接著下，
     * 並自動重試多次，直到檔案大小達到伺服器回報的總長度。
     */
    private fun downloadResumable(urlStr: String, dest: File, onProgress: ((Int) -> Unit)?) {
        val maxAttempts = 100
        var total = -1L
        var attempt = 0

        while (true) {
            attempt++
            var existing = if (dest.exists()) dest.length() else 0L
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

            try {
                conn.connect()
                val code = conn.responseCode
                val append: Boolean
                when (code) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        // 206：續傳成功，從 Content-Range: bytes start-end/total 取總長
                        append = true
                        total = conn.getHeaderField("Content-Range")
                            ?.substringAfter('/')?.toLongOrNull() ?: total
                    }
                    HttpURLConnection.HTTP_OK -> {
                        // 200：伺服器不支援 Range，只能從頭下
                        append = false
                        existing = 0L
                        total = conn.contentLengthLong
                    }
                    else -> throw IOException("下載失敗，HTTP $code")
                }

                var downloaded = existing
                conn.inputStream.use { input ->
                    FileOutputStream(dest, append).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (total > 0) onProgress?.invoke((downloaded * 100L / total).toInt())
                        }
                    }
                }

                // 串流正常結束：檔案夠長就成功；否則（連線提前斷）丟例外進重試
                if (total <= 0 || dest.length() >= total) {
                    onProgress?.invoke(100)
                    return
                }
                throw IOException("連線提前中斷（${dest.length()}/$total）")
            } catch (e: Exception) {
                if (attempt >= maxAttempts) {
                    Log.e(TAG, "下載重試 $maxAttempts 次仍失敗：${e.message}")
                    throw e
                }
                val delaySec = minOf(attempt.toLong() * 2, 15L)
                Log.w(TAG, "下載中斷(第 $attempt 次)：${e.message}，${delaySec}s 後從 ${dest.length()} bytes 續傳")
                try { Thread.sleep(delaySec * 1000) } catch (ie: InterruptedException) { throw ie }
            } finally {
                conn.disconnect()
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
            stripSpaces(extractField(rec.result, "text"))
        } catch (e: Exception) {
            Log.e(TAG, "取得最終結果失敗: ${e.message}")
            ""
        }
    }

    @Synchronized
    fun getPartialText(): String {
        val rec = recognizer ?: return ""
        return try {
            stripSpaces(extractField(rec.partialResult, "partial"))
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 強制結束目前這一句：拿到目前累積的最終文字並重置辨識器。
     * 用於「講太久一直沒有自然停頓」時強制斷句。
     */
    @Synchronized
    fun flushAndReset(): String {
        val rec = recognizer ?: return ""
        return try {
            val text = stripSpaces(extractField(rec.result, "text"))
            rec.reset()
            text
        } catch (e: Exception) {
            Log.e(TAG, "強制斷句失敗: ${e.message}")
            ""
        }
    }

    /**
     * Vosk 日文輸出是空格分詞（例如「これ は テスト です」），
     * 這裡去掉半形/全形空格還原成自然日文，翻譯品質才會好。
     */
    private fun stripSpaces(text: String): String =
        text.replace(" ", "").replace("　", "")

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
        loadedModel = null
    }
}
