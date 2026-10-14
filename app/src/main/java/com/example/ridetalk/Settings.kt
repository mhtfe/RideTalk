package com.example.ridetalk

import android.content.Context

object Settings {
    val ACTIONS = listOf(
        "هیچ کاری",
        "پخش / توقف موسیقی",
        "آهنگ بعدی",
        "آهنگ قبلی",
        "روشن / خاموش کردن مکالمه",
        "تغییر حالت موسیقی (قطع کامل ↔ کم شدن)",
        "بلند کردن صدای موسیقی",
        "کم کردن صدای موسیقی"
    )
    val GESTURES = listOf("یک بار لمس", "دو بار لمس", "سه بار لمس", "نگه داشتن / دکمه‌های دیگر")

    var sensitivity = 3.5f
    var swapped = false          // false: من هدفون چپ رو دارم
    var headsetMic = true        // میکروفون هدفون به جای گوشی
    var fullStop = false         // قطع کامل موسیقی (در غیر این صورت فقط کم می‌شه)
    var stereoA2dp = false       // تلاش آزمایشی برای خروجی استریو
    val taps = intArrayOf(1, 2, 3, 4)

    fun load(c: Context) {
        val p = c.getSharedPreferences("rt_settings", Context.MODE_PRIVATE)
        sensitivity = p.getFloat("sens", 3.5f)
        swapped = p.getBoolean("swapped", false)
        headsetMic = p.getBoolean("headset", true)
        fullStop = p.getBoolean("fullStop", false)
        stereoA2dp = p.getBoolean("stereo", false)
        val t = p.getString("taps", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
        if (t != null && t.size == 4) for (i in 0..3) taps[i] = t[i].coerceIn(0, ACTIONS.size - 1)
    }

    fun save(c: Context) {
        c.getSharedPreferences("rt_settings", Context.MODE_PRIVATE).edit()
            .putFloat("sens", sensitivity)
            .putBoolean("swapped", swapped)
            .putBoolean("headset", headsetMic)
            .putBoolean("fullStop", fullStop)
            .putBoolean("stereo", stereoA2dp)
            .putString("taps", taps.joinToString(","))
            .apply()
    }
}
