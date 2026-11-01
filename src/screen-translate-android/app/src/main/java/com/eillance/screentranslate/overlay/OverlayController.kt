package com.eillance.screentranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.eillance.screentranslate.ocr.Box

/**
 * Owns the two overlay windows: the full-screen translation layer and the bubble.
 *
 * The translation layer has two personalities:
 *  - **Tap mode, showing:** opaque and touchable; tapping anywhere dismisses it.
 *  - **Pass-through** (tap mode idle, continuous mode): FLAG_NOT_TOUCHABLE. Android 12+ only lets
 *    touches through an overlay whose window alpha is <= the system's obscuring limit (0.8), so the
 *    layer is slightly translucent then.
 *
 * The layer is a normal (non-secure) window: continuous mode compares frames against a reference
 * captured *with* the overlay showing, so it never needs to be invisible to capture.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayController(
  private val context: Context,
  onBubbleTap: () -> Unit,
  onBubbleLongPress: () -> Unit,
  private val onOverlayTap: () -> Unit,
) {
  enum class LayerMode { INTERACTIVE, PASS_THROUGH }

  private val wm = context.getSystemService(WindowManager::class.java)
  private val density = context.resources.displayMetrics.density
  private val bubbleSize = (56 * density).toInt()

  val translations = TranslationOverlayView(context).apply {
    setOnTouchListener { _, e ->
      if (e.actionMasked == MotionEvent.ACTION_UP) onOverlayTap()
      true
    }
  }
  private var layerMode = LayerMode.PASS_THROUGH
  private var translationParams = fullScreenParams(layerMode)
  private var translationsAttached = false

  private val bubbleParams = WindowManager.LayoutParams(
    bubbleSize, bubbleSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
  ).apply {
    gravity = Gravity.TOP or Gravity.START
    x = context.resources.displayMetrics.widthPixels - bubbleSize - (8 * density).toInt()
    y = (context.resources.displayMetrics.heightPixels * 0.35f).toInt()
  }

  val bubble: BubbleView = BubbleView(
    context,
    onTap = onBubbleTap,
    onLongPress = onBubbleLongPress,
    onDrag = { dx, dy ->
      bubbleParams.x += dx
      bubbleParams.y += dy
      updateBubble()
    },
    onDragEnd = { snapBubbleToEdge() },
  )

  fun attach() {
    if (!translationsAttached) {
      wm.addView(translations, translationParams)
      translationsAttached = true
    }
    // Added after the translation layer so the bubble stays on top.
    if (bubble.parent == null) wm.addView(bubble, bubbleParams)
  }

  fun detach() {
    if (translationsAttached) runCatching { wm.removeViewImmediate(translations) }
    translationsAttached = false
    if (bubble.parent != null) runCatching { wm.removeViewImmediate(bubble) }
  }

  fun setLayerMode(mode: LayerMode) {
    if (mode == layerMode) return
    layerMode = mode
    translationParams = fullScreenParams(mode)
    if (translationsAttached) wm.updateViewLayout(translations, translationParams)
  }

  /** Hides both windows so the next captured frame shows only the underlying app. */
  fun hideForCapture() {
    translations.visibility = View.INVISIBLE
    bubble.visibility = View.INVISIBLE
  }

  fun showAfterCapture() {
    translations.visibility = View.VISIBLE
    bubble.visibility = View.VISIBLE
  }

  /**
   * Screen areas change detection must ignore: the bubble (it moves and animates) and the status
   * bar (clock, notification icons). In screen coordinates, which match the captured frame.
   */
  fun volatileAreas(): List<Box> {
    val loc = IntArray(2)
    bubble.getLocationOnScreen(loc)
    val pad = (4 * density).toInt()
    val bubbleBox = Box(loc[0] - pad, loc[1] - pad, loc[0] + bubbleSize + pad, loc[1] + bubbleSize + pad)
    val statusBar = Box(0, 0, Int.MAX_VALUE / 2, statusBarHeight())
    return listOf(bubbleBox, statusBar)
  }

  private fun statusBarHeight(): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val insets = wm.currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
      return insets.top
    }
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else (24 * density).toInt()
  }

  private fun updateBubble() {
    if (bubble.parent != null) runCatching { wm.updateViewLayout(bubble, bubbleParams) }
  }

  private fun snapBubbleToEdge() {
    val screenW = context.resources.displayMetrics.widthPixels
    val margin = (8 * density).toInt()
    bubbleParams.x = if (bubbleParams.x + bubbleSize / 2 < screenW / 2) margin else screenW - bubbleSize - margin
    updateBubble()
  }

  private fun fullScreenParams(mode: LayerMode): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (mode != LayerMode.INTERACTIVE) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    return WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
      alpha = if (mode == LayerMode.INTERACTIVE) 1f else maxPassThroughAlpha()
    }
  }

  private fun maxPassThroughAlpha(): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
    } else {
      1f
    }
}
