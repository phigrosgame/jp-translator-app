package com.jptranslator.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var tvJapanese: TextView
    private lateinit var tvChinese: TextView
    private lateinit var tvHistory: TextView
    private lateinit var scrollView: ScrollView

    // 拖拽相关
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    companion object {
        // 更新翻译结果的回调
        var updateCallback: ((String, String) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingWindow()

        // 注册回调
        updateCallback = { japanese, chinese ->
            updateTranslation(japanese, chinese)
        }
    }

    private fun showFloatingWindow() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_window, null)

        tvJapanese = floatingView!!.findViewById(R.id.tvJapanese)
        tvChinese = floatingView!!.findViewById(R.id.tvChinese)
        tvHistory = floatingView!!.findViewById(R.id.tvHistory)
        scrollView = floatingView!!.findViewById(R.id.scrollView)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params!!.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params!!.y = 100

        windowManager.addView(floatingView, params)

        // 设置拖拽
        setupDrag()

        // 关闭按钮
        floatingView!!.findViewById<View>(R.id.btnClose).setOnClickListener {
            stopSelf()
        }
    }

    private fun setupDrag() {
        floatingView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params!!.x
                    initialY = params!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params!!.x = initialX + dx
                        params!!.y = initialY + dy
                        windowManager.updateViewLayout(floatingView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateTranslation(japanese: String, chinese: String) {
        floatingView?.post {
            // 更新当前显示
            tvJapanese.text = japanese
            tvChinese.text = chinese

            // 添加到历史
            val history = tvHistory.text.toString()
            val newHistory = buildString {
                if (history.isNotEmpty()) {
                    append(history)
                    append("\n\n")
                }
                append("【日】$japanese")
                append("\n")
                append("【中】$chinese")
            }
            tvHistory.text = newHistory

            // 自动滚动到底部
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateCallback = null
        floatingView?.let {
            windowManager.removeView(it)
            floatingView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
