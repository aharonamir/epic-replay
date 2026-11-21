package app.epicreplay.core.conformance

import app.epicreplay.core.intake.Intake
import app.epicreplay.core.sheet.ContactSheet
import app.epicreplay.core.text.EnglishLexicon
import app.epicreplay.core.text.MandarinLexicon
import app.epicreplay.core.text.SpokenUnits
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The functions of the conformance table that `core/` has ported so far (T412): `lexicon.scan`, `units.spoken_units`, `cells.narrow`, `cells.layout`. */
object TextFunctions {
    private data class CommentatorInputs(val slots: List<JsonObject>, val ps: JsonObject, val uc: JsonObject, val sb: JsonObject, val pack: JsonObject)

    private fun commentatorInputs(a: Map<String, JsonElement>) = CommentatorInputs(
        a.getValue("slots").jsonArray.map { it.jsonObject }, a.getValue("play_sheet").jsonObject, a.getValue("user_context").jsonObject, a.getValue("storyboard").jsonObject, a.getValue("pack").jsonObject,
    )

    private fun strings(e: JsonElement) = e.jsonArray.map { it.jsonPrimitive.content }

    fun table(shared: File): Map<String, (Map<String, JsonElement>) -> JsonElement> {
        val english = EnglishLexicon(File(shared, "lexicons/en.json").readText())
        val mandarin = MandarinLexicon(File(shared, "lexicons/zh.json").readText())
        val checks = app.epicreplay.core.checks.Checks.fromShared(shared)
        val commentator = app.epicreplay.core.commentator.Commentator(app.epicreplay.core.commentator.CommentatorWords.load(shared), checks, File(shared, "contracts/script.schema.json").readText())
        val spotter = app.epicreplay.core.spotter.Spotter(english, File(shared, "contracts/play_sheet.schema.json").readText())
        return mapOf(
            "checks.text" to { a -> checks.checkTexts(a.getValue("language").jsonPrimitive.content, strings(a.getValue("texts")), a.getValue("known").jsonPrimitive.content, strings(a.getValue("extra"))) },
            "checks.check" to { a -> checks.check(a.getValue("script").jsonObject, a.getValue("play_sheet").jsonObject, a.getValue("user_context").jsonObject, a.getValue("pack").jsonObject) },
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
            "signals.scan" to app.epicreplay.core.signals.Synthetic::scan,
            "storyboard.build" to { a ->
                app.epicreplay.core.storyboard.Storyboard.build(a.getValue("play_sheet").jsonObject, a.getValue("clip_meta").jsonObject, a.getValue("min_output_s").jsonPrimitive.content.toDouble())
            },
            "slots.build" to { a ->
                app.epicreplay.core.style.Slots.build(a.getValue("storyboard").jsonObject, a.getValue("pack").jsonObject, (a.getValue("climax_event_id") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)
            },
            "voice.word_start" to { a ->
                JsonArray(a.getValue("calls").jsonArray.map { c ->
                    val o = c.jsonObject
                    val al = o["alignment"] as? JsonObject
                    val r = app.epicreplay.core.voice.Voice.wordStart(
                        (al?.get("chars") as? JsonPrimitive)?.content, (al?.get("starts") as? JsonArray)?.map { it.jsonPrimitive.double },
                        o.getValue("delivery").jsonPrimitive.content, o.getValue("text").jsonPrimitive.content, o.getValue("word").jsonPrimitive.content, o.getValue("language").jsonPrimitive.content,
                    )
                    if (r == null) JsonNull else JsonPrimitive(r)
                })
            },
            "voice.place" to { a ->
                val ls = a.getValue("lines").jsonArray.map { l -> val o = l.jsonObject; app.epicreplay.core.voice.Line(o.getValue("id").jsonPrimitive.content, o.getValue("role").jsonPrimitive.content, o.getValue("max_duration_s").jsonPrimitive.double, o.getValue("anchor").jsonObject.getValue("timeline_t").jsonPrimitive.double) }
                val tk = a.getValue("takes").jsonArray.map { it.jsonObject }
                fun num(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.double
                val recs = ls.zip(tk).map { (l, t) ->
                    val raw = t.getValue("raw_duration_s").jsonPrimitive.double
                    val f = app.epicreplay.core.voice.Voice.modelledFit(raw, l.maxDurationS)
                    app.epicreplay.core.voice.Rec(l.id, l.role, "voice/${l.id}.wav", raw, f.first, f.second)
                }
                val ms = tk.map { t -> app.epicreplay.core.voice.Measure(t.getValue("lead_s").jsonPrimitive.double, num(t, "onset_s"), num(t, "word_at_s")) }
                app.epicreplay.core.voice.Voice.place(ls, recs, ms, a.getValue("delay").jsonPrimitive.double, a.getValue("end").jsonPrimitive.double) { r, room -> app.epicreplay.core.voice.Voice.modelledFit(r.rawDurationS, room) }
                JsonArray(recs.map { r ->
                    buildJsonObject {
                        put("id", r.id); put("role", r.role); put("file", r.file); put("raw_duration_s", r.rawDurationS)
                        put("speed_factor", r.speedFactor); put("duration_s", r.durationS)
                        put("dropped", r.dropped?.let(::JsonPrimitive) ?: JsonNull)
                        r.overS?.let { put("over_s", it) }
                        r.hitOffsetS?.let { put("hit_offset_s", it); put("hit_source", r.hitSource!!); put("target_s", r.targetS!!) }
                        put("timeline_start", r.timelineStart); put("timeline_end", r.timelineEnd)
                    }
                })
            },
            "mix.layers" to { a ->
                app.epicreplay.core.mix.Mix.layers(a.getValue("storyboard").jsonObject, a.getValue("voice").jsonObject, a.getValue("pack").jsonObject, a.getValue("user_context").jsonObject, a.getValue("clip_meta").jsonObject)
            },
            "visuals.plan" to { a ->
                app.epicreplay.core.visuals.VisualsPlan.plan(a.getValue("storyboard").jsonObject, a["focus_box"], a.getValue("src_frames").jsonPrimitive.int)
            },
            "spotter.plan_overview" to { a -> spotter.planOverview(a.getValue("info").jsonObject) },
            "spotter.map_events" to { a ->
                val grids = a.getValue("grids").jsonArray.map { g -> g.jsonArray.map { it.jsonObject } }
                JsonArray(a.getValue("answers").jsonArray.map { JsonArray(spotter.mapEvents(it.jsonObject, grids)) })
            },
            "spotter.sensitivity" to { a -> JsonArray(a.getValue("looks").jsonArray.map { spotter.sensitivity(it as? JsonObject) }) },
            "spotter.play_sheet" to { a ->
                val m = spotter.make(
                    a.getValue("run_id").jsonPrimitive.content, a.getValue("info").jsonObject, a.getValue("looks").jsonArray.map { it.jsonObject },
                    a["events_look"] as? JsonObject, a.getValue("signals").jsonObject, a.getValue("snap_s").jsonPrimitive.double,
                    a.getValue("model").jsonPrimitive.content, a.getValue("clip_meta").jsonObject,
                )
                buildJsonObject {
                    put("play_sheet", m.playSheet); put("accepted", m.accepted); put("errors", JsonArray(m.errors.map(::JsonPrimitive))); put("cleared", JsonArray(m.cleared.map(::JsonPrimitive)))
                }
            },
            "commentator.brief" to { a ->
                commentator.brief(a.getValue("play_sheet").jsonObject, a.getValue("user_context").jsonObject, a.getValue("storyboard").jsonObject, a.getValue("slots").jsonArray.map { it.jsonObject })
            },
            "commentator.system_prompt" to { a -> JsonPrimitive(commentator.systemPrompt(a.getValue("pack").jsonObject)) },
            "commentator.failures" to { a ->
                val (slots, ps, uc, sb, pack) = commentatorInputs(a)
                JsonArray(a.getValue("drafts").jsonArray.map { d ->
                    val g = commentator.failureGroups(d.jsonObject, slots, ps, uc, sb, pack)
                    fun script(t: String) = buildJsonObject { put("where", "script"); put("detail", t) }
                    JsonArray(g.own + g.schema.map { it.first }.toSortedSet().map { script("schema $it") } + g.links.map(::script) + g.checks)
                })
            },
            "commentator.patched" to { a ->
                val (slots, ps, uc, sb, pack) = commentatorInputs(a)
                JsonArray(a.getValue("drafts").jsonArray.map { d ->
                    val (draft, replaced) = commentator.patched(d as? JsonObject, slots, ps, uc, sb, pack)
                    buildJsonObject { put("draft", draft); put("replaced", JsonArray(replaced.map(::JsonPrimitive))) }
                })
            },
            "commentator.script" to { a ->
                val (slots, ps, uc, sb, pack) = commentatorInputs(a)
                val agent = a["agent_draft"] as? JsonObject
                val o = if (agent != null && commentator.failures(agent, slots, ps, uc, sb, pack).isEmpty()) commentator.accepted(agent, slots, ps, uc, pack)
                else commentator.conclude(a["structured_draft"] as? JsonObject, "the agent submitted no script that passes", slots, ps, uc, sb, pack)
                buildJsonObject { put("script", o.script); put("status", o.status); put("note", o.note) }
            },
            "commentator.generic" to { a ->
                val (slots, ps, uc, sb, pack) = commentatorInputs(a)
                val o = commentator.generic(slots, ps, uc, sb, pack)
                buildJsonObject { put("script", o.script); put("status", o.status); put("note", o.note) }
            },
            "intake.check" to { a ->
                JsonArray(a.getValue("files").jsonArray.map { f ->
                    val o = f.jsonObject
                    Intake.check(o.getValue("name").jsonPrimitive.content, o.getValue("size_bytes").jsonPrimitive.content.toLong(), o.getValue("info").jsonObject)?.let(::JsonPrimitive) ?: JsonNull
                })
            },
            "intake.work_check" to { a -> JsonArray(a.getValue("infos").jsonArray.map { Intake.checkWork(it.jsonObject)?.let(::JsonPrimitive) ?: JsonNull }) },
            "intake.capture_fps" to { a -> JsonArray(a.getValue("streams").jsonArray.map { JsonPrimitive(Intake.captureFps(it as? JsonObject)) }) },
            "intake.meta" to { a ->
                JsonArray(a.getValue("items").jsonArray.map { i -> val o = i.jsonObject; Intake.clipMeta(o.getValue("src_info").jsonObject, o.getValue("work_info").jsonObject, o.getValue("sha256").jsonPrimitive.content) })
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
