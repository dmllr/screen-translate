package com.eillance.screentranslate.langid

import com.eillance.screentranslate.NativeLibrary
import java.io.Closeable
import java.io.File

/** fastText lid.176 model wrapper (JNI). Thread-safe for concurrent [classify] calls. */
class FastTextClassifier private constructor(private var handle: Long) : LanguageClassifier, Closeable {

  override fun classify(text: String, topK: Int): List<LangGuess> {
    val line = text.replace('\n', ' ').replace('\r', ' ').trim()
    if (line.isEmpty() || handle == 0L) return emptyList()
    val raw = FastTextNative.nativePredict(handle, line, topK) ?: return emptyList()
    return (raw.indices step 2).map { LangGuess(raw[it], raw[it + 1].toFloat()) }
  }

  @Synchronized
  override fun close() {
    if (handle != 0L) FastTextNative.nativeFree(handle)
    handle = 0L
  }

  companion object {
    fun load(model: File): FastTextClassifier {
      NativeLibrary.ensureLoaded()
      return FastTextClassifier(FastTextNative.nativeLoad(model.absolutePath))
    }
  }
}

internal object FastTextNative {
  @JvmStatic external fun nativeLoad(path: String): Long
  @JvmStatic external fun nativeFree(handle: Long)
  @JvmStatic external fun nativePredict(handle: Long, text: String, k: Int): Array<String>?
}
