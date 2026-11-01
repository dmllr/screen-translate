package com.eillance.screentranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.eillance.screentranslate.ocr.Box
import com.eillance.screentranslate.ocr.Frame
import kotlin.math.max
import kotlin.math.min

/** What the overlay draws for one block, in frame coordinates. */
data class OverlayItem(val box: Box, val text: String, val background: Int, val foreground: Int)

/**
 * Full-screen, non-touchable view that paints translated text over the original text,
 * on a background sampled from around the original block so it blends into the app.
 */
@SuppressLint("ViewConstructor")
class TranslationOverlayView(context: Context) : View(context) {

  private var items: List<OverlayItem> = emptyList()
  private var frameW = 1
  private var frameH = 1
  private var banner: String? = null
  private var opacity = 0.92f

  private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
  private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0202124.toInt() }
  private val bannerText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = sp(13f) }
  private val rect = RectF()
  private val layoutCache = HashMap<OverlayItem, StaticLayout>()

  fun show(items: List<OverlayItem>, frameWidth: Int, frameHeight: Int, banner: String?, opacity: Float) {
    this.items = items
    this.frameW = max(1, frameWidth)
    this.frameH = max(1, frameHeight)
    this.banner = banner
    this.opacity = opacity
    layoutCache.clear()
    invalidate()
  }

  fun clear() = show(emptyList(), frameW, frameH, null, opacity)

  val isShowingSomething: Boolean get() = items.isNotEmpty() || banner != null

  override fun onDraw(canvas: Canvas) {
    val sx = width.toFloat() / frameW
    val sy = height.toFloat() / frameH
    val alpha = (opacity * 255).toInt().coerceIn(0, 255)
    for (item in items) {
      rect.set(item.box.left * sx, item.box.top * sy, item.box.right * sx, item.box.bottom * sy)
      val pad = 2f * resources.displayMetrics.density
      rect.inset(-pad, -pad)
      bgPaint.color = item.background
      bgPaint.alpha = alpha
      val layout = layoutCache.getOrPut(item) { fit(item.text, rect.width(), rect.height(), item.foreground) }
      // Text that cannot fit even at the minimum size is allowed to grow the box downwards.
      val bottom = max(rect.bottom, rect.top + layout.height + pad)
      canvas.drawRoundRect(rect.left, rect.top, rect.right, bottom, pad * 2, pad * 2, bgPaint)
      canvas.save()
      canvas.translate(rect.left + pad / 2, rect.top + max(0f, (rect.height() - layout.height) / 2f))
      layout.draw(canvas)
      canvas.restore()
    }
    banner?.let { drawBanner(canvas, it) }
  }

  private fun drawBanner(canvas: Canvas, text: String) {
    val pad = sp(10f)
    val w = (width - 2 * pad).toInt()
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, bannerText, w).build()
    val top = resources.displayMetrics.density * 48
    canvas.drawRoundRect(pad / 2, top, width - pad / 2, top + layout.height + pad, pad, pad, bannerPaint)
    canvas.save()
    canvas.translate(pad, top + pad / 2)
    layout.draw(canvas)
    canvas.restore()
  }

  /** Largest font size (binary search) whose layout fits in [w] x [h]. */
  private fun fit(text: String, w: Float, h: Float, color: Int): StaticLayout {
    val width = max(1, w.toInt())
    var lo = sp(7f)
    var hi = min(sp(28f), max(lo, h * 0.9f))
    textPaint.color = color
    var best: StaticLayout? = null
    repeat(10) {
      val mid = (lo + hi) / 2
      val l = build(text, width, mid)
      if (l.height <= h) { best = l; lo = mid } else hi = mid
    }
    return best ?: build(text, width, sp(7f))
  }

  private fun build(text: String, width: Int, size: Float): StaticLayout {
    val paint = TextPaint(textPaint).apply { textSize = size }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
      .setAlignment(Layout.Alignment.ALIGN_NORMAL)
      .setIncludePad(false)
      .setLineSpacing(0f, 1f)
      .build()
  }

  private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

  companion object {
    /** Picks the dominant color around [box] in the frame and a readable text color for it. */
    fun colorsFor(frame: Frame, box: Box): Pair<Int, Int> {
      val b = box.clampTo(frame.width, frame.height)
      if (b.width <= 0 || b.height <= 0) return Color.WHITE to Color.BLACK
      var r = 0L; var g = 0L; var bl = 0L; var n = 0
      fun add(x: Int, y: Int) {
        val p = frame.argb[y.coerceIn(0, frame.height - 1) * frame.width + x.coerceIn(0, frame.width - 1)]
        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
      }
      val stepX = max(1, b.width / 24)
      val stepY = max(1, b.height / 8)
      for (x in b.left until b.right step stepX) { add(x, b.top); add(x, b.bottom - 1) }
      for (y in b.top until b.bottom step stepY) { add(b.left, y); add(b.right - 1, y) }
      val bg = Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
      val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
      return bg to if (lum > 140) Color.BLACK else Color.WHITE
    }
  }
}
