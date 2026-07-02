package com.jptranslator.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var btnFloatPerm: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    // 录屏授权结果
    private var resultCode = -1
    private var resultData: Intent? = null

    companion object {
    // 用 Int.MIN_VALUE 當「尚未授權」的哨兵值，避免跟 RESULT_OK(-1) 撞值
    	const val NO_RESULT = Int.MIN_VALUE
    	var sharedResultCode: Int = NO_RESULT
    	var sharedResultData: Intent? = null
    }

    // 请求悬浮窗权限
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

    // 请求录屏/音频捕获权限
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            resultCode = result.resultCode
            resultData = result.data

            // 保存到全局变量
            sharedResultCode = resultCode
            sharedResultData = resultData

            // 启动服务
            startTranslation()
        } else {
            Toast.makeText(this, "需要媒體投影權限才能擷取系統音訊", Toast.LENGTH_LONG).show()
        }
    }

    // 请求麦克风权限
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

        btnFloatPerm = findViewById(R.id.btnFloatPerm)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        // 初始化翻译和语音识别模型
        initModels()

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

    private fun initModels() {
        CoroutineScope(Dispatchers.IO).launch {
            // 预加载翻译模型
            TranslateHelper.init(this@MainActivity)
            // 预下载语音识别模型
            VoiceRecogHelper.downloadModel(this@MainActivity)
        }
    }

    private fun startTranslationFlow() {
        // 先检查麦克风权限
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
        // 启动悬浮窗服务
        val floatingIntent = Intent(this, FloatingWindowService::class.java)
        startService(floatingIntent)

        // 启动音频捕获服务
        val audioIntent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(audioIntent)
        } else {
            startService(audioIntent)
        }

        tvStatus.text = "狀態：翻譯執行中\n打開直播APP即可看到字幕"
        Toast.makeText(this, "翻譯已啟動，返回桌面看懸浮字幕", Toast.LENGTH_SHORT).show()

        // 自动返回桌面
        moveTaskToBack(true)
    }

    private fun stopTranslation() {
        stopService(Intent(this, FloatingWindowService::class.java))
        stopService(Intent(this, AudioCaptureService::class.java))
        tvStatus.text = "狀態：已停止"
        Toast.makeText(this, "翻譯已停止", Toast.LENGTH_SHORT).show()
    }
}
