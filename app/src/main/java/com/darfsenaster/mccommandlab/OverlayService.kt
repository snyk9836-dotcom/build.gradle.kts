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
    @Volatile private var lastPos: String = ""
    @Volatile private var posReq: String = ""
    private val ui = Handler(Looper.getMainLooper())

    private val dp: Float get() = resources.displayMetrics.density
    private val bubbleSize: Int get() = (56 * dp).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startFg()
        startSrv()
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
        ui.removeCallbacks(keepAlive)
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
            wm.addView(panel, pp)
            open = true
        }
    }

    // ---------- سيرفر الاتصال بماينكرافت ----------
    private fun toast(m: String) {
        ui.post { try { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {} }
    }

    private fun paintBubble(connected: Boolean) {
        ui.post {
            try {
                (bubble.background as? GradientDrawable)?.setColor(
                    if (connected) 0xFF2E9E5B.toInt() else 0xFFD9822B.toInt()
                )
            } catch (_: Exception) {}
        }
    }

    // نقرأ جواب querytarget حتى نعرف موقع اللاعب
    private fun handleMsg(m: String?) {
        try {
            if (m == null) return
            val j = JSONObject(m)
            val id = j.optJSONObject("header")?.optString("requestId") ?: return
            if (id != posReq) return
            val d = j.optJSONObject("body")?.opt("details")
            val arr = when (d) {
                is String -> org.json.JSONArray(d)
                is org.json.JSONArray -> d
                else -> null
            }
            val e = arr?.optJSONObject(0) ?: return
            val p = e.optJSONObject("position") ?: return
            lastPos = JSONObject().put("x", p.optDouble("x")).put("y", p.optDouble("y")).put("z", p.optDouble("z"))
                .put("dim", e.optInt("dimension")).put("yRot", e.optDouble("yRot")).toString()
        } catch (_: Exception) {}
    }

    private fun startSrv() {
        try {
            srv = object : WebSocketServer(InetSocketAddress("127.0.0.1", 8080)) {
                override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
                    // اتصال جديد يحل محل القديم بدون ما يسكّر الجديد بالغلط
                    val old = client
                    client = conn
                    if (old != null && old != conn) try { old.close() } catch (_: Exception) {}
                    paintBubble(true)
                }
                override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
                    if (client == conn) {
                        client = null
                        paintBubble(false)
                        toast("انقطع الاتصال (code=$code، " + (if (remote) "من ماينكرافت" else "من التطبيق") + ")")
                    }
                }
                override fun onMessage(conn: WebSocket?, message: String?) { handleMsg(message) }
                override fun onError(conn: WebSocket?, ex: Exception?) {
                    if (conn == null) toast("خطأ بالسيرفر: " + (ex?.message ?: "غير معروف"))
                }
                override fun onStart() {
                    // لا نقطع الاتصال بسبب غياب الـ pong (ماينكرافت يتوقف لما تفتح القائمة)
                    connectionLostTimeout = 0
                }
            }.also { it.isReuseAddr = true; it.connectionLostTimeout = 0; it.start() }
            startKeepAlive()
        } catch (e: Exception) {
            toast("السيرفر ما اشتغل: " + (e.message ?: ""))
        }
    }

    // نبض خفيف كل 20 ثانية حتى ما يعتبر ماينكرافت الاتصال خامل
    private val keepAlive = object : Runnable {
        override fun run() {
            try { client?.takeIf { it.isOpen }?.sendPing() } catch (_: Exception) {}
            ui.postDelayed(this, 20_000)
        }
    }
    private fun startKeepAlive() { ui.removeCallbacks(keepAlive); ui.postDelayed(keepAlive, 20_000) }

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
        fun queryPos(): String {
            val c = client
            if (c == null || !c.isOpen) return "no-game"
            lastPos = ""
            posReq = UUID.randomUUID().toString()
            val o = JSONObject()
                .put("header", JSONObject().put("version", 1).put("requestId", posReq)
                    .put("messageType", "commandRequest").put("messagePurpose", "commandRequest"))
                .put("body", JSONObject().put("version", 1).put("commandLine", "querytarget @s")
                    .put("origin", JSONObject().put("type", "player")))
            c.send(o.toString())
            return "sent"
        }

        @JavascriptInterface
        fun pos(): String = lastPos

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
                if (on && open) {
                    // نطلّع كيبورد الهاتف بعد ما النافذة تاخذ التركيز
                    ui.postDelayed({
                        try {
                            web.requestFocus()
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        } catch (_: Exception) {}
                    }, 120)
                }
            }
        }
    }
}
