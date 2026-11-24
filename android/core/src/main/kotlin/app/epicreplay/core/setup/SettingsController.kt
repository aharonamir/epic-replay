package app.epicreplay.core.setup

import app.epicreplay.core.download.ModelDownloader
import app.epicreplay.core.models.LoadedProfiles
import app.epicreplay.core.models.ModelChoice
import app.epicreplay.core.models.OnDeviceProfile
import app.epicreplay.core.models.Role

/** An on-device model that has files on the phone: [bytes] on disk now, and whether it is [complete] (else part of a download). */
class InstalledModel(val profile: OnDeviceProfile, val bytes: Long, val complete: Boolean, val usedBy: List<Role>)

/** A model could not be deleted now, and why. */
class ModelDeleteRefused(message: String) : Exception(message)

/**
 * What the settings page does about the models on the phone (spec 004 FR-SET-1, FR-SET-2, FR-011): lists each on-device model that has files, with its size, and deletes one:
 * its files, finished or part, and then the choice of every role that used it, so the next run asks for a new choice. [busy] says whether a download or a run is working, and then
 * nothing is deleted.
 */
class SettingsController(private val profilesNow: () -> LoadedProfiles, private val choice: ModelChoice, private val downloader: ModelDownloader, private val busy: () -> Boolean) {
    fun installed(): List<InstalledModel> = profilesNow().profiles.values.filterIsInstance<OnDeviceProfile>().filter { downloader.hasFiles(it) }.sortedBy { it.id }
        .map { p -> InstalledModel(p, downloader.sizeOnDisk(p), downloader.installed(p), Role.entries.filter { choice.chosen(it)?.id == p.id }) }

    /** Removes the files of [profile], then clears the choice of each role that named it. A failed removal leaves the choice as it was (the files may still be there). */
    suspend fun delete(profile: OnDeviceProfile) {
        if (busy()) throw ModelDeleteRefused("Wait for the download or the run to finish, then delete.")
        downloader.delete(profile)
        for (role in Role.entries) if (choice.chosen(role)?.id == profile.id) choice.clear(role)
    }
}
