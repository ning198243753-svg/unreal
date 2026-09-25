package com.moon.location.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.moon.location.ModuleStatus
import com.moon.location.service.SpoofService

/**
 * Minimal M1 control surface. Map selection (WebView + 高德) replaces this in M3.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var latInput: EditText
    private lateinit var lngInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.START
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
        refresh()
    }

    private fun refresh() {
        val active = ModuleStatus.isModuleActive()
        status.text = if (active) {
            "模块已激活（LSPosed）\n作用域需包含：系统框架/system、com.android.phone"
        } else {
            "未检测到模块激活\n请在 LSPosed 中启用本模块，作用域勾选：系统框架(system) / android / com.android.phone / com.oplus.location 等，然后重启手机。"
        }
    }
}
