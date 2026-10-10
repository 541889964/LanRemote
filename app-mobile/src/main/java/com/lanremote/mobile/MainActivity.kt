package com.lanremote.mobile

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.*
import dadb.Dadb
import kotlinx.coroutines.*
import okhttp3.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var connected = false
    private var currentIp = ""
    private var autoRunning = false
    private var flowRunning = false

    private val client = OkHttpClient.Builder()
        .pingInterval(3, TimeUnit.SECONDS).build()

    private val BG = Color.parseColor("#0F172A")
    private val CARD_LIGHT = Color.parseColor("#334155")
    private val ACCENT = Color.parseColor("#6366F1")
    private val ACCENT_LIGHT = Color.parseColor("#818CF8")
    private val TEXT = Color.parseColor("#F1F5F9")
    private val TEXT_DIM = Color.parseColor("#94A3B8")
    private val SUCCESS = Color.parseColor("#10B981")

    private lateinit var scanView: LinearLayout
    private lateinit var remoteView: LinearLayout
    private lateinit var spinner: SpinnerView
    private lateinit var scanIcon: TextView
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var connInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ============================================================
        //  关键修复：让 dadb 用 App 私有目录存 adbkey
        //  默认情况下 dadb 会尝试写 ".android/adbkey"（相对路径），
        //  在 Android 上相当于 "/.android/adbkey"，没权限。
        //  把 user.home 指向 filesDir，预建 .android 目录。
        // ============================================================
        try {
            val adbDir = File(filesDir, ".android")
            if (!adbDir.exists()) adbDir.mkdirs()
            System.setProperty("user.home", filesDir.absolutePath)
            System.setProperty("user.dir", filesDir.absolutePath)
        } catch (_: Exception) {}

        setContentView(buildRoot())
        autoRunning = true
        startAutoFlow()
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        scanView = buildScanView()
        remoteView = buildRemoteView().apply { visibility = View.GONE }
        root.addView(scanView)
        root.addView(remoteView)
        return root
    }

    private fun buildScanView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val iconWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
        }
        spinner = SpinnerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        scanIcon = TextView(this).apply {
            text = "📺"; textSize = 60f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        iconWrap.addView(spinner); iconWrap.addView(scanIcon)
        container.addView(iconWrap); container.addView(space(40))

        statusText = TextView(this).apply {
            text = "正在启动…"; setTextColor(TEXT); textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(statusText); container.addView(space(12))

        subText = TextView(this).apply {
            text = "正在准备…"; setTextColor(TEXT_DIM); textSize = 13f
            gravity = Gravity.CENTER; setLineSpacing(6f, 1f)
        }
        container.addView(subText)
        return container
    }

    private fun buildRemoteView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(50), dp(20), dp(20))
        }
        container.addView(TextView(this).apply {
            text = "LanRemote"; setTextColor(TEXT); textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        connInfo = TextView(this).apply {
            text = ""; setTextColor(SUCCESS); textSize = 12f
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(36))
        }
        container.addView(connInfo)

        val dpad = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
        }
        val size = dp(76); val gap = dp(8)
        fun place(v: View, x: Int, y: Int) {
            v.layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER; leftMargin = x; topMargin = y
            }
            dpad.addView(v)
        }
        place(circleBtn("▲") { sendKey("DPAD_UP") }, 0, -size - gap)
        place(circleBtn("▼") { sendKey("DPAD_DOWN") }, 0, size + gap)
        place(circleBtn("◀") { sendKey("DPAD_LEFT") }, -size - gap, 0)
        place(circleBtn("▶") { sendKey("DPAD_RIGHT") }, size + gap, 0)
        place(circleBtn("OK", true) { sendKey("DPAD_CENTER") }, 0, 0)
        container.addView(dpad); container.addView(space(30))

        val funcs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; weightSum = 3f
        }
        listOf(
            Triple("← 返回", "BACK", CARD_LIGHT),
            Triple("⌂ 主页", "HOME", CARD_LIGHT),
            Triple("⏯ 暂停", "PLAY_PAUSE", ACCENT)
        ).forEach { (label, key, color) ->
            funcs.addView(chip(label, color) { sendKey(key) })
        }
        container.addView(funcs)
        return container
    }

    private fun startAutoFlow() {
        if (flowRunning) return
        flowRunning = true
        scope.launch {
            try {
                while (autoRunning && !connected) {
                    updateScanState("正在扫描局域网…", "寻找可连接的电视设备", "📺")
                    val devices = scanLanDevices()
                    if (devices.isEmpty()) {
                        updateScanState("未发现设备",
                            "请确认：\n1. 电视已开启开发者选项\n2. 已打开 USB 调试（网络调试）\n\n5 秒后自动重试…",
                            "🔍")
                        delay(5000); continue
                    }
                    var deployed = false
                    for (ip in devices) {
                        try {
                            updateScanState("等待电视授权",
                                "⚠️ 请现在看电视屏幕！\n用电视遥控器点「允许」\n（务必勾选「始终允许」）",
                                "📡")
                            deployToDevice(ip)
                            deployed = true; currentIp = ip; break
                        } catch (e: Exception) {
                            updateScanState("连接 $ip 失败",
                                "原因: ${e.message ?: "未知"}\n\n如提示授权被拒，请到电视\n开发者选项撤销 USB 调试授权",
                                "❌")
                            delay(3000); continue
                        }
                    }
                    if (!deployed) {
                        updateScanState("所有设备连接失败",
                            "10 秒后重新扫描…\n\n1. 电视 → 开发者选项 → 撤销授权\n2. 重启 App，盯着电视点允许",
                            "❌")
                        delay(10000); continue
                    }
                    updateScanState("正在建立遥控通道…", "等待电视端启动…", "📡")
                    connectWebSocketSync(currentIp)
                }
            } finally { flowRunning = false }
        }
    }

    private suspend fun scanLanDevices(): List<String> = withContext(Dispatchers.IO) {
        val myIp = getLocalIp()
        val prefix = myIp.substringBeforeLast(".")
        val found = mutableListOf<String>()
        val lastIp = getPrefs().getString("last_ip", null)
        if (lastIp != null && testPort(lastIp, 5555)) found.add(lastIp)
        val jobs = (1..254).map { i ->
            async {
                val ip = "$prefix.$i"
                if (ip != lastIp && testPort(ip, 5555)) {
                    synchronized(found) { found.add(ip) }
                }
            }
        }
        jobs.awaitAll()
        found.sortedByDescending { it == lastIp }
    }

    private fun testPort(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 250) }; true
    } catch (_: Exception) { false }

    private suspend fun deployToDevice(ip: String) = withContext(Dispatchers.IO) {
        val dadb: Dadb = try {
            withTimeout(60_000L) { Dadb.create(ip, 5555) }
        } catch (e: TimeoutCancellationException) {
            throw Exception("等待授权超时")
        } catch (e: Exception) {
            throw Exception("ADB连接失败: ${e.message ?: "未知"}")
        }
        try {
            val model = try { dadb.shell("getprop ro.product.model").output.trim() }
                        catch (_: Exception) { "" }
            if (model.isEmpty()) throw Exception("设备无响应")
            withContext(Dispatchers.Main) {
                updateScanState("已连接设备", "型号: $model", "✅")
            }
            val output = dadb.shell("pm list packages com.lanremote.tv").output
            val installed = output.contains("com.lanremote.tv")
            if (!installed) {
                withContext(Dispatchers.Main) {
                    updateScanState("正在部署电视端",
                        "⚠️ 电视上请用遥控器点「安装」", "📦")
                }
                val tempApk = File(cacheDir, "lanremote-tv.apk")
                if (!tempApk.exists() || tempApk.length() == 0L) {
                    assets.open("lanremote-tv.apk").use { input ->
                        tempApk.outputStream().use { input.copyTo(it) }
                    }
                }
                dadb.install(tempApk)
                withContext(Dispatchers.Main) {
                    updateScanState("部署完成", "正在启动电视端…", "📦")
                }
                delay(3000)
            }
            dadb.shell("am start -n com.lanremote.tv/.MainActivity")
            getPrefs().edit().putString("last_ip", ip).apply()
        } finally {
            try { dadb.close() } catch (_: Exception) {}
        }
    }

    private suspend fun connectWebSocketSync(ip: String) {
        var attempts = 0
        while (autoRunning && !connected && attempts < 30) {
            val deferred = CompletableDeferred<Boolean>()
            handler.post {
                val req = Request.Builder().url("ws://$ip:8765").build()
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected = true; deferred.complete(true)
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!deferred.isCompleted) deferred.complete(false)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (connected) {
                            connected = false
                            handler.post {
                                scanView.visibility = View.VISIBLE
                                remoteView.visibility = View.GONE
                                if (autoRunning) startAutoFlow()
                            }
                        }
                    }
                })
            }
            val ok = deferred.await()
            if (ok) { onConnected(ip); return }
            attempts++
            updateScanState("正在建立遥控通道…", "第 $attempts 次尝试…", "📡")
            delay(1500)
        }
        updateScanState("无法连接电视端", "5 秒后重新开始…", "❌")
        delay(5000)
        flowRunning = false
        startAutoFlow()
    }

    private fun onConnected(ip: String) {
        handler.post {
            scanView.visibility = View.GONE
            remoteView.visibility = View.VISIBLE
            connInfo.text = "● 已连接 · $ip"
        }
    }

    private fun updateScanState(main: String, sub: String, icon: String) {
        handler.post {
            statusText.text = main
            subText.text = sub
            scanIcon.text = icon
            spinner.visibility = if (icon == "📺" || icon == "🔍") View.VISIBLE else View.GONE
        }
    }

    private fun circleBtn(text: String, big: Boolean = false, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text; setTextColor(TEXT); textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (big) ACCENT else CARD_LIGHT)
                setStroke(dp(2), ACCENT_LIGHT)
            }
            elevation = dp(4).toFloat()
            setOnClickListener { onClick() }
        }

    private fun chip(label: String, color: Int, onClick: () -> Unit): View =
        TextView(this).apply {
            text = label; setTextColor(TEXT); textSize = 14f
            gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat(); setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4); marginStart = dp(4) }
            setOnClickListener { onClick() }
        }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun getLocalIp(): String = try {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    } catch (_: Exception) { "192.168.1.1" }

    private fun getPrefs(): SharedPreferences =
        getSharedPreferences("lan_remote", Context.MODE_PRIVATE)

    private fun sendKey(key: String) {
        ws?.send("""{"type":"key","key":"$key"}""")
        try { (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(10) }
        catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        autoRunning = false; connected = false
        ws?.close(1000, "bye"); scope.cancel()
    }

    class SpinnerView(context: Context) : View(context) {
        private var angle = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#6366F1")
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10f
            color = Color.parseColor("#334155")
        }
        init {
            ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1200; repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(cx, cy) - 15f
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), angle, 90f, false, paint)
        }
    }
}
