package app.epicreplay.core.download

import app.epicreplay.core.io.Durable
import app.epicreplay.core.models.ModelFile
import app.epicreplay.core.models.OnDeviceProfile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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
class JavaDownloadHttp(private val connectTimeoutMs: Int = 20_000, private val readTimeoutMs: Int = 2_000) : DownloadHttp {
    override fun get(url: String, headers: Map<String, String>): DownloadResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val status = conn.responseCode
            val out = HashMap<String, String>()
            conn.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) out[k.lowercase()] = v.last() }
            val stream = if (status in 200..299) conn.inputStream else null
            return DownloadResponse(status, out, stream) { try { conn.disconnect() } catch (_: Exception) {} }
        } catch (e: Exception) {
            try { conn.disconnect() } catch (_: Exception) {} // setup failed: nobody else holds this connection
            throw e
        }
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
 * - Cancelling the coroutine keeps the `.part` so the next call resumes. Reads time out in short slices and are retried
 *   while nothing was cancelled, so a cancel is seen within a slice even when the server has gone quiet; a body that
 *   sends nothing for [stallTimeoutMs] ends the attempt.
 * - A file counts as installed only with a verification marker (`<name>.verified`: its size, its modification time and
 *   its profile checksum, written after the checksum was checked), so a same-size file of unknown origin is not trusted.
 *   The marker detects a replaced or rewritten file, not silent corruption that keeps the size and the modification time
 *   (storage bit rot); that would need the whole file hashed on every use, 3.4 GB, so [verify] is the explicit full check.
 * - Downloads and deletes of one file never overlap, also between two instances on the same folder.
 * - The resume record (`<name>.part.meta`) holds the URL, the checksum and the validator: a `.part` is resumed only
 *   for the same URL and checksum.
 */
class ModelDownloader(
    private val dir: File,
    private val policy: DownloadPolicy = DownloadPolicy.DEFAULT,
    private val storage: StorageProbe,
    private val http: DownloadHttp = JavaDownloadHttp(),
    private val slackMb: Long = 100,
    /** A body that sends nothing for this long ends the attempt (the `.part` is kept); reads time out in slices of [JavaDownloadHttp.readTimeoutMs]. */
    private val stallTimeoutMs: Long = 60_000,
) {
    init {
        require(slackMb >= 1) { "the storage slack is at least 1 MB" }
    }

    private fun safe(f: ModelFile): String {
        if (!SAFE_NAME.matches(f.name) || f.name == "." || f.name == "..") throw DownloadError.UnsafeName(f.name)
        return f.name
    }
    private fun finalFile(f: ModelFile) = File(dir, safe(f))
    private fun partFile(f: ModelFile) = File(dir, safe(f) + ".part")
    private fun metaFile(f: ModelFile) = File(dir, safe(f) + ".part.meta")
    private fun markerFile(f: ModelFile) = File(dir, safe(f) + ".verified")
    private fun lockFor(f: ModelFile): Mutex = locks.getOrPut(File(dir, f.name).absolutePath) { Mutex() }

    private fun writeMarker(f: ModelFile) {
        val file = finalFile(f)
        Durable.writeAtomic(markerFile(f), "${file.length()}:${file.lastModified()}:${f.sha256.lowercase()}".toByteArray())
    }

    /** True when [f] is in place, complete, and was verified against its checksum (and has not been touched since). */
    private fun verified(f: ModelFile): Boolean {
        val file = finalFile(f)
        if (!file.isFile || file.length() != f.bytes) return false
        val marker = markerFile(f).takeIf { it.isFile }?.readText()?.trim() ?: return false
        return marker == "${file.length()}:${file.lastModified()}:${f.sha256.lowercase()}"
    }

    /** Whether every file of [profile] is complete (right size, and verified when it was downloaded). */
    fun installed(profile: OnDeviceProfile): Boolean = profile.files.all { verified(it) }

    /**
     * Checks the SHA-256 of each file in place and writes the marker for those that match; one that does not is
     * deleted. For files that arrived by other means than [download] (copied by hand, or a marker that was lost).
     */
    suspend fun verify(profile: OnDeviceProfile): Boolean = withContext(Dispatchers.IO) {
        profile.files.map { f ->
            lockFor(f).withLock {
                val file = finalFile(f)
                if (!file.isFile) false
                else if (file.length() != f.bytes) { file.delete(); markerFile(f).delete(); false } // the wrong length cannot be the model
                else if (sha256(file).equals(f.sha256, ignoreCase = true)) { writeMarker(f); true }
                else { file.delete(); markerFile(f).delete(); false }
            }
        }.all { it }
    }

    private fun sha256(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        hashExisting(file, d)
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    /** Bytes still to fetch for [profile], counting what a resumable `.part` already holds. */
    fun remainingBytes(profile: OnDeviceProfile): Long = profile.files.sumOf { f ->
        if (verified(f)) 0L
        else if (completePart(f) != null) 0L // an estimate: download() checks it first, and counts again if its bytes are wrong
        else f.bytes - (resumable(f)?.first ?: 0L)
    }

    /** Downloads the files of [profile] that are not there yet. Throws a [DownloadError]; a failed file leaves its `.part` unless it is corrupt. */
    suspend fun download(profile: OnDeviceProfile, onProgress: (Progress) -> Unit = {}) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        // first, any complete .part (a cancel or a crash between the last byte and the rename) is checked and finished, or
        // deleted: only then is it known how much is still to fetch, and the storage check below is made on that
        settleCompleteParts(profile)
        // before the first byte: the free storage covers what is left, and the profile's own floor
        val remaining = remainingBytes(profile)
        val needed = maxOf(profile.minFreeStorageMb.toLong(), (remaining + MB - 1) / MB + slackMb) // rounded up, never down
        val free = storage.freeMb()
        if (remaining > 0 && free < needed) throw DownloadError.InsufficientStorage(needed, free)
        val total = profile.files.sumOf { it.bytes }
        var done = profile.files.filter { verified(it) }.sumOf { it.bytes }
        for (file in profile.files) {
            lockFor(file).withLock { // one download or delete of this file at a time
                if (verified(file)) return@withLock
                val before = done
                try {
                    fetch(file) { fileBytes -> onProgress(Progress(file.name, fileBytes, file.bytes, before + fileBytes, total)) }
                } catch (e: IOException) {
                    throw DownloadError.Storage(file.name) // the part stays, so the next call resumes
                }
                done += file.bytes
            }
        }
    }

    /** Deletes the files of [profile], complete or partial (spec 004 FR-011). */
    suspend fun delete(profile: OnDeviceProfile) {
        for (f in profile.files) {
            lockFor(f).withLock {
                val files = listOf(finalFile(f), markerFile(f), partFile(f), metaFile(f))
                files.forEach { if (it.exists()) it.delete() }
                if (files.any { it.exists() }) throw DownloadError.Storage(f.name) // reported, never silently kept
            }
        }
    }

    private suspend fun fetch(file: ModelFile, progress: (Long) -> Unit) {
        val part = partFile(file)
        val meta = metaFile(file)
        var resumeFrom = 0L
        var validator: String? = null
        resumable(file)?.let { (bytes, v) -> resumeFrom = bytes; validator = v }
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
                Durable.writeAtomic(meta, "${file.url}\n${file.sha256.lowercase()}\n${validatorOf(response.headers) ?: ""}".toByteArray())
            }
            val digest = MessageDigest.getInstance("SHA-256")
            if (append) hashExisting(part, digest)
            var written = if (append) resumeFrom else 0L
            val body = response.body ?: throw DownloadError.Http(response.status, hostOf(file.url))
            val ctx = currentCoroutineContext()
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                var lastData = System.nanoTime()
                while (true) {
                    ctx.ensureActive() // cancelling keeps the .part
                    val n = try {
                        body.read(buf)
                    } catch (e: SocketTimeoutException) {
                        // reads time out in short slices, so a cancel is seen within one slice; a stall that lasts is an interruption
                        ctx.ensureActive()
                        if ((System.nanoTime() - lastData) / 1_000_000 > stallTimeoutMs) throw DownloadError.Interrupted(file.name)
                        continue
                    } catch (e: IOException) {
                        ctx.ensureActive() // a cancel that raced with a read error is a cancellation
                        throw DownloadError.Interrupted(file.name)
                    }
                    ctx.ensureActive() // a cancel that came while the read returned data: no write, no progress call
                    if (n < 0) break
                    lastData = System.nanoTime()
                    if (written + n > file.bytes) throw DownloadError.SizeMismatch(file.name).also { discard(file) }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    written += n
                    if (written - lastReport >= REPORT_EVERY || written == file.bytes) { progress(written); lastReport = written }
                }
                out.flush()
                out.fd.sync()
            }
            if (written != file.bytes) throw DownloadError.Interrupted(file.name) // the server closed early: resumable
            ctx.ensureActive() // a cancel before the commit keeps the complete .part: the next call checks it and finishes
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(file.sha256, ignoreCase = true)) {
                discard(file)
                throw DownloadError.ChecksumMismatch(file.name)
            }
            progress(written)
            if (!part.renameTo(finalFile(file))) throw IOException("rename failed")
            meta.delete()
            writeMarker(file)
        } finally {
            response.close()
        }
    }

    /**
     * The length and validator of [f]'s `.part` when it can be resumed: it is shorter than the file, and its record names
     * this address, this checksum and a validator. A newer profile that reuses the file name does not resume an older part.
     * The storage check and the download use this one rule.
     */
    private fun resumable(f: ModelFile): Pair<Long, String>? {
        val part = partFile(f)
        val meta = metaFile(f)
        if (!part.isFile || !meta.isFile || part.length() !in 1 until f.bytes) return null
        val rec = readMeta(meta) ?: return null
        if (rec.first != f.url || !rec.second.equals(f.sha256, ignoreCase = true)) return null
        return rec.third?.let { part.length() to it }
    }

    private suspend fun settleCompleteParts(profile: OnDeviceProfile) {
        for (f in profile.files) {
            lockFor(f).withLock {
                val part = completePart(f) ?: return@withLock
                if (verified(f)) { discard(f); return@withLock }
                val ok = sha256(part).equals(f.sha256, ignoreCase = true)
                currentCoroutineContext().ensureActive() // a cancel during the checksum, whatever it found, keeps the part as it is
                if (ok) {
                    if (!part.renameTo(finalFile(f))) throw DownloadError.Storage(f.name)
                    metaFile(f).delete()
                    writeMarker(f)
                } else {
                    discard(f) // the bytes are wrong: gone, and the free space is counted again below
                }
            }
        }
    }

    /** [f]'s `.part` when it is complete and its record names this address and checksum (the checksum of its bytes is checked when it is finished). */
    private fun completePart(f: ModelFile): File? {
        val part = partFile(f)
        val meta = metaFile(f)
        if (!part.isFile || part.length() != f.bytes || !meta.isFile) return null
        val rec = readMeta(meta) ?: return null
        return part.takeIf { rec.first == f.url && rec.second.equals(f.sha256, ignoreCase = true) }
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

    /**
     * The strong ETag, else the Last-Modified, the server gave: what must not change between the first request and a
     * resume. A weak ETag (`W/"..."`) promises no byte-for-byte identity, so it is not a validator: without another one
     * the file is started again instead of resumed.
     */
    private fun validatorOf(h: Map<String, String>): String? =
        h["etag"]?.takeIf { !it.startsWith("W/") } ?: h["last-modified"]

    private fun readMeta(f: File): Triple<String, String, String?>? {
        val lines = f.readText().split("\n")
        if (lines.size < 3 || lines[0].isEmpty() || lines[1].isEmpty()) return null
        return Triple(lines[0], lines[1], lines[2].takeIf { it.isNotEmpty() })
    }

    private fun contentRangeOk(value: String?, start: Long, total: Long): Boolean {
        val m = Regex("""^bytes (\d+)-(\d+)/(\d+)$""").find(value ?: return false) ?: return false
        val (s, e, t) = m.destructured
        // digit strings beyond Long are not a valid range: false, so the download restarts, not an exception
        return s.toLongOrNull() == start && t.toLongOrNull() == total && e.toLongOrNull() == total - 1
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
        /** One lock per file path, shared by every downloader on the same folder. */
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        const val MB = 1024L * 1024L
        private const val REPORT_EVERY = 256L * 1024L
    }
}
