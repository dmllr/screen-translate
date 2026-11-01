package com.eillance.screentranslate.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TranslateMode {
  /** Translate once each time the bubble is tapped. */
  TAP,
  /** Keep translating while the screen content changes (experimental). */
  CONTINUOUS,
}

data class AppSettings(
  val targetLanguage: String = "en",
  /** Used when auto-detection fails or is disabled. */
  val fallbackSourceLanguage: String = "ja",
  val autoDetect: Boolean = true,
  val mode: TranslateMode = TranslateMode.TAP,
  /** Continuous mode: how often to check the screen for changes. */
  val continuousIntervalMs: Long = 700,
  val detectorPackId: String = "ocr-det-v6-small",
  val detectorMaxSide: Int = 1280,
  val translationThreads: Int = 2,
  val overlayOpacity: Float = 0.92f,
)

/** SharedPreferences-backed settings, exposed as a StateFlow so the service reacts to changes. */
class SettingsStore(context: Context) {
  private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val _state = MutableStateFlow(read())
  val state: StateFlow<AppSettings> = _state.asStateFlow()

  val current: AppSettings get() = _state.value

  fun update(transform: (AppSettings) -> AppSettings) {
    val next = transform(_state.value)
    prefs.edit()
      .putString(K_TARGET, next.targetLanguage)
      .putString(K_FALLBACK, next.fallbackSourceLanguage)
      .putBoolean(K_AUTO, next.autoDetect)
      .putString(K_MODE, next.mode.name)
      .putLong(K_INTERVAL, next.continuousIntervalMs)
      .putString(K_DETECTOR, next.detectorPackId)
      .putInt(K_DET_SIDE, next.detectorMaxSide)
      .putInt(K_THREADS, next.translationThreads)
      .putFloat(K_OPACITY, next.overlayOpacity)
      .apply()
    _state.value = next
  }

  private fun read(): AppSettings {
    val d = AppSettings()
    return AppSettings(
      targetLanguage = prefs.getString(K_TARGET, d.targetLanguage) ?: d.targetLanguage,
      fallbackSourceLanguage = prefs.getString(K_FALLBACK, d.fallbackSourceLanguage) ?: d.fallbackSourceLanguage,
      autoDetect = prefs.getBoolean(K_AUTO, d.autoDetect),
      mode = runCatching { TranslateMode.valueOf(prefs.getString(K_MODE, d.mode.name)!!) }.getOrDefault(d.mode),
      continuousIntervalMs = prefs.getLong(K_INTERVAL, d.continuousIntervalMs),
      detectorPackId = prefs.getString(K_DETECTOR, d.detectorPackId) ?: d.detectorPackId,
      detectorMaxSide = prefs.getInt(K_DET_SIDE, d.detectorMaxSide),
      translationThreads = prefs.getInt(K_THREADS, d.translationThreads),
      overlayOpacity = prefs.getFloat(K_OPACITY, d.overlayOpacity),
    )
  }

  private companion object {
    const val K_TARGET = "target_language"
    const val K_FALLBACK = "fallback_source_language"
    const val K_AUTO = "auto_detect"
    const val K_MODE = "mode"
    const val K_INTERVAL = "continuous_interval_ms"
    const val K_DETECTOR = "detector_pack"
    const val K_DET_SIDE = "detector_max_side"
    const val K_THREADS = "translation_threads"
    const val K_OPACITY = "overlay_opacity"
  }
}
