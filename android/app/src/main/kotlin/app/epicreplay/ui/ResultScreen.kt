package app.epicreplay.ui

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** A first, plain result (T426 builds the real one: tabs, script panel, models, cost, share): the finished video with its controls, and a way to start over. */
@Composable
fun ResultScreen(video: File, onNew: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("YOUR REPLAY", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("result_headline"))
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(video.path)
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    seekTo(1) // the first frame, the title card, until it is played: the video needs its sound
                }
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("player"),
        )
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().height(56.dp).testTag("new_clip"), colors = ButtonDefaults.buttonColors(containerColor = accent.fill, contentColor = accent.onAccent)) { Text("MAKE ANOTHER") }
    }
}
