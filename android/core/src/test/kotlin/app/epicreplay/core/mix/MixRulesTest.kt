package app.epicreplay.core.mix

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/** The mix's numbers are shared data (`shared/mix/rules.json`): they fit their schema, and say what the PC's FFmpeg graph has used all along. */
class MixRulesTest {
    private val shared = File(System.getProperty("shared.dir"))

    @Test
    fun theSharedRulesFitTheirSchemaAndAreTheNumbersOfThePcMix(): Unit {
        val text = File(shared, "mix/rules.json").readText()
        val errors = mutableListOf<ValidationError>()
        JsonSchema.fromDefinition(File(shared, "contracts/mix_rules.schema.json").readText()).validate(Json.parseToJsonElement(text), errors::add)
        assertEquals(emptyList(), errors.map { it.message })
        val r = MixRules.parse(text)
        assertEquals(-14.0, r.targetLufs); assertEquals(-1.0, r.ceilingDbtp); assertEquals(1.0, r.limitMarginDb); assertEquals(48000, r.sampleRate)
        assertEquals(0.5, r.fadeInS); assertEquals(1.0, r.fadeOutS)
        // sidechaincompress=threshold=0.02:ratio=8:attack=20:release=350:makeup=1
        assertEquals(listOf(0.02, 8.0, 20.0, 350.0, 1.0), r.duck.let { listOf(it.threshold, it.ratio, it.attackMs, it.releaseMs, it.makeup) })
    }
}
