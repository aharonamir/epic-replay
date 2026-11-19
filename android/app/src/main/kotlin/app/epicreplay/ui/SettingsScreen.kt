package app.epicreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.KeyRow
import app.epicreplay.core.secrets.KeyStatus
import app.epicreplay.core.secrets.KeysController

/** The keys part of settings (spec 004 FR-008): each key masked, replace, delete. The key is never shown in full. */
@Composable
fun KeysSection(controller: KeysController, modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    var rows by remember { mutableStateOf(controller.rows()) }
    // While keys can be typed or shown masked, the window is excluded from screenshots, screen
    // recording and the recents thumbnail (review: the typed key must not be captured).
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("API keys", style = MaterialTheme.typography.titleLarge)
        Text(
            "Your keys stay on this phone, encrypted, and are never backed up. They go only to the service they belong to.",
            style = MaterialTheme.typography.bodyMedium,
        )
        rows.forEach { row -> KeyRowView(row, controller) { rows = controller.rows(); onChanged() } }
    }
}

@Composable
private fun KeyRowView(row: KeyRow, controller: KeysController, onChanged: () -> Unit) {
    var typed by remember(row.kind) { mutableStateOf("") }
    var editing by remember(row.kind) { mutableStateOf(row.status != KeyStatus.PRESENT) }
    var problem by remember(row.kind) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(row.kind.label, style = MaterialTheme.typography.titleMedium)
        when (row.status) {
            KeyStatus.PRESENT -> Text("Saved: ${row.masked}")
            KeyStatus.ABSENT -> Text("Not set")
            KeyStatus.UNREADABLE -> Text("The saved key can no longer be read. Enter it again.")
        }
        if (editing) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it; problem = null },
                label = { Text(if (row.status == KeyStatus.PRESENT) "New key" else "Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) {
                Button(onClick = {
                    problem = controller.save(row.kind, typed)
                    if (problem == null) { typed = ""; editing = false; onChanged() }
                }) { Text("Save") }
            } else {
                OutlinedButton(onClick = { editing = true }) { Text("Replace") }
            }
            if (row.status != KeyStatus.ABSENT) {
                OutlinedButton(onClick = {
                    problem = controller.delete(row.kind)
                    if (problem == null) { typed = ""; editing = true; onChanged() }
                }) { Text("Delete") }
            }
        }
    }
}
