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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
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
 * The first run as a wizard (spec 004 FR-WIZ-1, T411): the steps that apply, one screen each, "Step n of m" above them: the two models; a cloud model's key (with its address and
 * model name shown, or entered for a server of the user's own); an on-device model's download; the voice's key; the consent; then the one-question check. A key is entered alone on its
 * screen and saving it moves on. Nothing here sends anything; a call is made only by [FirstRunActions.ask], after Ready.
 */
@Composable
fun FirstRunScreen(controller: SetupController, keys: KeysController, actions: FirstRunActions, modifier: Modifier = Modifier, onContinue: (() -> Unit)? = null) {
    var tick by remember { mutableIntStateOf(0) } // bumped whenever something the step depends on changed
    var changing by remember { mutableStateOf(false) }
    val step = remember(tick) { controller.step() }
    val outline = remember(tick) { controller.outline() }
    val at = outline.indexOfFirst { !it.second }.let { if (it < 0) outline.lastIndex else it }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Step ${at + 1} of ${outline.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("wizard_step"))
        if (changing || step is SetupStep.NeedsChoice) {
            Text("Where do the models run?", style = MaterialTheme.typography.headlineSmall)
            for (role in Role.entries) RolePicker(controller, role) { tick++ }
            if (changing && step !is SetupStep.NeedsChoice) Button(onClick = { changing = false; tick++ }) { Text("Done") }
        } else {
            when (step) {
                is SetupStep.NeedsChoice -> {}
                is SetupStep.NeedsKey -> {
                    Text("A key is needed before anything is sent.", style = MaterialTheme.typography.headlineSmall)
                    for (p in listOfNotNull(controller.chosen(Role.VISION), controller.chosen(Role.TEXT)).distinctBy { it.id }.filterIsInstance<app.epicreplay.core.models.ServerProfile>()) {
                        Text("${p.label}\n${p.apiBase} · model ${p.model}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("server_${p.id}"))
                    }
                    KeyStep(keys, step.key) { tick++ }
                }
                is SetupStep.NeedsDownload -> { Summary(controller) { changing = true }; DownloadPart(step, actions) { tick++ } }
                is SetupStep.NeedsVoiceKey -> {
                    Text("The voice", style = MaterialTheme.typography.headlineSmall)
                    Text("Every replay is spoken with ElevenLabs. Each replay costs about five cents of voice; the key stays on this phone.", style = MaterialTheme.typography.bodyMedium)
                    KeyStep(keys, step.key) { tick++ }
                }
                is SetupStep.NeedsConsent -> { Summary(controller) { changing = true }; ConsentPart(step) { controller.confirmConsent(); tick++ } }
                is SetupStep.Ready -> { Summary(controller) { changing = true }; ReadyPart(actions, onContinue) }
            }
        }
    }
}

/** One key on a screen of its own: its field, Save, and on a saved key the wizard goes on. */
@Composable
private fun KeyStep(keys: KeysController, kind: app.epicreplay.core.secrets.KeyKind, onSaved: () -> Unit) {
    SecureWhileTyping()
    KeyRowView(keys.rows().first { it.kind == kind }, keys) { onSaved() }
}

@Composable
private fun RolePicker(controller: SetupController, role: Role, onChosen: () -> Unit) {
    var chosen by remember(role) { mutableStateOf(controller.chosen(role)?.id) }
    var editing by remember(role) { mutableStateOf(false) }
    val ownId = app.epicreplay.core.models.CustomServers.ID
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (role == Role.VISION) "Looking at the clip" else "Writing the script", style = MaterialTheme.typography.titleMedium)
        for (option in controller.options(role).filter { it.profile.id != ownId }) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = chosen == option.profile.id, role = SemanticsRole.RadioButton, onClick = {
                    controller.choose(role, option.profile.id); chosen = option.profile.id; editing = false; onChosen()
                }),
            ) {
                RadioButton(selected = chosen == option.profile.id, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(option.profile.label)
                    Text(option.meaning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // a server of the user's own (FR-CUSTOM-1): chosen once it is saved; its form opens to enter or to change it
        val mine = controller.customServer()
        Row(
            Modifier.fillMaxWidth().testTag("own_server_${role.id}").selectable(selected = chosen == ownId, role = SemanticsRole.RadioButton, onClick = {
                if (mine != null) { controller.choose(role, ownId); chosen = ownId; onChosen() } else editing = true
            }),
        ) {
            RadioButton(selected = chosen == ownId, onClick = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(mine?.label ?: "Your own server")
                Text(mine?.let { "${it.apiBase} · model ${it.model}" } ?: "Any OpenAI-compatible server over HTTPS: its address, model and key.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (mine != null && !editing) OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("edit_own_server_${role.id}")) { Text("Change my server") }
        if (editing) CustomServerForm(mine) { server ->
            controller.saveCustomServer(server, role); chosen = ownId; editing = false; onChosen()
        }
    }
}

/** The form of the user's own server: name, address, model, the prices that the spending caps count with, and whether it takes tool calls. */
@Composable
private fun CustomServerForm(initial: app.epicreplay.core.models.CustomServer?, onSave: (app.epicreplay.core.models.CustomServer) -> Unit) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var base by remember { mutableStateOf(initial?.apiBase ?: "https://") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var pin by remember { mutableStateOf(initial?.priceInPerM?.takeIf { it > 0 }?.toString() ?: "") }
    var pout by remember { mutableStateOf(initial?.priceOutPerM?.takeIf { it > 0 }?.toString() ?: "") }
    var tools by remember { mutableStateOf(initial?.supportsTools ?: true) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(start = 8.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(label, { label = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server_label"))
        OutlinedTextField(base, { base = it; problem = null }, label = { Text("Address, e.g. https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server_base"))
        OutlinedTextField(model, { model = it; problem = null }, label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("server_model"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(pin, { pin = it; problem = null }, label = { Text("USD per million in") }, singleLine = true, modifier = Modifier.weight(1f).testTag("server_price_in"))
            OutlinedTextField(pout, { pout = it; problem = null }, label = { Text("USD per million out") }, singleLine = true, modifier = Modifier.weight(1f).testTag("server_price_out"))
        }
        Text("The prices let the spending caps count this server's cost; leave them empty if you do not know them (the caps then cannot count it).", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.toggleable(tools, role = SemanticsRole.Checkbox, onValueChange = { tools = it }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(tools, onCheckedChange = null); Text("It takes tool calls", Modifier.padding(start = 8.dp))
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("server_problem")) }
        Button(onClick = {
            problem = app.epicreplay.core.models.CustomServers.problem(label, base, model, pin, pout)
            if (problem == null) onSave(app.epicreplay.core.models.CustomServers.of(label, base, model, pin, pout, tools)!!)
        }, modifier = Modifier.testTag("server_save")) { Text("Save") }
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
private fun ReadyPart(actions: FirstRunActions, onContinue: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var answers by remember { mutableStateOf<Map<Role, String>>(emptyMap()) }
    Text("Everything is set.")
    if (onContinue != null) Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("continue")) { Text("Make a replay") }
    Text("Or try both models with one question first.")
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
