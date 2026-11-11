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
import android.widget.Toast
import android.widget.TextView
import kotlin.math.abs
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.UUID
import org.json.JSONObject

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var web: WebView
    private lateinit var bp: WindowManager.LayoutParams
    private lateinit var pp: WindowManager.LayoutParams
    private var open = false
    private var srv: WebSocketServer? = null
    @Volatile private var client: WebSocket? = null
    private val ui = Handler(Looper.getMainLooper())
    private var bubbleBg: GradientDrawable? = null
    private val connectCmd = "/connect ws://127.0.0.1:8080"

    private val dp: Float get() = resources.displayMetrics.density
    private val bubbleSize: Int get() = (56 * dp).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("فعّل «العرض فوق التطبيقات الأخرى» أول")
            stopSelf()
            return
        }
        startFg()
        startSrv()
        makePanel()
        try {
            makeBubble()
        } catch (e: Exception) {
            toast("ما كدرت أرسم الدائرة")
            stopSelf()
            return
        }
        copyConnect(false)
    }

    private fun toast(t: String) {
        ui.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }
    }

    // ينسخ أمر الربط حتى تلصقه بشات ماينكرافت
    private fun copyConnect(showToast: Boolean) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("connect", connectCmd))
            if (showToast) toast("اننسخ أمر الربط، الصقه بشات ماينكرافت")
        } catch (_: Exception) {}
    }

    // البرتقالي = بانتظار ماينكرافت، الأخضر = متصل
    private fun setState(connected: Boolean) {
        ui.post {
            bubbleBg?.setColor(if (connected) 0xFF3F9A62.toInt() else 0xFFD9822B.toInt())
        }
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
        try {
            clampBubble()
            wm.updateViewLayout(bubble, bp)
            if (open) placePanel(false)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { if (open) wm.removeView(panel) } catch (_: Exception) {}
        try { wm.removeView(bubble) } catch (_: Exception) {}
        try { web.destroy() } catch (_: Exception) {}
        try { srv?.stop(100) } catch (_: Exception) {}
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
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFD9822B.toInt())
                setStroke((3 * dp).toInt(), Color.WHITE)
            }
            bubbleBg = bg
            background = bg
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
        val link = TextView(this).apply {
            text = "ربط"
            setTextColor(0xFF9BE3B4.toInt())
            textSize = 14f
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { copyConnect(true) }
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { toggle() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(link)
        bar.addView(quit)
        bar.addView(close)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF15171C.toInt())
            addJavascriptInterface(Bridge(), "AB")
            settings.allowFileAccess = true
            val updated = java.io.File(filesDir, "index.html")
            loadUrl(if (updated.exists()) "file://" + updated.absolutePath else "file:///android_asset/index.html")
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
            try {
                wm.addView(panel, pp)
                open = true
            } catch (e: Exception) {
                toast("ما كدرت أفتح القائمة")
            }
        }
    }

    // ---------- سيرفر الاتصال بماينكرافت ----------
    private fun startSrv() {
        try {
            srv = object : WebSocketServer(InetSocketAddress("127.0.0.1", 8080)) {
                override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                    // صفحات المتصفح ترسل Origin، ماينكرافت ما يرسله: نرفض المتصفح
                    val origin = try { handshake?.getFieldValue("Origin") ?: "" } catch (_: Exception) { "" }
                    if (origin.startsWith("http")) { conn?.close(); return }
                    client = conn
                    setState(true)
                }
                override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                    if (client == conn) { client = null; setState(false) }
                }
                override fun onMessage(conn: WebSocket?, message: String?) {}
                override fun onError(conn: WebSocket?, ex: Exception?) {
                    if (conn == null) toast("المنفذ 8080 مشغول أو ما اشتغل السيرفر، اقفل التطبيق وافتحه من جديد")
                }
                override fun onStart() {}
            }.also {
                it.isReuseAddr = true
                it.connectionLostTimeout = 15
                it.start()
            }
        } catch (e: Exception) {
            toast("ما اشتغل السيرفر: " + (e.message ?: ""))
        }
    }

    private fun setFocus(on: Boolean) {
        pp.flags = if (on) pp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else pp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (open) wm.updateViewLayout(panel, pp)
    }

    // ---------- جسر بين الصفحة والتطبيق ----------
    inner class Bridge {
        @JavascriptInterface
        fun run(cmd: String): String {
            val c = client
            if (c == null || !c.isOpen) return "no-game"
            val o = JSONObject()
                .put("header", JSONObject().put("version", 1).put("requestId", UUID.randomUUID().toString())
                    .put("messageType", "commandRequest").put("messagePurpose", "commandRequest"))
                .put("body", JSONObject().put("version", 1).put("commandLine", cmd.trim().removePrefix("/"))
                    .put("origin", JSONObject().put("type", "player")))
            c.send(o.toString())
            return "sent"
        }

        @JavascriptInterface
        fun status(): String = if (client?.isOpen == true) "connected" else "waiting"

        @JavascriptInterface
        fun paste() {
            ui.post {
                setFocus(true)
                ui.postDelayed({
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@OverlayService)?.toString() ?: ""
                    setFocus(false)
                    web.evaluateJavascript("window.__pasted(" + JSONObject.quote(t) + ")", null)
                }, 250)
            }
        }

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
