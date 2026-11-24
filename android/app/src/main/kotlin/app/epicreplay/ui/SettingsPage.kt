package app.epicreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.epicreplay.core.download.ModelNotice
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.secrets.KeysController
import app.epicreplay.core.setup.InstalledModel
import app.epicreplay.core.setup.SetupController
import kotlinx.coroutines.launch

/** What the settings page needs from the app, so a test can give it fakes. */
class SettingsActions(
    val installed: () -> List<InstalledModel>,
    /** Deletes a model's files and the choices that used it; returns the reason it could not, or null. */
    val deleteModel: suspend (OnDeviceProfile) -> String?,
    /** The number of finished replays and the bytes the run folders take. */
    val storage: () -> Pair<Int, Long>,
    val onChangeModels: () -> Unit,
    val onPast: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * Settings as one page (spec 004 FR-SET-1, FR-SET-2, FR-CLOUD-1, FR-011): the models chosen, a cloud server of the user's own (address, model, API key in one form), the on-device
 * models on the phone with their sizes and Delete, the keys, and the storage the past replays take.
 */
@Composable
fun SettingsPage(controller: SetupController, keys: KeysController, actions: SettingsActions, modifier: Modifier = Modifier, onChanged: () -> Unit = {}) {
    var tick by remember { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<InstalledModel?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val installed = remember(tick) { actions.installed() }
    val storage = remember(tick) { actions.storage() }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("settings_page"))

        Text("Models", style = MaterialTheme.typography.titleLarge)
        for (role in Role.entries) {
            val p = controller.chosen(role)
            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("model_${role.id}")) {
                Text(if (role == Role.VISION) "Looking at the clip" else "Writing the script", style = MaterialTheme.typography.labelMedium)
                Text(p?.label ?: "Not chosen yet: you will be asked before the next run.")
            }
        }
        OutlinedButton(onClick = actions.onChangeModels, modifier = Modifier.testTag("settings_models")) { Text("Change models") }

        val mine = controller.customServer()
        Text("Cloud server of your own", style = MaterialTheme.typography.titleMedium)
        Text(mine?.let { "${it.label}\n${it.apiBase} · model ${it.model}" } ?: "Any OpenAI-compatible server over HTTPS, not only DeepSeek: base address, model name and API key.", modifier = Modifier.testTag("own_server_summary"))
        if (!editingServer) OutlinedButton(onClick = { editingServer = true }, modifier = Modifier.testTag("settings_server")) { Text(if (mine == null) "Add a server" else "Change my server") }
        else {
            CustomServerForm(mine, keySaved = controller.customKeySaved()) { server, key ->
                controller.saveCustomServer(server, null, key).also { problem -> if (problem == null) { editingServer = false; tick++; onChanged() } }
            }
            TextButton(onClick = { editingServer = false }) { Text("Cancel") }
        }
        Text("Choose it for a role under Change models.", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("On this phone", style = MaterialTheme.typography.titleLarge)
        if (installed.isEmpty()) Text("No model is stored on this phone.", modifier = Modifier.testTag("no_models"))
        installed.forEach { m ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = false) {}.testTag("installed_${m.profile.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.profile.label, style = MaterialTheme.typography.titleMedium)
                Text(ModelNotice.megabytes(m.bytes) + if (m.complete) "" else " (a download that was not finished)", modifier = Modifier.testTag("size_${m.profile.id}"))
                if (m.usedBy.isNotEmpty()) Text("Used for: " + m.usedBy.joinToString(", ") { if (it == Role.VISION) "looking at the clip" else "writing the script" }, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { asking = m }, modifier = Modifier.testTag("delete_model_${m.profile.id}")) { Text("Delete", color = Ink.dangerText) }
            }
        }
        notice?.let { Text(it, color = Ink.dangerText, modifier = Modifier.testTag("settings_notice")) }

        HorizontalDivider()
        KeysSection(keys, Modifier, onChanged = { tick++; onChanged() })

        HorizontalDivider()
        Text("Storage", style = MaterialTheme.typography.titleLarge)
        Text("${storage.first} ${if (storage.first == 1) "replay" else "replays"} · ${ModelNotice.megabytes(storage.second)} used by the clips and videos of your runs", modifier = Modifier.testTag("storage"))
        OutlinedButton(onClick = actions.onPast, modifier = Modifier.testTag("settings_past")) { Text("Past replays") }

        Button(onClick = actions.onBack, modifier = Modifier.fillMaxWidth().testTag("settings_back")) { Text("Done") }
    }
    asking?.let { m ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Delete ${m.profile.label}?") },
            text = { Text("Its ${ModelNotice.megabytes(m.bytes)} are removed from this phone." + if (m.usedBy.isNotEmpty()) " You will be asked to choose a model again before the next run." else "") },
            confirmButton = { TextButton(onClick = { asking = null; scope.launch { notice = actions.deleteModel(m.profile); tick++; onChanged() } }, modifier = Modifier.testTag("delete_model_confirm")) { Text("Delete", color = Ink.dangerText) } },
            dismissButton = { TextButton(onClick = { asking = null }, modifier = Modifier.testTag("delete_model_cancel")) { Text("Keep it") } },
        )
    }
}
