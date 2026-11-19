package app.epicreplay.core.download

import java.net.URI

/** A host the app may fetch model files from: exactly [host], or any host ending in [suffix] (which starts with a dot). */
class HostRule private constructor(private val exact: String?, private val suffix: String?) {
    fun matches(host: String): Boolean {
        val h = host.lowercase()
        return exact?.let { h == it } ?: h.endsWith(suffix!!)
    }

    override fun toString(): String = exact ?: "*$suffix"

    companion object {
        fun exact(host: String) = HostRule(host.lowercase(), null)
        fun suffix(suffix: String): HostRule {
            require(suffix.startsWith(".") && suffix.length > 3) { "a suffix rule starts with a dot" }
            return HostRule(null, suffix.lowercase())
        }
    }
}

/**
 * Where model files may come from (spec 004 FR-009, T410): HTTPS only, from these hosts, and a redirect may only
 * lead to another allowed host. The list is code, not data: a profile file cannot widen it.
 */
class DownloadPolicy(val hosts: List<HostRule>, val requireHttps: Boolean = true, val maxRedirects: Int = 5) {
    /** Null when [url] may be fetched, else the reason it may not. */
    fun refusal(url: String): DownloadError? {
        val uri = try { URI(url) } catch (e: Exception) { return DownloadError.DisallowedHost("a malformed address") }
        val host = uri.host ?: return DownloadError.DisallowedHost("an address with no host")
        if (requireHttps && !uri.scheme.equals("https", ignoreCase = true)) return DownloadError.NotHttps(host)
        // the default HTTPS port only: an allowed host on another port is another service
        if (requireHttps && uri.port != -1 && uri.port != 443) return DownloadError.DisallowedHost("$host (port ${uri.port})")
        if (hosts.none { it.matches(host) }) return DownloadError.DisallowedHost(host)
        return null
    }

    companion object {
        /** Hugging Face, where the S1 models live, and its CDN (observed 2026-11-19: `huggingface.co` answers 302 to `us.aws.cdn.hf.co`). */
        val DEFAULT = DownloadPolicy(listOf(HostRule.exact("huggingface.co"), HostRule.suffix(".cdn.hf.co")))
    }
}

/** Why a download did not complete. Messages name hosts and files, never more of an address than its host. */
sealed class DownloadError(message: String) : Exception(message) {
    class NotHttps(host: String) : DownloadError("a model file may only be fetched over HTTPS (not from $host)")
    class DisallowedHost(host: String) : DownloadError("$host is not a host model files may come from")
    class TooManyRedirects : DownloadError("too many redirects")
    class InsufficientStorage(val neededMb: Long, val freeMb: Long) :
        DownloadError("the download needs about $neededMb MB of free storage; $freeMb MB is free")
    class Http(val status: Int, host: String) : DownloadError("$host answered HTTP $status")
    class SizeMismatch(val file: String) : DownloadError("$file is not the size the profile says")
    class ChecksumMismatch(val file: String) : DownloadError("$file does not match its checksum and was deleted")
    class Interrupted(val file: String) : DownloadError("the connection ended before $file was complete; it can be resumed")
    class Storage(val file: String) : DownloadError("$file could not be written to storage")
    class UnsafeName(val file: String) : DownloadError("a model file name must be a plain file name")
}
