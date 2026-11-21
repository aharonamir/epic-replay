package app.epicreplay.media

import app.epicreplay.core.json.PythonJson
import app.epicreplay.core.intake.Intake
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What S0 gave: `ok` with the clip's metadata, or `failed` with the words for the page. */
class IntakeResult(val status: String, val note: String, val meta: JsonObject?)

/**
 * S0 on the phone (`pc/src/epic_replay/stages/s0_intake.py`, FR-001 and FR-002): the PC's checks on what the phone's decoders find, then the working
 * copy and `clip_meta.json`. The working copy is the upload itself, copied into the run folder as `work.mp4`: the phone does not convert it to 30 fps
 * (the PC's FFmpeg does), so the clip is read at 30 frames a second by time instead ([ClipFrames], `SignalDecoder`); its size in the metadata is what
 * the PC's conversion would give (the upright picture, at most 1080 lines high), which is the size the export uses.
 */
object ClipIntake {
    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun run(upload: File, displayName: String, runDir: File): IntakeResult {
        Intake.checkName(displayName, upload.length())?.let { return IntakeResult("failed", it, null) } // before the file is opened, as on the PC
        val probed = ClipProbe.probe(upload.path)
        val srcInfo = probed?.info ?: JsonObject(emptyMap())
        Intake.check(displayName, upload.length(), srcInfo)?.let { return IntakeResult("failed", it, null) }
        // what can really be read: the length of a file cut short is not the one its header promised
        val read = ClipProbe.readable(upload.path) ?: return IntakeResult("failed", "The clip could not be converted; try another file.", null)
        val rotation = ClipProbe.rotation(upload.path)
        val (cw, ch) = read.codedWidth to read.codedHeight
        val (uw, uh) = if (rotation == 90 || rotation == 270) ch to cw else cw to ch
        val (ww, wh) = Intake.workSize(uw, uh)
        val workInfo = buildJsonObject {
            put("format", read.info.getValue("format"))
            put("streams", JsonArray(listOf(buildJsonObject { put("codec_type", "video"); put("width", ww); put("height", wh) })))
        }
        Intake.checkWork(workInfo)?.let { return IntakeResult("failed", it, null) }
        runDir.mkdirs()
        Files.copy(upload.toPath(), File(runDir, "work.mp4").toPath(), StandardCopyOption.REPLACE_EXISTING)
        val srcWithRate = buildJsonObject {
            put("format", srcInfo.getValue("format"))
            // the real average rate from the samples; the header's streams otherwise
            put("streams", JsonArray(read.info.getValue("streams").jsonArray.map { it.jsonObject }))
        }
        val meta = Intake.clipMeta(srcWithRate, workInfo, sha256(upload))
        File(runDir, "clip_meta.json").writeText(PythonJson.dumpsIndentedAscii(meta, 2) + "\n")
        return IntakeResult("ok", "", meta)
    }
}
