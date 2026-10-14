package com.example.ridetalk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }; root.addView(this)
        }
        status = TextView(this).apply { textSize = 18f; setPadding(0, 0, 0, 32); text = "متوقف" }
        root.addView(status)

        toggle = btn("شروع") { if (Engine.running) stopTalk() else startTalk() }
        btn("کالیبره: صدای من (هدفون چپ)") { calibrate(1) }
        btn("کالیبره: صدای دوستم (هدفون راست)") { calibrate(2) }

        root.addView(Switch(this).apply {
            text = "جابه‌جایی چپ/راست (من هدفون راست رو دارم)"
            setOnCheckedChangeListener { _, c -> Engine.swapped = c }
        })
        root.addView(TextView(this).apply { text = "حساسیت تشخیص صحبت (چپ = حساس‌تر)"; setPadding(0, 32, 0, 0) })
        root.addView(SeekBar(this).apply {
            max = 60; progress = 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    Engine.sensitivity = 1.5f + p / 10f
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        setContentView(root)

        h.post(object : Runnable {
            override fun run() {
                status.text = Engine.status
                toggle.text = if (Engine.running) "توقف" else "شروع"
                h.postDelayed(this, 250)
            }
        })
    }

    private fun calibrate(who: Int) {
        if (!Engine.running) { Toast.makeText(this, "اول برنامه رو شروع کن", Toast.LENGTH_SHORT).show(); return }
        Engine.beginCalibration(who)
    }

    private fun startTalk() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else launchService()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchService()
    }

    private fun launchService() = startForegroundService(Intent(this, TalkService::class.java))
    private fun stopTalk() = stopService(Intent(this, TalkService::class.java))
}
