package com.lanremote.mobile

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
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
import kotlinx.coroutines.*
import okhttp3.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var connected = false
    private var autoRunning = false
    private var flowRunning = false

    private val client = OkHttpClient.Builder()
        .pingInterval(3, TimeUnit.SECONDS).build()

    private val BG = Color.parseColor("#0F172A")
    private val CARD = Color.parseColor("#1E293B")
    private val CARD_LIGHT = Color.parseColor("#334155")
    private val ACCENT = Color.parseColor("#6366F1")
    private val ACCENT_LIGHT = Color.parseColor("#818CF8")
    private val PURPLE = Color.parseColor("#8B5CF6")
    private val TEXT = Color.parseColor("#F1F5F9")
    private val TEXT_DIM = Color.parseColor("#94A3B8")
    private val SUCCESS = Color.parseColor("#10B981")

    // 三个主视图
    private lateinit var scanView: LinearLayout
    private lateinit var listView: LinearLayout
    private lateinit var remoteView: LinearLayout

    // scanView 元素
    private lateinit var spinner: SpinnerView
    private lateinit var scanIcon: TextView
    private lateinit var statusText: TextView
    private lateinit var subText: TextView

    // listView 元素
    private lateinit var listContainer: LinearLayout
    private lateinit var listTitle: TextView

    // remoteView 元素
    private lateinit var connInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        autoRunning = true
        startAutoFlow()
    }

    private fun buildRoot(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        scanView = buildScanView()
        listView = buildListView().apply { visibility = View.GONE }
        remoteView = buildRemoteView().apply { visibility = View.GONE }
        root.addView(scanView)
        root.addView(listView)
        root.addView(remoteView)
        return root
    }

    // ==================== 扫描视图 ====================
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
            text = "📡"; textSize = 60f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        iconWrap.addView(spinner); iconWrap.addView(scanIcon)
        container.addView(iconWrap); container.addView(space(40))

        statusText = TextView(this).apply {
            text = "正在扫描局域网…"; setTextColor(TEXT); textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(statusText); container.addView(space(12))

        subText = TextView(this).apply {
            text = "正在寻找已运行的 LanRemote TV…"
            setTextColor(TEXT_DIM); textSize = 13f
            gravity = Gravity.CENTER; setLineSpacing(6f, 1f)
        }
        container.addView(subText)
        return container
    }

    // ==================== 设备列表视图 ====================
    private fun buildListView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(60), dp(20), dp(20))
        }

        container.addView(TextView(this).apply {
            text = "LanRemote"; setTextColor(TEXT); textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        listTitle = TextView(this).apply {
            text = "发现 0 个设备"; setTextColor(TEXT_DIM); textSize = 13f
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(30))
        }
        container.addView(listTitle)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listContainer)

        container.addView(space(20))

        val rescanBtn = TextView(this).apply {
            text = "重新扫描"
            setTextColor(Color.WHITE); textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = gradientBg()
            setOnClickListener {
                listView.visibility = View.GONE
                scanView.visibility = View.VISIBLE
                startScan()
            }
        }
        container.addView(rescanBtn)

        return container
    }

    private fun buildDeviceCard(ip: String, name: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                cardBg(), null
            )
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        card.addView(TextView(this).apply {
            text = "📺"; textSize = 32f
            setPadding(0, 0, dp(16), 0)
        })

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = name; setTextColor(TEXT); textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        info.addView(TextView(this).apply {
            text = ip; setTextColor(TEXT_DIM); textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        card.addView(info)

        card.addView(TextView(this).apply {
            text = "连接 ▶"; setTextColor(ACCENT_LIGHT); textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        card.setOnClickListener { connectTo(ip) }
        return card
    }

    // ==================== 遥控视图 ====================
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

    // ==================== 自动流程 ====================
    private fun startAutoFlow() {
        if (flowRunning) return
        flowRunning = true
        scope.launch {
            try {
                scanView.visibility = View.VISIBLE
                listView.visibility = View.GONE
                remoteView.visibility = View.GONE
                updateScanState("正在扫描局域网…", "寻找已运行的 LanRemote TV…", "📡")

                val devices = scanLanDevices()

                when {
                    devices.isEmpty() -> {
                        updateScanState(
                            "未发现电视",
                            "请确认：\n1. 电视上已安装并打开 LanRemote TV\n2. 手机和电视在同一 Wi-Fi\n\n5 秒后自动重试…",
                            "🔍"
                        )
                        delay(5000)
                        flowRunning = false
                        startAutoFlow()
                    }
                    devices.size == 1 -> {
                        // 只有一个：自动连
                        updateScanState("发现 1 个设备", "自动连接 ${devices[0]}…", "✅")
                        delay(300)
                        connectTo(devices[0])
                    }
                    else -> {
                        // 多个：显示列表
                        showDeviceList(devices)
                    }
                }
            } finally {
                flowRunning = false
            }
        }
    }

    private fun startScan() {
        if (flowRunning) return
        startAutoFlow()
    }

    private suspend fun scanLanDevices(): List<String> = withContext(Dispatchers.IO) {
        val myIp = getLocalIp()
        val prefix = myIp.substringBeforeLast(".")
        val found = mutableListOf<String>()

        val lastIp = getPrefs().getString("last_ip", null)
        if (lastIp != null && testPort(lastIp, 8765)) found.add(lastIp)

        val jobs = (1..254).map { i ->
            async {
                val ip = "$prefix.$i"
                if (ip != lastIp && testPort(ip, 8765)) {
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

    private fun showDeviceList(devices: List<String>) {
        handler.post {
            scanView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            remoteView.visibility = View.GONE
            listTitle.text = "发现 ${devices.size} 个设备"
            listContainer.removeAllViews()
            devices.forEachIndexed { idx, ip ->
                val name = "电视 ${idx + 1}"
                listContainer.addView(buildDeviceCard(ip, name))
            }
        }
    }

    private fun connectTo(ip: String) {
        handler.post {
            scanView.visibility = View.VISIBLE
            listView.visibility = View.GONE
            remoteView.visibility = View.GONE
            updateScanState("正在连接…", ip, "📡")
        }

        scope.launch {
            val deferred = CompletableDeferred<Boolean>()
            handler.post {
                val req = Request.Builder().url("ws://$ip:8765").build()
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        connected = true
                        deferred.complete(true)
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
            if (ok) {
                getPrefs().edit().putString("last_ip", ip).apply()
                handler.post {
                    scanView.visibility = View.GONE
                    listView.visibility = View.GONE
                    remoteView.visibility = View.VISIBLE
                    connInfo.text = "● 已连接 · $ip"
                }
            } else {
                updateScanState("连接失败", "5 秒后重新扫描…", "❌")
                delay(5000)
                startAutoFlow()
            }
        }
    }

    private fun updateScanState(main: String, sub: String, icon: String) {
        handler.post {
            statusText.text = main
            subText.text = sub
            scanIcon.text = icon
            spinner.visibility = if (icon == "📡" || icon == "🔍") View.VISIBLE else View.GONE
        }
    }

    // ==================== UI 辅助 ====================
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

    private fun cardBg() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(CARD)
        setStroke(dp(1), CARD_LIGHT)
    }

    private fun gradientBg() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(ACCENT, PURPLE)
    ).apply { cornerRadius = dp(14).toFloat() }

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
