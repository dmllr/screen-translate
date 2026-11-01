package com.eillance.screentranslate.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.eillance.screentranslate.capture.ScreenTranslateService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  /** Re-checked on resume, since permissions are granted in other screens. */
  private val permissionState = mutableStateOf(PermissionState())
  private val serviceRunning = mutableStateOf(false)

  private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    val data = result.data
    if (result.resultCode == RESULT_OK && data != null) {
      ScreenTranslateService.start(this, result.resultCode, data)
      // Leave the app so the user lands where they want to translate.
      moveTaskToBack(true)
    }
  }

  private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
    refreshPermissions()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AppTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showLicenses by rememberSaveable { mutableStateOf(false) }
        if (showLicenses) {
          LicensesScreen(onBack = { showLicenses = false })
          return@AppTheme
        }
        Scaffold(
          bottomBar = {
            NavigationBar {
              NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
              NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Download, null) }, label = { Text("Models") })
              NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
          },
        ) { padding ->
          val modifier = Modifier.padding(padding)
          when (tab) {
            0 -> HomeScreen(
              modifier = modifier,
              permissions = permissionState.value,
              running = serviceRunning.value,
              onRequestOverlay = ::requestOverlayPermission,
              onRequestNotifications = ::requestNotificationPermission,
              onStart = ::requestProjection,
              onStop = { ScreenTranslateService.stop(this) },
              onOpenModels = { tab = 1 },
            )
            1 -> ModelsScreen(modifier)
            else -> SettingsScreen(modifier, onOpenLicenses = { showLicenses = true })
          }
        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
          refreshPermissions()
          serviceRunning.value = ScreenTranslateService.running
          delay(1000)
        }
      }
    }
  }

  private fun refreshPermissions() {
    permissionState.value = PermissionState(
      overlay = Settings.canDrawOverlays(this),
      notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
    )
  }

  private fun requestOverlayPermission() {
    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
  }

  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  private fun requestProjection() {
    val mpm = getSystemService(MediaProjectionManager::class.java)
    // Overlay coordinates assume the whole display is captured, so ask for the full display
    // rather than offering "single app" sharing on Android 14+.
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
      mpm.createScreenCaptureIntent()
    }
    projectionLauncher.launch(intent)
  }
}

data class PermissionState(val overlay: Boolean = false, val notifications: Boolean = false)
