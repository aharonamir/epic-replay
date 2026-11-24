package app.epicreplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.epicreplay.core.ui.PageWords
import app.epicreplay.core.ui.PastItem

/** The past replays (spec 004 FR-023): every finished video, newest first, with a delete that takes the clip and every version made from it. */
@Composable
fun PastScreen(items: List<PastItem>, words: PageWords, onOpen: (PastItem) -> Unit, onDelete: (PastItem) -> Unit, onMake: () -> Unit, modifier: Modifier = Modifier) {
    var asking by remember { mutableStateOf<PastItem?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(words.past("headline").uppercase(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("past_headline"))
        Text(if (items.size == 1) words.past("one") else words.past("many").replace("{n}", items.size.toString()), color = Ink.muted, modifier = Modifier.testTag("past_count"))
        if (items.isEmpty()) Text(words.past("empty"), modifier = Modifier.testTag("past_empty"))
        items.forEach { it ->
            Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Ink.line), RoundedCornerShape(6.dp)).padding(16.dp).testTag("past_${it.runId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(it.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(it.date, it.durationS?.let { d -> "${Math.round(d)} s" }, "${it.costUsd} USD", if (it.versions > 1) "${it.versions} versions" else null).joinToString(" · "), color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(it) }, modifier = Modifier.testTag("open_${it.runId}")) { Text("Open") }
                    OutlinedButton(onClick = { asking = it }, modifier = Modifier.testTag("delete_${it.runId}")) { Text("Delete", color = Ink.dangerText) }
                }
            }
        }
        OutlinedButton(onClick = onMake, modifier = Modifier.fillMaxWidth().testTag("past_make")) { Text(words.past("make")) }
    }
    asking?.let { item ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(words.result("delete")) },
            text = { Text("The clip and the video are removed from this phone." + if (item.versions > 1) " " + words.result("all_versions").replace("{n}", item.versions.toString()) else "") },
            confirmButton = { TextButton(onClick = { asking = null; onDelete(item) }, modifier = Modifier.testTag("past_delete_confirm")) { Text("Delete", color = Ink.dangerText) } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Keep it") } },
        )
    }
}
