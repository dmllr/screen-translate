package com.eillance.screentranslate.capture

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.eillance.screentranslate.ocr.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteOrder

/**
 * Mirrors the default display into an ImageReader. The latest image is kept so a frame can be
 * read at any time; [generation] increases whenever the display produced a new frame, which lets
 * callers wait for "a frame rendered after I hid my overlay".
 */
class ScreenCapturer(
  private val projection: MediaProjection,
  width: Int,
  height: Int,
  private val densityDpi: Int,
) {
  private val thread = HandlerThread("ScreenCapturer").apply { start() }
  private val handler = Handler(thread.looper)
  private val lock = Any()

  private var reader: ImageReader
  private var display: VirtualDisplay
  private var latest: Image? = null

  var width = width; private set
  var height = height; private set

  @Volatile var generation: Long = 0; private set

  init {
    reader = newReader(width, height)
    display = projection.createVirtualDisplay(
      "ScreenTranslate", width, height, densityDpi,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
    ) ?: error("Could not create the capture display")
  }

  @SuppressLint("WrongConstant")
  private fun newReader(w: Int, h: Int): ImageReader =
    ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
      setOnImageAvailableListener({ r ->
        val img = try { r.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return@setOnImageAvailableListener
        synchronized(lock) {
          latest?.close()
          latest = img
          generation++
        }
      }, handler)
    }

  /** Re-targets the mirror after a rotation / display size change. */
  fun resize(w: Int, h: Int) {
    if (w == width && h == height) return
    synchronized(lock) {
      latest?.close()
      latest = null
      val old = reader
      reader = newReader(w, h)
      display.resize(w, h, densityDpi)
      display.surface = reader.surface
      old.close()
      width = w
      height = h
    }
  }

  /** Waits (up to [timeoutMs]) for a frame newer than [afterGeneration], then returns the latest frame. */
  suspend fun captureAfter(afterGeneration: Long, timeoutMs: Long = 400): Frame? {
    withTimeoutOrNull(timeoutMs) {
      while (generation <= afterGeneration) delay(8)
    }
    return captureLatest()
  }

  fun captureLatest(): Frame? = synchronized(lock) {
    val img = latest ?: return null
    toFrame(img)
  }

  private fun toFrame(image: Image): Frame {
    val plane = image.planes[0]
    val w = image.width
    val h = image.height
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val out = IntArray(w * h)
    if (pixelStride == 4 && rowStride % 4 == 0) {
      val ints = buffer.asIntBuffer()
      val rowInts = rowStride / 4
      val row = IntArray(w)
      for (y in 0 until h) {
        ints.position(y * rowInts)
        ints.get(row, 0, w)
        val base = y * w
        for (x in 0 until w) {
          // Memory is R,G,B,A -> little-endian int 0xAABBGGRR -> convert to ARGB 0xAARRGGBB.
          val v = row[x]
          out[base + x] = (v and 0xFF00FF00.toInt()) or ((v and 0xFF) shl 16) or ((v ushr 16) and 0xFF)
        }
      }
    } else {
      for (y in 0 until h) for (x in 0 until w) {
        val i = y * rowStride + x * pixelStride
        val r = buffer.get(i).toInt() and 0xFF
        val g = buffer.get(i + 1).toInt() and 0xFF
        val b = buffer.get(i + 2).toInt() and 0xFF
        out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return Frame(out, w, h)
  }

  fun release() {
    synchronized(lock) {
      latest?.close()
      latest = null
    }
    display.release()
    reader.close()
    thread.quitSafely()
  }
}
