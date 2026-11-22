package app.epicreplay.core.voice

import app.epicreplay.core.audio.Pcm
import app.epicreplay.core.budget.CapExceeded
import app.epicreplay.core.budget.Caps
import app.epicreplay.core.budget.InMemoryLedgerStorage
import app.epicreplay.core.budget.Ledger
import app.epicreplay.core.budget.PaidResult
import app.epicreplay.core.budget.paidCall
import app.epicreplay.core.net.HttpRequest
import app.epicreplay.core.net.HttpResponse
import app.epicreplay.core.net.HttpTransport
import app.epicreplay.core.secrets.Secret
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Model calls and voice calls reserve their estimates in one ledger before they are sent, so together they cannot go over a cap. */
class VoiceCapsTest {
    /** A provider that answers a take of silence and counts the requests it was sent. */
    private class FakeEleven : HttpTransport {
        val sent = AtomicInteger()
        override suspend fun send(request: HttpRequest, profileId: String): HttpResponse { sent.incrementAndGet(); delay(5); return HttpResponse(200, ByteArray(10)) }
    }

    private fun ledger(run: Double, day: Double = 20.0) = Ledger(InMemoryLedgerStorage(), Caps(runUsd = run, dayUsd = day, preCheckpointUsd = 5.0), today = { "2026-11-21" }, now = { "t" })
    private fun speech(t: FakeEleven, l: Ledger) = SpeechAdapter(t, { Secret("k") }, "eleven_v3", { Pcm(FloatArray(4800), 48000) }, l, null, "run")

    @Test
    fun aVoiceCallThatWouldGoOverTheRunCapIsNotSent(): Unit = runBlocking {
        val t = FakeEleven()
        val l = ledger(run = 0.0035) // a take of 30 characters, its tag counted, reserves 0.003 USD
        val s = speech(t, l)
        s.synthesise("a".repeat(26), "v", "x", "en", false) // 26 characters and the tag: 0.003
        assertEquals(1, t.sent.get())
        assertFailsWith<CapExceeded> { s.synthesise("b".repeat(26), "v", "x", "en", false) } // 0.003 + 0.003 is over 0.0035
        assertEquals(1, t.sent.get(), "the second request was never sent")
        assertEquals(0.003, l.runTotal("run"), 1e-9)
    }

    @Test
    fun manyModelAndVoiceCallsAtOnceNeverGoOverTheRunCapOrTheDayCap(): Unit = runBlocking {
        for ((run, day) in listOf(0.05 to 20.0, 20.0 to 0.04)) {
            val t = FakeEleven()
            val l = ledger(run, day)
            val s = speech(t, l)
            val done = AtomicInteger(); val blocked = AtomicInteger()
            // 40 voice takes of 30 characters (0.003 each) and 40 model calls of 0.004, all at once
            val jobs = (0 until 40).flatMap { i ->
                listOf(
                    async(Dispatchers.Default) { try { s.synthesise("t${i}".padEnd(26, 'x'), "v", "x", "en", false); done.incrementAndGet() } catch (e: CapExceeded) { blocked.incrementAndGet() } },
                    async(Dispatchers.Default) { try { l.paidCall("run", "S2", "p", "m", 0.004) { delay(2); PaidResult("ok", 0.004) }; done.incrementAndGet() } catch (e: CapExceeded) { blocked.incrementAndGet() } },
                )
            }
            jobs.awaitAll()
            val cap = minOf(run, day)
            assertTrue(l.runTotal("run") <= cap + 1e-9, "run total ${l.runTotal("run")} over $cap")
            assertTrue(l.dayTotal() <= day + 1e-9, "day total ${l.dayTotal()} over $day")
            assertTrue(blocked.get() > 0 && done.get() > 0, "some calls were let in and some blocked: ${done.get()} / ${blocked.get()}")
            assertEquals(80, done.get() + blocked.get())
            assertTrue(t.sent.get() <= done.get(), "a blocked voice call sent nothing")
        }
    }

    @Test
    fun aTimeoutIsCountedAsSpentAtItsEstimateAndAFailedCallIsReleased(): Unit = runBlocking {
        val l = ledger(run = 1.0)
        val timing = object : HttpTransport {
            override suspend fun send(request: HttpRequest, profileId: String): HttpResponse = throw app.epicreplay.core.models.ModelError.Timeout(profileId, "took too long")
        }
        assertFailsWith<app.epicreplay.core.models.ModelError.Timeout> { SpeechAdapter(timing, { Secret("k") }, "eleven_v3", { error("") }, l, null, "run").synthesise("x".repeat(26), "v", "x", "en", false) }
        assertEquals(0.003, l.runTotal("run"), 1e-9) // it may have been billed
        val failing = object : HttpTransport { override suspend fun send(request: HttpRequest, profileId: String) = HttpResponse(500, "boom".toByteArray()) }
        assertFailsWith<SpeechError> { SpeechAdapter(failing, { Secret("k") }, "eleven_v3", { error("") }, l, null, "run").synthesise("y".repeat(26), "v", "x", "en", false) }
        assertEquals(0.003, l.runTotal("run"), 1e-9) // an error answer was not billed
        assertTrue(l.inFlight().isEmpty())
        assertFailsWith<SpeechError> { SpeechAdapter(failing, { null }, "eleven_v3", { error("") }, l, null, "run").synthesise("z", "v", "x", "en", false) } // no key: nothing is sent, nothing is kept
        assertEquals(0.003, l.runTotal("run"), 1e-9)
    }
}
