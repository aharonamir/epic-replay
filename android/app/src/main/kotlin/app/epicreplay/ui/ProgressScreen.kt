package app.epicreplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.epicreplay.core.ui.PageWords
import app.epicreplay.core.ui.RunState
import app.epicreplay.core.ui.StepView

/**
 * The progress screen (ui.md section 2): "Making your replay", the bar of eight segments (filled when a step is finished, outlined while it runs, empty before), the time left in
 * words, and the eight steps with their state and seconds. At step 4 the play card opens in place ([card]); a stopped run says what happened and offers another clip.
 */
@Composable
fun ProgressScreen(state: RunState, words: PageWords, onAnother: () -> Unit, modifier: Modifier = Modifier, voiceKey: Boolean = false, onRetry: (() -> Unit)? = null, onAddKey: (() -> Unit)? = null, card: @Composable () -> Unit = {}) {
    val accent = LocalAccent.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(words.progress("label").uppercase(), style = MaterialTheme.typography.titleLarge)
            Text(words.progress("step").replace("{n}", state.stepN.toString()), style = MaterialTheme.typography.labelMedium, color = Ink.muted, modifier = Modifier.testTag("step_n"))
        }
        Row(Modifier.fillMaxWidth().testTag("bar"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in state.steps) Box(
                Modifier.weight(1f).height(8.dp).then(
                    when (s.status) {
                        "done" -> Modifier.background(accent.fill)
                        "running", "waiting_user" -> Modifier.border(BorderStroke(2.dp, accent.fill))
                        else -> Modifier.background(Ink.line)
                    },
                ).testTag("segment_${s.n}_${s.status}"),
            )
        }
        val atCard = state.status == "waiting_play_card"
        Text(
            (if (atCard) words.yourTurn else state.message ?: state.timeLeft).uppercase(), style = MaterialTheme.typography.headlineMedium,
            color = if (state.message != null) Ink.dangerText else Ink.text, modifier = Modifier.testTag("status_line"),
        )
        if (voiceKey) Text(words.stopped("voice_key"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("voice_key_note"))
        for (s in state.steps) {
            StepRow(s, words)
            if (s.n == 4 && atCard) card()
        }
        if (voiceKey && onAddKey != null) Button(onClick = onAddKey, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("add_key"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent)) { Text(words.stopped("add_key").uppercase(), style = MaterialTheme.typography.titleMedium) }
        // a run that broke keeps everything it finished: trying again goes on from the stage that failed
        if (state.status == "failed" && onRetry != null) androidx.compose.material3.OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("retry")) { Text(words.stopped("retry").uppercase(), style = MaterialTheme.typography.titleMedium) }
        if (state.status in setOf("stopped", "failed")) Button(onClick = onAnother, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("another"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent)) { Text(words.stopped("again").uppercase(), style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StepRow(s: StepView, words: PageWords) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("step_${s.n}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val mark = Modifier.size(18.dp)
        when (s.status) {
            "done" -> Box(mark.background(accent.fill, CircleShape))
            "running", "waiting_user" -> Box(mark.border(BorderStroke(3.dp, accent.fill), CircleShape))
            "failed" -> Box(mark.background(Ink.dangerText, CircleShape))
            else -> Box(mark.border(BorderStroke(2.dp, Ink.waiting), CircleShape))
        }
        Text(s.name, style = MaterialTheme.typography.titleMedium, color = if (s.status == "waiting") Ink.muted else Ink.text, modifier = Modifier.weight(1f))
        when {
            s.status == "running" -> Text(words.progress("working").uppercase(), style = MaterialTheme.typography.labelSmall, color = accent.text)
            s.status == "done" && s.durationS != null -> Text("${s.durationS} s", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
        }
    }
}
