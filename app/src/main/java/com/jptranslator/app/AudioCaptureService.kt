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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val chunkSize = 3200 
    private val silenceThreshold = 500
    
    // 累積大約 3-5 秒的語音片段，再送到 Gemini 進行識別與翻譯
    private val maxBufferBytes = 16000 * 2 * 5 // 5秒

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "audio_capture_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startCapture()
    }

    private fun startCapture() {
        val resultCode = MainActivity.sharedResultCode
        val resultData = MainActivity.sharedResultData

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return
        }

        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setupAudioPlaybackCapture()
        } else {
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioPlaybackCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfig).build())
            .setBufferSizeInBytes(minBufferSize * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()
        startProcessingLoop()
    }

    private fun startProcessingLoop() {
        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(chunkSize)
            val audioBuffer = ByteArrayOutputStream()
            var silenceCount = 0

            while (isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, chunkSize) ?: 0
                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }

                audioBuffer.write(buffer, 0, bytesRead)
                
                if (isSilence(buffer)) {
                    silenceCount++
                } else {
                    silenceCount = 0
                }

                // 若靜音超過 1 秒或緩衝區已滿，送出翻譯
                if (silenceCount >= 10 || audioBuffer.size() >= maxBufferBytes) {
                    val data = audioBuffer.toByteArray()
                    audioBuffer.reset()
                    silenceCount = 0
                    
                    if (data.size > 1000) { // 過短的聲音忽略
                        val result = GeminiService.translateAudio(this@AudioCaptureService, data)
                        if (!result.isNullOrBlank()) {
                            FloatingWindowService.updateCallback?.invoke("Gemini翻譯", result)
                        }
                    }
                }
            }
        }
    }

    private fun isSilence(audioData: ByteArray): Boolean {
        var sum = 0.0
        val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        while (shortBuffer.hasRemaining()) {
            val sample = shortBuffer.get().toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / shortBuffer.capacity())
        return rms < silenceThreshold
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "音訊捕獲服務", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Gemini 翻譯中...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
