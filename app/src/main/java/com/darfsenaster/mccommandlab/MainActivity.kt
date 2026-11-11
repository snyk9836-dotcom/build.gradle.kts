package com.darfsenaster.mccommandlab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    // غيّرها إذا اسم الحساب أو الريبو مختلف
    private val owner = "snyk9836-dotcom"
    private val repo = "build.gradle.kts"

    private lateinit var status: TextView
    private var forcedVersion = 0L
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val title = TextView(this).apply {
            text = "مختبر الأوامر"
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val hint = TextView(this).apply {
            text = "دائرة عايمة فوق ماينكرافت فيها كل الأوامر والسكريبتات.\nاضغط تشغيل، افتح ماينكرافت، الصق أمر الربط بالشات (ينتسخ تلقائياً)، وتصير الدائرة خضرة."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        }
        val start = Button(this).apply {
            text = "تشغيل الدائرة"
            setOnClickListener { startBubble() }
        }
        val stop = Button(this).apply {
            text = "إيقاف الدائرة"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }
        status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }

        root.addView(title)
        root.addView(hint)
        root.addView(start)
        root.addView(stop)
        root.addView(status)
        setContentView(root)

        checkUpdates()
    }

    // ---------- التحديثات ----------
    private fun checkUpdates() {
        status.text = "جاري فحص التحديثات..."
        Thread {
            var msg = ""
            // 1) تحديث الصفحة (تلقائي بدون تثبيت)
            try {
                val raw = "https://raw.githubusercontent.com/$owner/$repo/main/app/src/main/assets/index.html"
                val html = get(raw)
                if (html.contains("</html>") && html.length > 5000) {
                    val f = File(filesDir, "index.html")
                    val tmp = File(filesDir, "index.html.tmp")
                    tmp.writeText(html)
                    tmp.renameTo(f)
                    msg = "الصفحة محدّثة ✅"
                }
            } catch (_: Exception) {
                msg = "ما قدرت أحدّث الصفحة (تأكد من الإنترنت)"
            }

            // 2) تحديث التطبيق (يحتاج ضغطة تثبيت)
            var newer = 0L
            try {
                val j = JSONObject(get("https://api.github.com/repos/$owner/$repo/releases/latest"))
                val n = Regex("\\d+").find(j.optString("name"))?.value?.toLong() ?: 0L
                val cur = packageManager.getPackageInfo(packageName, 0).longVersionCode
                if (n > cur) newer = n
            } catch (_: Exception) {}

            runOnUiThread {
                status.text = msg
                if (newer > 0) {
                    forcedVersion = newer
                    showForceDialog()
                }
            }
        }.start()
    }

    // نافذة إجبارية: ما تنقفل إلا بالتحديث
    private fun showForceDialog() {
        if (isFinishing || dialog?.isShowing == true) return
        dialog = AlertDialog.Builder(this)
            .setTitle("تحديث جديد 🚀")
            .setMessage("نزلنا تحديث جديد للتطبيق (نسخة $forcedVersion).\nلازم تحمّله حتى تقدر تستخدم التطبيق.")
            .setCancelable(false)
            .setPositiveButton("تحديث", null)
            .create()
        dialog!!.setCanceledOnTouchOutside(false)
        dialog!!.show()
        // الزر ما يقفل النافذة بعد الضغط
        dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val apk = "https://github.com/$owner/$repo/releases/download/latest/app-debug.apk"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk)))
        }
    }

    override fun onResume() {
        super.onResume()
        if (forcedVersion > 0) showForceDialog()
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Cache-Control", "no-cache")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------- الدائرة ----------
    private fun startBubble() {
        if (forcedVersion > 0) { showForceDialog(); return }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل «العرض فوق التطبيقات الأخرى» ثم ارجع واضغط تشغيل", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        Toast.makeText(this, "ظهرت الدائرة ⚡ أمر الربط منسوخ، الصقه بماينكرافت", Toast.LENGTH_LONG).show()
    }
}
