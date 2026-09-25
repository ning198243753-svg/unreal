package com.moon.location.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.moon.location.ModuleStatus
import com.moon.location.config.StatusReader
import com.moon.location.service.SpoofService

/**
 * Minimal M2.5 control surface with live status readback. Map selection
 * (WebView + 高德) replaces this in M3.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var latInput: EditText
    private lateinit var lngInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.START
            setPadding(0, 0, 0, pad)
        }
        root.addView(status)

        latInput = EditText(this).apply {
            hint = "纬度 latitude (e.g. 39.9087)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText("39.9087")
        }
        lngInput = EditText(this).apply {
            hint = "经度 longitude (e.g. 116.3975)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText("116.3975")
        }
        root.addView(latInput)
        root.addView(lngInput)

        root.addView(Button(this).apply {
            text = "开始模拟"
            setOnClickListener {
                val lat = latInput.text.toString().toDoubleOrNull() ?: return@setOnClickListener
                val lng = lngInput.text.toString().toDoubleOrNull() ?: return@setOnClickListener
                SpoofService.start(this@MainActivity, lat, lng)
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "停止模拟"
            setOnClickListener {
                SpoofService.stop(this@MainActivity)
                refresh()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.postDelayed(refresh, 2000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun refresh() {
        val active = ModuleStatus.isModuleActive()
        val sb = StringBuilder()
        sb.append(if (active) "✅ 模块已激活（LSPosed）\n" else "❌ 未检测到模块激活\n")

        val st = StatusReader.read(this)
        if (st != null) {
            sb.append("— 系统侧状态 —\n")
            sb.append("SDK: ").append(st.optInt("sdk")).append('\n')
            sb.append("配置可读: ").append(st.optBoolean("configReadable")).append('\n')
            st.optJSONObject("snapshot")?.let { snap ->
                sb.append("revision: ").append(snap.optLong("revision")).append('\n')
                sb.append("模拟中: ").append(snap.optBoolean("started")).append('\n')
                sb.append("目标: ").append(snap.optDouble("lat")).append(", ")
                    .append(snap.optDouble("lng")).append('\n')
            } ?: sb.append("快照: 无\n")
            sb.append("直推泵就绪: ").append(st.optBoolean("pumpReady"))
                .append("（provider=").append(st.optInt("pumpManagers")).append("）\n")
            sb.append("已注入: ").append(st.optLong("injected")).append('\n')
            st.optString("error", "").takeIf { it.isNotEmpty() && it != "null" }
                ?.let { sb.append("错误: ").append(it).append('\n') }
        } else if (active) {
            sb.append("（未取到系统侧状态，请确认作用域包含 system/android 并已重启）\n")
        } else {
            sb.append("\n请在 LSPosed 中启用本模块，作用域勾选：\n系统框架(system) / android / com.android.phone / com.oplus.location 等，然后重启手机。\n")
        }
        status.text = sb.toString()
    }
}
