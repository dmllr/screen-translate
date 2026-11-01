package com.eillance.screentranslate.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The floating "translate" button. Tap = translate / clear, long-press = switch mode,
 * drag = move. Renders its own state (idle, busy, continuous) so no image assets are needed.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
  context: Context,
  private val onTap: () -> Unit,
  private val onLongPress: () -> Unit,
  private val onDrag: (dx: Int, dy: Int) -> Unit,
  private val onDragEnd: () -> Unit,
) : View(context) {

  enum class State { IDLE, BUSY, SHOWING, CONTINUOUS }

  var state: State = State.IDLE
    set(value) { field = value; invalidate() }

  private val density = resources.displayMetrics.density
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeWidth = 3 * density; color = Color.WHITE
  }
  private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 15 * density; isFakeBoldText = true
  }
  private val arc = RectF()
  private var sweepStart = 0f

  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
  private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
  private var downX = 0f
  private var downY = 0f
  private var lastX = 0f
  private var lastY = 0f
  private var dragging = false
  private var longPressed = false
  private val longPressRunnable = Runnable { longPressed = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onLongPress() }

  override fun onDraw(canvas: Canvas) {
    val r = width / 2f
    fill.color = when (state) {
      State.IDLE -> 0xCC3F51B5.toInt()
      State.BUSY -> 0xCC5C6BC0.toInt()
      State.SHOWING -> 0xCC00897B.toInt()
      State.CONTINUOUS -> 0xCCD81B60.toInt()
    }
    canvas.drawCircle(r, r, r - density, fill)
    canvas.drawText(if (state == State.CONTINUOUS) "A∞" else "文A", r, r + label.textSize / 3, label)
    if (state == State.BUSY) {
      val inset = 3 * density
      arc.set(inset, inset, width - inset, height - inset)
      canvas.drawArc(arc, sweepStart, 90f, false, stroke)
      sweepStart = (sweepStart + 12f) % 360f
      postInvalidateOnAnimation()
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY
        dragging = false; longPressed = false
        postDelayed(longPressRunnable, longPressMs)
      }
      MotionEvent.ACTION_MOVE -> {
        if (!dragging && (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)) {
          dragging = true
          removeCallbacks(longPressRunnable)
        }
        if (dragging) {
          onDrag((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
          lastX = event.rawX; lastY = event.rawY
        }
      }
      MotionEvent.ACTION_UP -> {
        removeCallbacks(longPressRunnable)
        when {
          dragging -> onDragEnd()
          !longPressed -> { performClick(); onTap() }
        }
      }
      MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
    }
    return true
  }

  override fun performClick(): Boolean = super.performClick()
}
