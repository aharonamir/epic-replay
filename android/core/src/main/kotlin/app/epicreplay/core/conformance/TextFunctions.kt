package app.epicreplay.core.conformance

import app.epicreplay.core.sheet.ContactSheet
import app.epicreplay.core.text.EnglishLexicon
import app.epicreplay.core.text.MandarinLexicon
import app.epicreplay.core.text.SpokenUnits
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The functions of the conformance table that `core/` has ported so far (T412): `lexicon.scan`, `units.spoken_units`, `cells.narrow`, `cells.layout`. */
object TextFunctions {
    private fun strings(e: JsonElement) = e.jsonArray.map { it.jsonPrimitive.content }

    fun table(shared: File): Map<String, (Map<String, JsonElement>) -> JsonElement> {
        val english = EnglishLexicon(File(shared, "lexicons/en.json").readText())
        val mandarin = MandarinLexicon(File(shared, "lexicons/zh.json").readText())
        return mapOf(
            "lexicon.scan" to { a ->
                val extra = strings(a.getValue("extra"))
                val texts = strings(a.getValue("texts"))
                JsonArray(
                    if (a.getValue("language").jsonPrimitive.content == "zh") texts.map { t ->
                        buildJsonObject {
                            put("banned", JsonArray(mandarin.findBanned(t, extra).map(::JsonPrimitive)))
                            put("famous_names", JsonArray(mandarin.famousNames(t).map(::JsonPrimitive)))
                            put("numbers", JsonArray(mandarin.numbersIn(t).map(::JsonPrimitive)))
                        }
                    } else texts.map { t ->
                        buildJsonObject {
                            put("banned", JsonArray(english.findBanned(t, extra).map(::JsonPrimitive)))
                            put("descriptor", JsonArray(english.findDescriptorBanned(t).map(::JsonPrimitive)))
                        }
                    },
                )
            },
            "units.spoken_units" to { a ->
                val language = a.getValue("language").jsonPrimitive.content
                JsonArray(strings(a.getValue("texts")).map { JsonPrimitive(SpokenUnits.of(it, language)) })
            },
            "cells.narrow" to { a ->
                val sheets = ContactSheet.narrow(
                    a.getValue("start_s").jsonPrimitive.double, a.getValue("end_s").jsonPrimitive.double,
                    a.getValue("fps").jsonPrimitive.double, a.getValue("n_frames").jsonPrimitive.int,
                    a.getValue("picks").jsonArray.map { it.jsonPrimitive.int },
                )
                JsonArray(sheets.map { s -> JsonArray(s.map(::JsonPrimitive)) })
            },
            "cells.layout" to { a ->
                val l = ContactSheet.layout(a.getValue("width").jsonPrimitive.int, a.getValue("height").jsonPrimitive.int, a.getValue("count").jsonPrimitive.int)
                buildJsonObject {
                    put("cell_w", JsonPrimitive(l.cellW)); put("cell_h", JsonPrimitive(l.cellH)); put("rows", JsonPrimitive(l.rows))
                    put("width", JsonPrimitive(l.width)); put("height", JsonPrimitive(l.height))
                    put("label_x", JsonPrimitive(l.labelX)); put("label_y", JsonPrimitive(l.labelY))
                    put("label_scale", JsonPrimitive(l.labelScale)); put("label_thickness", JsonPrimitive(l.labelThickness))
                }
            },
        )
    }
}
