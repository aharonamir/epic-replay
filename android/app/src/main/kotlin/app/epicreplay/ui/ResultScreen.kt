package app.epicreplay.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.epicreplay.core.ui.PageWords
import app.epicreplay.core.ui.ResultData
import java.io.File

/** What the result screen can do besides show: share the video, make another, go back (from the past list), delete this clip and every version of it. */
class ResultActions(val onShare: () -> Unit, val onNew: () -> Unit, val onDelete: () -> Unit, val onBack: (() -> Unit)? = null)

/**
 * The finished replay (spec 004 FR-017, FR-018, FR-023; ui.md section 4): the video, what the coded fallback wrote when it did, the script with the time each line is spoken, the models
 * each stage ran on, the time and cost; share, make another, delete.
 */
@Composable
fun ResultScreen(video: File, data: ResultData?, words: PageWords, actions: ResultActions, modifier: Modifier = Modifier, turnForFullScreen: Boolean = true) {
    val accent = LocalAccent.current
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (actions.onBack != null) TextButton(onClick = actions.onBack, modifier = Modifier.testTag("result_back")) { Text("Back to past replays") }
        Text(words.result("headline").uppercase(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("result_headline"))
        if (data != null) Text(data.headline, style = MaterialTheme.typography.titleLarge, color = accent.text, modifier = Modifier.testTag("result_title"))
        VideoPlayer(video, turnForFullScreen = turnForFullScreen)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = actions.onShare, modifier = Modifier.weight(1f).height(56.dp).testTag("share"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent)) { Text("SHARE") }
            OutlinedButton(onClick = actions.onNew, modifier = Modifier.weight(1f).height(56.dp).testTag("new_clip")) { Text(if (actions.onBack == null) "MAKE ANOTHER" else "MAKE A NEW ONE") }
        }
        if (data != null) {
            if (data.fallback.lines.isNotEmpty()) Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, accent.text), RoundedCornerShape(6.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About this replay", fontWeight = FontWeight.Bold)
                data.fallback.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("fallback_note")) }
            }
            Section("script", words.panel("script")) {
                val panel = data.panel
                if (panel == null) Text("No script was kept for this replay.", color = Ink.muted)
                else {
                    panel.lines.forEachIndexed { i, l ->
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("line_$i").then(if (l.key) Modifier.border(BorderStroke(1.dp, accent.text), RoundedCornerShape(6.dp)).padding(8.dp) else Modifier.padding(vertical = 4.dp))) {
                            Text(l.time + l.role.uppercase() + if (l.generic) " · written by code" else "", style = MaterialTheme.typography.labelMedium, color = if (l.key) accent.text else Ink.muted)
                            Text(l.text, style = MaterialTheme.typography.bodyLarge)
                            if (l.gloss != null) Text(l.gloss!!, style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                            if (l.dropped) Text(words.panel("dropped"), style = MaterialTheme.typography.bodySmall, color = Ink.dangerText)
                        }
                    }
                    if (panel.passed) Text(words.panel("passed"), color = Ink.muted, modifier = Modifier.testTag("checks_passed"))
                    else { Text(words.panel("failed"), color = Ink.dangerText); panel.failures.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
            }
            Section("models", "The models") {
                if (data.models.isEmpty()) Text("This replay did not record which models it used.", color = Ink.muted)
                data.models.forEach { Column { Text(it.name, style = MaterialTheme.typography.labelMedium, color = Ink.muted); Text(it.value, modifier = Modifier.testTag("model_row")); if (it.steps.isNotEmpty()) Text("Used for: " + it.steps.joinToString(", ") + ". The other steps ran as code on this phone.", style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.testTag("model_steps")) } }
            }
            Section("cost", words.panel("time")) {
                data.time.rows.forEach { r -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(r.name, modifier = Modifier.weight(1f)); Text((if (r.duration.isEmpty()) "" else r.duration + " · ") + "${r.cost} USD", style = MaterialTheme.typography.bodyMedium) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(words.panel("total"), fontWeight = FontWeight.Bold); Text("${data.time.duration} · ${data.time.cost} USD", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("total_cost")) }
            }
        }
        TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("delete")) { Text(words.result("delete"), color = Ink.dangerText) }
        Spacer(Modifier.height(24.dp))
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(words.result("delete")) },
        text = { Text("The clip and the video are removed from this phone." + if ((data?.versions ?: 1) > 1) " " + words.result("all_versions").replace("{n}", data!!.versions.toString()) else "") },
        confirmButton = { TextButton(onClick = { confirm = false; actions.onDelete() }, modifier = Modifier.testTag("delete_confirm")) { Text("Delete", color = Ink.dangerText) } },
        dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.testTag("delete_cancel")) { Text("Keep it") } },
    )
}

/** A panel that opens and closes, closed at first (ui.md section 4). */
@Composable
private fun Section(tag: String, title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Ink.line), RoundedCornerShape(6.dp))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp).testTag("section_$tag"), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text(if (open) "−" else "+")
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/**
 * The finished video with its controls and a full-screen button (spec 004 FR-PLAYER-1): full screen is a dialog over the whole screen, the phone turned to landscape for a landscape
 * video, the system bars hidden, playing from where the small player was and leaving the small one where the big one stopped.
 */
@Composable
fun VideoPlayer(video: File, modifier: Modifier = Modifier, turnForFullScreen: Boolean = true) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var position by rememberSaveable { mutableStateOf(0) }
    var playing by rememberSaveable { mutableStateOf(false) }
    val small = remember { arrayOfNulls<VideoView>(1) }
    val landscape = remember(video) { isLandscape(video) }
    Column(modifier) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(video.path)
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    seekTo(maxOf(position, 1)) // the first frame, the title card, until it is played: the video needs its sound
                    small[0] = this
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 9f / 16f).testTag("player"),
        )
        TextButton(onClick = { small[0]?.let { position = it.currentPosition; playing = it.isPlaying; it.pause() }; fullscreen = true }, modifier = Modifier.testTag("fullscreen")) { Text("Full screen") }
    }
    if (fullscreen) FullScreenPlayer(video, landscape && turnForFullScreen, position, playing) { at -> position = at; small[0]?.seekTo(at); fullscreen = false }
}

@Composable
private fun FullScreenPlayer(video: File, landscape: Boolean, startMs: Int, autoPlay: Boolean, onExit: (Int) -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val view = androidx.compose.ui.platform.LocalView.current
    val big = remember { arrayOfNulls<VideoView>(1) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val before = activity?.requestedOrientation
        if (landscape) activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { before?.let { activity.requestedOrientation = it } }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { onExit(big[0]?.currentPosition ?: startMs) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect {
            window?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply {
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).testTag("fullscreen_player")) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(video.path)
                        setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                        setOnPreparedListener { seekTo(startMs); if (autoPlay) start() }
                        big[0] = this
                    }
                },
                modifier = Modifier.fillMaxSize().displayCutoutPadding(),
            )
            TextButton(onClick = { onExit(big[0]?.currentPosition ?: startMs) }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().testTag("exit_fullscreen")) { Text("Exit full screen", color = androidx.compose.ui.graphics.Color.White) }
        }
    }
}

/** Whether the video's picture is wider than it is tall (a clip of the phone's own can be either); wider when the size cannot be read. */
private fun isLandscape(video: File): Boolean = try {
    android.media.MediaMetadataRetriever().let { r ->
        try {
            r.setDataSource(video.path)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) h > w else w >= h
        } finally { r.release() }
    }
} catch (e: Exception) { true }
