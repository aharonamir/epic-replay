package app.epicreplay.core.ui

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The words both apps show (`shared/copy/page.json`; the PC's `web/copy.py` reads the same file): the steps and their stages, the progress words, the play card's words and the stopped messages. */
class PageWords(val data: JsonObject) {
    class Step(val name: String, val stages: List<String>)

    val wordmark: String get() = data.getValue("wordmark").jsonPrimitive.content
    val headline: List<String> get() = data.getValue("headline").jsonArray.map { it.jsonPrimitive.content }
    val tip: String get() = data.getValue("tip").jsonPrimitive.content
    val narrators: String get() = data.getValue("narrators").jsonPrimitive.content
    val language: String get() = data.getValue("language").jsonPrimitive.content
    val go: String get() = data.getValue("go").jsonPrimitive.content
    val yourTurn: String get() = data.getValue("your_turn").jsonPrimitive.content
    val confirmed: String get() = data.getValue("confirmed").jsonPrimitive.content
    val timeLeft: List<String> get() = data.getValue("time_left").jsonArray.map { it.jsonPrimitive.content }
    val steps: List<Step> get() = data.getValue("steps").jsonArray.map { s -> Step(s.jsonObject.getValue("name").jsonPrimitive.content, s.jsonObject.getValue("stages").jsonArray.map { it.jsonPrimitive.content }) }
    fun optional(key: String): String = data.getValue("optional").jsonObject.getValue(key).jsonPrimitive.content
    fun progress(key: String): String = data.getValue("progress").jsonObject.getValue(key).jsonPrimitive.content
    fun card(key: String): String = data.getValue("card").jsonObject.getValue(key).jsonPrimitive.content
    fun result(key: String): String = data.getValue("result").jsonObject.getValue(key).jsonPrimitive.content
    fun panel(key: String): String = data.getValue("panels").jsonObject.getValue(key).jsonPrimitive.content
    fun past(key: String): String = data.getValue("past").jsonObject.getValue(key).jsonPrimitive.content

    /** A script line's role as the script panel names it; a role the list does not know has its underscores made spaces. */
    fun role(role: String): String = (data.getValue("roles").jsonObject[role] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: role.replace("_", " ")
    fun stopped(key: String): String = data.getValue("stopped").jsonObject.getValue(key).jsonPrimitive.content

    companion object {
        fun parse(text: String) = PageWords(Json.parseToJsonElement(text).jsonObject)
        fun load(shared: File) = parse(File(shared, "copy/page.json").readText())
    }
}
