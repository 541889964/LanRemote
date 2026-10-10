package com.lanremote.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var ipInput: EditText
    private lateinit var connectBtn: TextView
    private var retryCount = 0
    private var running = false
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
    private val ERROR = Color.parseColor("#EF4444")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
    }

    private fun buildUI(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "LanRemote"
            setTextColor(TEXT)
            textSize = 32f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "局域网投屏遥控器"
            setTextColor(TEXT_DIM)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        })

        root.addView(buildConnectCard())
        root.addView(space(16))
        root.addView(buildStatusRow())
        root.addView(space(20))
        root.addView(buildDPad())
        root.addView(space(20))
        root.addView(buildFuncRow())

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(root)
        }
    }

    private fun buildConnectCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(20), dp(20), dp(20), dp(20))
            elevation = dp(8).toFloat()
        }

        card.addView(TextView(this).apply {
            text = "连接设备"
            setTextColor(TEXT)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        card.addView(space(12))

        ipInput = EditText(this).apply {
            hint = "输入电视 IP，如 192.168.1.100"
            setHintTextColor(TEXT_DIM)
            setTextColor(TEXT)
            textSize = 15f
            background = inputBg()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setSingleLine()
        }
        card.addView(ipInput)
        card.addView(space(12))

        connectBtn = TextView(this).apply {
            text = "连 接"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = gradientBg()
            setPadding(0, dp(16), 0, dp(16))
            elevation = dp(6).toFloat()
            setOnClickListener { toggleConnect() }
        }
        card.addView(connectBtn)

        return card
    }

    private fun buildStatusRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        statusDot = View(this).apply {
            background = circle(ERROR, dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(10)
            }
        }
        statusText = TextView(this).apply {
            text = "未连接"
            setTextColor(TEXT_DIM)
            textSize = 13f
        }
        row.addView(statusDot)
        row.addView(statusText)
        return row
    }

    private fun buildDPad(): View {
        val wrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280))
        }

        val btnSize = dp(72)
        val gap = dp(6)

        fun place(view: View, cx: Int, cy: Int) {
            view.layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
                leftMargin = cx
                topMargin = cy
            }
            wrapper.addView(view)
        }

        place(makeCircleBtn("▲") { sendKey("DPAD_UP") }, 0, -btnSize - gap)
        place(makeCircleBtn("▼") { sendKey("DPAD_DOWN") }, 0, btnSize + gap)
        place(makeCircleBtn("◀") { sendKey("DPAD_LEFT") }, -btnSize - gap, 0)
        place(makeCircleBtn("▶") { sendKey("DPAD_RIGHT") }, btnSize + gap, 0)
        place(makeCircleBtn("OK", big = true) { sendKey("DPAD_CENTER") }, 0, 0)

        return wrapper
    }

    private fun buildFuncRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        listOf(
            Triple("← 返回", "BACK", CARD_LIGHT),
            Triple("⌂ 主页", "HOME", CARD_LIGHT),
            Triple("⏯ 暂停", "PLAY_PAUSE", ACCENT)
        ).forEach { (label, key, color) ->
            row.addView(makeChip(label, color) { sendKey(key) })
        }
        return row
    }

    private fun makeCircleBtn(text: String, big: Boolean = false, onClick: () -> Unit): View {
        return TextView(this).apply {
            this.text = text
            setTextColor(TEXT)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                circle(if (big) ACCENT else CARD_LIGHT, dp(72), stroke = true),
                null
            )
            elevation = dp(4).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun makeChip(label: String, color: Int, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label
            setTextColor(TEXT)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = chipBg(color)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4); marginStart = dp(4) }
            setOnClickListener { onClick() }
        }
    }

    private fun cardBg() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(CARD)
        setStroke(dp(1), CARD_LIGHT)
    }

    private fun inputBg() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor("#0F172A"))
        setStroke(dp(1), CARD_LIGHT)
    }

    private fun gradientBg() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(ACCENT, PURPLE)
    ).apply { cornerRadius = dp(14).toFloat() }

    private fun chipBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(color)
    }

    private fun circle(color: Int, size: Int, stroke: Boolean = false) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke) setStroke(dp(2), ACCENT_LIGHT)
    }

    private fun space(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toggleConnect() {
        if (running) {
            running = false
            ws?.close(1000, "user disconnect")
            ws = null
            setStatus("已断开", ERROR)
            connectBtn.text = "连 接"
            return
        }
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入 IP", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        retryCount = 0
        connectBtn.text = "断开连接"
        doConnect(ip)
    }

    private fun doConnect(ip: String) {
        setStatus("连接中...", Color.parseColor("#F59E0B"))
        val req = Request.Builder().url("ws://$ip:8765").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                retryCount = 0
                handler.post { setStatus("已连接 · $ip", SUCCESS) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { setStatus("连接失败，重试中...", ERROR) }
                scheduleReconnect(ip)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { setStatus("已断开", TEXT_DIM) }
                if (running) scheduleReconnect(ip)
            }
        })
    }

    private fun scheduleReconnect(ip: String) {
        if (!running) return
        val delay = minOf(30_000L, 1000L * (1 shl minOf(retryCount, 5)))
        retryCount++
        handler.postDelayed({ if (running) doConnect(ip) }, delay)
    }

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        (statusDot.background as? GradientDrawable)?.setColor(color)
    }

    private fun sendKey(key: String) {
        ws?.send("""{"type":"key","key":"$key"}""")
        try {
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(10)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        ws?.close(1000, "bye")
    }
}
