package com.eillance.screentranslate

/** Loads libscreentranslate.so (Bergamot + fastText) exactly once. */
object NativeLibrary {
  @Volatile private var loaded = false

  @Synchronized
  fun ensureLoaded() {
    if (loaded) return
    System.loadLibrary("screentranslate")
    loaded = true
  }
}
