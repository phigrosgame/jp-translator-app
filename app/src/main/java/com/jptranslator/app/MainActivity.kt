package com.jptranslator.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: android.widget.EditText
    private lateinit var etModelName: android.widget.EditText
    private lateinit var btnFloatPerm: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isVoiceModelReady = false
    @Volatile private var isTranslateModelReady = false

    companion object {
        const val NO_RESULT = Int.MIN_VALUE
        var sharedResultCode: Int = NO_RESULT
        var sharedResultData: Intent? = null
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "懸浮窗權限已開啟", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "請手動開啟懸浮窗權限", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            resultCode = result.resultCode
            resultData = result.data

            sharedResultCode = resultCode
            sharedResultData = resultData

            startTranslation()
        } else {
            Toast.makeText(this, "需要媒體投影權限才能擷取系統音訊", Toast.LENGTH_LONG).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "需要麥克風權限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        etModelName = findViewById(R.id.etModelName)
        btnFloatPerm = findViewById(R.id.btnFloatPerm)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        etApiKey.setText(GeminiService.getApiKey(this))
        etModelName.setText(GeminiService.getModelName(this))

        btnStart.isEnabled = true

        btnFloatPerm.setOnClickListener {
            requestOverlayPermission()
        }

        btnStart.setOnClickListener {
            startTranslationFlow()
        }

        btnStop.setOnClickListener {
            stopTranslation()
        }
    }

    // (移除所有舊 Vosk 方法)
}
    private fun startTranslationFlow() {
        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                requestOverlayPermission()
            } else {
                requestMediaProjection()
            }
        } else {
            requestMediaProjection()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun startTranslation() {
        // 儲存設定
        GeminiService.setApiKey(this, etApiKey.text.toString())
        GeminiService.setModelName(this, etModelName.text.toString().ifEmpty { "gemini-2.0-flash" })

        val floatingIntent = Intent(this, FloatingWindowService::class.java)
        startService(floatingIntent)


        val audioIntent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(audioIntent)
        } else {
            startService(audioIntent)
        }

        tvStatus.text = "狀態：翻譯執行中\n打開直播APP即可看到字幕"
        Toast.makeText(this, "翻譯已啟動，返回桌面看懸浮字幕", Toast.LENGTH_SHORT).show()

        moveTaskToBack(true)
    }

    private fun stopTranslation() {
        stopService(Intent(this, FloatingWindowService::class.java))
        stopService(Intent(this, AudioCaptureService::class.java))
        tvStatus.text = "狀態：已停止"
        Toast.makeText(this, "翻譯已停止", Toast.LENGTH_SHORT).show()
    }
}