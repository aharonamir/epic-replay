package app.epicreplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.epicreplay.core.card.PlayCard
import app.epicreplay.core.ui.CardAnswerInput
import app.epicreplay.core.ui.CardModel
import app.epicreplay.core.ui.CardState
import app.epicreplay.core.ui.CardView
import app.epicreplay.core.ui.PageWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Where the card's pictures come from: a frame of the clip by its number (null when it cannot be read). */
fun interface FrameSource {
    suspend fun frame(n: Int): ImageBitmap?
}

/** What the card does with the user's answer. */
class CardActions(
    val confirm: (CardAnswerInput) -> Unit, val stop: (String) -> Unit, val markFine: () -> Unit,
)

/**
 * The play card (ui.md section 3): the frame of the key moment with its number and time, the nudges (a tap moves one frame, a hold five a second; "10 back" and "10 on"), the
 * other moments, the activity, the events ("Check this" ones need Keep or Remove), and the three answers with the cost of the rest of the run. The key moment is always an item
 * to answer. All the rules are in [CardModel].
 */
@Composable
fun PlayCardPanel(ps: JsonObject, meta: JsonObject, costUsd: Double, words: PageWords, frames: FrameSource, actions: CardActions, modifier: Modifier = Modifier) {
    var s by remember { mutableStateOf(CardModel.start(ps, meta)) }
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.frame) { picture = frames.frame(s.frame) }
    Column(modifier.fillMaxWidth().border(BorderStroke(1.dp, Ink.line), RoundedCornerShape(6.dp)).background(Ink.surface, RoundedCornerShape(6.dp)).padding(16.dp).testTag("play_card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.needsFineCheck && !s.fineAnswered) {
            Text(words.card("unchecked"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("fine_question"))
            Button(onClick = { actions.markFine(); s = CardModel.fine(s) }, modifier = Modifier.fillMaxWidth().testTag("fine_yes"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent)) { Text(words.card("fine")) }
            OutlinedButton(onClick = { actions.stop(app.epicreplay.core.ui.RunSession.USER_STOP) }, modifier = Modifier.fillMaxWidth().testTag("fine_no")) { Text(words.card("not_fine")) }
            return@Column
        }
        if (s.view == CardView.REJECT) {
            Text(words.card("generic_note"), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = { runCatching { actions.confirm(CardModel.answerInput(s, generic = true)) }.onFailure { error = it.message } }, enabled = s.keyMomentAnswered,
                modifier = Modifier.fillMaxWidth().testTag("generic"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent),
            ) { Text(words.card("generic")) }
            if (!s.keyMomentAnswered) Text(words.card("why_generic"), style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            OutlinedButton(onClick = { actions.stop(app.epicreplay.core.ui.RunSession.REJECTED) }, modifier = Modifier.fillMaxWidth().testTag("stop_here")) { Text(words.card("stop")) }
            OutlinedButton(onClick = { s = CardModel.back(s) }, modifier = Modifier.fillMaxWidth().testTag("back_to_card")) { Text(words.card("back")) }
            return@Column
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink.raised).border(BorderStroke(2.dp, if (s.keyMomentAnswered) Ink.line else accent.text), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            picture?.let { Image(it, "Frame ${s.frame} of the clip", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
        }
        Text(words.card("frame").replace("{frame}", s.frame.toString()).replace("{t}", "%.2f".format(java.util.Locale.ROOT, s.seconds)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("frame_label"))
        if (!s.keyMomentAnswered) Text(words.card("check").uppercase() + " · " + words.card("pick"), style = MaterialTheme.typography.labelSmall, color = accent.text, modifier = Modifier.testTag("pick_flag"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((tag, label, by) in listOf(Triple("back10", "back10", -10), Triple("earlier", "earlier", -1), Triple("later", "later", 1), Triple("on10", "on10", 10))) {
                RepeatButton(words.card(label), Modifier.weight(1f).testTag(tag)) { s = CardModel.nudge(s, by) }
            }
        }
        // "This one" is the answer to the moment: a filled button in the accent while it is unanswered (it looked like a disabled outline), and a ticked one that can still be pressed once it is
        if (!s.keyMomentAnswered) Button(onClick = { s = CardModel.thisOne(s) }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("this_one"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent)) { Text(words.card("this_one").uppercase(), maxLines = 1) }
        else OutlinedButton(onClick = { s = CardModel.thisOne(s) }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("this_one")) { Text("✓ " + words.card("this_one"), maxLines = 1) }
        val others = CardModel.otherMoments(ps)
        if (others.isNotEmpty()) {
            Text(words.card("others"), style = MaterialTheme.typography.labelSmall, color = Ink.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((frame, _) in others) OutlinedButton(onClick = { s = CardModel.pick(s, frame) }, modifier = Modifier.testTag("other_$frame")) { Text("%.1f s".format(java.util.Locale.ROOT, frame / s.fps), style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (s.fixing) {
            OutlinedTextField(s.activity, { s = CardModel.editActivity(s, it) }, label = { Text(words.card("activity")) }, modifier = Modifier.fillMaxWidth().testTag("edit_activity"))
            OutlinedTextField(s.outcome, { s = CardModel.editOutcome(s, it) }, label = { Text(words.card("outcome")) }, modifier = Modifier.fillMaxWidth().testTag("edit_outcome"))
        } else {
            Text(s.activity.uppercase(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("activity"))
        }
        for (e in CardModel.events(ps, s)) {
            val flagged = e.toCheck && e.decision == null
            Column(Modifier.fillMaxWidth().then(if (flagged) Modifier.border(BorderStroke(1.dp, accent.text), RoundedCornerShape(4.dp)) else Modifier).padding(8.dp).testTag("event_${e.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(e.text, style = MaterialTheme.typography.bodyLarge, color = if (e.decision == false) Ink.muted else Ink.text, textDecoration = if (e.decision == false) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                if (flagged) Text(words.card("check").uppercase(), style = MaterialTheme.typography.labelSmall, color = accent.text)
                if (e.toCheck || s.fixing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { s = CardModel.decide(s, e.id, true) }, modifier = Modifier.testTag("keep_${e.id}")) { Text(words.card("keep")) }
                    OutlinedButton(onClick = { s = CardModel.decide(s, e.id, false) }, modifier = Modifier.testTag("remove_${e.id}")) { Text(words.card("remove")) }
                }
            }
        }
        val why = CardModel.whyNot(ps, s, words)
        Button(
            onClick = { runCatching { actions.confirm(CardModel.answerInput(s)) }.onFailure { error = it.message ?: words.card("failed") } }, enabled = why == null,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("yes"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent, disabledContainerColor = Ink.raised, disabledContentColor = Ink.muted),
        ) { Text(words.card("yes").uppercase(), style = MaterialTheme.typography.titleMedium) }
        if (why != null) Text(why, style = MaterialTheme.typography.bodyMedium, color = Ink.muted, modifier = Modifier.testTag("why_not"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { s = CardModel.toggleFixing(s) }, modifier = Modifier.testTag("fix")) { Text(if (s.fixing) words.card("fix_done") else words.card("fix")) }
            OutlinedButton(onClick = { s = CardModel.reject(s) }, modifier = Modifier.testTag("reject")) { Text(words.card("reject")) }
        }
        error?.let { Text(it, color = Ink.dangerText, modifier = Modifier.testTag("card_error")) }
        Text(words.card("cost").replace("{cost}", "%.2f".format(java.util.Locale.ROOT, costUsd)), style = MaterialTheme.typography.bodyMedium, color = Ink.muted, modifier = Modifier.testTag("cost"))
    }
}

/** A button that acts on a tap and, held, five times a second after a short wait (the nudges of the card). */
@Composable
private fun RepeatButton(label: String, modifier: Modifier, onStep: () -> Unit) {
    val step = rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        modifier.height(48.dp).semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button; contentDescription = label; onClick(label) { step.value(); true } }.border(BorderStroke(1.dp, Ink.controlLine), RoundedCornerShape(24.dp)).pointerInput(Unit) {
            detectTapGestures(onPress = {
                step.value()
                val held = scope.launch { delay(450); while (true) { step.value(); delay(200) } }
                tryAwaitRelease(); held.cancel()
            })
        },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}
