package com.eillance.screentranslate.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.eillance.screentranslate.app
import com.eillance.screentranslate.translate.BergamotTranslator
import com.eillance.screentranslate.translate.RoutePlanner
import kotlin.concurrent.thread

/**
 * Debug builds only. Translates a string with the installed models, bypassing capture and OCR:
 *
 *   adb shell am broadcast -n com.eillance.screentranslate/.debug.DebugTranslateReceiver \
 *     --es from ja --es to en --es text "こんにちは世界" [--ei repeat 50] [--ei workers 2]
 *   adb logcat -s DebugTranslate Marian
 */
class DebugTranslateReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val from = intent.getStringExtra("from") ?: "ja"
    val to = intent.getStringExtra("to") ?: "en"
    val text = intent.getStringExtra("text") ?: "こんにちは、世界。今日はいい天気ですね。"
    val workers = intent.getIntExtra("workers", 2)
    // > 1 sends that many distinct sentences in one call, so every worker thread gets a batch.
    val repeat = intent.getIntExtra("repeat", 1).coerceAtLeast(1)
    val image = intent.getStringExtra("image")
    val pending = goAsync()
    val models = context.app.models
    if (image != null) {
      // Full pipeline (OCR → language ID → translation) on a screenshot, e.g.
      //   adb exec-out screencap -p > s.png && adb push s.png /data/local/tmp/s.png
      //   adb shell am broadcast ... --es image /data/local/tmp/s.png
      thread(name = "pipeline") {
        try {
          val bmp = android.graphics.BitmapFactory.decodeFile(image) ?: error("cannot decode $image")
          val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
          val result = context.app.pipeline.process(com.eillance.screentranslate.ocr.Frame(px, bmp.width, bmp.height))
          Log.i(TAG, "pipeline: ${result.blocks.size} blocks, timings=${result.timings}, missing=${result.missingModels}")
          result.blocks.forEach { b -> Log.i(TAG, "  [${b.sourceLanguage}] \"${b.block.text}\" → \"${b.translation}\"") }
          Log.i(TAG, "pipeline done ms")
        } catch (e: Throwable) {
          Log.e(TAG, "failed", e)
        } finally {
          pending.finish()
        }
      }
      return
    }
    thread(name = "debug-translate") {
      try {
        val route = RoutePlanner.plan(from, to, models::installedTranslation)
        Log.i(TAG, "route $from→$to: $route")
        BergamotTranslator(workers = workers).use { translator ->
          val t0 = SystemClock.elapsedRealtime()
          val inputs = if (repeat == 1) listOf(text) else List(repeat) { "$text ($it)" }
          val out = translator.translate(route, inputs)
          Log.i(TAG, "${SystemClock.elapsedRealtime() - t0} ms, ${inputs.size} texts: \"${inputs.first()}\" → \"${out.first()}\"")
        }
      } catch (e: Throwable) {
        Log.e(TAG, "failed", e)
      } finally {
        pending.finish()
      }
    }
  }

  private companion object {
    const val TAG = "DebugTranslate"
  }
}
