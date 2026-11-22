package app.epicreplay.core.compose

import kotlinx.coroutines.CancellationException

/** Which video encoder an export attempt may use: the phone's choice (the hardware one first) or the software one (spike S2: the hardware encoder sometimes gave a sample the muxer refused). */
enum class EncoderChoice { DEFAULT, SOFTWARE }

/** An export that did not finish or whose output was refused. */
class ExportFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One try at the export with the given encoder: it writes the file or throws [ExportFailure], and removes what it wrote when it fails. */
fun interface ExportAttempt {
    suspend fun run(encoder: EncoderChoice)
}

/**
 * The retry of spike S2 and plan risk 95: one try with the phone's encoder, one more try with it, then one with the software encoder; each failure is told to [log]
 * (the progress screen and the run's events). The call returns the encoder the export finished with, or throws the last failure.
 */
object ExportPolicy {
    suspend fun run(attempt: ExportAttempt, log: (String) -> Unit = {}): EncoderChoice {
        val order = listOf(EncoderChoice.DEFAULT to "the first try", EncoderChoice.DEFAULT to "the retry", EncoderChoice.SOFTWARE to "the software encoder")
        var last: ExportFailure? = null
        for ((i, step) in order.withIndex()) {
            try {
                attempt.run(step.first)
                if (i > 0) log("export finished on ${if (step.first == EncoderChoice.SOFTWARE) "the software encoder" else "the retry"}")
                return step.first
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportFailure) {
                last = e
                log("export failed on ${step.second}: ${(e.message ?: "no message").take(200)}" + if (i < order.lastIndex) "; " + (if (order[i + 1].first == EncoderChoice.SOFTWARE) "trying the software encoder" else "trying again") else "")
            }
        }
        throw last!!
    }
}
