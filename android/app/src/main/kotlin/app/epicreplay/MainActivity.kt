package app.epicreplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.epicreplay.ui.KeysSection

class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Settings is the whole app until the first-run flow (T411); the model choice itself is T411's screen.
                Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState())) {
                    KeysSection(graph.keys)
                }
            }
        }
    }
}
