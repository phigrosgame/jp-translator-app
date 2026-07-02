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

    // 靜音判斷閾值：只用來決定要不要更新暫時字幕，不影響最終斷句（斷句交給 Vosk 自己判斷）
    private val silenceThreshold = 500

    // 每隔幾個 chunk 更新一次暫時字幕（約每 0.3 秒）
    private val partialUpdateEveryNChunks = 3

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

            while (isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, chunkSize) ?: 0

                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }

                val chunkData = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                val isSpeechEnd = VoiceRecogHelper.acceptAudioChunk(chunkData, bytesRead)

                if (isSpeechEnd) {
                    // Vosk 偵測到自然停頓，一句話講完了
                    val japaneseText = VoiceRecogHelper.getFinalText()
                    partialCounter = 0
                    if (japaneseText.isNotBlank() && japaneseText.length >= 2) {
                        processFinalSegment(japaneseText)
                    }
                } else if (!isSilence(chunkData)) {
                    // 还在讲话中，定期更新暂时字幕，给即时反馈
                    partialCounter++
                    if (partialCounter >= partialUpdateEveryNChunks) {
                        partialCounter = 0
                        val partialText = VoiceRecogHelper.getPartialText()
                        if (partialText.isNotBlank()) {
                            FloatingWindowService.partialCallback?.invoke(partialText)
                        }
                    }
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
                .setSmallIcon(android.R.drawab