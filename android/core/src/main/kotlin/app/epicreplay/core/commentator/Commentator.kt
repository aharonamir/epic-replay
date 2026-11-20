package app.epicreplay.core.commentator

import app.epicreplay.core.PyMath
import app.epicreplay.core.checks.Checks
import app.epicreplay.core.json.PyValue
import app.epicreplay.core.text.PyText
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What the Commentator is told and what it falls back on, word for word (`shared/copy/commentator.json`; the PC's recordings are keyed on the system prompt). */
class CommentatorWords(val data: JsonObject) {
    val maxIterations: Int get() = data.getValue("max_iterations").jsonPrimitive.content.toInt()
    val draftSchema: JsonObject get() = data.getValue("draft_schema").jsonObject
    val zhHeadlineMax: Int get() = data.getValue("zh_headline_max").jsonPrimitive.content.toInt()
    val zhPunctuation: String get() = data.getValue("zh_punctuation").jsonPrimitive.content
    val structuredDraftIntro: String get() = data.getValue("structured_draft_intro").jsonPrimitive.content
    fun toolDescription(name: String): String = data.getValue("tools").jsonObject.getValue(name).jsonPrimitive.content

    companion object {
        fun load(shared: File) = CommentatorWords(Json.parseToJsonElement(File(shared, "copy/commentator.json").readText()).jsonObject)
    }
}

/** The result of a run's drafts: the script, `ok` or `degraded` (the fallback wrote a line), and a note. */
class ScriptOutcome(val script: JsonObject, val draft: JsonObject, val status: String, val note: String)

/**
 * The Commentator's plain code (`pc/src/epic_replay/agents/commentator.py`, plan 4.7): the brief and the system prompt, how a draft is assembled
 * into a script and judged, how a failing draft is mended with generic lines. No model is called here; [CommentatorRun] is the loop that asks one.
 */
class Commentator(private val words: CommentatorWords, private val checks: Checks, scriptSchema: String) {
    private val schema = JsonSchema.fromDefinition(scriptSchema)
    private val LINK_EPS = 0.002 // contracts.EPS
    private val keyRoles = setOf("climax", "replay_climax")
    // Python's \b is Unicode-aware. The JVM needs a flag for that; Android's regex (ICU) is Unicode-aware already and refuses the flag.
    private val caption = words.data.getValue("caption_pattern").jsonPrimitive.content.let { p ->
        val base = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        try { Pattern.compile(p, base or Pattern.UNICODE_CHARACTER_CLASS) } catch (e: IllegalArgumentException) { Pattern.compile(p, base) }
    }
    private val generic = words.data.getValue("generic").jsonObject
    private val genericZh = words.data.getValue("generic_zh").jsonObject

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
    private fun JsonObject.num(k: String): Double = getValue(k).jsonPrimitive.double
    private fun strip(s: String, extra: String = "") = s.trim { PyText.isSpace(it) || it in extra }
    private fun cjk(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF
    private fun units(text: String, lang: String) = if (lang == "zh") text.codePoints().filter { cjk(it) }.count().toInt() else PyText.splitWords(text).size

    // --- what the model is told

    private fun isCaption(event: JsonObject, ps: JsonObject): Boolean {
        val actor = (ps["actors"] as? JsonArray).orEmpty().map { it.jsonObject }.firstOrNull { it["id"] == event["actor"] }
        val text = listOf("action", "object", "outcome").joinToString(" ") { PyValue.strOrEmpty(event[it]) }
        return caption.matcher("$text ${actor?.str("descriptor") ?: ""}").find()
    }

    private fun usableEvents(ps: JsonObject): List<JsonObject> = (ps["events"] as? JsonArray).orEmpty().map { it.jsonObject }.filter {
        ((it["confidence"] as? JsonPrimitive)?.double ?: 0.0) >= 0.7 || it.str("source") == "user"
    }.filter { !isCaption(it, ps) }

    /** The source times on screen while the slot plays, or null over a still segment. */
    private fun sourceSpan(slot: JsonObject, sb: JsonObject): JsonElement {
        val seg = sb.getValue("segments").jsonArray.map { it.jsonObject }.first { it.str("id") == slot.str("segment_id") }
        val src = seg["source"] as? JsonObject ?: return JsonNull
        val t0 = slot.getValue("anchor").jsonObject.num("timeline_t")
        val t1 = t0 + slot.num("max_duration_s")
        val speed = seg.num("speed"); val start = seg.num("timeline_start"); val inS = src.num("in_s"); val outS = src.num("out_s")
        val a = inS + (maxOf(t0, start) - start) * speed
        val b = inS + (minOf(t1, seg.num("timeline_end")) - start) * speed
        return JsonArray(listOf(JsonPrimitive(PyMath.round(minOf(a, outS), 2)), JsonPrimitive(PyMath.round(minOf(b, outS), 2))))
    }

    fun brief(ps: JsonObject, uc: JsonObject, sb: JsonObject, slots: List<JsonObject>): JsonObject {
        val actors = (ps["actors"] as? JsonArray).orEmpty().map { it.jsonObject }.associateBy { it.str("id") }
        fun who(e: JsonObject): JsonElement {
            val a = actors[e.str("actor")] ?: return JsonNull
            return if (PyValue.truthy(a["user_name"])) a.getValue("user_name") else a["descriptor"] ?: JsonNull
        }
        val named = listOf("player_name", "team_home", "team_away", "score").filter { PyValue.truthy(uc[it]) }
        return buildJsonObject {
            put("activity", (ps["activity"] as? JsonObject)?.get("value") ?: JsonNull)
            put("setting", ps["setting"] ?: JsonNull)
            put("outcome", (ps["outcome_summary"] as? JsonObject)?.get("value") ?: JsonNull)
            val climax = ps.getValue("climax").jsonObject
            put("key_moment_s", climax.getValue("t")); put("key_event", climax["event_id"] ?: JsonNull)
            put("named_by_user", JsonObject(named.associateWith { uc.getValue(it) }))
            put("user_description", uc["description"] ?: JsonNull)
            put("events", JsonArray(usableEvents(ps).map { e ->
                buildJsonObject {
                    put("id", e.getValue("id")); put("from_s", e.getValue("t_start")); put("to_s", e.getValue("t_end")); put("who", who(e))
                    put("action", e.getValue("action")); put("outcome", e["outcome"] ?: JsonNull)
                }
            }))
            put("slots", JsonArray(slots.map { s ->
                buildJsonObject {
                    put("id", s.getValue("id")); put("role", s.getValue("role")); put("max_words", s.getValue("max_words")); put("seconds", s.getValue("max_duration_s"))
                    put("shows_source_s", sourceSpan(s, sb))
                    put("segment", sb.getValue("segments").jsonArray.map { it.jsonObject }.first { it.str("id") == s.str("segment_id") }.getValue("kind"))
                }
            }))
        }
    }

    /** The system prompt for the pack as its language sees it. */
    fun systemPrompt(pack: JsonObject): String {
        val sc = pack.getValue("script").jsonObject
        val roles = sc.getValue("role_notes").jsonObject.entries.joinToString("\n") { (r, n) -> "- $r: ${n.jsonPrimitive.content}" }
        val examples = sc.getValue("example_lines").jsonObject.entries.joinToString("\n") { (r, t) -> "- $r: ${t.jsonPrimitive.content}" }
        val rules = sc.getValue("rules").jsonArray.joinToString("\n") { "- ${it.jsonPrimitive.content}" }
        var text = words.data.getValue("system_prompt").jsonPrimitive.content
            .replace("{persona}", sc.getValue("persona").jsonPrimitive.content).replace("{rules}", rules).replace("{roles}", roles).replace("{examples}", examples)
        if (pack.str("language").ifEmpty { "en" } == "zh") for (pair in words.data.getValue("zh_prompt").jsonArray) text = text.replace(pair.jsonArray[0].jsonPrimitive.content, pair.jsonArray[1].jsonPrimitive.content)
        return text
    }

    // --- the draft

    private fun language(pack: JsonObject) = pack.str("language").ifEmpty { "en" }

    fun wordIn(word: String, text: String, language: String): Boolean {
        if (language == "zh") {
            val w = strip(word, words.zhPunctuation + " ")
            val n = PyText.length(w)
            return n in 1..3 && w.codePoints().allMatch { cjk(it) } && w in text
        }
        val punct = ".,!?;:'\"…"
        val w = strip(word, punct).lowercase(Locale.ROOT)
        return w.isNotEmpty() && w in PyText.splitWords(text).map { strip(it, punct).lowercase(Locale.ROOT) }
    }

    private fun delivery(slot: JsonObject, line: JsonObject, pack: JsonObject): String {
        val own = PyValue.strOrEmpty(line["delivery"])
        val default = pack.getValue("voice").jsonObject.getValue("delivery").jsonObject.getValue(slot.str("role")).jsonPrimitive.content
        if (language(pack) == "zh" && (slot.str("role") == "climax" || !own.all { it.code < 128 })) return default
        return own.ifEmpty { default }
    }

    /** The draft laid out as a script: one line per slot, the pack's voice, the user's choice of approval. */
    fun assemble(draft: JsonObject, slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, pack: JsonObject): JsonObject {
        val byId = LinkedHashMap<String, JsonObject>()
        for (l in (draft["lines"] as? JsonArray).orEmpty()) { val o = l as? JsonObject ?: continue; (o["id"] as? JsonPrimitive)?.let { byId[it.content] = o } }
        val known = (ps["events"] as? JsonArray).orEmpty().map { it.jsonObject.str("id") }.toSet()
        val lines = slots.map { s ->
            val d = byId[s.str("id")] ?: JsonObject(emptyMap())
            val refs = LinkedHashSet<String>()
            for (r in (d["event_refs"] as? JsonArray).orEmpty()) (r as? JsonPrimitive)?.takeIf { it.isString }?.let { refs += it.content }
            val line = LinkedHashMap<String, JsonElement>(s)
            line["text"] = JsonPrimitive(strip(PyValue.strOrEmpty(d["text"])))
            line["delivery"] = JsonPrimitive(delivery(s, d, pack))
            line["event_refs"] = JsonArray(refs.map(::JsonPrimitive))
            line["generic"] = JsonPrimitive(refs.none { it in known })
            if (s.str("role") in keyRoles && PyValue.truthy(d["hit_word"])) line["hit_word"] = JsonPrimitive(strip(PyValue.str(d["hit_word"]), ".,!?;:'\"… " + words.zhPunctuation))
            JsonObject(line)
        }
        val v = pack.getValue("voice").jsonObject
        return buildJsonObject {
            put("schema_version", "1.0"); put("run_id", ps.getValue("run_id")); put("language", language(pack)); put("style", pack.getValue("id"))
            put("words_per_second", v.getValue("words_per_second")); put("startup_s", v.getValue("startup_s")); put("reaction_delay_s", v.getValue("reaction_delay_s"))
            put("voice", v.getValue("primary")); put("headline", strip(PyValue.strOrEmpty(draft["headline"]))); put("lower_third", strip(PyValue.strOrEmpty(draft["lower_third"])))
            put("lines", JsonArray(lines))
            put("approved", if (uc["express"] == null || PyValue.truthy(uc["express"])) "express" else "no")
        }
    }

    /** A draft's failures in four groups: its own rules, the script schema's errors as (path, message), the links to the play sheet and the storyboard, and the S6 checks. */
    class Groups(val own: List<JsonObject>, val schema: List<Pair<String, String>>, val links: List<String>, val checks: List<JsonObject>)

    fun failureGroups(draft: JsonObject, slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, sb: JsonObject, pack: JsonObject): Groups {
        val script = assemble(draft, slots, ps, uc, pack)
        val lang = language(pack)
        val own = ArrayList<JsonObject>()
        val ids = (draft["lines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("id") }.toSet()
        fun fail(where: String, detail: String) = buildJsonObject { put("where", where); put("detail", detail) }
        for (s in slots) if (s.getValue("id") !in ids) own += fail(s.str("id"), "no line for this slot")
        for (ln in script.getValue("lines").jsonArray.map { it.jsonObject }) {
            if (ln.str("text").isEmpty()) continue
            if (ln.str("role") in keyRoles && !wordIn(ln.str("hit_word"), ln.str("text"), lang)) own += fail(ln.str("id"), "hit_word must be ${if (lang == "en") "one word" else "one to three characters"} of the line")
        }
        if (lang == "zh") for (name in listOf("headline", "lower_third")) if (PyText.length(script.str(name)) > words.zhHeadlineMax) own += fail(name, "at most ${words.zhHeadlineMax} characters")
        val errs = mutableListOf<ValidationError>()
        schema.validate(script, errs::add)
        val schemaErrs = errs.map { e -> (e.objectPath.toString().removePrefix("#").trim('/').ifEmpty { "<root>" }) to e.message.take(160) }
        return Groups(own, schemaErrs, links(script, ps, sb), checks.check(script, ps, uc, pack).map { it.jsonObject }.map { fail(it.str("where"), it.str("detail")) })
    }

    private fun links(sc: JsonObject, ps: JsonObject, sb: JsonObject): List<String> {
        val out = ArrayList<String>()
        val segIds = sb.getValue("segments").jsonArray.map { it.jsonObject.str("id") }.toSet()
        val evIds = ps.getValue("events").jsonArray.map { it.jsonObject.str("id") }.toSet()
        val m = sb.getValue("markers").jsonObject
        val markers = listOf("climax_live_t", "climax_replay_t").mapNotNull { (m[it] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.double }
        for (ln in sc.getValue("lines").jsonArray.map { it.jsonObject }) {
            val id = ln.str("id")
            if (ln.str("segment_id") !in segIds) out += "$id: unknown segment ${ln.str("segment_id")}"
            for (r in ln.getValue("event_refs").jsonArray) if (r.jsonPrimitive.content !in evIds) out += "$id: unknown event ${r.jsonPrimitive.content}"
            val anchor = ln.getValue("anchor").jsonObject
            if (anchor.str("type") == "hit_at" && markers.none { Math.abs(it - anchor.num("timeline_t")) <= LINK_EPS }) out += "$id: hit_at anchor is not a storyboard marker"
        }
        return out
    }

    /** Every failure of a draft, as `{where, detail}`, in the order the model is told them. */
    fun failures(draft: JsonObject, slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, sb: JsonObject, pack: JsonObject): List<JsonObject> {
        val g = failureGroups(draft, slots, ps, uc, sb, pack)
        fun script(d: String) = buildJsonObject { put("where", "script"); put("detail", d) }
        return g.own + g.schema.map { (p, m) -> script("$p: $m") } + g.links.map(::script) + g.checks
    }

    // --- the fallback

    fun genericLine(slot: JsonObject, language: String): JsonObject {
        val role = slot.str("role"); val maxWords = slot.getValue("max_words").jsonPrimitive.content.toInt()
        val options = (if (language == "zh") genericZh else generic).getValue(role).jsonArray.map { it.jsonPrimitive.content }
        val text = options.firstOrNull { units(it, language) <= maxWords } ?: options.last()
        val hit: JsonElement = when {
            role !in keyRoles -> JsonNull
            language == "zh" -> JsonPrimitive(String(Character.toChars(text.codePoints().filter { cjk(it) }.findFirst().asInt)))
            else -> JsonPrimitive(strip(PyText.splitWords(text).first(), ".!"))
        }
        return buildJsonObject { put("id", slot.getValue("id")); put("text", text); put("hit_word", hit) }
    }

    /** The draft with every failing line, or a missing one, replaced by a generic line; and the ids replaced. A failing headline or lower third becomes the pack's labels. */
    fun patched(draftIn: JsonObject?, slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, sb: JsonObject, pack: JsonObject): Pair<JsonObject, List<String>> {
        var lines = ((draftIn?.get("lines") as? JsonArray).orEmpty()).filterIsInstance<JsonObject>().toMutableList()
        val rest = LinkedHashMap<String, JsonElement>(draftIn ?: JsonObject(emptyMap()))
        rest["lines"] = JsonArray(lines)
        var draft = JsonObject(rest)
        val replaced = LinkedHashSet<String>()
        val lang = language(pack)
        val labels = pack.getValue("script").jsonObject.getValue("labels").jsonObject
        repeat(2) {
            val bad = failures(draft, slots, ps, uc, sb, pack).map { it.str("where") }.toSet()
            for (s in slots) if (s.str("id") in bad) {
                lines = lines.filter { it.str("id") != s.str("id") }.toMutableList()
                lines += genericLine(s, lang)
                replaced += s.str("id")
            }
            val m = LinkedHashMap<String, JsonElement>(draft)
            m["lines"] = JsonArray(lines)
            if ((bad.intersect(setOf("headline", "script")).isNotEmpty()) || !PyValue.truthy(m["headline"])) m["headline"] = JsonPrimitive(PyText.take(labels.str("end_card_kicker").ifEmpty { "REPLAY" }, 40))
            if ((bad.intersect(setOf("lower_third", "script")).isNotEmpty()) || !PyValue.truthy(m["lower_third"])) m["lower_third"] = JsonPrimitive(PyText.take(labels.str("replay_bug").ifEmpty { "REPLAY" }, 40))
            draft = JsonObject(m)
        }
        return draft to replaced.toList().sorted()
    }

    /** The agent's draft passed every check: it is the script. */
    fun accepted(draft: JsonObject, slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, pack: JsonObject): ScriptOutcome {
        val script = assemble(draft, slots, ps, uc, pack)
        return ScriptOutcome(script, draft, "ok", "${script.getValue("lines").jsonArray.size} lines")
    }

    /** No accepted draft: the structured draft (null when that call failed too) with every failing line replaced by a generic one; the stage is `degraded`. */
    fun conclude(structured: JsonObject?, note: String, slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, sb: JsonObject, pack: JsonObject): ScriptOutcome {
        val (draft, replaced) = patched(structured, slots, ps, uc, sb, pack)
        return ScriptOutcome(assemble(draft, slots, ps, uc, pack), draft, "degraded", "$note; generic lines for ${replaced.joinToString(", ").ifEmpty { "none" }}")
    }

    /** Generic mode: no model is asked and every line is the generic line of its slot. */
    fun generic(slots: List<JsonObject>, ps: JsonObject, uc: JsonObject, sb: JsonObject, pack: JsonObject): ScriptOutcome {
        val (draft, _) = patched(null, slots, ps, uc, sb, pack)
        return ScriptOutcome(assemble(draft, slots, ps, uc, pack), draft, "ok", "generic mode: no claim about what happened")
    }
}
