package com.example.ridetalk

import android.content.Context
import android.media.*
import android.os.Build
import kotlin.math.*

/**
 * میکروفون (گوشی یا هدفون) -> تشخیص صحبت -> تشخیص گوینده -> پخش برای طرف مقابل
 * + کم کردن / قطع موسیقی با Audio Focus
 */
object Engine {
    const val SR = 16000
    const val N = 512                 // ~32ms
    private const val NB = 14
    private const val HANG = 40       // ~1.3 ثانیه ادامه بعد از آخرین صحبت
    private const val MIC_GAIN = 2.5f
    private const val CAL_FRAMES = 100

    @Volatile var running = false
    @Volatile var status = "متوقف"
    @Volatile var route = ""
    @Volatile var calibrating = 0     // 0 هیچ، 1 من، 2 دوست
    @Volatile var talkEnabled = true
    @Volatile var speakerState = 0    // 0 سکوت، 1 من، 2 دوست، 3 نامشخص
    @Volatile var level = 0f

    private var thread: Thread? = null
    private val prof = arrayOfNulls<FloatArray>(2)
    @Volatile private var beepHz = 0
    private var beepPos = 0

    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val edges = IntArray(NB + 1) {
        (100.0 * (4000.0 / 100.0).pow(it.toDouble() / NB) * N / SR).roundToInt()
    }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val nc = FloatArray(260)

    fun isCalibrated(i: Int) = prof[i] != null

    fun beep(high: Boolean) { beepPos = 0; beepHz = if (high) 1000 else 500 }

    fun beginCalibration(who: Int) {
        calibrating = who
        status = if (who == 1) "حالا چند ثانیه حرف بزن (من)…" else "حالا دوستت چند ثانیه حرف بزنه…"
    }

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        load(app)
        running = true
        status = "در حال راه‌اندازی…"
        thread = Thread {
            try { loop(app) } catch (e: Exception) { status = "خطا: ${e.message}" } finally { running = false }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        status = "متوقف"
        speakerState = 0
    }

    // پروفایل صدا جدا برای هر حالت میکروفون ذخیره می‌شه (گوشی / هدفون)
    private fun key(i: Int) = "p${i}_" + if (Settings.headsetMic) "h" else "m"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("rt", Context.MODE_PRIVATE)
        for (i in 0..1) {
            prof[i] = null
            val v = p.getString(key(i), null)?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: continue
            if (v.size == NB + 1) prof[i] = v.toFloatArray()
        }
    }

    private fun save(ctx: Context) {
        val e = ctx.getSharedPreferences("rt", Context.MODE_PRIVATE).edit()
        for (i in 0..1) prof[i]?.let { e.putString(key(i), it.joinToString(",")) }
        e.apply()
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val a = re[i]; re[i] = re[j]; re[j] = a
                val b = im[i]; im[i] = im[j]; im[j] = b
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** شکل طیف (۱۴ باند) + گام صدا؛ برای فریم‌های بی‌صدا null */
    private fun features(x: FloatArray): FloatArray? {
        var r0 = 0f
        for (i in 0 until N) r0 += x[i] * x[i]
        if (r0 < 1f) return null
        var best = 0f
        for (lag in 40..228) {
            var c = 0f
            for (i in 0 until N - lag) c += x[i] * x[i + lag]
            nc[lag] = c / r0
            if (nc[lag] > best) best = nc[lag]
        }
        if (best < 0.35f) return null
        var lag0 = 40
        for (lag in 40..228) if (nc[lag] >= 0.9f * best) { lag0 = lag; break }
        val f0 = SR.toFloat() / lag0

        for (i in 0 until N) { re[i] = x[i] * win[i]; im[i] = 0f }
        fft()
        val f = FloatArray(NB + 1)
        var mean = 0f
        for (b in 0 until NB) {
            var e = 1e-3f
            for (k in edges[b] until edges[b + 1]) e += re[k] * re[k] + im[k] * im[k]
            f[b] = ln(e); mean += f[b]
        }
        mean /= NB
        for (b in 0 until NB) f[b] -= mean
        f[NB] = ln(f0 / 120f) * 3f
        return f
    }

    private fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return sqrt(s)
    }

    private fun mixBeep(out: ShortArray) {
        val hz = beepHz
        if (hz == 0) return
        val total = SR * 15 / 100
        for (i in 0 until N) {
            if (beepPos >= total) { beepHz = 0; return }
            val env = min(1f, min(beepPos, total - beepPos) / 300f)
            val v = (sin(2.0 * PI * hz * beepPos / SR) * 9000 * env).toInt()
            out[2 * i] = (out[2 * i] + v).coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = (out[2 * i + 1] + v).coerceIn(-32768, 32767).toShort()
            beepPos++
        }
    }

    private fun loop(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headset = Settings.headsetMic
        var bleOut = false

        if (headset) {
            status = "در حال اتصال به میکروفون هدفون…"
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = am.availableCommunicationDevices
                val ble = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                val d = ble ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (d == null) {
                    am.mode = AudioManager.MODE_NORMAL
                    status = "هدفون بلوتوثی با میکروفون پیدا نشد"
                    return
                }
                am.setCommunicationDevice(d)
                bleOut = ble != null
            } else {
                am.startBluetoothSco(); am.isBluetoothScoOn = true
            }
            Thread.sleep(2000)
        }

        val stereoTry = headset && !bleOut && Settings.stereoA2dp
        val stereoOut = !headset || bleOut || stereoTry
        val focusUsage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        val outUsage = if (headset && !stereoTry) AudioAttributes.USAGE_VOICE_COMMUNICATION else focusUsage
        route = when {
            !headset -> "میکروفون گوشی • خروجی استریو"
            bleOut -> "میکروفون هدفون (LE Audio) • خروجی استریو"
            stereoTry -> "میکروفون هدفون • خروجی A2DP (آزمایشی)"
            else -> "میکروفون هدفون (مونو) • صدا برای هر دو گوش پخش می‌شه"
        }

        fun mk(gain: Int) = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(focusUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        val focusDuck = mk(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        val focusStop = mk(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        var focusReq = focusDuck
        var focus = false

        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            val minRec = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                if (headset) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
                SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minRec, N * 4))
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) { status = "خطا در میکروفون (اجازه‌ها؟)"; return }
            val wantIn = when {
                !headset -> AudioDeviceInfo.TYPE_BUILTIN_MIC
                bleOut -> AudioDeviceInfo.TYPE_BLE_HEADSET
                else -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == wantIn }
                ?.let { r.setPreferredDevice(it) }

            val minTrk = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(outUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(max(minTrk, N * 4 * 2))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t
            if (stereoTry) {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                    ?.let { t.setPreferredDevice(it) }
            }

            val buf = ShortArray(N)
            val x = FloatArray(N)
            val out = ShortArray(N * 2)
            var prevX = 0f; var prevY = 0f
            var floor = 0f; var warm = 0
            var hang = 0
            var gL = 0f; var gR = 0f
            var s = 0f; var meSpeaking = true
            val calSum = FloatArray(NB + 1); var calCnt = 0

            fun flush() { mixBeep(out); t.write(out, 0, N * 2) }

            r.startRecording(); t.play()
            status = "آماده"
            while (running) {
                var got = 0
                while (got < N && running) {
                    val n = r.read(buf, got, N - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < N) break

                var e = 0.0
                for (i in 0 until N) {
                    val xi = buf[i].toFloat()
                    val y = 0.94f * (prevY + xi - prevX)   // فیلتر بالاگذر برای صدای باد
                    prevX = xi; prevY = y; x[i] = y; e += y * y
                }
                val rms = sqrt(e / N).toFloat()
                level = min(1f, rms / 3000f)

                if (warm < 25) {
                    floor = (floor * warm + rms) / (warm + 1); warm++
                    out.fill(0); flush(); continue
                }

                val echoGuard = if (headset && gL + gR > 0f) 1.6f else 1f
                val speech = talkEnabled && rms > max(floor * Settings.sensitivity * echoGuard, 120f)
                if (speech) hang = HANG else if (hang > 0) hang--
                val active = hang > 0
                if (!active) floor = 0.98f * floor + 0.02f * rms

                val feat = if (speech) features(x) else null

                // ---- کالیبراسیون ----
                if (calibrating != 0) {
                    if (focus) { am.abandonAudioFocusRequest(focusReq); focus = false }
                    if (feat != null) {
                        for (k in 0..NB) calSum[k] += feat[k]
                        calCnt++
                        status = "در حال یادگیری صدا… ${calCnt * 100 / CAL_FRAMES}٪"
                    }
                    if (calCnt >= CAL_FRAMES) {
                        prof[calibrating - 1] = FloatArray(NB + 1) { calSum[it] / calCnt }
                        save(ctx)
                        calibrating = 0; calCnt = 0; calSum.fill(0f)
                        status = "صدا ذخیره شد ✓"
                        beep(true)
                    }
                    out.fill(0); flush(); continue
                }
                if (calCnt > 0) { calCnt = 0; calSum.fill(0f) }

                // ---- تشخیص گوینده ----
                val p0 = prof[0]; val p1 = prof[1]
                val calibrated = p0 != null && p1 != null
                if (feat != null && calibrated) {
                    val d0 = dist(feat, p0!!); val d1 = dist(feat, p1!!)
                    val score = (d1 - d0) / (d0 + d1 + 1e-3f)     // مثبت = من
                    s = 0.7f * s + 0.3f * score
                    if (s > 0.08f) meSpeaking = true else if (s < -0.08f) meSpeaking = false
                }

                // ---- موسیقی ----
                if (active && !focus) {
                    focusReq = if (Settings.fullStop) focusStop else focusDuck
                    focus = am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else if (!active && focus) {
                    am.abandonAudioFocusRequest(focusReq); focus = false
                }

                // ---- مسیر صدا ----
                var tL = 0f; var tR = 0f
                if (!talkEnabled) {
                    speakerState = 0; status = "مکالمه خاموشه"
                } else if (active) {
                    if (!calibrated) {
                        tL = 1f; tR = 1f; speakerState = 3
                        status = "صحبت (صدا کالیبره نشده → هر دو گوش)"
                    } else {
                        val toRight = (meSpeaking != Settings.swapped)
                        if (toRight) tR = 1f else tL = 1f
                        speakerState = if (meSpeaking) 1 else 2
                        status = if (meSpeaking) "من حرف می‌زنم ← دوستم می‌شنوه" else "دوستم حرف می‌زنه ← من می‌شنوم"
                    }
                } else { speakerState = 0; status = "گوش می‌دم…" }

                if (active && !stereoOut) { tL = 1f; tR = 1f }
                for (i in 0 until N) {
                    val k = (i + 1).toFloat() / N
                    val gl = gL + (tL - gL) * k
                    val gr = gR + (tR - gR) * k
                    val v = (x[i] * MIC_GAIN).coerceIn(-32000f, 32000f)
                    out[2 * i] = (v * gl).toInt().toShort()
                    out[2 * i + 1] = (v * gr).toInt().toShort()
                }
                gL = tL; gR = tR
                flush()
            }
        } finally {
            if (focus) am.abandonAudioFocusRequest(focusReq)
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
            if (headset) {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
                am.mode = AudioManager.MODE_NORMAL
            }
            route = ""
        }
    }
}
