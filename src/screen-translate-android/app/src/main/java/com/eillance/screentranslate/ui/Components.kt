package com.eillance.screentranslate.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eillance.screentranslate.lang.Language
import com.eillance.screentranslate.models.ModelPack
import com.eillance.screentranslate.models.PackState
import java.util.Locale

@Composable
fun AppTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val context = LocalContext.current
  val scheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
  }
  MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePicker(label: String, languages: List<Language>, selected: String, onSelect: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    OutlinedTextField(
      value = languages.firstOrNull { it.code == selected }?.name ?: selected,
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
      modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      languages.forEach { lang ->
        DropdownMenuItem(text = { Text(lang.name) }, onClick = { onSelect(lang.code); expanded = false })
      }
    }
  }
}

/** One installable model with its state and install/delete action. */
@Composable
fun PackRow(pack: ModelPack, state: PackState?, onInstall: () -> Unit, onDelete: () -> Unit, title: String = pack.title) {
  Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
          "${pack.description} · ${formatSize(pack.totalSize)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      when (state) {
        is PackState.Installed -> TextButton(onClick = onDelete) { Text("Delete") }
        is PackState.Downloading -> Text("${(state.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        else -> OutlinedButton(onClick = onInstall) { Text(if (state is PackState.Failed) "Retry" else "Get") }
      }
    }
    if (state is PackState.Downloading) LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
    if (state is PackState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
  }
}

fun formatSize(bytes: Long): String = when {
  bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
  bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
  else -> "$bytes B"
}
