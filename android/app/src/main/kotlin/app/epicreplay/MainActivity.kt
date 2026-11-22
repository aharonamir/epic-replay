package app.epicreplay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import app.epicreplay.download.DownloadService
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
    private val graph by lazy { (application as EpicApp).graph }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // variant A of the icon on the dark ground (FR-ICON-2); the app's own theme takes over after it
        super.onCreate(savedInstanceState)
        val actions = FirstRunActions(
            startDownload = { profile ->
                // the progress notification needs the permission on Android 13+; the download goes on whatever the answer
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                DownloadService.start(this, profile.id)
            },
            cancelDownload = { DownloadService.cancel(this, graph.downloads) },
            downloadStatus = graph.downloads.status,
            acknowledgeDownload = graph.downloads::acknowledge,
            unmetered = graph::isUnmetered,
            ask = { role -> askOneQuestion(role) },
        )
        val vm by viewModels<app.epicreplay.ui.AppViewModel> { object : androidx.lifecycle.ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = app.epicreplay.ui.AppViewModel(graph, applicationContext) as T } }
        setContent {
            app.epicreplay.ui.AppRoot(vm, { graph.setup.step() is app.epicreplay.core.setup.SetupStep.Ready }, { onContinue ->
                FirstRunScreen(graph.setup, graph.keys, actions, Modifier, onContinue)
            })
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
