package com.moon.location.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.moon.location.ModuleStatus
import com.moon.location.config.StatusReader
import com.moon.location.engine.AmapSearch
import com.moon.location.service.SpoofService
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * M3 control surface: WebView + Leaflet + 高德瓦片 (map display) with AMap
 * Web-service REST search. Click the map or search to pick a location, then start.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var pick: TextView

    private var pickedLat = 39.9087
    private var pickedLng = 116.3975

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 2000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (10 * density).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = TextView(this).apply {
            textSize = 12f
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0x11000000)
        }
        root.addView(status)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Android")
            loadUrl("file:///android_asset/map.html")
        }
        root.addView(webView)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        pick = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "选点: —"
        }
        bar.addView(pick)
        bar.addView(Button(this).apply {
            text = "开始模拟"
            setOnClickListener {
                SpoofService.start(this@MainActivity, pickedLat, pickedLng)
                refreshStatus()
            }
        })
        bar.addView(Button(this).apply {
            text = "停止"
            setOnClickListener {
                SpoofService.stop(this@MainActivity)
                refreshStatus()
            }
        })
        root.addView(bar)

        setContentView(root)
    }

    /** JS bridge exposed to map.html as `Android`. */
    private inner class Bridge {
        @JavascriptInterface
        fun onPick(lat: Double, lng: Double) {
            pickedLat = lat
            pickedLng = lng
            main.post { pick.text = "选点: %.6f, %.6f".format(lat, lng) }
        }

        @JavascriptInterface
        fun search(query: String) {
            io.execute {
                val pois = AmapSearch.search(this@MainActivity, query)
                val json = AmapSearch.toJson(pois)
                main.post {
                    webView.evaluateJavascript(
                        "window.onSearchResult(${JSONObject.quote(json)})", null,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        main.postDelayed(refresh, 2000)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val active = ModuleStatus.isModuleActive()
        val sb = StringBuilder()
        sb.append(if (active) "✅ 模块已激活" else "❌ 未激活（LSPosed 勾选 system/android 等并重启）")
        if (!AmapSearch.hasKey()) sb.append("　⚠ 未内置高德 key")
        val st = StatusReader.read(this)
        if (st != null) {
            st.optJSONObject("snapshot")?.let { snap ->
                sb.append("　rev=").append(snap.optLong("revision"))
                sb.append(" started=").append(snap.optBoolean("started"))
            }
            sb.append("　泵=").append(if (st.optBoolean("pumpReady")) "on" else "off")
            sb.append("　注入=").append(st.optLong("injected"))
        }
        status.text = sb.toString()
    }
}
