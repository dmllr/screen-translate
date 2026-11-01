package com.eillance.screentranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.eillance.screentranslate.BuildConfig
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eillance.screentranslate.app
import com.eillance.screentranslate.models.PackState
import com.eillance.screentranslate.models.StaticCatalog
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(modifier: Modifier, onOpenLicenses: () -> Unit) {
  val app = LocalContext.current.app
  val s by app.settings.state.collectAsState()
  val states by app.models.states.collectAsState()

  Column(
    modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionCard("Text detector") {
      StaticCatalog.detectors.forEach { pack ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          RadioButton(
            selected = s.detectorPackId == pack.id,
            onClick = { app.settings.update { it.copy(detectorPackId = pack.id) } },
            enabled = states[pack.id] is PackState.Installed,
          )
          Column {
            Text(pack.title)
            Text(pack.description, style = MaterialTheme.typography.bodySmall)
          }
        }
      }
      LabeledSlider(
        "Detection resolution: ${s.detectorMaxSide}px",
        "Higher finds smaller text but is slower.",
        s.detectorMaxSide.toFloat(), 640f..2048f, steps = 11,
      ) { v -> app.settings.update { it.copy(detectorMaxSide = (v / 128).roundToInt() * 128) } }
    }

    SectionCard("Translation") {
      LabeledSlider(
        "Translation threads: ${s.translationThreads}",
        "More threads translate long screens faster but use more memory.",
        s.translationThreads.toFloat(), 1f..4f, steps = 2,
      ) { v -> app.settings.update { it.copy(translationThreads = v.roundToInt()) } }
    }

    SectionCard("Continuous mode") {
      LabeledSlider(
        "Check every ${s.continuousIntervalMs} ms",
        "How often the screen is checked for changes.",
        s.continuousIntervalMs.toFloat(), 300f..3000f, steps = 8,
      ) { v -> app.settings.update { it.copy(continuousIntervalMs = ((v / 100).roundToInt() * 100).toLong()) } }
    }

    SectionCard("Overlay") {
      LabeledSlider(
        "Background opacity: ${(s.overlayOpacity * 100).roundToInt()}%",
        null,
        s.overlayOpacity, 0.5f..1f, steps = 9,
      ) { v -> app.settings.update { it.copy(overlayOpacity = v) } }
    }

    SectionCard("About") {
      Text(
        "Screen Translate ${BuildConfig.VERSION_NAME}. Built on open-source software and openly licensed models.",
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedButton(onClick = onOpenLicenses) { Text("Open-source licenses") }
    }
  }
}

@Composable
private fun LabeledSlider(
  title: String,
  description: String?,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  steps: Int,
  onChange: (Float) -> Unit,
) {
  Column {
    Text(title)
    description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
  }
}
