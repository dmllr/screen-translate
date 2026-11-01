package com.eillance.screentranslate

import android.app.Application
import com.eillance.screentranslate.lang.Languages
import com.eillance.screentranslate.models.ModelPack
import com.eillance.screentranslate.models.ModelRepository
import com.eillance.screentranslate.pipeline.TranslationPipeline
import com.eillance.screentranslate.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide singletons. Small enough not to warrant a DI framework. */
class ScreenTranslateApp : Application() {
  /** Outlives activities, so downloads keep going when the UI is closed. */
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  val settings by lazy { SettingsStore(this) }
  val models by lazy { ModelRepository(this) }
  val pipeline by lazy { TranslationPipeline(models, settings) }

  fun install(pack: ModelPack) {
    appScope.launch { models.install(pack) }
  }

  /** Translation directions needed for `from -> to` (English pivot for non-English pairs). */
  fun requiredDirections(from: String, to: String): List<Pair<String, String>> = when {
    from == to -> emptyList()
    from == Languages.ENGLISH || to == Languages.ENGLISH -> listOf(from to to)
    else -> listOf(from to Languages.ENGLISH, Languages.ENGLISH to to)
  }
}

val android.content.Context.app: ScreenTranslateApp get() = applicationContext as ScreenTranslateApp
