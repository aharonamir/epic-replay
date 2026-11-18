package app.epicreplay.core.models

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** A scripted engine: counts loads and overlapping calls, and can block a call until it is cancelled. */
class FakeEngine(private val body: (GenerateRequest, FakeEngine) -> GenerateResult) : InferenceEngine {
    override val mediaMarker = "<__media__>"
    val loads = AtomicInteger(); val closed = AtomicInteger(); val cancels = AtomicInteger()
    val inFlight = AtomicInteger(); @Volatile var peak = 0
    val requests = mutableListOf<GenerateRequest>()
    @Volatile var loadArgs: List<Any?> = emptyList()
    private val cancelled = CountDownLatch(1)
    @Volatile var blockUntilCancelled = false

    override fun load(modelPath: String, projectorPath: String?, contextTokens: Int, threads: Int, imageMaxTokens: Int) {
        loads.incrementAndGet(); loadArgs = listOf(modelPath, projectorPath, contextTokens, threads, imageMaxTokens)
    }

    override fun generate(request: GenerateRequest): GenerateResult {
        requests += request
        peak = maxOf(peak, inFlight.incrementAndGet())
        try {
            if (blockUntilCancelled) {
                cancelled.await(10, TimeUnit.SECONDS)
                return GenerateResult("", 5, 0, Stop.CANCELLED, 1, 1, 0)
            }
            return body(request, this)
        } finally { inFlight.decrementAndGet() }
    }

    override fun cancel() { cancels.incrementAndGet(); cancelled.countDown() }
    override fun close() { closed.incrementAndGet() }
}

class OnDeviceBackendTest {
    private lateinit var dir: File
    @BeforeTest fun setUp() { dir = Files.createTempDirectory("ondevice").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun profile(ram: Int = 1000) = OnDeviceProfile(
        id = "phone-model", label = "Phone", roles = listOf(Role.VISION, Role.TEXT), supportsTools = false, status = "experimental", notes = "",
        engine = "llama.cpp", files = listOf(ModelFile("w.gguf", "https://x/w", "0".repeat(64), 10, "weights"), ModelFile("p.gguf", "https://x/p", "0".repeat(64), 10, "projector")),
        contextTokens = 4096, minFreeRamMb = ram, minFreeStorageMb = 100, license = "x",
    )

    private fun install() { File(dir, "w.gguf").writeText("w"); File(dir, "p.gguf").writeText("p") }
    private val ok = { _: GenerateRequest, _: FakeEngine -> GenerateResult("""{"cell": 6}""", 300, 8, Stop.END_OF_TEXT, 10, 20, 5) }

    private fun backend(engine: FakeEngine, free: Long = 5000, p: OnDeviceProfile = profile(), cfg: OnDeviceConfig = OnDeviceConfig(), role: Role = Role.VISION) =
        OnDeviceBackend(p, role, dir, { free }, { engine }, cfg)

    private val schema = buildJsonObject { put("type", JsonPrimitive("object")) }

    @Test
    fun lookPutsAMarkerForEachImageWithItsLabelAndReturnsTheParsedReplyAtNoCost() = runBlocking {
        install()
        val engine = FakeEngine(ok)
        val r = backend(engine).look(listOf(LabeledImage("Grid 1", byteArrayOf(1)), LabeledImage(null, byteArrayOf(2))), "Which cell?", schema, "high")
        val req = engine.requests.single()
        assertEquals(listOf("user"), req.roles)
        assertTrue(req.contents[0].startsWith("Grid 1\n<__media__>\n<__media__>\nWhich cell?"), req.contents[0])
        assertTrue(req.contents[0].contains("Answer with JSON only, matching this schema:"))
        assertEquals(2, req.images.size); assertEquals("""{"type":"object"}""", req.schema)
        assertEquals(6, r.data!!.getValue("cell").let { (it as JsonPrimitive).content.toInt() })
        assertEquals(0.0, r.usd); assertEquals(300L to 8L, r.usage.inputTokens to r.usage.outputTokens)
    }

    @Test
    fun chatSendsTheMessagesAsGivenAndNoSchemaMeansNoData() = runBlocking {
        install()
        val engine = FakeEngine { _, _ -> GenerateResult("hello", 5, 1, Stop.END_OF_TEXT, 1, 1, 0) }
        val r = backend(engine, role = Role.TEXT).chat(listOf(ChatMessage(ChatRole.SYSTEM, "be brief"), ChatMessage(ChatRole.USER, "hi")))
        assertEquals(listOf("system", "user"), engine.requests[0].roles); assertEquals(listOf("be brief", "hi"), engine.requests[0].contents)
        assertEquals("hello", r.text); assertEquals(null, r.data); assertEquals(null, engine.requests[0].schema)
    }

    @Test
    fun theModelIsLoadedOnceWithTheProfilesFilesAndTheConfig() = runBlocking {
        install()
        val engine = FakeEngine(ok)
        val b = backend(engine, cfg = OnDeviceConfig(threads = 4, imageMaxTokens = 280))
        b.look(emptyList(), "q"); b.look(emptyList(), "q")
        assertEquals(1, engine.loads.get())
        assertEquals(listOf(File(dir, "w.gguf").path, File(dir, "p.gguf").path, 4096, 4, 280), engine.loadArgs)
    }

    @Test
    fun missingFilesAreNamedAndNothingIsLoaded() {
        File(dir, "w.gguf").writeText("w") // the projector is missing
        val engine = FakeEngine(ok)
        val e = assertFailsWith<ModelError.NotInstalled> { runBlocking { backend(engine).look(emptyList(), "q") } }
        assertEquals(listOf("p.gguf"), e.missing); assertEquals(0, engine.loads.get())
    }

    @Test
    fun notEnoughFreeMemoryRefusesBeforeLoadingNeverAfter() {
        install()
        val engine = FakeEngine(ok)
        val e = assertFailsWith<ModelError.OutOfMemory> { runBlocking { backend(engine, free = 800, p = profile(ram = 5000)).look(emptyList(), "q") } }
        assertEquals(0, engine.loads.get()); assertTrue("5000" in e.message!! && "800" in e.message!!)
    }

    @Test
    fun onceLoadedTheMemoryIsNotCheckedAgainBecauseTheModelItselfUsesIt() = runBlocking {
        install()
        var free = 5000L
        val engine = FakeEngine(ok)
        val b = OnDeviceBackend(profile(ram = 4000), Role.VISION, dir, { free }, { engine })
        b.look(emptyList(), "q"); free = 100 // the loaded model now holds the memory
        b.look(emptyList(), "q")
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun callsAreSerialisedNeverOverlappingOnTheEngine() = runBlocking {
        install()
        val engine = FakeEngine { _, _ -> Thread.sleep(40); GenerateResult("x", 1, 1, Stop.END_OF_TEXT, 1, 1, 0) }
        val b = backend(engine)
        (1..6).map { async { b.chat(listOf(ChatMessage(ChatRole.USER, "x"))) } }.awaitAll()
        assertEquals(1, engine.peak); assertEquals(6, engine.requests.size)
    }

    @Test
    fun aTimeoutStopsTheRunningGenerationAndTheNextCallSucceeds() = runBlocking {
        install()
        val engine = FakeEngine(ok); engine.blockUntilCancelled = true
        val b = backend(engine, cfg = OnDeviceConfig(textTimeoutS = 0.3))
        val t0 = System.nanoTime()
        assertFailsWith<ModelError.Timeout> { b.chat(listOf(ChatMessage(ChatRole.USER, "x"))) }
        assertTrue((System.nanoTime() - t0) / 1e9 < 2.0, "the call did not return soon after the timeout")
        assertEquals(1, engine.cancels.get()); assertEquals(0, engine.inFlight.get()) // the engine is free again
        engine.blockUntilCancelled = false
        assertEquals("""{"cell": 6}""", b.chat(listOf(ChatMessage(ChatRole.USER, "x")), schema).text) // the next call works
    }

    @Test
    fun cancellingTheCallerStopsTheGenerationAndFreesTheEngine() = runBlocking {
        install()
        val engine = FakeEngine(ok); engine.blockUntilCancelled = true
        val b = backend(engine)
        val job = launch { b.chat(listOf(ChatMessage(ChatRole.USER, "x"))) }
        delay(150)
        job.cancel(); job.join()
        assertTrue(job.isCancelled); assertEquals(1, engine.cancels.get()); assertEquals(0, engine.inFlight.get())
        engine.blockUntilCancelled = false
        assertEquals("""{"cell": 6}""", b.chat(listOf(ChatMessage(ChatRole.USER, "x")), schema).text)
    }

    @Test
    fun aCancelledWaitingCallNeverReachesTheEngine() = runBlocking {
        install()
        val engine = FakeEngine { _, _ -> Thread.sleep(300); GenerateResult("x", 1, 1, Stop.END_OF_TEXT, 1, 1, 0) }
        val b = backend(engine)
        val first = launch { b.chat(listOf(ChatMessage(ChatRole.USER, "1"))) }
        delay(50)
        val second = launch { b.chat(listOf(ChatMessage(ChatRole.USER, "2"))) }
        delay(50); second.cancel(); first.join(); second.join()
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun anEngineThatReportsCancelledWithoutBeingAskedIsACancellation() {
        install()
        val engine = FakeEngine { _, _ -> GenerateResult("", 1, 0, Stop.CANCELLED, 1, 1, 0) }
        assertFailsWith<CancellationException> { runBlocking { backend(engine).chat(listOf(ChatMessage(ChatRole.USER, "x"))) } }
    }

    @Test
    fun anErrorFromTheEngineIsPassedOnAndTheEngineStaysUsable() = runBlocking {
        install()
        var fail = true
        val engine = FakeEngine { _, _ -> if (fail) throw RuntimeException("the prompt could not be evaluated") else GenerateResult("fine", 1, 1, Stop.END_OF_TEXT, 1, 1, 0) }
        val b = backend(engine)
        assertFailsWith<RuntimeException> { b.chat(listOf(ChatMessage(ChatRole.USER, "x"))) }
        fail = false
        assertEquals("fine", b.chat(listOf(ChatMessage(ChatRole.USER, "x"))).text)
    }

    @Test
    fun aFailedLoadClosesTheEngineAndTheNextCallTriesAgain() {
        install()
        var attempts = 0
        val engines = mutableListOf<FakeEngine>()
        val b = OnDeviceBackend(profile(), Role.VISION, dir, { 5000 }, { FakeEngine(ok).also { engines += it; attempts++ } })
        // the first engine's load throws
        val failing = object : InferenceEngine by FakeEngine(ok) {
            override fun load(modelPath: String, projectorPath: String?, contextTokens: Int, threads: Int, imageMaxTokens: Int) = throw RuntimeException("the model could not be loaded")
        }
        val b2 = OnDeviceBackend(profile(), Role.VISION, dir, { 5000 }, { failing })
        assertFailsWith<RuntimeException> { runBlocking { b2.look(emptyList(), "q") } }
        runBlocking { b.look(emptyList(), "q") }
        assertEquals(1, attempts)
    }

    @Test
    fun unloadFreesTheEngineAndTheNextCallLoadsAgain() = runBlocking {
        install()
        val engines = mutableListOf<FakeEngine>()
        val b = OnDeviceBackend(profile(), Role.VISION, dir, { 5000 }, { FakeEngine(ok).also { engines += it } })
        b.look(emptyList(), "q"); b.unload(); b.look(emptyList(), "q")
        assertEquals(2, engines.size); assertEquals(1, engines[0].closed.get())
    }

    @Test
    fun toolsAreRefusedForAProfileWithoutThem() {
        install()
        assertFailsWith<IllegalArgumentException> { runBlocking { backend(FakeEngine(ok)).chat(emptyList(), null, listOf(ToolDef("t", "d", JsonObject(emptyMap())))) } }
    }
}
