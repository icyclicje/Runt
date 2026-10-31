package com.mivimoose.scanner.data

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mivimoose.scanner.R

/** The app's synthesised sounds (the same set as the desktop scanner) and haptics. */
class Sounds(ctx: Context, private val prefs: Prefs) {
    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    init {
        val res = mapOf(
            "new" to R.raw.snd_new, "dupe" to R.raw.snd_dupe, "hit" to R.raw.snd_hit, "big" to R.raw.snd_big,
            "jackpot_gunshot" to R.raw.snd_jackpot_gunshot, "jackpot_explosion" to R.raw.snd_jackpot_explosion,
            "jackpot_cash" to R.raw.snd_jackpot_cash, "jackpot_airhorn" to R.raw.snd_jackpot_airhorn,
            "jackpot_laser" to R.raw.snd_jackpot_laser, "jackpot_fanfare" to R.raw.snd_jackpot_fanfare,
            "pack" to R.raw.snd_pack, "milestone" to R.raw.snd_milestone, "ignored" to R.raw.snd_ignored, "undo" to R.raw.snd_undo,
            "ready" to R.raw.snd_ready, "error" to R.raw.snd_error, "reading" to R.raw.snd_reading, "deal" to R.raw.snd_deal,
            "select" to R.raw.snd_select, "pause" to R.raw.snd_pause, "resume" to R.raw.snd_resume, "click" to R.raw.snd_click,
            "whoosh" to R.raw.snd_whoosh,
        )
        for ((k, r) in res) ids[k] = pool.load(ctx, r, 1)
    }

    /** Card and event sounds. "jackpot" plays the style chosen in settings. */
    fun play(name: String) {
        val s = prefs.value
        val ui = name in UI
        if (!s.sounds || (ui && !s.uiSounds)) return
        val key = if (name == "jackpot") "jackpot_" + s.jackpotSound else name
        val id = ids[key] ?: return
        pool.play(id, s.volume, s.volume, if (name.startsWith("jackpot")) 2 else 1, 0, 1f)
    }

    /** Short buzz patterns: tick, success, heavy (jackpot). */
    fun buzz(kind: String = "tick") {
        if (!prefs.value.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (kind) {
            "heavy" -> VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 120, 40, 200), intArrayOf(0, 255, 0, 255, 0, 255), -1)
            "success" -> VibrationEffect.createWaveform(longArrayOf(0, 25, 50, 35), intArrayOf(0, 160, 0, 220), -1)
            "warn" -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), intArrayOf(0, 200, 0, 200), -1)
            else -> VibrationEffect.createOneShot(12, 120)
        }
        runCatching { v.vibrate(effect) }
    }

    companion object {
        val UI = setOf("click", "select", "pause", "resume", "whoosh", "reading", "deal")
        val JACKPOT_STYLES = listOf("gunshot", "explosion", "cash", "airhorn", "laser", "fanfare")
    }
}
