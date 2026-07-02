package com.jptranslator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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

    // 节流：每 2.5 秒处理一次音频
    private val processIntervalMs = 2500L
    private var lastProcessTime = 0L

    // 音频缓冲
    private val audioBuffer = mutableListOf<Byte>()
    private val maxBufferSize = sampleRate * 2 * 3 // 最多存3秒

    // 静音检测阈值
    private val silenceThreshold = 500

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "audio_capture_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 初始化语音识别
        VoiceRecogHelper.init()

        // 开始捕获
        startCapture()
    }

    private fun startCapture() {
        val resultCode = MainActivity.sharedResultCode
        val resultData = MainActivity.sharedResultData

        if (resultCode == -1 || resultData == null) {
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
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

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
            .setBufferSizeInBytes(bufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()
        startProcessingLoop()
    }

    private fun startProcessingLoop() {
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val buffer = ByteArray(bufferSize)

            while (true) {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0

                if (bytesRead > 0) {
                    // 添加到缓冲
                    audioBuffer.addAll(buffer.take(bytesRead).toList())

                    // 限制缓冲大小
                    if (audioBuffer.size > maxBufferSize) {
                        audioBuffer.subList(0, audioBuffer.size - maxBufferSize).clear()
                    }

                    // 节流：每隔一段时间处理一次
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProcessTime >= processIntervalMs) {
                        lastProcessTime = currentTime

                        // 检测是否静音
                        val audioData = audioBuffer.toByteArray()
                        if (!isSilence(audioData)) {
                            processAudioChunk(audioData)
                        }

                        // 清空缓冲
                        audioBuffer.clear()
                    }
                }

                // 稍微延迟，避免CPU占用过高
                delay(10)
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

    private fun processAudioChunk(audioData: ByteArray) {
        VoiceRecogHelper.recognize(audioData, sampleRate) { japaneseText ->
            if (japaneseText.isNotBlank()) {
                // 翻译
                TranslateHelper.translate(japaneseText) { chineseText ->
                    // 更新悬浮窗
                    FloatingWindowService.updateCallback?.invoke(japaneseText, chineseText)
                }
            }
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
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
