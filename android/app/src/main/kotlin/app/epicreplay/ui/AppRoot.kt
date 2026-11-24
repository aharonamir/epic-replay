package app.epicreplay.ui

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.epicreplay.core.ui.RunView
import kotlinx.coroutines.launch

/**
 * What the app shows (spec 004 US4): the first-run choice of models until everything is set, then the start screen, the run in hand (the progress with the play card at step 4,
 * a stopped run's message), and the finished video. The accent is the narrator's: chosen on the start screen, then the run's own.
 */
@UnstableApi
@Composable
fun AppRoot(vm: AppViewModel, isReady: () -> Boolean, keys: app.epicreplay.core.secrets.KeysController, firstRun: @Composable (onContinue: () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var ready by remember { mutableStateOf(isReady()) }
    var choosing by remember { mutableStateOf<app.epicreplay.core.ui.NarratorCard?>(null) }
    val view by vm.session.view.collectAsState()
    val onStart by vm.onStart.collectAsState()
    val starting by vm.starting.collectAsState()
    val clip by vm.clip.collectAsState()
    val problem by vm.clipProblem.collectAsState()
    val scope = rememberCoroutineScope()
    var keysTick by remember { mutableStateOf(0) }
    var showKeys by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(0) } // 0 closed, 1 the choice, 2 the models, 3 the keys
    var pastPage by remember { mutableStateOf(0) } // 0 closed, 1 the list, 2 one of them
    var viewing by remember { mutableStateOf<java.io.File?>(null) }
    var pastTick by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val setupReady = remember(keysTick, settings, ready) { isReady() }
    val voiceKeyMissing = remember(keysTick) { keys.rows().first { it.kind == app.epicreplay.core.secrets.KeyKind.ELEVENLABS }.status != app.epicreplay.core.secrets.KeyStatus.PRESENT }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch { vm.chooseClip(uri) } }
    val film = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { scope.launch { vm.chooseClip(it) } } }
    val accent = if (onStart) (choosing?.let { accentOf(it.accent, null) } ?: DefaultAccent) else vm.accent(view)
    EpicTheme(accent) {
        androidx.compose.material3.Surface(color = Ink.ground, modifier = modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            AppHeader(onSettings = if (ready && settings == 0 && !showKeys && pastPage == 0) ({ settings = 1 }) else null, onPast = if (ready && settings == 0 && !showKeys && pastPage == 0) ({ notice = null; pastPage = 1 }) else null)
            notice?.let { Text(it, color = Ink.dangerText, modifier = Modifier.padding(horizontal = 16.dp).testTag("notice")) }
            when {
                pastPage == 1 -> PastScreen(remember(pastTick, ready) { vm.pastItems() }, vm.words, { viewing = java.io.File(vm.runsDir, it.runId); pastPage = 2 },
                    { item -> scope.launch { notice = vm.deleteRun(item.runId); pastTick++ } }, { pastPage = 0; vm.another() })
                pastPage == 2 && viewing != null -> {
                    val dir = viewing!!
                    ResultScreen(java.io.File(dir, "final.mp4"), remember(dir) { vm.result(dir) }, vm.words,
                        ResultActions({ ctx.startActivity(vm.shareIntent(dir)) }, { pastPage = 0; vm.another() }, { scope.launch { notice = vm.deleteRun(dir.name); pastTick++; if (notice == null) pastPage = 1 } }, { pastPage = 1 }))
                }
                settings == 1 -> Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Button(onClick = { settings = 2 }, modifier = Modifier.fillMaxWidth().testTag("settings_models")) { Text("Models: where they run, the server, the download") }
                    androidx.compose.material3.Button(onClick = { settings = 3 }, modifier = Modifier.fillMaxWidth().testTag("settings_keys")) { Text("Keys") }
                    androidx.compose.material3.OutlinedButton(onClick = { settings = 0 }, modifier = Modifier.fillMaxWidth().testTag("settings_back")) { Text("Back") }
                }
                settings == 2 -> firstRun { settings = 0 }
                settings == 3 -> Column {
                    KeysSection(keys, onChanged = { keysTick++ })
                    androidx.compose.material3.Button(onClick = { settings = 0 }, modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text("Done") }
                }
                showKeys -> Column {
                    KeysSection(keys, onChanged = { keysTick++ })
                    androidx.compose.material3.Button(onClick = { showKeys = false }, modifier = Modifier.padding(16.dp).androidx_fill().testTagKeys()) { Text("Done") }
                }
                !ready -> firstRun { ready = true }
                !onStart && view == null && starting -> androidx.compose.material3.Text("MAKING YOUR REPLAY", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
                onStart || view == null -> StartScreen(
                    vm.cards, vm.words, vm.consentCopy.consentText, vm.providerLine(), clip?.second, problem,
                    StartActions({ pick.launch("video/*") }, { film.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE)) }, ), { choosing = it }, vm::make,
                    voiceKeyMissing = voiceKeyMissing, onAddKey = { showKeys = true },
                    setupProblem = if (setupReady) null else "Setup is not finished: a key, the download or the consent is missing.", onSetup = { settings = 1 },
                )
                view!!.state.status == "done" && view!!.state.hasVideo -> {
                    val dir = view!!.dir
                    ResultScreen(java.io.File(dir, "final.mp4"), remember(dir) { vm.result(dir) }, vm.words,
                        ResultActions({ ctx.startActivity(vm.shareIntent(dir)) }, vm::another, { scope.launch { notice = vm.deleteRun(dir.name) } }))
                }
                else -> RunScreen(vm, view!!) { showKeys = true }
            }
        } }
    }
}

@UnstableApi
@Composable
private fun RunScreen(vm: AppViewModel, view: RunView, onAddKey: () -> Unit) {
    ProgressScreen(view.state, vm.words, vm::another, voiceKey = view.needsVoiceKey, onRetry = { vm.session.start(view.dir) }, onAddKey = onAddKey) {
        if (view.atCard) {
            val frames = remember(view.dir) { vm.frames(view.dir) }
            androidx.compose.runtime.DisposableEffect(view.dir) { onDispose { frames.close() } }
            PlayCardPanel(
                view.playSheet!!, view.clipMeta!!, vm.restCost(view), vm.words, frames,
                CardActions({ vm.session.answer(view.dir, it) }, { note -> vm.session.stopAtCard(view.dir, note) }, { vm.session.markFine(view.dir) }),
            )
        }
    }
}


private fun Modifier.androidx_fill() = this.fillMaxWidth()
private fun Modifier.testTagKeys() = this.testTag("keys_done")
