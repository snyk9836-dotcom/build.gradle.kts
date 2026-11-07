package com.darfsenaster.mccommandlab

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var web: WebView
    private lateinit var bp: WindowManager.LayoutParams
    private lateinit var pp: WindowManager.LayoutParams
    private var open = false
    private val ui = Handler(Looper.getMainLooper())

    private val dp: Float get() = resources.displayMetrics.density
    private val bubbleSize: Int get() = (56 * dp).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startFg()
        makePanel()
        makeBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clampBubble()
        wm.updateViewLayout(bubble, bp)
        if (open) placePanel(false)
    }

    override fun onDestroy() {
        try { if (open) wm.removeView(panel) } catch (_: Exception) {}
        try { wm.removeView(bubble) } catch (_: Exception) {}
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------- إشعار الخدمة ----------
    @Suppress("DEPRECATION")
    private fun startFg() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("overlay", "الدائرة العايمة", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("مختبر الأوامر شغال")
            .setContentText("اضغط إيقاف لإخفاء الدائرة")
            .addAction(android.R.drawable.ic_delete, "إيقاف", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- قياس الشاشة ----------
    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun clampBubble() {
        val (sw, sh) = screen()
        bp.x = bp.x.coerceIn(0, sw - bubbleSize)
        bp.y = bp.y.coerceIn(0, sh - bubbleSize)
    }

    // ---------- الدائرة ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun makeBubble() {
        val size = bubbleSize
        bubble = TextView(this).apply {
            text = "⚡"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFD9822B.toInt())
                setStroke((3 * dp).toInt(), Color.WHITE)
            }
            elevation = 8 * dp
        }
        val (sw, sh) = screen()
        bp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - size - (8 * dp).toInt()
            y = (sh * 0.4).toInt()
        }

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = bp.x; oy = bp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (abs(dx) + abs(dy) > 10 * dp) moved = true
                    if (moved) {
                        bp.x = (ox + dx).toInt()
                        bp.y = (oy + dy).toInt()
                        clampBubble()
                        wm.updateViewLayout(bubble, bp)
                        if (open) placePanel(false)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggle()
                    true
                }
                else -> false
            }
        }
        wm.addView(bubble, bp)
    }

    // ---------- القائمة (الصفحة) ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun makePanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF15171C.toInt())
                cornerRadius = 14 * dp
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1F2229.toInt())
            setPadding((12 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        val title = TextView(this).apply {
            text = "مختبر الأوامر"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val quit = TextView(this).apply {
            text = "إيقاف"
            setTextColor(0xFFFF8A80.toInt())
            textSize = 14f
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { stopSelf() }
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { toggle() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(quit)
        bar.addView(close)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF15171C.toInt())
            addJavascriptInterface(Bridge(), "AB")
            loadUrl("file:///android_asset/index.html")
        }

        panel.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (44 * dp).toInt()))
        panel.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun placePanel(first: Boolean) {
        val (sw, sh) = screen()
        val w = minOf((sw * 0.92).toInt(), (560 * dp).toInt())
        val h = (sh * 0.7).toInt()
        pp.width = w
        pp.height = h
        pp.x = (bp.x + bubbleSize / 2 - w / 2).coerceIn(0, maxOf(0, sw - w))
        val gap = (8 * dp).toInt()
        pp.y = if (bp.y > sh / 2) {
            (bp.y - h - gap).coerceAtLeast(0)
        } else {
            (bp.y + bubbleSize + gap).coerceAtMost(maxOf(0, sh - h))
        }
        if (!first) wm.updateViewLayout(panel, pp)
    }

    private fun toggle() {
        if (open) {
            wm.removeView(panel)
            open = false
        } else {
            placePanel(true)
            wm.addView(panel, pp)
            open = true
        }
    }

    // ---------- جسر بين الصفحة والتطبيق ----------
    inner class Bridge {
        @JavascriptInterface
        fun copy(text: String) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cmd", text))
        }

        // نفتح التركيز فقط وقت الكتابة (كي ما تتوقف اللعبة طول الوقت)
        @JavascriptInterface
        fun setFocusable(on: Boolean) {
            ui.post {
                pp.flags = if (on) {
                    pp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    pp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                if (open) wm.updateViewLayout(panel, pp)
            }
        }
    }
}
