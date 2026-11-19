package app.epicreplay.core.download

import app.epicreplay.core.io.Durable
import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Free storage, in MB, where the models are kept (spec 004 FR-009: checked before the first byte). */
fun interface StorageProbe {
    fun freeMb(): Long
}

/** How far a download is: this file, and all the files of the profile. */
class Progress(val file: String, val fileBytes: Long, val fileTotal: Long, val doneBytes: Long, val totalBytes: Long)

/** One HTTP answer, redirects not followed. [headers] keys are lower case. */
class DownloadResponse(val status: Int, val headers: Map<String, String>, val body: InputStream?, val close: () -> Unit)

/** The network of the downloader; tests use the real one against a server on this computer. */
fun interface DownloadHttp {
    fun get(url: String, headers: Map<String, String>): DownloadResponse
}

/** `HttpURLConnection` with redirects off: the downloader follows them itself, one allowed host at a time. */
class JavaDownloadHttp(private val connectTimeoutMs: Int = 20_000, private val readTimeoutMs: Int = 60_000) : DownloadHttp {
    override fun get(url: String, headers: Map<String, String>): DownloadResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val status = conn.responseCode
        val out = HashMap<String, String>()
        conn.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) out[k.lowercase()] = v.last() }
        val stream = if (status in 200..299) conn.inputStream else null
        return DownloadResponse(status, out, stream) { try { conn.disconnect() } catch (_: Exception) {} }
    }
}

/**
 * Downloads and removes the files of an on-device profile (spec 004 FR-009 and FR-011, T410).
 *
 * - Each file goes to `<name>.part` and is renamed to `<name>` only when its length and SHA-256 match the profile.
 * - A `.part` is resumed only on a `206` whose `Content-Range` starts where the file ends and covers the profile's
 *   size, and whose `ETag` or `Last-Modified` is the one recorded when the file was started. Anything else (a `200`,
 *   another range, another validator, a `.part` longer than the file) deletes the `.part` and starts again from the
 *   first byte: a download is never appended to blindly.
 * - HTTPS only, from the hosts of the [policy]; a redirect is followed only to another allowed host.
 * - Free storage is checked before the first byte; a file that fails its checksum is deleted.
 * - Cancelling the coroutine keeps the `.part` so the next call resumes.
 */
class ModelDownloader(
    private val dir: File,
    private val policy: DownloadPolicy = DownloadPolicy.DEFAULT,
    private val storage: StorageProbe,
    private val http: DownloadHttp = JavaDownloadHttp(),
    private val slackMb: Long = 100,
) {
    private fun finalFile(f: ModelFile) = File(dir, f.name)
    private fun partFile(f: ModelFile) = File(dir, f.name + ".part")
    private fun metaFile(f: ModelFile) = File(dir, f.name + ".part.meta")

    /** Whether every file of [profile] is complete (right size, and verified when it was downloaded). */
    fun installed(profile: OnDeviceProfile): Boolean = profile.files.all { finalFile(it).let { f -> f.isFile && f.length() == it.bytes } }

    /** Bytes still to fetch for [profile], counting what a resumable `.part` already holds. */
    fun remainingBytes(profile: OnDeviceProfile): Long = profile.files.sumOf { f ->
        if (finalFile(f).let { it.isFile && it.length() == f.bytes }) 0L
        else f.bytes - (partFile(f).takeIf { it.isFile && it.length() in 1 until f.bytes && metaFile(f).isFile }?.length() ?: 0L)
    }

    /** Downloads the files of [profile] that are not there yet. Throws a [DownloadError]; a failed file leaves its `.part` unless it is corrupt. */
    suspend fun download(profile: OnDeviceProfile, onProgress: (Progress) -> Unit = {}) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        // before the first byte: the free storage covers what is left, and the profile's own floor
        val needed = maxOf(profile.minFreeStorageMb.toLong(), remainingBytes(profile) / MB + slackMb)
        val free = storage.freeMb()
        if (remainingBytes(profile) > 0 && free < needed) throw DownloadError.InsufficientStorage(needed, free)
        val total = profile.files.sumOf { it.bytes }
        var done = profile.files.filter { installedFile(it) }.sumOf { it.bytes }
        for (file in profile.files) {
            if (installedFile(file)) continue
            val before = done
            fetch(file) { fileBytes -> onProgress(Progress(file.name, fileBytes, file.bytes, before + fileBytes, total)) }
            done += file.bytes
        }
    }

    private fun installedFile(f: ModelFile) = finalFile(f).let { it.isFile && it.length() == f.bytes }

    /** Deletes the files of [profile], complete or partial (spec 004 FR-011). */
    fun delete(profile: OnDeviceProfile) {
        for (f in profile.files) {
            finalFile(f).delete(); partFile(f).delete(); metaFile(f).delete()
        }
    }

    private suspend fun fetch(file: ModelFile, progress: (Long) -> Unit) {
        val part = partFile(file)
        val meta = metaFile(file)
        var resumeFrom = 0L
        var validator: String? = null
        if (part.isFile && meta.isFile && part.length() in 1 until file.bytes) {
            resumeFrom = part.length()
            validator = meta.readText().trim().takeIf { it.isNotEmpty() }
        }
        if (resumeFrom > 0 && validator == null) resumeFrom = 0
        if (resumeFrom == 0L) discard(file)

        var response = open(file.url, buildHeaders(resumeFrom, validator))
        try {
            var append = false
            if (resumeFrom > 0) {
                val resumable = response.status == 206 && contentRangeOk(response.headers["content-range"], resumeFrom, file.bytes) &&
                    validatorOf(response.headers) == validator
                if (resumable) {
                    append = true
                } else {
                    // a 200 (the server ignored Range), another range, another validator: start again, never append
                    response.close()
                    discard(file)
                    resumeFrom = 0
                    response = open(file.url, buildHeaders(0, null))
                }
            }
            if (!append) {
                if (response.status != 200) throw DownloadError.Http(response.status, hostOf(file.url))
                val length = response.headers["content-length"]?.toLongOrNull()
                if (length != null && length != file.bytes) throw DownloadError.SizeMismatch(file.name)
                dir.mkdirs()
                meta.writeText(validatorOf(response.headers) ?: "")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            if (append) hashExisting(part, digest)
            var written = if (append) resumeFrom else 0L
            val body = response.body ?: throw DownloadError.Http(response.status, hostOf(file.url))
            val ctx = currentCoroutineContext()
            try {
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        ctx.ensureActive() // cancelling keeps the .part
                        val n = try { body.read(buf) } catch (e: IOException) { throw DownloadError.Interrupted(file.name) }
                        if (n < 0) break
                        if (written + n > file.bytes) throw DownloadError.SizeMismatch(file.name).also { discard(file) }
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        if (written - lastReport >= REPORT_EVERY || written == file.bytes) { progress(written); lastReport = written }
                    }
                    out.flush()
                    out.fd.sync()
                }
            } catch (e: DownloadError.Interrupted) {
                throw e
            }
            if (written != file.bytes) throw DownloadError.Interrupted(file.name) // the server closed early: resumable
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(file.sha256, ignoreCase = true)) {
                discard(file)
                throw DownloadError.ChecksumMismatch(file.name)
            }
            progress(written)
            if (!part.renameTo(finalFile(file))) throw IOException("rename failed")
            meta.delete()
        } finally {
            response.close()
        }
    }

    private fun discard(f: ModelFile) {
        partFile(f).delete(); metaFile(f).delete()
    }

    private fun buildHeaders(resumeFrom: Long, validator: String?): Map<String, String> = buildMap {
        put("User-Agent", "EpicReplay")
        put("Accept-Encoding", "identity") // the bytes of the file, not a compressed copy of them
        if (resumeFrom > 0) {
            put("Range", "bytes=$resumeFrom-")
            if (validator != null) put("If-Range", validator)
        }
    }

    /** The ETag, else the Last-Modified, the server gave: what must not change between the first request and a resume. */
    private fun validatorOf(h: Map<String, String>): String? = h["etag"] ?: h["last-modified"]

    private fun contentRangeOk(value: String?, start: Long, total: Long): Boolean {
        val m = Regex("""^bytes (\d+)-(\d+)/(\d+)$""").find(value ?: return false) ?: return false
        val (s, e, t) = m.destructured
        return s.toLong() == start && t.toLong() == total && e.toLong() == total - 1
    }

    private fun hashExisting(file: File, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "the server"

    /** GETs [url], following redirects only to allowed hosts. Redirects are counted; each hop is checked before it is requested. */
    private fun open(url: String, headers: Map<String, String>): DownloadResponse {
        var current = url
        var hops = 0
        while (true) {
            policy.refusal(current)?.let { throw it }
            val r = try { http.get(current, headers) } catch (e: IOException) { throw DownloadError.Http(0, hostOf(current)) }
            if (r.status in listOf(301, 302, 303, 307, 308)) {
                val location = r.headers["location"]
                r.close()
                if (location == null) throw DownloadError.Http(r.status, hostOf(current))
                if (++hops > policy.maxRedirects) throw DownloadError.TooManyRedirects()
                current = java.net.URI(current).resolve(location).toString()
                continue
            }
            return r
        }
    }

    companion object {
        const val MB = 1024L * 1024L
        private const val REPORT_EVERY = 256L * 1024L
    }
}
