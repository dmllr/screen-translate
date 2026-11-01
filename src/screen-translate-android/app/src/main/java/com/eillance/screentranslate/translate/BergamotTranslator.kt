package com.eillance.screentranslate.translate

import com.eillance.screentranslate.NativeLibrary
import com.eillance.screentranslate.lang.Languages
import com.eillance.screentranslate.models.InstalledTranslation
import java.io.Closeable

/** A route from one language to another using installed models. */
sealed interface TranslationRoute {
  data class Direct(val model: InstalledTranslation) : TranslationRoute
  data class Pivot(val first: InstalledTranslation, val second: InstalledTranslation) : TranslationRoute
}

class MissingModelException(val from: String, val to: String, val missing: List<Pair<String, String>>) :
  Exception("No translation model for $from → $to (missing: ${missing.joinToString { "${it.first}→${it.second}" }})")

object RoutePlanner {
  /**
   * Firefox models only exist to/from English, so X→Y (neither English) pivots through English.
   * @param lookup returns installed files for a direction or null.
   */
  fun plan(from: String, to: String, lookup: (String, String) -> InstalledTranslation?): TranslationRoute {
    lookup(from, to)?.let { return TranslationRoute.Direct(it) }
    val en = Languages.ENGLISH
    if (from == en || to == en) throw MissingModelException(from, to, listOf(from to to))
    val first = lookup(from, en)
    val second = lookup(en, to)
    if (first != null && second != null) return TranslationRoute.Pivot(first, second)
    val missing = buildList {
      if (first == null) add(from to en)
      if (second == null) add(en to to)
    }
    throw MissingModelException(from, to, missing)
  }
}

/**
 * Bergamot (Firefox Translations engine) via JNI. Keeps loaded models in a small LRU because
 * loading a model takes ~0.3–1 s and ~50–150 MB of RAM.
 */
class BergamotTranslator(
  workers: Int = 2,
  cacheSize: Int = 4000,
  private val maxLoadedModels: Int = 3,
) : Closeable {

  private val service: Long
  private val loaded = object : LinkedHashMap<String, Long>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
      val evict = size > maxLoadedModels
      if (evict) BergamotNative.nativeFreeModel(eldest.value)
      return evict
    }
  }

  init {
    NativeLibrary.ensureLoaded()
    service = BergamotNative.nativeCreateService(workers, cacheSize)
  }

  @Synchronized
  fun translate(route: TranslationRoute, texts: List<String>): List<String> {
    if (texts.isEmpty()) return emptyList()
    val array = texts.toTypedArray()
    val result = when (route) {
      is TranslationRoute.Direct -> BergamotNative.nativeTranslate(service, handle(route.model), 0L, array, false)
      is TranslationRoute.Pivot -> {
        // Load both before translating so LRU eviction cannot free the first while using the second.
        val a = handle(route.first)
        val b = handle(route.second)
        BergamotNative.nativeTranslate(service, a, b, array, false)
      }
    }
    return result?.toList() ?: texts.map { "" }
  }

  @Synchronized
  override fun close() {
    loaded.values.forEach(BergamotNative::nativeFreeModel)
    loaded.clear()
    BergamotNative.nativeDestroyService(service)
  }

  private fun handle(m: InstalledTranslation): Long {
    val key = "${m.from}-${m.to}:${m.model.absolutePath}"
    loaded[key]?.let { return it }
    val h = BergamotNative.nativeLoadModel(service, BergamotConfig.yaml(m))
    loaded[key] = h
    return h
  }
}

/** Builds the Marian/Bergamot YAML config the same way Firefox's translations engine does. */
object BergamotConfig {
  fun yaml(m: InstalledTranslation): String {
    // Firefox: "intgemm.alphas.bin" models carry precomputed quantization multipliers.
    val precision = if (m.model.name.endsWith("intgemm.alphas.bin")) "int8shiftAlphaAll" else "int8shiftAll"
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return buildString {
      appendLine("models:")
      appendLine("  - ${q(m.model.absolutePath)}")
      appendLine("vocabs:")
      m.vocabs.forEach { appendLine("  - ${q(it.absolutePath)}") }
      appendLine("shortlist:")
      appendLine("  - ${q(m.lex.absolutePath)}")
      appendLine("  - false")
      appendLine("beam-size: 1")
      appendLine("normalize: 1.0")
      appendLine("word-penalty: 0")
      appendLine("max-length-break: 128")
      appendLine("mini-batch-words: 1024")
      appendLine("workspace: 128")
      appendLine("max-length-factor: 2.0")
      appendLine("skip-cost: true")
      appendLine("cpu-threads: 0")
      appendLine("quiet: true")
      appendLine("quiet-translation: true")
      appendLine("gemm-precision: $precision")
      appendLine("alignment: soft")
    }
  }
}

internal object BergamotNative {
  @JvmStatic external fun nativeCreateService(workers: Int, cacheSize: Int): Long
  @JvmStatic external fun nativeDestroyService(handle: Long)
  @JvmStatic external fun nativeLoadModel(service: Long, yamlConfig: String): Long
  @JvmStatic external fun nativeFreeModel(handle: Long)
  @JvmStatic external fun nativeTranslate(
    service: Long, model: Long, pivotModel: Long, texts: Array<String>, html: Boolean,
  ): Array<String>?
}
