package app.epicreplay.ui

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
fun AppRoot(vm: AppViewModel, isReady: () -> Boolean, firstRun: @Composable (onContinue: () -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var ready by remember { mutableStateOf(isReady()) }
    var choosing by remember { mutableStateOf<app.epicreplay.core.ui.NarratorCard?>(null) }
    val view by vm.session.view.collectAsState()
    val onStart by vm.onStart.collectAsState()
    val starting by vm.starting.collectAsState()
    val clip by vm.clip.collectAsState()
    val problem by vm.clipProblem.collectAsState()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch { vm.chooseClip(uri) } }
    val film = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { scope.launch { vm.chooseClip(it) } } }
    val accent = if (onStart) (choosing?.let { accentOf(it.accent, null) } ?: DefaultAccent) else vm.accent(view)
    EpicTheme(accent) {
        androidx.compose.material3.Surface(color = Ink.ground, modifier = modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            AppHeader()
            when {
                !ready -> firstRun { ready = true }
                !onStart && view == null && starting -> androidx.compose.material3.Text("MAKING YOUR REPLAY", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
                onStart || view == null -> StartScreen(
                    vm.cards, vm.words, vm.consentCopy.consentText, vm.providerLine(), clip?.second, problem,
                    StartActions({ pick.launch("video/*") }, { film.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE)) }, ), { choosing = it }, vm::make,
                )
                view!!.state.status == "done" && view!!.state.hasVideo -> ResultScreen(java.io.File(view!!.dir, "final.mp4"), vm::another)
                else -> RunScreen(vm, view!!)
            }
        } }
    }
}

@UnstableApi
@Composable
private fun RunScreen(vm: AppViewModel, view: RunView) {
    ProgressScreen(view.state, vm.words, vm::another) {
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

