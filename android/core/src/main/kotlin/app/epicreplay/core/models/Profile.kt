package app.epicreplay.core.models

import kotlinx.serialization.json.JsonObject
import kotlin.math.round

/** A file of an on-device model (`files` of an `on_device` profile). */
data class ModelFile(val name: String, val url: String, val sha256: String, val bytes: Long, val role: String)

/** `image` of a server profile: the longest side a sheet is resized to and the `detail` field to send. */
data class ImageSpec(val maxSidePx: Int?, val detail: String?)

/**
 * A model profile (`shared/models/<id>.json`, `shared/contracts/model-backend.md`). The loader makes
 * these from files that passed the schema and the loader's own rules; no profile exists in a state
 * the PC loader would refuse.
 */
sealed interface Profile {
    val id: String
    val label: String
    val kind: String
    val location: String
    val roles: List<Role>
    val supportsTools: Boolean
    val status: String
    val notes: String
    val priceInPerM: Double
    val priceOutPerM: Double

    /** The cost of a call from its token counts, rounded to six places as the ledger records it. */
    fun usd(inputTokens: Long, outputTokens: Long): Double =
        round((inputTokens * priceInPerM + outputTokens * priceOutPerM) / 1e6 * 1e6) / 1e6
}

/** `kind: openai_compatible`: any server that answers the OpenAI chat route. */
data class ServerProfile(
    override val id: String,
    override val label: String,
    override val location: String,
    override val roles: List<Role>,
    override val supportsTools: Boolean,
    override val status: String,
    override val notes: String,
    override val priceInPerM: Double,
    override val priceOutPerM: Double,
    val apiBase: String,
    val model: String,
    /** The environment variable that holds the key on the PC; null: the profile sends no key at all. The phone keeps keys in its own store. */
    val apiKeyEnv: String?,
    val callCeilingUsd: Double,
    val callMeanUsd: Double,
    val timeoutS: Map<Role, Double>,
    val maxParallel: Int,
    val jsonMode: String,
    val image: ImageSpec,
    val extraBody: JsonObject,
    val serve: String,
) : Profile {
    override val kind: String get() = KIND

    fun timeout(role: Role): Double = timeoutS.getValue(role)

    companion object {
        const val KIND = "openai_compatible"
    }
}

/** `kind: on_device`: model files run inside the app (spec 004 FR-001). The price is always 0. */
data class OnDeviceProfile(
    override val id: String,
    override val label: String,
    override val roles: List<Role>,
    override val supportsTools: Boolean,
    override val status: String,
    override val notes: String,
    val engine: String,
    val files: List<ModelFile>,
    val contextTokens: Int,
    val minFreeRamMb: Int,
    val minFreeStorageMb: Int,
    val license: String,
) : Profile {
    override val kind: String get() = KIND
    override val location: String get() = "device"
    override val priceInPerM: Double get() = 0.0
    override val priceOutPerM: Double get() = 0.0

    companion object {
        const val KIND = "on_device"
    }
}
