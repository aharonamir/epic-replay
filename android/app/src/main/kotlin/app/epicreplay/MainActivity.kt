package app.epicreplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.epicreplay.core.budget.paidCall
import app.epicreplay.core.budget.PaidResult
import app.epicreplay.core.models.ChatMessage
import app.epicreplay.core.models.ChatRole
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.setup.SetupStep
import app.epicreplay.ui.FirstRunActions
import app.epicreplay.ui.FirstRunScreen

class MainActivity : ComponentActivity() {
    private val graph by lazy { AppGraph(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = FirstRunActions(
            download = { profile, progress -> graph.modelDownloader().download(profile, progress) },
            unmetered = graph::isUnmetered,
            ask = { role -> askOneQuestion(role) },
        )
        setContent {
            MaterialTheme {
                FirstRunScreen(graph.setup, graph.keys, actions, Modifier.statusBarsPadding())
            }
        }
    }

    /** One short question to the model chosen for [role] (a paid one goes through the ledger and its caps). */
    private suspend fun askOneQuestion(role: Role): String {
        val ready = graph.setup.step() as? SetupStep.Ready ?: return "Not ready: something is missing."
        val profile = ready.models.of(role)
        return try {
            val backend = graph.backendFor(profile, role)
            val question = listOf(ChatMessage(ChatRole.USER, "In one short sentence: what is a highlight video?"))
            val result = when (profile) {
                is ServerProfile -> graph.ledger.paidCall("first-run-question", "question", profile.apiBase, profile.model, profile.callCeilingUsd) {
                    val r = backend.chat(question); PaidResult(r, r.usd, false)
                }
                is OnDeviceProfile -> backend.chat(question)
            }
            result.text.trim().ifEmpty { "(no words came back)" }
        } catch (e: Exception) {
            "${profile.label} could not answer: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
