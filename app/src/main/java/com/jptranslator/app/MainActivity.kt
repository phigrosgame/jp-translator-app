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

    private lateinit var btnFloatPerm: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressDownload: ProgressBar
    private lateinit var modelGroup: RadioGroup
    private lateinit var rbSmall: RadioButton
    private lateinit var rbLarge: RadioButton
    private lateinit var tvModelHint: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isVoiceModelReady = false
    @Volatile private var isTranslateModelReady = false

    private var resultCode = -1
    private var resultData: Intent? = null

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

        btnFloatPerm = findViewById(R.id.btnFloatPerm)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        progressDownload = findViewById(R.id.progressDownload)
        modelGroup = findViewById(R.id.modelGroup)
        rbSmall = findViewById(R.id.rbSmall)
        rbLarge = findViewById(R.id.rbLarge)
        tvModelHint = findViewById(R.id.tvModelHint)

        btnStart.isEnabled = false

        setupModelSelector()
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

    private fun setupModelSelector() {
        // 依儲存的偏好設定選中狀態
        val preferred = VoiceRecogHelper.getPreferredModel(this)
        if (preferred == VoiceRecogHelper.MODEL_LARGE) rbLarge.isChecked = true else rbSmall.isChecked = true
        updateModelHint()

        modelGroup.setOnCheckedChangeListener { _, checkedId ->
            val choice = if (checkedId == R.id.rbLarge) VoiceRecogHelper.MODEL_LARGE
                         else VoiceRecogHelper.MODEL_SMALL
            VoiceRecogHelper.setPreferredModel(this, choice)
            updateModelHint()
            // 切換模型：載入（已存在直接載入，不存在則下載）
            prepareVoiceModel(choice)
        }
    }

    private fun updateModelHint() {
        val choice = VoiceRecogHelper.getPreferredModel(this)
        tvModelHint.text = when {
            VoiceRecogHelper.isModelPresent(this, choice) -> "此模型已在本機，可直接使用"
            choice == VoiceRecogHelper.MODEL_LARGE -> "高精度模型約 1GB，切換後將開始下載（建議 Wi-Fi）"
            else -> "尚未下載，切換後將開始下載"
        }
    }

    private fun initModels() {
        updateDownloadStatus()

        // 翻译模型（MLKit，无逐字节进度，只有完成/失败回呼）
        TranslateHelper.init(this@MainActivity) { success ->
            mainHandler.post {
                isTranslateModelReady = true
                if (!success) {
                    Toast.makeText(
                        this@MainActivity,
                        "翻譯模型下載失敗，請確認已連接 Wi-Fi 後重新打開 App",
                        Toast.LENGTH_LONG
                    ).show()
                }
                updateDownloadStatus()
            }
        }

        // 语音辨识模型（Vosk）：載入使用者偏好的模型
        prepareVoiceModel(VoiceRecogHelper.getPreferredModel(this))
    }

    /** 載入指定語音模型：已存在則直接載入，否則下載（顯示進度）。 */
    private fun prepareVoiceModel(choice: String) {
        isVoiceModelReady = false
        val present = VoiceRecogHelper.isModelPresent(this, choice)
        btnStart.isEnabled = false
        btnStart.text = "模型準備中，請稍候..."
        if (present) {
            tvStatus.text = "狀態：正在載入語音模型..."
        } else {
            progressDownload.progress = 0
            tvStatus.text = "狀態：正在下載語音模型..."
        }

        VoiceRecogHelper.downloadModel(
            context = this@MainActivity,
            choice = choice,
            onProgress = { percent ->
                mainHandler.post {
                    progressDownload.progress = percent
                    tvStatus.text = "狀態：正在下載語音模型... $percent%"
                }
            },
            onDone = { success ->
                mainHandler.post {
                    isVoiceModelReady = success
                    if (!success) {
                        Toast.makeText(
                            this@MainActivity,
                            "語音模型下載/載入失敗，請確認網路後再試",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    updateModelHint()
                    updateDownloadStatus()
                }
            }
        )
    }

    private fun updateDownloadStatus() {
        if (isVoiceModelReady && isTranslateModelReady) {
            progressDownload.progress = 100
            tvStatus.text = "狀態：就緒"
            btnStart.isEnabled = true
            btnStart.text = "開始翻譯"
        } else {
            btnStart.isEnabled = false
            btnStart.text = "模型下載中，請稍候..."
            val parts = mutableListOf<String>()
            if (!isVoiceModelReady) parts.add("語音模型")
            if (!isTranslateModelReady) parts.add("翻譯模型")
            tvStatus.text = "狀態：正在下載 ${parts.joinToString("、")}..."
        }
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