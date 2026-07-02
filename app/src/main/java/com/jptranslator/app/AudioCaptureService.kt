package com.jptranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    // 音频参数
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 每次讀取約 0.1 秒的音訊，做串流辨識（不再固定 2.5 秒才處理一次）
    private val chunkSize = 3200 // 16000Hz * 2bytes * 0.1s

    // 靜音判斷閾值：低於此音量視為靜音（用來判斷句子是否真的講完）
    private val silenceThreshold = 500

    // 每隔幾個 chunk 更新一次暫時字幕（約每 0.3 秒）
    private val partialUpdateEveryNChunks = 3

    // 「停頓容忍」：講話中的短停頓（換氣、思考）不算講完，會繼續累積合併。
    // 只有連續靜音超過這個 chunk 數（約 1.5 秒）才判定整段話講完、送出翻譯。
    // 想更快出字幕就調小、想合併更完整就調大。
    private val pauseToleranceChunks = 15 // 15 * 0.1s ≈ 1.5 秒

    // 保險上限：一段話累積太久（約 15 秒）都沒停夠，就強制送出一次，避免無限累積。
    private val maxPendingChunks = 150 // 150 * 0.1s = 15 秒

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "audio_capture_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        VoiceRecogHelper.init(this)
        startCapture()
    }

    private fun startCapture() {
        val resultCode = MainActivity.sharedResultCode
        val resultData = MainActivity.sharedResultData

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "沒有錄屏授權，無法捕獲音訊")
            stopSelf()
            return
        }

        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setupAudioPlaybackCapture()
        } else {
            Log.e(TAG, "Android 版本太低，需要 Android 10 以上")
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioPlaybackCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()
        startProcessingLoop()
    }

    private fun startProcessingLoop() {
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(chunkSize)
            var partialCounter = 0

            // 累積合併：把 Vosk 因為短停頓吐出的多個片段先接起來，
            // 直到真的停夠久（或累積太久）才整句翻譯，避免「一句話被切成好幾段」。
            val pending = StringBuilder()
            var pendingChunks = 0       // 本句從開始累積到現在的 chunk 數（含短停頓）
            var silenceAfterSpeech = 0  // 有內容後，連續靜音的 chunk 數

            fun flushPending(forced: Boolean) {
                if (forced) {
                    // 強制送出：把還沒被 Vosk 收尾的當前片段也一起拿進來
                    val tail = VoiceRecogHelper.flushAndReset()
                    if (tail.isNotBlank()) pending.append(tail)
                }
                val full = pending.toString()
                pending.setLength(0)
                pendingChunks = 0
                silenceAfterSpeech = 0
                partialCounter = 0
                if (full.length >= 2) processFinalSegment(full)
            }

            while (isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, chunkSize) ?: 0

                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }

                val chunkData = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                val isSpeechEnd = VoiceRecogHelper.acceptAudioChunk(chunkData, bytesRead)
                val silent = isSilence(chunkData)

                // Vosk 因短停頓吐出一個片段 → 先併進 pending，不急著翻譯
                if (isSpeechEnd) {
                    val seg = VoiceRecogHelper.getFinalText()
                    if (seg.isNotBlank()) pending.append(seg)
                }

                if (pending.isNotEmpty()) pendingChunks++

                if (silent) {
                    if (pending.isNotEmpty()) silenceAfterSpeech++
                } else {
                    // 還在講話：重置靜音計數，並更新暫時字幕（已累積 + 當前 partial）
                    silenceAfterSpeech = 0
                    partialCounter++
                    if (partialCounter >= partialUpdateEveryNChunks) {
                        partialCounter = 0
                        val show = pending.toString() + VoiceRecogHelper.getPartialText()
                        if (show.isNotBlank()) {
                            FloatingWindowService.partialCallback?.invoke(show)
                        }
                    }
                }

                val enoughPause = pending.isNotEmpty() && silenceAfterSpeech >= pauseToleranceChunks
                val tooLong = pendingChunks >= maxPendingChunks
                if (enoughPause || tooLong) {
                    flushPending(forced = tooLong && !enoughPause)
                }
            }
        }
    }

    private fun isSilence(audioData: ByteArray): Boolean {
        if (audioData.isEmpty()) return true

        var sum = 0.0
        val shortBuffer = ByteBuffer.wrap(audioData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        while (shortBuffer.hasRemaining()) {
            val sample = shortBuffer.get().toDouble()
            sum += sample * sample
        }

        val rms = sqrt(sum / shortBuffer.capacity())
        return rms < silenceThreshold
    }

    private fun processFinalSegment(japaneseText: String) {
        TranslateHelper.translate(japaneseText) { chineseText ->
            FloatingWindowService.updateCallback?.invoke(japaneseText, chineseText)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音訊捕獲服務",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "直播音訊翻譯後台服務"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("日語翻譯字幕")
                .setContentText("正在擷取系統音訊並翻譯...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("日語翻譯字幕")
                .setContentText("正在擷取系統音訊並翻譯...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection?.stop()
        mediaProjection = null
        VoiceRecogHelper.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}