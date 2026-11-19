package app.epicreplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import app.epicreplay.core.download.DownloadError
import app.epicreplay.core.download.ModelNotice
import app.epicreplay.core.download.Progress
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.secrets.KeysController
import app.epicreplay.core.setup.SetupController
import app.epicreplay.core.setup.SetupStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the screen needs from the app, so a test can give it fakes (no network, no model). */
class FirstRunActions(
    val download: suspend (OnDeviceProfile, (Progress) -> Unit) -> Unit,
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
                    KeysSection(keys)
                    Button(onClick = { tick++ }) { Text("Continue") }
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
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<Progress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Text(step.notice.text())
    ModelNotice.connectionAdvice(step.profile, actions.unmetered())?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    progress?.let {
        LinearProgressIndicator(progress = { if (it.totalBytes > 0) it.doneBytes.toFloat() / it.totalBytes else 0f }, modifier = Modifier.fillMaxWidth())
        Text("${ModelNotice.megabytes(it.doneBytes)} of ${ModelNotice.megabytes(it.totalBytes)}")
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (job?.isActive == true) {
        OutlinedButton(onClick = { job?.cancel() }) { Text("Cancel") } // the part stays, so a later tap resumes
    } else {
        Button(onClick = {
            message = null
            job = scope.launch {
                try {
                    actions.download(step.profile) { progress = it }
                    onInstalled()
                } catch (e: CancellationException) {
                    message = "Stopped. What was downloaded is kept; tap Download to continue."
                    throw e
                } catch (e: DownloadError) {
                    message = e.message ?: "The download failed."
                }
            }
        }) { Text(if (step.remainingBytes < step.profile.files.sumOf { it.bytes }) "Resume download" else "Download") }
    }
}

@Composable
private fun ConsentPart(step: SetupStep.NeedsConsent, onConfirm: () -> Unit) {
    var ticked by remember { mutableStateOf(false) }
    step.blocks.forEach { Text(it) }
    Row {
        Checkbox(checked = ticked, onCheckedChange = { ticked = it })
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
