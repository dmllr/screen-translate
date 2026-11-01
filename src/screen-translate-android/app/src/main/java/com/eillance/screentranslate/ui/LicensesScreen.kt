package com.eillance.screentranslate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eillance.screentranslate.licenses.LicenseEntry
import com.eillance.screentranslate.licenses.OpenSourceLicenses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings → Open-source licenses: list of components, tap one to read its license text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val index = remember { OpenSourceLicenses.load(context) }
  var open by remember { mutableStateOf<LicenseEntry?>(null) }

  BackHandler { if (open != null) open = null else onBack() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(open?.name ?: "Open-source licenses") },
        navigationIcon = {
          IconButton(onClick = { if (open != null) open = null else onBack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    val entry = open
    if (entry != null) {
      LicenseText(entry, Modifier.padding(padding))
    } else {
      LazyColumn(Modifier.fillMaxSize().padding(padding)) {
        item {
          Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(index.sourceNotice, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
          }
        }
        index.sections.forEach { section ->
          item {
            Text(
              section.title,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
          }
          items(section.entries, key = { section.id + it.name }) { e ->
            ListItem(
              headlineContent = { Text(e.name) },
              supportingContent = {
                Column {
                  Text(listOf(e.license, e.version).filter { it.isNotBlank() }.joinToString(" · "))
                  if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                }
              },
              modifier = Modifier.clickable { open = e },
            )
            HorizontalDivider()
          }
        }
      }
    }
  }
}

/** License text, chunked so the 300+ kB ONNX Runtime notices scroll smoothly. */
@Composable
private fun LicenseText(entry: LicenseEntry, modifier: Modifier) {
  val context = LocalContext.current
  var chunks by remember(entry) { mutableStateOf<List<String>>(emptyList()) }
  LaunchedEffect(entry) {
    chunks = withContext(Dispatchers.IO) {
      OpenSourceLicenses.text(context, entry).lines().chunked(40).map { it.joinToString("\n") }
    }
  }
  LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    item {
      Text(entry.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp))
    }
    items(chunks) { chunk ->
      Text(chunk, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    }
  }
}
