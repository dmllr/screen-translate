package com.eillance.screentranslate.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.eillance.screentranslate.R
import com.eillance.screentranslate.app
import com.eillance.screentranslate.lang.Languages
import com.eillance.screentranslate.ocr.Frame
import com.eillance.screentranslate.overlay.BubbleView
import com.eillance.screentranslate.overlay.OverlayController
import com.eillance.screentranslate.overlay.OverlayController.LayerMode
import com.eillance.screentranslate.overlay.OverlayItem
import com.eillance.screentranslate.overlay.TranslationOverlayView
import com.eillance.screentranslate.pipeline.NotReadyException
import com.eillance.screentranslate.pipeline.PipelineResult
import com.eillance.screentranslate.settings.TranslateMode
import com.eillance.screentranslate.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Foreground service holding the MediaProjection, the overlay windows and the translation loop.
 *
 * Tap mode:        bubble tap → hide overlay → grab a clean frame → OCR + translate → show.
 * Continuous mode: poll frames and compare them with a reference captured with the current
 *                  translations showing; when the content changes and then settles,
 *                  re-translate. See [ChangeDetector].
 */
class ScreenTranslateService : Service() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pipeline").apply { priority = Thread.NORM_PRIORITY + 1 } }
    .asCoroutineDispatcher()

  private var projection: MediaProjection? = null
  private var capturer: ScreenCapturer? = null
  private lateinit var overlay: OverlayController
  private var continuousJob: Job? = null
  private var busy = false
  private val changeDetector = ChangeDetector()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    overlay = OverlayController(
      this,
      onBubbleTap = ::onBubbleTap,
      onBubbleLongPress = ::toggleMode,
      onOverlayTap = ::clearOverlay,
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
      ACTION_START -> start(intent)
    }
    return START_NOT_STICKY
  }

  private fun start(intent: Intent) {
    if (projection != null) return
    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
    val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
    if (data == null) { stopSelf(); return }

    // Must be in the foreground with type mediaProjection *before* obtaining the projection.
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    val mpm = getSystemService(MediaProjectionManager::class.java)
    val proj = mpm.getMediaProjection(resultCode, data) ?: run { stopSelf(); return }
    proj.registerCallback(object : MediaProjection.Callback() {
      override fun onStop() { stopSelf() }
    }, Handler(Looper.getMainLooper()))
    projection = proj

    val (w, h) = screenSize()
    capturer = ScreenCapturer(proj, w, h, resources.displayMetrics.densityDpi)
    overlay.attach()
    running = true

    scope.launch {
      app.settings.state.distinctUntilChangedBy { it.mode }.collect { applyMode(it.mode) }
    }
  }

  // --- user actions -------------------------------------------------------------------------

  private fun onBubbleTap() {
    when (app.settings.current.mode) {
      TranslateMode.TAP -> if (overlay.translations.isShowingSomething) clearOverlay() else scope.launch { translateOnce() }
      TranslateMode.CONTINUOUS -> { reference = null }  // force a refresh on the next tick
    }
  }

  private fun toggleMode() {
    val next = if (app.settings.current.mode == TranslateMode.TAP) TranslateMode.CONTINUOUS else TranslateMode.TAP
    app.settings.update { it.copy(mode = next) }
    toast(if (next == TranslateMode.CONTINUOUS) "Continuous translation on" else "Tap-to-translate mode")
  }

  private fun clearOverlay() {
    overlay.translations.clear()
    if (app.settings.current.mode == TranslateMode.TAP) {
      overlay.setLayerMode(LayerMode.PASS_THROUGH)
      overlay.bubble.state = BubbleView.State.IDLE
    }
  }

  private fun applyMode(mode: TranslateMode) {
    continuousJob?.cancel()
    overlay.translations.clear()
    reference = null
    when (mode) {
      TranslateMode.TAP -> {
        overlay.setLayerMode(LayerMode.PASS_THROUGH)
        overlay.bubble.state = BubbleView.State.IDLE
      }
      TranslateMode.CONTINUOUS -> {
        overlay.setLayerMode(LayerMode.PASS_THROUGH)
        overlay.bubble.state = BubbleView.State.CONTINUOUS
        continuousJob = scope.launch { continuousLoop() }
      }
    }
  }

  // --- tap mode -----------------------------------------------------------------------------

  private suspend fun translateOnce() {
    if (busy) return
    busy = true
    overlay.bubble.state = BubbleView.State.BUSY
    try {
      val frame = captureClean() ?: run { toast("Screen capture not ready yet"); return }
      val result = withContext(worker) { app.pipeline.process(frame) }
      val shown = render(result, frame)
      if (shown) {
        overlay.setLayerMode(LayerMode.INTERACTIVE)
        overlay.bubble.state = BubbleView.State.SHOWING
      } else {
        toast("No text found")
        overlay.bubble.state = BubbleView.State.IDLE
      }
    } catch (e: NotReadyException) {
      toast(e.message ?: "Models missing")
      overlay.bubble.state = BubbleView.State.IDLE
    } catch (e: Exception) {
      Log.e(TAG, "translation failed", e)
      toast("Translation failed: ${e.message}")
      overlay.bubble.state = BubbleView.State.IDLE
    } finally {
      busy = false
    }
  }

  /** Hides our windows, waits for the compositor to produce a frame without them, and grabs it. */
  private suspend fun captureClean(): Frame? {
    val cap = capturer ?: return null
    overlay.hideForCapture()
    try {
      val gen = cap.generation
      delay(16)
      return cap.captureAfter(gen)
    } finally {
      overlay.showAfterCapture()
    }
  }

  // --- continuous mode ----------------------------------------------------------------------

  /**
   * Signature of the screen *as the user sees it*, i.e. captured with our current translations
   * showing. An unchanged screen reproduces it exactly, overlay included, so only real content
   * changes (outside the bubble and status bar) differ from it.
   */
  private var reference: ChangeDetector.Signature? = null

  private suspend fun continuousLoop() {
    var last: ChangeDetector.Signature? = null
    while (scope.isActive) {
      delay(app.settings.current.continuousIntervalMs)
      if (busy) continue
      val cap = capturer ?: continue
      val frame = cap.captureLatest() ?: continue
      val sig = withContext(worker) { changeDetector.signature(frame, overlay.volatileAreas()) }
      val ref = reference
      // Re-translate only once the content differs from the reference AND has stopped changing
      // (two consecutive matching samples), so scrolling or animations don't cause a refresh storm.
      val settled = last != null && !changeDetector.changed(sig, last)
      last = sig
      if (ref != null && (!changeDetector.changed(sig, ref) || !settled)) continue
      refreshContinuous()
      last = null
    }
  }

  private suspend fun refreshContinuous() {
    val cap = capturer ?: return
    busy = true
    try {
      // The current translations stay up while the new ones are computed; they are hidden only
      // for the single clean frame, so there is no visible blank period.
      val frame = captureClean() ?: return
      val result = withContext(worker) { app.pipeline.process(frame) }
      if (app.settings.current.mode != TranslateMode.CONTINUOUS) return
      val gen = cap.generation
      render(result, frame)
      // Reference = the first frame composited with the new overlay. If the overlay did not change
      // (same translations) no new frame comes and the timeout returns the current one, which is right.
      delay(32)
      val shown = cap.captureAfter(gen, timeoutMs = 300) ?: return
      reference = withContext(worker) { changeDetector.signature(shown, overlay.volatileAreas()) }
    } catch (e: NotReadyException) {
      toast(e.message ?: "Models missing")
      app.settings.update { it.copy(mode = TranslateMode.TAP) }
    } catch (e: Exception) {
      Log.e(TAG, "continuous refresh failed", e)
    } finally {
      busy = false
    }
  }

  // --- rendering ----------------------------------------------------------------------------

  /** @return true if anything is displayed. */
  private fun render(result: PipelineResult, frame: Frame): Boolean {
    val items = result.blocks.mapNotNull { tb ->
      val text = tb.translation?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val (bg, fg) = TranslationOverlayView.colorsFor(frame, tb.block.box)
      OverlayItem(tb.block.box, text, bg, fg)
    }
    val banner = result.missingModels.takeIf { it.isNotEmpty() }?.let { missing ->
      "Missing translation models: " + missing.joinToString { "${Languages.name(it.first)} → ${Languages.name(it.second)}" } +
        ". Install them in Screen Translate."
    }
    overlay.translations.show(items, result.frameWidth, result.frameHeight, banner, app.settings.current.overlayOpacity)
    return items.isNotEmpty() || banner != null
  }

  // --- lifecycle ----------------------------------------------------------------------------

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    val (w, h) = screenSize()
    capturer?.resize(w, h)
    clearOverlay()
    reference = null
  }

  override fun onDestroy() {
    running = false
    scope.cancel()
    overlay.detach()
    capturer?.release()
    capturer = null
    projection?.stop()
    projection = null
    // Free native models (hundreds of MB) once the queue drains; they reload lazily next time.
    worker.executor.execute { app.pipeline.close() }
    worker.close()
    super.onDestroy()
  }

  private fun screenSize(): Pair<Int, Int> {
    val wm = getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val b = wm.maximumWindowMetrics.bounds
      b.width() to b.height()
    } else {
      @Suppress("DEPRECATION")
      val m = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
      m.widthPixels to m.heightPixels
    }
  }

  private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

  private fun buildNotification(): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Screen translation", NotificationManager.IMPORTANCE_LOW),
    )
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    val stop = PendingIntent.getService(
      this, 1, Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle("Screen Translate is active")
      .setContentText("Tap the bubble to translate. Long-press it to switch mode.")
      .setContentIntent(open)
      .addAction(0, "Stop", stop)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val TAG = "ScreenTranslateService"
    private const val CHANNEL_ID = "screen_translate"
    private const val NOTIFICATION_ID = 1
    const val ACTION_START = "com.eillance.screentranslate.START"
    const val ACTION_STOP = "com.eillance.screentranslate.STOP"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"

    @Volatile var running: Boolean = false
      private set

    fun start(context: Context, resultCode: Int, data: Intent) {
      val intent = Intent(context, ScreenTranslateService::class.java)
        .setAction(ACTION_START)
        .putExtra(EXTRA_RESULT_CODE, resultCode)
        .putExtra(EXTRA_RESULT_DATA, data)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      context.startService(Intent(context, ScreenTranslateService::class.java).setAction(ACTION_STOP))
    }
  }
}
