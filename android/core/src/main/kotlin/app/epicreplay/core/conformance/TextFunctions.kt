package app.epicreplay.core.conformance

import app.epicreplay.core.audio.AudioAnalysis
import app.epicreplay.core.intake.Intake
import app.epicreplay.core.sheet.ContactSheet
import app.epicreplay.core.voice.ElevenLabs
import app.epicreplay.core.PyMath
import app.epicreplay.core.audio.Landed
import app.epicreplay.core.audio.Loudness
import app.epicreplay.core.compose.OverlayPlan
import app.epicreplay.core.mix.MixLayer
import app.epicreplay.core.mix.MixRender
import app.epicreplay.core.mix.MixRules
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
            "intake.work_size" to { a ->
                JsonArray(a.getValue("sizes").jsonArray.map { s -> val (w, h) = Intake.workSize(s.jsonArray[0].jsonPrimitive.content.toInt(), s.jsonArray[1].jsonPrimitive.content.toInt()); JsonArray(listOf(JsonPrimitive(w), JsonPrimitive(h))) })
            },
            "audio.measure" to { a ->
                val (y, sr) = DrawnSignal.draw(a.getValue("recipe").jsonObject)
                val (ta, tb) = AudioAnalysis.trimBounds(y, sr)
                val window = a.getValue("window_s").jsonPrimitive.double
                buildJsonObject {
                    put("trim", JsonArray(listOf(JsonPrimitive(ta), JsonPrimitive(tb))))
                    put("first_onset_s", AudioAnalysis.firstOnset(y, sr))
                    put("rises", JsonArray(a.getValue("times").jsonArray.map { JsonPrimitive(AudioAnalysis.snapToRise(y, sr, it.jsonPrimitive.double, window)) }))
                    put("blocks", AudioAnalysis.blockRms(y, sr).size)
                }
            },
            "flow.run" to { a -> app.epicreplay.core.run.FlowHarness.run(JsonObject(a), File(System.getProperty("shared.dir"))) },
            "card.answer" to { a ->
                try {
                    val r = app.epicreplay.core.card.PlayCard.answer(a.getValue("play_sheet").jsonObject, a.getValue("user_context").jsonObject, a.getValue("clip_meta").jsonObject, a.getValue("frame").jsonPrimitive.int, a.getValue("edits").jsonObject, a.getValue("context").jsonObject, a.getValue("generic").jsonPrimitive.content == "true")
                    buildJsonObject { put("play_sheet", r.playSheet); put("user_context", r.userContext) }
                } catch (e: IllegalArgumentException) { buildJsonObject { put("error", e.message ?: "") } }
            },
            "card.items" to { a ->
                val ps = a.getValue("play_sheet").jsonObject
                buildJsonObject { put("to_check", JsonArray(app.epicreplay.core.card.PlayCard.toCheck(ps).map { JsonPrimitive(it) })); put("nearest", app.epicreplay.core.card.PlayCard.nearestEvent(ps)) }
            },
            "card.rest_cost" to { a ->
                val shared = File(System.getProperty("shared.dir"))
                val files = File(shared, "models").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map { app.epicreplay.core.models.ProfileFile(it.name, it.readText()) }
                val loaded = app.epicreplay.core.models.ProfileLoader(File(shared, "contracts/model_profile.schema.json").readText(), app.epicreplay.core.models.Platform.PC).load(files)
                JsonPrimitive(app.epicreplay.core.card.PlayCard.restCostUsd(a.getValue("clip_meta").jsonObject, a.getValue("pack").jsonObject, loaded.profiles.getValue("deepseek-flash")))
            },
            "ui.state" to { a ->
                val shared = File(System.getProperty("shared.dir"))
                val files = a.getValue("files").jsonArray.map { it.jsonPrimitive.content }.toSet()
                val typical = a.getValue("typical").jsonObject.mapValues { it.value.jsonPrimitive.double }
                app.epicreplay.core.ui.RunState.of(a.getValue("manifest").jsonObject, typical, app.epicreplay.core.ui.PageWords.load(shared), "clip_meta.json" in files, "final.mp4" in files).toJson()
            },
            "audio.loudness" to { a ->
                val (y, sr) = DrawnSignal.draw(a.getValue("recipe").jsonObject)
                val ch = if (a.getValue("channels").jsonPrimitive.int == 1) listOf(y) else listOf(y, y)
                buildJsonObject { put("lufs", PyMath.round(Loudness.integrated(ch, sr), 1)); put("peak_dbfs", PyMath.round(Loudness.truePeakDb(ch), 1)) }
            },
            "mix.render" to { a ->
                val rules = MixRules.parse(java.io.File(System.getProperty("shared.dir"), "mix/rules.json").readText())
                val layers = a.getValue("layers").jsonArray.map { e ->
                    val o = e.jsonObject
                    val (y, _) = DrawnSignal.draw(o.getValue("recipe").jsonObject)
                    MixLayer(o.getValue("kind").jsonPrimitive.content, if (o["channels"]?.jsonPrimitive?.int == 2) listOf(y, y) else listOf(y), o.getValue("start").jsonPrimitive.double, o.getValue("gain_db").jsonPrimitive.double, o["loop"]?.jsonPrimitive?.content == "true")
                }
                val r = MixRender.render(layers, a.getValue("duration_s").jsonPrimitive.double, rules)
                val sr = rules.sampleRate
                val mono = FloatArray(r.left.size) { (r.left[it] + r.right[it]) / 2f }
                buildJsonObject {
                    put("lufs", PyMath.round(r.lufs, 1)); put("peak_dbfs", PyMath.round(r.peakDbfs, 1)); put("duration_s", PyMath.round(r.left.size.toDouble() / sr, 2))
                    put("window_db", JsonArray(a.getValue("windows").jsonArray.map { w ->
                        val from = (w.jsonArray[0].jsonPrimitive.double * sr).toInt(); val to = minOf(mono.size, (w.jsonArray[1].jsonPrimitive.double * sr).toInt())
                        var sum = 0.0; for (i in from until to) sum += mono[i].toDouble() * mono[i]
                        val rms = if (to > from) Math.sqrt(sum / (to - from)) else 0.0
                        JsonPrimitive(PyMath.round(20 * Math.log10(maxOf(rms, 1e-9)), 1))
                    }))
                }
            },
            "compose.overlays" to { a ->
                val times = a.getValue("times").jsonArray.map { it.jsonPrimitive.double }
                val g = a.getValue("pack").jsonObject.getValue("graphics").jsonObject
                val accent = g.getValue("accent").jsonPrimitive.content
                buildJsonObject {
                    put("colours", buildJsonObject {
                        put("accent", accent); put("accent_text", (g["accent_text"] as? JsonPrimitive)?.content?.ifEmpty { null } ?: accent); put("on_accent", OverlayPlan.onAccent(accent))
                    })
                    put("overlays", JsonArray(OverlayPlan.specs(a.getValue("storyboard").jsonObject, a.getValue("script").jsonObject, a.getValue("pack").jsonObject).map { s ->
                        buildJsonObject {
                            put("kind", s.kind); put("text", buildJsonObject { s.text.forEach { (k, v) -> put(k, v) } })
                            put("start", s.start); put("end", s.end); put("fade_s", s.fadeS)
                            put("alpha", JsonArray(times.map { JsonPrimitive(PyMath.round(s.alpha(it), 6)) }))
                        }
                    }))
                }
            },
            "compose.landed" to { a ->
                val (o, sr) = DrawnSignal.draw(a.getValue("output").jsonObject)
                val (t, _) = DrawnSignal.draw(a.getValue("take").jsonObject)
                val at = Math.round(a.getValue("offset_s").jsonPrimitive.double * sr).toInt()
                val out = o.copyOf()
                for (i in t.indices) if (at + i < out.size) out[at + i] += t[i]
                JsonPrimitive(PyMath.round(Landed.landed(t, out, a.getValue("planned_start_s").jsonPrimitive.double, sr), 6))
            },
            "speech.request" to { a ->
                JsonArray(a.getValue("takes").jsonArray.map { t ->
                    val o = t.jsonObject
                    val text = o.getValue("text").jsonPrimitive.content; val delivery = o.getValue("delivery").jsonPrimitive.content
                    val voice = o.getValue("voice_id").jsonPrimitive.content; val language = o.getValue("language").jsonPrimitive.content
                    buildJsonObject {
                        put("request", ElevenLabs.request(text, voice, delivery, o.getValue("model").jsonPrimitive.content, o.getValue("key").jsonPrimitive.content, o.getValue("timings").jsonPrimitive.content == "true", language))
                        put("chars", ElevenLabs.chars(text, delivery)); put("estimate_usd", ElevenLabs.estimateUsd(text, delivery)); put("recording_key", ElevenLabs.recordingKey(text, voice, delivery, language))
                    }
                })
            },
            "speech.alignment" to { a -> JsonArray(a.getValue("answers").jsonArray.map { ElevenLabs.alignment(it.jsonObject) ?: JsonNull }) },
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
