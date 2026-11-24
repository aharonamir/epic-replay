package app.epicreplay.core.setup

import app.epicreplay.core.consent.ConsentCopy
import app.epicreplay.core.consent.ConsentRecord
import app.epicreplay.core.consent.ConsentStore
import app.epicreplay.core.download.ModelDownloader
import app.epicreplay.core.download.ModelNotice
import app.epicreplay.core.models.CustomServer
import app.epicreplay.core.models.CustomServerStore
import app.epicreplay.core.models.CustomServers
import app.epicreplay.core.models.LoadedProfiles
import app.epicreplay.core.models.ModelChoice
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Profile
import app.epicreplay.core.models.Role
import app.epicreplay.core.models.ServerProfile
import app.epicreplay.core.secrets.KeyKind
import app.epicreplay.core.secrets.KeyStatus
import app.epicreplay.core.secrets.SecretStore

/** One thing the user can pick for a role, with what it means in one line (spec 004 US1.1). */
class RoleOption(val profile: Profile, val role: Role, val meaning: String)

/** What stands between the user and a run, in the order the screens ask for it. */
sealed interface SetupStep {
    /** No profile is chosen for [role]: the first-run screen asks (D3). */
    class NeedsChoice(val role: Role) : SetupStep

    /** A cloud profile is chosen and its key is not set (or no longer readable): the app asks for the key and does nothing else. */
    class NeedsKey(val key: KeyKind) : SetupStep

    /** An on-device model is chosen and not downloaded: the user sees the notice and taps to download. */
    class NeedsDownload(val profile: OnDeviceProfile, val remainingBytes: Long, val notice: ModelNotice) : SetupStep

    /** The consent for these two places has not been given (or was given for narrower places): the words to show. */
    /** The voice's key (ElevenLabs) is not set: every run speaks, so it is asked for before the consent (spec 004 FR-WIZ-1). */
    class NeedsVoiceKey(val key: KeyKind) : SetupStep

    class NeedsConsent(val blocks: List<String>, val consentText: String) : SetupStep

    class Ready(val models: ModelsUsed) : SetupStep
}

/**
 * The first-run and settings logic (spec 004 US1 and US3, T411), without any UI: what each role can run on, what is
 * missing before a run, and what has been agreed. Each role is chosen on its own; a run starts only at [SetupStep.Ready].
 */
class SetupController(
    private val profilesNow: () -> LoadedProfiles,
    private val choice: ModelChoice,
    private val secrets: SecretStore,
    private val downloader: ModelDownloader,
    private val consent: ConsentStore,
    private val copy: ConsentCopy,
    private val now: () -> String,
    private val customServers: CustomServerStore? = null,
    private val onProfilesChanged: () -> Unit = {},
) {
    constructor(profiles: LoadedProfiles, choice: ModelChoice, secrets: SecretStore, downloader: ModelDownloader, consent: ConsentStore, copy: ConsentCopy, now: () -> String) : this({ profiles }, choice, secrets, downloader, consent, copy, now)
    private val profiles: LoadedProfiles get() = profilesNow()

    /** The profiles this phone can offer for [role]: cloud and on-device ones that list it (FR-005). */
    fun options(role: Role): List<RoleOption> = profiles.offeredFor(role).map { RoleOption(it, role, meaning(it, role)) }

    fun chosen(role: Role): Profile? = choice.chosen(role)

    fun choose(role: Role, profileId: String) = choice.choose(role, profileId)

    /** The next thing needed, or [SetupStep.Ready]. Nothing is requested from a provider before it is Ready. */
    fun step(): SetupStep {
        val vision = choice.chosen(Role.VISION) ?: return SetupStep.NeedsChoice(Role.VISION)
        val text = choice.chosen(Role.TEXT) ?: return SetupStep.NeedsChoice(Role.TEXT)
        for (p in listOf(vision, text).distinctBy { it.id }) {
            if (p is ServerProfile && p.apiKeyEnv != null && secrets.status(keyFor(p)) != KeyStatus.PRESENT) return SetupStep.NeedsKey(keyFor(p))
        }
        for (p in listOf(vision, text).distinctBy { it.id }) {
            if (p is OnDeviceProfile && !downloader.installed(p)) return SetupStep.NeedsDownload(p, downloader.remainingBytes(p), ModelNotice.of(p))
        }
        if (secrets.status(KeyKind.ELEVENLABS) != KeyStatus.PRESENT) return SetupStep.NeedsVoiceKey(KeyKind.ELEVENLABS)
        val record = consent.load()
        if (record == null || !copy.covers(record, vision.location, text.location)) {
            return SetupStep.NeedsConsent(copy.blocks(vision.location, vision.label, text.location, text.label), copy.consentText)
        }
        return SetupStep.Ready(ModelsUsed(vision, text))
    }

    /**
     * The wizard's steps for what is chosen so far, in order, each with whether it is done (spec 004 FR-WIZ-1): the models, a cloud model's key, an on-device model's download,
     * the voice's key, the consent. The screen shows "Step n of m" from it.
     */
    fun outline(): List<Pair<String, Boolean>> {
        val vision = choice.chosen(Role.VISION); val text = choice.chosen(Role.TEXT)
        val chosen = listOfNotNull(vision, text).distinctBy { it.id }
        val steps = ArrayList<Pair<String, Boolean>>()
        steps += "Models" to (vision != null && text != null)
        val needing = chosen.filterIsInstance<ServerProfile>().filter { it.apiKeyEnv != null }.map { keyFor(it) }.distinct()
        if (chosen.isEmpty()) steps += "Key" to false
        for (k in needing) steps += "Key" to (secrets.status(k) == KeyStatus.PRESENT)
        if (chosen.any { it is OnDeviceProfile }) steps += "Download" to chosen.filterIsInstance<OnDeviceProfile>().all { downloader.installed(it) }
        steps += "Voice" to (secrets.status(KeyKind.ELEVENLABS) == KeyStatus.PRESENT)
        steps += "Agree" to (vision != null && text != null && consent.load()?.let { copy.covers(it, vision.location, text.location) } == true)
        return steps
    }

    /**
     * The user's own server (FR-CLOUD-1): saved as a profile file, offered from now on, and chosen for [role]. [apiKey] is what the form's key field holds: a typed key is checked and
     * saved with the server (under the server's own key kind); an empty field keeps the key that is saved, and is refused when none is. Null means the form has no key field (nothing is
     * checked or changed). Returns the reason nothing was saved, or null.
     */
    fun saveCustomServer(server: CustomServer, role: Role?, apiKey: String? = null): String? {
        val store = customServers ?: error("this build keeps no server of the user's own")
        val keys = app.epicreplay.core.secrets.KeysController(secrets)
        val old = store.load()
        if (apiKey != null) {
            if (apiKey.isBlank()) {
                if (secrets.status(KeyKind.CUSTOM_SERVER) != KeyStatus.PRESENT) return "Type the API key; it stays on this phone."
                // a saved key goes only to the address it was typed for: a new address needs the key typed again
                if (old != null && old.apiBase.trim().trimEnd('/') != server.apiBase.trim().trimEnd('/')) return "The address changed, so type the API key again: a saved key is not sent to a different address."
            } else keys.problem(apiKey)?.let { return it }
        }
        store.save(server)
        if (apiKey != null && apiKey.isNotBlank()) {
            val failed = keys.save(KeyKind.CUSTOM_SERVER, apiKey)
            if (failed != null) { if (old != null) store.save(old) else store.delete(); onProfilesChanged(); return failed } // both or neither
        }
        onProfilesChanged()
        if (role != null) choice.choose(role, CustomServers.ID) // from Settings no role is named: the choices stay as they are
        return null
    }

    fun customServer(): CustomServer? = customServers?.load()

    /** Whether the key of the user's own server is saved (the form may then leave its key field empty). */
    fun customKeySaved(): Boolean = secrets.status(KeyKind.CUSTOM_SERVER) == KeyStatus.PRESENT

    /** The user confirmed the words of [SetupStep.NeedsConsent]: the record names where the frames and the text go. */
    fun confirmConsent() {
        val vision = choice.chosen(Role.VISION) ?: error("no vision model is chosen")
        val text = choice.chosen(Role.TEXT) ?: error("no text model is chosen")
        consent.save(ConsentRecord(now(), copy.textVersion, vision.location, text.location))
    }

    companion object {
        /** The key a cloud profile is called with: the user's own server has its own, every shipped profile the model provider's. */
        fun keyFor(p: ServerProfile): KeyKind = if (p.id == CustomServers.ID) KeyKind.CUSTOM_SERVER else KeyKind.MODEL_PROVIDER

        /** The one line of spec US1.1: what a choice means (the provider's name is the profile's label). */
        fun meaning(profile: Profile, role: Role): String = when (profile) {
            is OnDeviceProfile -> "Stays on this phone; free; needs a ${ModelNotice.megabytes(profile.files.sumOf { it.bytes })} download; slower and less accurate."
            is ServerProfile -> "${if (role == Role.VISION) "Frames go" else "Text goes"} to ${profile.label}; costs money."
        }
    }
}
