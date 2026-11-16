package app.epicreplay.core.models

import app.epicreplay.core.secrets.ModelChoiceStore

/** The chosen profile id is not a loaded profile, or the profile does not list the role. */
class ProfileChoiceException(message: String) : Exception(message)

/**
 * The profile chosen for each role, checked against the loaded profiles (spec 004 FR-005): only a profile
 * that this platform loaded and that lists the role can be chosen, and a stored choice whose profile is
 * gone (a deleted file, a new platform rule) reads as no choice, so the app asks again (D3).
 */
class ModelChoice(private val store: ModelChoiceStore, private val loaded: LoadedProfiles) {
    fun choose(role: Role, profileId: String) {
        val p = loaded.profiles[profileId]
            ?: throw ProfileChoiceException("no usable model profile $profileId; the profiles are ${loaded.profiles.keys.joinToString().ifEmpty { "none" }}")
        if (role !in p.roles) {
            throw ProfileChoiceException("model profile $profileId is not for the ${role.id} role (it lists ${p.roles.joinToString { it.id }})")
        }
        store.set(role, profileId)
    }

    fun clear(role: Role) = store.set(role, null)

    /** The chosen profile, or null when there is no choice or the stored one no longer qualifies. */
    fun chosen(role: Role): Profile? = store.get(role)?.let { loaded.profiles[it] }?.takeIf { role in it.roles }
}
