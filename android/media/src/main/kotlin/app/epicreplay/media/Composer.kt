package app.epicreplay.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.epicreplay.core.compose.EncoderChoice
import app.epicreplay.core.compose.ExportAttempt
import app.epicreplay.core.compose.ExportFailure
import app.epicreplay.core.compose.ExportPolicy
import app.epicreplay.core.compose.OverlaySpec
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** An overlay for Media3: a full-frame picture shown with the alpha of its spec's fade at each instant (nothing before its start or after its end). */
@UnstableApi
class FadingOverlay(private val bitmap: Bitmap, private val spec: OverlaySpec) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(spec.alpha(presentationTimeUs / 1_000_000.0).toFloat()).build()
}

/**
 * S10 on the phone (plan 4.11, FR-030): the S9 segments played one after the other, the overlays over them, and the mix as the sound, written as one H.264 and AAC
 * file by Media3. The export goes through [ExportPolicy]: the phone's encoder, once more on a refusal, then the software encoder (spike S2: the hardware encoder
 * sometimes gave a sample the muxer refused); each failure is told to [log].
 */
@UnstableApi
class Composer(private val context: Context, hardwareCodecs: ((String) -> List<MediaCodecInfo>)? = null) {
    /** The encoders the first two tries may use: the phone's own order, or [hardwareCodecs] (tests give it a list with nothing in it). */
    private val hardwareSelector: EncoderSelector = hardwareCodecs?.let { f -> EncoderSelector { ImmutableList.copyOf(f(it)) } } ?: EncoderSelector.DEFAULT

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var sizePortrait = false

    /** What an export finished with. */
    class Result(val encoder: EncoderChoice, val exportResult: ExportResult?)

    /** [portrait]: the storyboard is taller than wide, so the encoder must be told (Media3 otherwise swaps the sides of a portrait output). */
    suspend fun compose(segments: List<File>, mix: File, overlays: List<Pair<OverlaySpec, Bitmap>>, out: File, portrait: Boolean = false, log: (String) -> Unit = {}): Result {
        sizePortrait = portrait
        var last: ExportResult? = null
        val encoder = ExportPolicy.run(ExportAttempt { choice -> last = once(segments, mix, overlays, out, choice) }, log)
        return Result(encoder, last)
    }

    private fun software(mime: String): ImmutableList<MediaCodecInfo> = ImmutableList.copyOf(EncoderSelector.DEFAULT.selectEncoderInfos(mime).filter { it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") })

    private suspend fun once(segments: List<File>, mix: File, overlays: List<Pair<OverlaySpec, Bitmap>>, out: File, choice: EncoderChoice): ExportResult {
        out.delete()
        val video = EditedMediaItemSequence.Builder(segments.map { EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(it))).setRemoveAudio(true).build() }).build()
        val sound = EditedMediaItemSequence.Builder(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(mix))).setRemoveVideo(true).build())).build()
        val effects = Effects(emptyList(), if (overlays.isEmpty()) emptyList() else listOf(OverlayEffect(ImmutableList.copyOf<TextureOverlay>(overlays.map { (spec, bmp) -> FadingOverlay(bmp, spec) }))))
        val composition = Composition.Builder(listOf(video, sound)).setEffects(effects).build()
        val encoders = DefaultEncoderFactory.Builder(context).setEnableFallback(false)
            .setVideoEncoderSelector(if (choice == EncoderChoice.SOFTWARE) EncoderSelector { software(it) } else hardwareSelector).build()
        val done = CompletableDeferred<ExportResult>()
        val stopped = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val active = AtomicReference<Transformer?>(null)
        main.post {
            try {
                if (cancelled.get()) return@post
                val t = Transformer.Builder(context).setEncoderFactory(encoders).setPortraitEncodingEnabled(sizePortrait).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(exportResult) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                active.set(t)
                t.start(composition, out.path)
            } catch (e: Exception) { done.completeExceptionally(e) }
        }
        try {
            return done.await()
        } catch (e: CancellationException) {
            cancelled.set(true)
            main.post { try { active.get()?.cancel() } finally { stopped.complete(Unit) } }
            withContext(NonCancellable) { stopped.await(); out.delete() }
            throw e
        } catch (e: Exception) {
            out.delete()
            throw ExportFailure(e.message ?: e.javaClass.simpleName, e)
        }
    }
}
