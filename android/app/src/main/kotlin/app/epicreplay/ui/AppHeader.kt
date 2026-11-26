package app.epicreplay.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.epicreplay.R

/** The top of every screen (spec 004 FR-ICON-2, design.md header): the rewind mark of the icon, 30 by 20 dp in the accent yellow, beside the wordmark. */
@Composable
fun AppHeader(modifier: Modifier = Modifier, onSettings: (() -> Unit)? = null, onPast: (() -> Unit)? = null, onHome: (() -> Unit)? = null, homeButton: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        // the mark and the wordmark are the way home, as on most apps: they take the user to the start screen (a replay in the making goes on)
        Row(Modifier.weight(1f).then(if (onHome != null) Modifier.clickable(onClickLabel = "Home", onClick = onHome).androidx_tag("home_logo") else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_mark), contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("EPIC REPLAY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        }
        if (homeButton && onHome != null) androidx.compose.material3.TextButton(onClick = onHome, modifier = Modifier.androidx_tag("home")) { Text("Home") }
        if (onPast != null) androidx.compose.material3.TextButton(onClick = onPast, modifier = Modifier.androidx_tag("past")) { Text("Past") }
        if (onSettings != null) androidx.compose.material3.TextButton(onClick = onSettings, modifier = Modifier.androidx_tag("settings")) { Text("Settings") }
    }
}

private fun Modifier.androidx_tag(t: String) = this.testTag(t)
