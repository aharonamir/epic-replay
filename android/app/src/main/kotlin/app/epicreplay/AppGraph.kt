package app.epicreplay

import android.content.Context
import app.epicreplay.core.secrets.AesGcmCipher
import app.epicreplay.core.secrets.KeysController
import app.epicreplay.core.secrets.ModelChoiceStore
import app.epicreplay.core.secrets.SecretStore
import app.epicreplay.secrets.AndroidKeystoreKey
import java.io.File

/** The app's long-lived objects. The key files live in `files/secrets/`, which the backup rules exclude. */
class AppGraph(context: Context) {
    private val root: File = context.filesDir
    val secrets = SecretStore(File(root, SECRETS_DIR), AesGcmCipher { AndroidKeystoreKey.get() })
    val keys = KeysController(secrets)
    val modelChoice = ModelChoiceStore(File(root, "settings/model_choice.json"))

    companion object {
        const val SECRETS_DIR = "secrets"
    }
}
