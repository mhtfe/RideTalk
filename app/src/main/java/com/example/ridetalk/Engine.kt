package com.example.ridetalk

import android.content.Context
import android.media.*
import kotlin.math.*

/**
 * حلقه‌ی اصلی صدا:
 * میکروفون گوشی -> تشخیص صحبت -> تشخیص گوینده -> پخش فقط در هدفون طرف مقابل
 * + کم کردن موسیقی با Audio Focus (ducking)
 */
object Engine {
    const val SR = 16000
    const val N = 512                 // ~32ms
    private const val NB = 14         // تعداد باندهای طیفی
    private const val HANG = 40       // ~1.3 ثانیه ادامه بعد از آخرین صحبت
    private const val MIC_GAIN = 2.5f
    private const val CAL_FRAMES = 100

    @Volatile var running = false
    @Volatile var status = "متوقف"
    @Volatile var sensitivity = 3.5f
    @Volatile var swapped = false     // false: من هدفون چپ رو دارم
    @Volatile var calibrating = 0     // 0 هیچ، 1 من، 2 دوست

    private var thread: Thread? = null
    private val prof = arrayOfNulls<FloatArray>(2)

    // ---- FFT / ویژگی‌ها ----
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val edges = IntArray(NB + 1) {
        (100.0 * (4000.0 / 100.0).pow(it.toDouble() / NB) * N / SR).roundToInt()
    }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val nc = FloatArray(260)

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

    /** شکل طیف (۱۴ باند) + گام صدا. اگه فریم voiced نباشه null برمی‌گردونه. */
    private fun features(x: FloatArray): FloatArray? {
        // گام صدا با خودهمبستگی
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

    private fun save(ctx: Context) {
        val e = ctx.getSharedPreferences("rt", Context.MODE_PRIVATE).edit()
        for (i in 0..1) prof[i]?.let { e.putString("p$i", it.joinToString(",")) }
        e.apply()
    }

    private fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("rt", Context.MODE_PRIVATE)
        for (i in 0..1) {
            val s = p.getString("p$i", null) ?: continue
            val v = s.split(",").mapNotNull { it.toFloatOrNull() }
            if (v.size == NB + 1) prof[i] = v.toFloatArray()
        }
    }

    private fun loop(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ورودی: میکروفون داخلی گوشی
        val minRec = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minRec, N * 4))
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { rec.setPreferredDevice(it) }

        // خروجی: استریو، مثل صدای مسیریاب (روی موسیقی میکس می‌شه)
        val minTrk = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(minTrk, N * 4 * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        var focus = false

        val buf = ShortArray(N)
        val x = FloatArray(N)
        val out = ShortArray(N * 2)
        val zeros = ShortArray(N * 2)
        var prevX = 0f; var prevY = 0f
        var floor = 0f; var warm = 0
        var hang = 0
        var gL = 0f; var gR = 0f
        var s = 0f; var meSpeaking = true
        val calSum = FloatArray(NB + 1); var calCnt = 0

        rec.startRecording(); track.play()
        status = "آماده — موسیقی رو پخش کن"
        try {
            while (running) {
                var got = 0
                while (got < N && running) {
                    val r = rec.read(buf, got, N - got)
                    if (r <= 0) break
                    got += r
                }
                if (got < N) break

                // فیلتر بالاگذر برای کم کردن صدای باد و لرزش
                var e = 0.0
                for (i in 0 until N) {
                    val xi = buf[i].toFloat()
                    val y = 0.94f * (prevY + xi - prevX)
                    prevX = xi; prevY = y; x[i] = y; e += y * y
                }
                val rms = sqrt(e / N).toFloat()

                if (warm < 25) { floor = (floor * warm + rms) / (warm + 1); warm++; track.write(zeros, 0, N * 2); continue }

                val speech = rms > max(floor * sensitivity, 120f)
                if (speech) hang = HANG else if (hang > 0) hang--
                val active = hang > 0
                if (!active) floor = 0.98f * floor + 0.02f * rms

                val feat = if (speech) features(x) else null

                // ---- حالت کالیبراسیون ----
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
                    }
                    track.write(zeros, 0, N * 2)
                    continue
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

                // ---- کم کردن موسیقی ----
                if (active && !focus) {
                    focus = am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                } else if (!active && focus) {
                    am.abandonAudioFocusRequest(focusReq); focus = false
                }

                // ---- مسیر صدا ----
                var tL = 0f; var tR = 0f
                if (active) {
                    if (!calibrated) { tL = 1f; tR = 1f; status = "صحبت (کالیبره نشده → هر دو گوش)" }
                    else {
                        val toRight = (meSpeaking != swapped)
                        if (toRight) tR = 1f else tL = 1f
                        status = if (meSpeaking) "من حرف می‌زنم ← دوستم می‌شنوه" else "دوستم حرف می‌زنه ← من می‌شنوم"
                    }
                } else status = "گوش می‌دم…"

                for (i in 0 until N) {
                    val t = (i + 1).toFloat() / N
                    val gl = gL + (tL - gL) * t
                    val gr = gR + (tR - gR) * t
                    val v = (x[i] * MIC_GAIN).coerceIn(-32000f, 32000f)
                    out[2 * i] = (v * gl).toInt().toShort()
                    out[2 * i + 1] = (v * gr).toInt().toShort()
                }
                gL = tL; gR = tR
                track.write(out, 0, N * 2)
            }
        } finally {
            if (focus) am.abandonAudioFocusRequest(focusReq)
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }
}
