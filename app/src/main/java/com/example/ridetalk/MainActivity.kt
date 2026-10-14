package com.example.ridetalk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())

    private val cBg = Color.parseColor("#0B1220")
    private val cCard = Color.parseColor("#16213A")
    private val cText = Color.parseColor("#E6EDF7")
    private val cSub = Color.parseColor("#8FA3C0")
    private val cBlue = Color.parseColor("#3B82F6")
    private val cGreen = Color.parseColor("#22C55E")
    private val cOrange = Color.parseColor("#F59E0B")
    private val cGray = Color.parseColor("#475569")
    private val cRed = Color.parseColor("#EF4444")

    private lateinit var circle: View
    private lateinit var statusTxt: TextView
    private lateinit var routeTxt: TextView
    private lateinit var toggle: Button
    private lateinit var calMe: Button
    private lateinit var calFriend: Button
    private lateinit var keyTxt: TextView

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 20)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        c.addView(label(title, 16f, cText, true))
        return c
    }

    private fun hint(parent: LinearLayout, t: String) {
        parent.addView(label(t, 12.5f, cSub).apply { setPadding(0, dp(6), 0, dp(6)) })
    }

    private fun pill(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        background = rounded(color, 28); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun sw(parent: LinearLayout, t: String, on: Boolean, f: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply {
            text = t; isChecked = on; setTextColor(cText); textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> f(c) }
        })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Settings.load(this)
        Engine.load(this)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(32))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        root.addView(label("RideTalk", 30f, cText, true))
        root.addView(label("اینترکام دوچرخه‌سواری با یک گوشی و یک هدفون", 13f, cSub))

        // ----- وضعیت -----
        val st = card(root, "وضعیت")
        st.gravity = Gravity.CENTER_HORIZONTAL
        circle = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cGray) } }
        st.addView(circle, LinearLayout.LayoutParams(dp(110), dp(110)).apply { topMargin = dp(20); bottomMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL })
        statusTxt = label("متوقف", 17f, cText, true).apply { gravity = Gravity.CENTER }
        st.addView(statusTxt, LinearLayout.LayoutParams(MATCH, WRAP))
        routeTxt = label("", 12f, cSub).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(12)) }
        st.addView(routeTxt, LinearLayout.LayoutParams(MATCH, WRAP))
        toggle = pill("شروع", cBlue) { if (Engine.running) stopTalk() else startTalk() }
        st.addView(toggle, LinearLayout.LayoutParams(MATCH, dp(54)))

        // ----- کالیبراسیون -----
        val cal = card(root, "یادگیری صدا")
        hint(cal, "هر نفر یک بار چند ثانیه (۵ تا ۸ ثانیه) با صدای معمولی حرف بزنه. برنامه رو اول «شروع» کن، بعد دکمه رو بزن. اگه حالت میکروفون رو عوض کردی، دوباره انجامش بده.")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        calMe = pill("صدای من", cGray) { calibrate(1) }
        calFriend = pill("صدای دوستم", cGray) { calibrate(2) }
        row.addView(calMe, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        row.addView(calFriend, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        cal.addView(row)
        sw(cal, "من هدفون راست رو دارم (جابه‌جایی چپ/راست)", Settings.swapped) { Settings.swapped = it; Settings.save(this) }

        // ----- موسیقی -----
        val mu = card(root, "موسیقی هنگام صحبت")
        val rg = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val r1 = RadioButton(this).apply { text = "فقط کم شدن صدا"; id = 101; setTextColor(cText) }
        val r2 = RadioButton(this).apply { text = "قطع کامل (بعد از سکوت ادامه پیدا می‌کنه)"; id = 102; setTextColor(cText) }
        rg.addView(r1); rg.addView(r2)
        rg.check(if (Settings.fullStop) 102 else 101)
        rg.setOnCheckedChangeListener { _, id -> Settings.fullStop = id == 102; Settings.save(this) }
        mu.addView(rg)

        // ----- میکروفون -----
        val mic = card(root, "میکروفون")
        sw(mic, "گرفتن صدا از میکروفون هدفون", Settings.headsetMic) {
            Settings.headsetMic = it; Settings.save(this); Engine.load(this)
        }
        hint(mic, "خاموش = میکروفون خود گوشی. تغییرش بعد از توقف و شروع دوباره اعمال می‌شه. با میکروفون هدفون، بلوتوث وارد حالت تماس می‌شه: ممکنه موسیقی مونو یا قطع بشه و صدا برای هر دو گوش پخش بشه (مگر هدفون LE Audio باشه).")
        sw(mic, "تلاش برای خروجی استریو (آزمایشی)", Settings.stereoA2dp) { Settings.stereoA2dp = it; Settings.save(this) }
        mic.addView(label("حساسیت تشخیص صحبت (چپ = حساس‌تر)", 13f, cSub).apply { setPadding(0, dp(10), 0, 0) })
        mic.addView(SeekBar(this).apply {
            max = 60; progress = ((Settings.sensitivity - 1.5f) * 10).toInt().coerceIn(0, 60)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { Settings.sensitivity = 1.5f + p / 10f }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { Settings.save(this@MainActivity) }
            })
        })

        // ----- لمس هدفون -----
        val tp = card(root, "کنترل لمس هدفون")
        hint(tp, "هر لمس رو به یک کار وصل کن. هدفون باید لمس‌ها رو به‌صورت «پخش/توقف، بعدی، قبلی» بفرسته (تنظیم پیش‌فرض بیشتر هدفون‌ها). موقع اجرای «روشن/خاموش مکالمه» یک بوق شنیده می‌شه.")
        for (i in 0..3) {
            tp.addView(label(Settings.GESTURES[i], 13.5f, cText, true).apply { setPadding(0, dp(10), 0, 0) })
            tp.addView(Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Settings.ACTIONS)
                setSelection(Settings.taps[i])
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        Settings.taps[i] = pos; Settings.save(this@MainActivity)
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
        }
        keyTxt = label("", 12.5f, cOrange).apply { setPadding(0, dp(12), 0, 0) }
        tp.addView(keyTxt)

        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); addView(root) })

        h.post(object : Runnable {
            override fun run() { refresh(); h.postDelayed(this, 120) }
        })
    }

    private fun refresh() {
        val on = Engine.running
        statusTxt.text = if (on) Engine.status else Engine.status.takeIf { it != "متوقف" } ?: "متوقف"
        routeTxt.text = if (on) Engine.route else ""
        toggle.text = if (on) "توقف" else "شروع"
        toggle.background = rounded(if (on) cRed else cBlue, 28)

        val color = when {
            !on -> cGray
            !Engine.talkEnabled -> cGray
            Engine.speakerState == 1 -> cGreen
            Engine.speakerState == 2 -> cOrange
            Engine.speakerState == 3 -> cGreen
            else -> cBlue
        }
        (circle.background as GradientDrawable).setColor(color)
        val target = if (on) 1f + Engine.level * 0.3f else 1f
        circle.scaleX += (target - circle.scaleX) * 0.4f
        circle.scaleY = circle.scaleX

        calMe.text = if (Engine.isCalibrated(0)) "صدای من ✓" else "صدای من"
        calFriend.text = if (Engine.isCalibrated(1)) "صدای دوستم ✓" else "صدای دوستم"
        calMe.background = rounded(if (Engine.isCalibrated(0)) cGreen else cGray, 24)
        calFriend.background = rounded(if (Engine.isCalibrated(1)) cGreen else cGray, 24)

        keyTxt.text = "آخرین دکمه‌ی دریافتی از هدفون: ${Controls.lastEvent}\nآخرین کار: ${Controls.lastAction}"
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
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.BLUETOOTH_CONNECT
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else launchService()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchService()
    }

    private fun launchService() = startForegroundService(Intent(this, TalkService::class.java))
    private fun stopTalk() = stopService(Intent(this, TalkService::class.java))

    override fun onDestroy() { h.removeCallbacksAndMessages(null); super.onDestroy() }
}
