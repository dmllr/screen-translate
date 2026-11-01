package com.eillance.screentranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eillance.screentranslate.app
import com.eillance.screentranslate.lang.Languages
import com.eillance.screentranslate.models.ModelPack
import com.eillance.screentranslate.models.PackState
import com.eillance.screentranslate.settings.TranslateMode

@Composable
fun HomeScreen(
  modifier: Modifier,
  permissions: PermissionState,
  running: Boolean,
  onRequestOverlay: () -> Unit,
  onRequestNotifications: () -> Unit,
  onStart: () -> Unit,
  onStop: () -> Unit,
  onOpenModels: () -> Unit,
) {
  val app = LocalContext.current.app
  val settings by app.settings.state.collectAsState()
  val states by app.models.states.collectAsState()
  val catalog by app.models.translationPacks.collectAsState()

  LaunchedEffect(Unit) { if (catalog.isEmpty()) app.models.refreshCatalog() }

  val missingRequired = app.models.missingRequired().filter { states[it.id] !is PackState.Installed }
  val neededDirections = app.requiredDirections(settings.fallbackSourceLanguage, settings.targetLanguage)
  val neededPacks: List<ModelPack> = neededDirections.mapNotNull { (f, t) -> catalog.firstOrNull { it.from == f && it.to == t } }
  val missingTranslation = neededPacks.filter { states[it.id] !is PackState.Installed }
  val unavailable = neededDirections.filter { (f, t) -> catalog.isNotEmpty() && catalog.none { it.from == f && it.to == t } }

  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Screen Translate", style = MaterialTheme.typography.headlineMedium)
    Text(
      "On-device OCR and translation. Nothing leaves your phone.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionCard("Languages") {
      LanguagePicker("Translate into", Languages.all, settings.targetLanguage) { code ->
        app.settings.update { it.copy(targetLanguage = code) }
      }
      LanguagePicker("Usual source language (fallback)", Languages.all, settings.fallbackSourceLanguage) { code ->
        app.settings.update { it.copy(fallbackSourceLanguage = code) }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Detect language automatically")
          Text(
            "Any language on screen → ${Languages.name(settings.targetLanguage)}. Falls back to the source above when unsure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(settings.autoDetect, { v -> app.settings.update { it.copy(autoDetect = v) } })
      }
    }

    if (missingRequired.isNotEmpty() || missingTranslation.isNotEmpty() || unavailable.isNotEmpty()) {
      SectionCard("Models needed") {
        val toInstall = missingRequired + missingTranslation
        if (toInstall.isNotEmpty()) {
          Text(
            toInstall.joinToString("\n") { "• ${it.title} (${formatSize(it.totalSize)})" },
            style = MaterialTheme.typography.bodyMedium,
          )
          val downloading = toInstall.any { states[it.id] is PackState.Downloading }
          Button(onClick = { toInstall.forEach(app::install) }, enabled = !downloading, modifier = Modifier.fillMaxWidth()) {
            Text(if (downloading) "Downloading…" else "Download ${formatSize(toInstall.sumOf { it.totalSize })}")
          }
        }
        if (catalog.isEmpty()) {
          Text("Loading the translation model list…", style = MaterialTheme.typography.bodySmall)
        }
        unavailable.forEach { (f, t) ->
          Text(
            "No ${Languages.name(f)} → ${Languages.name(t)} model has been published by Mozilla yet.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        OutlinedButton(onClick = onOpenModels) { Text("More languages…") }
      }
    }

    SectionCard("Permissions") {
      PermissionRow("Draw over other apps", "Needed for the bubble and the translation overlay.", permissions.overlay, onRequestOverlay)
      PermissionRow("Notifications", "Shows the running indicator with a Stop button.", permissions.notifications, onRequestNotifications)
    }

    SectionCard("Mode") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Continuous translation (experimental)")
          Text(
            "Re-translates whenever the screen changes. Uses more battery. Long-press the bubble to toggle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          settings.mode == TranslateMode.CONTINUOUS,
          { v -> app.settings.update { it.copy(mode = if (v) TranslateMode.CONTINUOUS else TranslateMode.TAP) } },
        )
      }
    }

    val ready = permissions.overlay && missingRequired.isEmpty()
    if (running) {
      FilledTonalButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop screen translation") }
    } else {
      Button(onClick = onStart, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Start screen translation") }
      if (!ready) {
        Text(
          "Grant the overlay permission and download the required models first.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, onRequest: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title)
      Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (granted) Text("Granted", color = MaterialTheme.colorScheme.primary) else OutlinedButton(onClick = onRequest) { Text("Grant") }
  }
}
