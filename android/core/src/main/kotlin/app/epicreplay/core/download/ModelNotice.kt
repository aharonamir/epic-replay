package app.epicreplay.core.download

import app.epicreplay.core.models.OnDeviceProfile
import java.net.URI

/**
 * What the user is shown before a model is downloaded (spec 004 FR-009 and FR-010): the model, its licence, where
 * the files come from and how big they are, all from the profile, and a hint about the connection.
 */
class ModelNotice(
    val title: String,
    val licence: String,
    val hosts: List<String>,
    val files: List<Pair<String, Long>>,
    val totalBytes: Long,
    val minFreeStorageMb: Int,
    val minFreeRamMb: Int,
) {
    /** The notice as plain text for a screen. */
    fun text(): String = buildString {
        appendLine(title)
        appendLine("Licence: $licence")
        appendLine("Downloaded from: ${hosts.joinToString(", ")}")
        appendLine("Size: ${megabytes(totalBytes)} in ${files.size} files (${files.joinToString { "${it.first}, ${megabytes(it.second)}" }})")
        appendLine("Needs about ${minFreeStorageMb} MB of free storage to download and about ${minFreeRamMb} MB of free memory to run.")
        append("The files are checked against a checksum, stay on this phone, and can be deleted in settings.")
    }

    companion object {
        fun of(profile: OnDeviceProfile): ModelNotice = ModelNotice(
            title = profile.label,
            licence = profile.license,
            hosts = profile.files.mapNotNull { runCatching { URI(it.url).host }.getOrNull() }.distinct(),
            files = profile.files.map { it.name to it.bytes },
            totalBytes = profile.files.sumOf { it.bytes },
            minFreeStorageMb = profile.minFreeStorageMb,
            minFreeRamMb = profile.minFreeRamMb,
        )

        /** Decimal units, one place: 3,398,849,280 is "3.4 GB", 557,368,096 is "557 MB". */
        fun megabytes(bytes: Long): String =
            if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format((bytes + 500_000) / 1_000_000)

        /** A warning for a metered connection (spec 004 FR-009); null on Wi-Fi or another unmetered network. */
        fun connectionAdvice(profile: OnDeviceProfile, unmetered: Boolean): String? =
            if (unmetered) null else "This download is ${megabytes(profile.files.sumOf { it.bytes })} and the connection looks metered: Wi-Fi is better."
    }
}
