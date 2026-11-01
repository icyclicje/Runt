package com.mivimoose.scanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Everything the user can change, persisted as one JSON object. */
data class Settings(
    val currency: String = "USD",
    /** Value tiers (USD): hit, big hit and jackpot get their own sound and effects. */
    val hitValue: Double = 5.0,
    val bigValue: Double = 50.0,
    val jackpotValue: Double = 100.0,
    val jackpotSound: String = "gunshot",
    val sounds: Boolean = true,
    val uiSounds: Boolean = true,
    val haptics: Boolean = true,
    val volume: Float = 1f,
    /** Scanned cards go into the collection by themselves (off: price check, with an Add button). */
    val autoAdd: Boolean = true,
    /** Count each pack's cards and value against its price while collecting (the set is detected). */
    val trackPacks: Boolean = false,
    /** What to do with a card you already own: warn (add it, warned), skip (don't add), ask. */
    val dupePolicy: String = "warn",
    /** auto, en, ja, ko, zh-tw, zh-cn, th, id */
    val scanLanguage: String = "auto",
    val defaultCondition: String = "NM",
    /** Set chosen by hand in the scanner (null = detected from the cards). */
    val focusSetId: String? = null,
    val showGraded: Boolean = true,
    val psaToken: String = "",
    /** GemRate API key (optional): population across PSA, CGC, Beckett and SGC. */
    val gemRateKey: String = "",
    val ocr: Boolean = true,
    /** Live price refresh while the app is open, minutes. */
    val refreshMinutes: Int = 15,
    val reduceMotion: Boolean = false,
    val onboarded: Boolean = false,
    val cardLanguages: Set<String> = setOf("en", "ja"),
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state
    val value get() = _state.value

    fun update(f: (Settings) -> Settings) {
        val n = f(_state.value)
        _state.value = n
        sp.edit().putString("json", write(n)).apply()
    }

    private fun read(): Settings {
        val j = sp.getString("json", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return Settings()
        val d = Settings()
        return Settings(
            currency = j.optString("currency", d.currency),
            hitValue = j.optDouble("hitValue", d.hitValue),
            bigValue = j.optDouble("bigValue", d.bigValue),
            jackpotValue = j.optDouble("jackpotValue", d.jackpotValue),
            jackpotSound = j.optString("jackpotSound", d.jackpotSound),
            sounds = j.optBoolean("sounds", d.sounds),
            uiSounds = j.optBoolean("uiSounds", d.uiSounds),
            haptics = j.optBoolean("haptics", d.haptics),
            volume = j.optDouble("volume", d.volume.toDouble()).toFloat(),
            // a new setting: on for everyone, whatever scan mode was last picked
            autoAdd = j.optBoolean("autoAdd", d.autoAdd),
            // "pack" was a scan mode before pack tracking became a setting
            trackPacks = j.optBoolean("trackPacks", j.optString("scanMode") == "pack"),
            dupePolicy = j.optString("dupePolicy", d.dupePolicy),
            scanLanguage = j.optString("scanLanguage", d.scanLanguage),
            defaultCondition = j.optString("defaultCondition", d.defaultCondition),
            focusSetId = j.optString("focusSetId", "").ifEmpty { null },
            showGraded = j.optBoolean("showGraded", d.showGraded),
            psaToken = j.optString("psaToken", ""),
            gemRateKey = j.optString("gemRateKey", ""),
            ocr = j.optBoolean("ocr", d.ocr),
            refreshMinutes = j.optInt("refreshMinutes", d.refreshMinutes),
            reduceMotion = j.optBoolean("reduceMotion", d.reduceMotion),
            onboarded = j.optBoolean("onboarded", false),
            cardLanguages = j.optJSONArray("cardLanguages")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: d.cardLanguages,
        )
    }

    private fun write(s: Settings) = JSONObject().apply {
        put("currency", s.currency); put("hitValue", s.hitValue); put("bigValue", s.bigValue); put("jackpotValue", s.jackpotValue)
        put("jackpotSound", s.jackpotSound); put("sounds", s.sounds); put("uiSounds", s.uiSounds); put("haptics", s.haptics)
        put("volume", s.volume.toDouble()); put("autoAdd", s.autoAdd); put("trackPacks", s.trackPacks); put("dupePolicy", s.dupePolicy)
        put("scanLanguage", s.scanLanguage); put("defaultCondition", s.defaultCondition); put("focusSetId", s.focusSetId ?: "")
        put("showGraded", s.showGraded); put("psaToken", s.psaToken); put("gemRateKey", s.gemRateKey)
        put("ocr", s.ocr); put("refreshMinutes", s.refreshMinutes); put("reduceMotion", s.reduceMotion); put("onboarded", s.onboarded)
        put("cardLanguages", org.json.JSONArray(s.cardLanguages.toList()))
    }.toString()
}
