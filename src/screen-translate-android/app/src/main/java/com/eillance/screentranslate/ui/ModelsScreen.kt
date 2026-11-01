package com.eillance.screentranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eillance.screentranslate.app
import com.eillance.screentranslate.lang.Languages
import com.eillance.screentranslate.models.StaticCatalog
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(modifier: Modifier) {
  val app = LocalContext.current.app
  val states by app.models.states.collectAsState()
  val catalog by app.models.translationPacks.collectAsState()
  val settings by app.settings.state.collectAsState()
  val scope = rememberCoroutineScope()
  var refreshing by remember { mutableStateOf(false) }
  var refreshError by remember { mutableStateOf<String?>(null) }
  var query by remember { mutableStateOf("") }

  suspend fun refresh() {
    refreshing = true
    refreshError = app.models.refreshCatalog().exceptionOrNull()?.message
    refreshing = false
  }
  LaunchedEffect(Unit) { if (catalog.isEmpty()) refresh() }

  val target = settings.targetLanguage
  val filtered = catalog
    .filter { query.isBlank() || Languages.name(it.from!!).contains(query, true) || Languages.name(it.to!!).contains(query, true) }
    // Directions touching the target language first.
    .sortedWith(compareBy({ if (it.to == target || it.from == target) 0 else 1 }, { Languages.name(it.from!!) }, { Languages.name(it.to!!) }))

  LazyColumn(
    modifier.fillMaxSize().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    item { Header("Text recognition (OCR)") }
    item { Credit("PaddleOCR models by PaddlePaddle · Apache-2.0 · downloaded from huggingface.co/PaddlePaddle") }
    items(StaticCatalog.detectors + StaticCatalog.recognizers, key = { it.id }) { pack ->
      PackRow(pack, states[pack.id], onInstall = { app.install(pack) }, onDelete = { app.models.delete(pack.id) })
    }
    item { HorizontalDivider() }
    item { Header("Language identification") }
    item { Credit("fastText lid.176 by Meta · CC BY-SA 3.0 · downloaded unmodified from fasttext.cc") }
    item {
      val pack = StaticCatalog.languageId
      PackRow(pack, states[pack.id], onInstall = { app.install(pack) }, onDelete = { app.models.delete(pack.id) })
    }
    item { HorizontalDivider() }
    item {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Header("Translation models by Mozilla", Modifier.weight(1f))
        TextButton(onClick = { scope.launch { refresh() } }, enabled = !refreshing) { Text(if (refreshing) "…" else "Refresh") }
      }
    }
    item { Credit("From Mozilla's Firefox Translations project · MPL-2.0 · downloaded from Mozilla's servers") }
    item {
      Text(
        "Models translate to or from English. Other pairs go through English, e.g. Japanese → German needs ja→en and en→de.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    refreshError?.let { err -> item { Text("Could not load the model list: $err", color = MaterialTheme.colorScheme.error) } }
    item {
      OutlinedTextField(query, { query = it }, label = { Text("Filter languages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    items(filtered, key = { it.id }) { pack ->
      PackRow(
        pack, states[pack.id],
        onInstall = { app.install(pack) },
        onDelete = { app.models.delete(pack.id) },
        title = "${Languages.name(pack.from!!)} → ${Languages.name(pack.to!!)}",
      )
    }
  }
}

/** Model provenance and license, as the licenses require attribution. */
@Composable
private fun Credit(text: String) {
  Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
  Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 12.dp, bottom = 4.dp))
}
