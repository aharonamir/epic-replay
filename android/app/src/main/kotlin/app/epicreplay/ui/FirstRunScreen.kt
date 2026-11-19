package app.epicreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import app.epicreplay.core.download.DownloadStatus
import kotlinx.coroutines.flow.StateFlow
import app.epicreplay.core.download.ModelNotice
import app.epicreplay.core.download.Progress
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.secrets.KeysController
import app.epicreplay.core.setup.SetupController
import app.epicreplay.core.setup.SetupStep
import kotlinx.coroutines.launch

/** What the screen needs from the app, so a test can give it fakes (no network, no model). */
class FirstRunActions(
    /** Starts the download outside the screen (a foreground service), so it goes on in the background. */
    val startDownload: (OnDeviceProfile) -> Unit,
    val cancelDownload: () -> Unit,
    val downloadStatus: StateFlow<DownloadStatus>,
    val acknowledgeDownload: () -> Unit,
    val unmetered: () -> Boolean,
    /** Asks the model chosen for [Role] one question and returns what it said (or why it could not). */
    val ask: suspend (Role) -> String,
)

/**
 * The first-run flow and, later, the models part of settings (spec 004 US1 and US3, T411). One screen that follows
 * [SetupController.step]: a choice for each role, then the key, the download, the consent, and when nothing is missing
 * the one-question check. Nothing here sends anything; a call is made only by [FirstRunActions.ask], after Ready.
 */
@Composable
fun FirstRunScreen(controller: SetupController, keys: KeysController, actions: FirstRunActions, modifier: Modifier = Modifier) {
    var tick by remember { mutableIntStateOf(0) } // bumped whenever something the step depends on changed
    var changing by remember { mutableStateOf(false) }
    val step = remember(tick) { controller.step() }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Where do the models run?", style = MaterialTheme.typography.headlineSmall)
        if (changing || step is SetupStep.NeedsChoice) {
            for (role in Role.entries) RolePicker(controller, role) { tick++ }
            if (changing && step !is SetupStep.NeedsChoice) Button(onClick = { changing = false; tick++ }) { Text("Done") }
        } else {
            Summary(controller) { changing = true }
            when (step) {
                is SetupStep.NeedsChoice -> {}
                is SetupStep.NeedsKey -> {
                    Text("A key is needed before anything is sent.", style = MaterialTheme.typography.titleMedium)
                    KeysSection(keys, onChanged = { tick++ }) // a saved or deleted key changes what is missing
                }
                is SetupStep.NeedsDownload -> DownloadPart(step, actions) { tick++ }
                is SetupStep.NeedsConsent -> ConsentPart(step) { controller.confirmConsent(); tick++ }
                is SetupStep.Ready -> ReadyPart(actions)
            }
        }
    }
}

@Composable
private fun RolePicker(controller: SetupController, role: Role, onChosen: () -> Unit) {
    var chosen by remember(role) { mutableStateOf(controller.chosen(role)?.id) }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (role == Role.VISION) "Looking at the clip" else "Writing the script", style = MaterialTheme.typography.titleMedium)
        for (option in controller.options(role)) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen == option.profile.id, role = SemanticsRole.RadioButton, onClick = {
                    controller.choose(role, option.profile.id); chosen = option.profile.id; onChosen()
                }),
            ) {
                RadioButton(selected = chosen == option.profile.id, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(option.profile.label)
                    Text(option.meaning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Summary(controller: SetupController, onChange: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Looking at the clip: ${controller.chosen(Role.VISION)?.label}")
        Text("Writing the script: ${controller.chosen(Role.TEXT)?.label}")
        OutlinedButton(onClick = onChange) { Text("Change") }
    }
}

@Composable
private fun DownloadPart(step: SetupStep.NeedsDownload, actions: FirstRunActions, onInstalled: () -> Unit) {
    val status by actions.downloadStatus.collectAsState()
    val mine = when (val s = status) {
        is DownloadStatus.Running -> s.profile.id == step.profile.id
        is DownloadStatus.Stopped -> s.profile.id == step.profile.id
        is DownloadStatus.Failed -> s.profile.id == step.profile.id
        is DownloadStatus.Done -> s.profile.id == step.profile.id
        DownloadStatus.Idle -> false
    }
    LaunchedEffect(status) { if (status is DownloadStatus.Done && mine) { actions.acknowledgeDownload(); onInstalled() } }
    Text(step.notice.text())
    ModelNotice.connectionAdvice(step.profile, actions.unmetered())?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    val running = status as? DownloadStatus.Running
    if (mine && running != null) {
        running.progress?.let {
            LinearProgressIndicator(progress = { if (it.totalBytes > 0) it.doneBytes.toFloat() / it.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
            Text("${ModelNotice.megabytes(it.doneBytes)} of ${ModelNotice.megabytes(it.totalBytes)}")
        }
        Text("It goes on in the background; a notification shows the progress.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = actions.cancelDownload) { Text("Cancel") } // the part stays, so a later tap resumes
    } else {
        when (val s = status) {
            is DownloadStatus.Stopped -> if (mine) Text("Stopped. What was downloaded is kept; tap Resume download to continue.")
            is DownloadStatus.Failed -> if (mine) Text(s.message, color = MaterialTheme.colorScheme.error)
            else -> {}
        }
        Button(onClick = { actions.startDownload(step.profile) }) {
            Text(if (step.remainingBytes < step.profile.files.sumOf { it.bytes }) "Resume download" else "Download")
        }
    }
}

@Composable
private fun ConsentPart(step: SetupStep.NeedsConsent, onConfirm: () -> Unit) {
    var ticked by remember { mutableStateOf(false) }
    step.blocks.forEach { Text(it) }
    Row(Modifier.fillMaxWidth().toggleable(value = ticked, role = SemanticsRole.Checkbox, onValueChange = { ticked = it })) {
        Checkbox(checked = ticked, onCheckedChange = null)
        Text(step.consentText, Modifier.padding(start = 8.dp))
    }
    Button(onClick = onConfirm, enabled = ticked) { Text("I agree") }
}

@Composable
private fun ReadyPart(actions: FirstRunActions) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var answers by remember { mutableStateOf<Map<Role, String>>(emptyMap()) }
    Text("Everything is set. Try both models with one question.")
    Button(enabled = !running, onClick = {
        running = true; answers = emptyMap()
        scope.launch {
            try {
                for (role in Role.entries) answers = answers + (role to actions.ask(role))
            } finally { running = false }
        }
    }) { Text("Ask both models one question") }
    for ((role, text) in answers) Text("${if (role == Role.VISION) "Vision" else "Text"}: $text")
}
