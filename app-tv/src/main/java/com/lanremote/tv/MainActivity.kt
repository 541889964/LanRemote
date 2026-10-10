package com.lanremote.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var titleText: TextView
    private lateinit var ipText: TextView
    private lateinit var stateText: TextView
    private var server: WsServer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val CARD = Color.parseColor("#1E293B")
    private val ACCENT = Color.parseColor("#6366F1")
    private val TEXT = Color.parseColor("#F1F5F9")
    private val TEXT_DIM = Color.parseColor("#94A3B8")
    private val SUCCESS = Color.parseColor("#10B981")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val pv = PlayerView(this).apply {
            player = this@MainActivity.player
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(pv)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(CARD)
                setStroke(dp(1), ACCENT)
            }
            elevation = dp(10).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(28); leftMargin = dp(28) }
        }
        titleText = TextView(this).apply {
            text = "LanRemote TV"
            setTextColor(TEXT); textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        ipText = TextView(this).apply {
            text = "IP: ${getIp()}"
            setTextColor(TEXT_DIM); textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
        }
        stateText = TextView(this).apply {
            text = "● 等待手机连接…"
            setTextColor(ACCENT); textSize = 14f
        }
        card.addView(titleText); card.addView(ipText); card.addView(stateText)
        root.addView(card)
        setContentView(root)
        startServer()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun getIp(): String {
        return try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (_: Exception) { "?.?.?.?" }
    }

    private fun startServer() {
        server = WsServer(8765)
        server?.start()
    }

    inner class WsServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            handler.post {
                stateText.text = "● 已连接 · ${conn.remoteSocketAddress.address.hostAddress}"
                stateText.setTextColor(SUCCESS)
            }
        }
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            handler.post { stateText.text = "● 手机已断开"; stateText.setTextColor(ACCENT) }
        }
        override fun onMessage(conn: WebSocket, message: String) {
            try {
                val json = JSONObject(message)
                when (json.optString("type")) {
                    "key" -> handleKey(json.optString("key"))
                    "play" -> {
                        val url = json.optString("url")
                        val title = json.optString("title")
                        handler.post {
                            player.setMediaItem(MediaItem.fromUri(url))
                            player.prepare(); player.play()
                            titleText.text = title
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        override fun onError(conn: WebSocket?, ex: Exception?) {}
        override fun onStart() {}
    }

    private fun handleKey(key: String) {
        handler.post {
            when (key) {
                "DPAD_LEFT" -> player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                "DPAD_RIGHT" -> player.seekTo(player.currentPosition + 10000)
                "DPAD_CENTER", "PLAY_PAUSE" -> if (player.isPlaying) player.pause() else player.play()
                "VOLUME_UP" -> player.volume = (player.volume + 0.1f).coerceAtMost(1f)
                "VOLUME_DOWN" -> player.volume = (player.volume - 0.1f).coerceAtLeast(0f)
                "BACK", "HOME" -> {
                    player.stop(); player.clearMediaItems()
                    titleText.text = "LanRemote TV"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        server?.stop()
    }
}
