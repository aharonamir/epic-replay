package app.epicreplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.epicreplay.core.ui.NarratorCard
import app.epicreplay.core.ui.PageWords
import app.epicreplay.core.ui.StartForm
import app.epicreplay.core.ui.StartInput

/** What the start screen needs from the app: the narrators, the words, the consent text, and how to pick or record a clip (the system's pickers). */
class StartActions(val pickClip: () -> Unit, val recordClip: () -> Unit)

/**
 * The start screen (ui.md section 1): the tagline, the clip (pick one or record one), the narrators with their captions, the language, the optional fields, the consent,
 * and "Make my replay", enabled by a valid clip and the consent. Choosing a narrator recolours the screen in its accent ([onNarrator]).
 */
@Composable
fun StartScreen(cards: List<NarratorCard>, words: PageWords, consentText: String, providerLine: String, clipName: String?, clipProblem: String?, actions: StartActions, onNarrator: (NarratorCard) -> Unit, onMake: (StartInput) -> Unit, modifier: Modifier = Modifier, voiceKeyMissing: Boolean = false, onAddKey: () -> Unit = {}, setupProblem: String? = null, onSetup: () -> Unit = {}) {
    var narrator by rememberSaveable { mutableStateOf(cards.first().id) }
    var language by rememberSaveable { mutableStateOf("en") }
    var player by rememberSaveable { mutableStateOf("") }
    var mute by rememberSaveable { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    val card = cards.first { it.id == narrator }
    val accent = LocalAccent.current
    val input = StartInput(clipName, narrator, language, player, mute, consent)
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text(words.headline[0].uppercase(), style = MaterialTheme.typography.headlineLarge)
            Text(words.headline[1].uppercase(), style = MaterialTheme.typography.headlineLarge, color = accent.text)
        }
        Column(Modifier.fillMaxWidth().border(BorderStroke(2.dp, Ink.line), RoundedCornerShape(6.dp)).background(Ink.surface, RoundedCornerShape(6.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(clipName ?: "Choose a clip from your phone, or film one now.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("clip_name"))
            if (clipProblem != null) Text(clipProblem, color = Ink.dangerText, modifier = Modifier.testTag("clip_problem"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = actions.pickClip, modifier = Modifier.testTag("pick_clip"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent)) { Text(if (clipName == null) "Choose a clip" else "Change") }
                OutlinedButton(onClick = actions.recordClip, modifier = Modifier.testTag("record_clip")) { Text("Film") }
            }
            Text("3 to 30 seconds · MP4, MOV or WebM", style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        }
        Text(words.tip, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        Text(words.narrators.uppercase(), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (c in cards) {
                val selected = c.id == narrator
                val cardAccent = accentOf(c.accent, null)
                Row(
                    Modifier.fillMaxWidth().testTag("narrator_${c.id}")
                        .border(BorderStroke(2.dp, if (selected) cardAccent.fill else Ink.line), RoundedCornerShape(6.dp)).background(if (selected) Ink.raised else Ink.surface, RoundedCornerShape(6.dp))
                        .selectable(selected, role = Role.RadioButton, onClick = { narrator = c.id; if (language == "zh" && !c.offersMandarin) language = "en"; onNarrator(c) }).padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = cardAccent.fill, unselectedColor = Ink.controlLine))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(c.description, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                        if (c.caption.isNotEmpty()) Text(c.caption.uppercase(), style = MaterialTheme.typography.labelMedium, color = cardAccent.text, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        if (card.offersMandarin) {
            Text(words.language.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.muted)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((code, label) in listOf("en" to "English", "zh" to "中文")) {
                    Row(Modifier.testTag("language_$code").selectable(language == code, role = Role.RadioButton, onClick = { language = code }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(language == code, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = accent.fill, unselectedColor = Ink.controlLine))
                        Spacer(Modifier.padding(4.dp)); Text(label)
                    }
                }
            }
        }
        Text(words.optional("label").uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.muted)
        OutlinedTextField(player, { player = it }, label = { Text(words.optional("star")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("player_name"))
        Row(Modifier.toggleable(mute, role = Role.Checkbox, onValueChange = { mute = it }).testTag("mute"), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(mute, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = accent.fill, checkmarkColor = accent.onAccent, uncheckedColor = Ink.controlLine))
            Spacer(Modifier.padding(4.dp)); Text(words.optional("mute"))
        }
        Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, Ink.line), RoundedCornerShape(6.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.toggleable(consent, role = Role.Checkbox, onValueChange = { consent = it }).testTag("consent"), verticalAlignment = Alignment.Top) {
                Checkbox(consent, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = accent.fill, checkmarkColor = accent.onAccent, uncheckedColor = Ink.controlLine))
                Spacer(Modifier.padding(4.dp)); Text(consentText, style = MaterialTheme.typography.bodyMedium)
            }
            Text(providerLine, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        }
        if (voiceKeyMissing) Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, accent.text), RoundedCornerShape(6.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(words.stopped("voice_key"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("voice_key_missing"))
            OutlinedButton(onClick = onAddKey, modifier = Modifier.testTag("add_key")) { Text(words.stopped("add_key")) }
        }
        if (setupProblem != null) Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, accent.text), RoundedCornerShape(6.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(setupProblem, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("setup_problem"))
            OutlinedButton(onClick = onSetup, modifier = Modifier.testTag("open_settings")) { Text("Open Settings") }
        }
        Button(
            onClick = { onMake(input) }, enabled = StartForm.canStart(input) && !voiceKeyMissing && setupProblem == null, modifier = Modifier.fillMaxWidth().height(64.dp).testTag("make"),
            colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent, disabledContainerColor = Ink.raised, disabledContentColor = Ink.muted),
        ) { Text(words.go.uppercase(), style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(32.dp))
    }
}
