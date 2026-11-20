package com.resker666.yeshunguangpet

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

internal class SkinFormatException(message: String) : Exception(message)

internal data class Animation(
    val row: Int,
    val startColumn: Int,
    val durationsMs: List<Int>,
    val loop: Boolean,
)

internal data class SkinManifest(
    val id: String,
    val name: String,
    val description: String?,
    val spriteSheet: String,
    val cellWidth: Int,
    val cellHeight: Int,
    val columns: Int,
    val rows: Int,
    val animations: Map<String, Animation>,
    val clickLines: List<String>,
) {
    val width: Int get() = cellWidth * columns
    val height: Int get() = cellHeight * rows

    companion object {
        private val validId = Regex("[a-z][a-z0-9-]{0,63}")
        private val reservedIds = Regex("con|prn|aux|nul|com[1-9]|lpt[1-9]")
        private val actionNames = setOf(
            "idle", "runningRight", "runningLeft", "waving", "jumping",
            "failed", "waiting", "running", "review",
        )
        private val speechEvents = setOf("click", "drag", "focusCompleted", "breakCompleted")
        private val knownFields = setOf(
            "schemaVersion", "id", "name", "description", "spriteSheet",
            "cellWidth", "cellHeight", "columns", "rows", "animations",
            "lookDirections", "speech", "behavior",
        )

        fun parse(source: Reader): SkinManifest {
            val fields = JsonReader(source).use { reader ->
                reader.strictness = Strictness.STRICT
                val root = readValue(reader, 0).asObject("pet.json")
                checkFormat(reader.peek() == JsonToken.END_DOCUMENT, "清单包含多余的内容")
                root.allowOnly(knownFields)
                root
            }
            checkFormat(fields.int("schemaVersion", 1..1) == 1, "当前仅支持 schemaVersion 1")
            val id = fields.string("id", 1..64)
            checkFormat(validId.matches(id) && !reservedIds.matches(id), "皮肤 ID 无效")
            val sheet = fields.optionalString("spriteSheet", 1..255) ?: "spritesheet.png"
            checkFormat(
                sheet.length > 4 && sheet.endsWith(".png", ignoreCase = true) &&
                    sheet.none { it in "/\\:<>|\"?*" || it.isISOControl() },
                "精灵图必须是同目录的 PNG 文件",
            )
            val cellWidth = fields.int("cellWidth", 16..512)
            val cellHeight = fields.int("cellHeight", 16..512)
            val columns = fields.int("columns", 1..64)
            val rows = fields.int("rows", 1..64)
            val width = cellWidth * columns
            val height = cellHeight * rows
            checkFormat(width <= 8192 && height <= 8192 && width.toLong() * height <= 16_777_216,
                "精灵图尺寸超出限制")

            val animationFields = fields["animations"].asObject("animations")
            animationFields.allowOnly(actionNames)
            val animations = animationFields.values.mapValues { (name, value) ->
                val clip = value.asObject(name)
                clip.allowOnly(setOf("row", "startColumn", "durationsMs", "loop"))
                val row = clip.int("row", 0 until rows, 0)
                val start = clip.int("startColumn", 0 until columns, 0)
                val durations = clip["durationsMs"].asList("durationsMs")
                checkFormat(durations.size in 1..(columns - start), "动作 $name 超出精灵图范围")
                val loop = clip.values["loop"]?.let {
                    (it as? BooleanValue)?.value ?: throw SkinFormatException("loop 必须是布尔值")
                } ?: false
                checkFormat(name != "idle" || loop, "idle 动作必须循环")
                checkFormat(name !in setOf("waving", "jumping") || !loop, "动作 $name 必须单次播放")
                Animation(row, start, durations.map { it.asInt("durationsMs", 20..10_000) }, loop)
            }
            checkFormat("idle" in animations, "皮肤缺少 idle 待机动作")
            fields.values["lookDirections"]?.takeUnless { it === NullValue }?.let {
                val frames = it.asList("lookDirections")
                checkFormat(frames.isEmpty() || frames.size == 16, "注视帧应为空或包含 16 项")
                frames.forEach { value ->
                    val point = value.asObject("lookDirections")
                    point.allowOnly(setOf("row", "column"))
                    point.int("row", 0 until rows)
                    point.int("column", 0 until columns)
                }
            }
            val speech = fields.values["speech"]?.takeUnless { it === NullValue }?.asObject("speech")
            speech?.allowOnly(speechEvents)
            val lines = speech?.values?.mapValues { (event, value) ->
                val items = value.asList(event)
                    .map { it.asString(event, 1..80) }
                checkFormat(items.size <= 8 && items.none { it.isBlank() || it.any(Char::isISOControl) },
                    "离线台词 $event 无效")
                items
            }
            fields.values["behavior"]?.takeUnless { it === NullValue }?.asObject("behavior")
            return SkinManifest(
                id, fields.string("name", 1..64), fields.optionalString("description", 0..400),
                sheet, cellWidth, cellHeight, columns, rows, animations,
                if (speech == null) listOf("我在。", "休息一下吧。") else lines?.get("click").orEmpty(),
            )
        }
    }
}

internal fun checkFormat(condition: Boolean, message: String) {
    if (!condition) throw SkinFormatException(message)
}

private sealed interface JsonValue
private data class ObjectValue(val values: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = values[key]

    fun allowOnly(keys: Set<String>) {
        val unknown = values.keys - keys
        checkFormat(unknown.isEmpty(), "未知字段：${unknown.joinToString()}")
    }

    fun int(key: String, range: IntRange, default: Int? = null): Int =
        if (!values.containsKey(key) && default != null) default else this[key].asInt(key, range)

    fun string(key: String, range: IntRange): String = this[key].asString(key, range)

    fun optionalString(key: String, range: IntRange): String? =
        this[key]?.takeUnless { it === NullValue }?.asString(key, range)
}
private data class ArrayValue(val values: List<JsonValue>) : JsonValue
private data class TextValue(val value: String) : JsonValue
private data class NumberValue(val value: String) : JsonValue
private data class BooleanValue(val value: Boolean) : JsonValue
private data object NullValue : JsonValue

private fun readValue(reader: JsonReader, depth: Int): JsonValue {
    checkFormat(depth <= 24, "清单嵌套过深")
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val fields = linkedMapOf<String, JsonValue>()
            while (reader.hasNext()) {
                val key = reader.nextName()
                checkFormat(key !in fields, "清单重复字段：$key")
                fields[key] = readValue(reader, depth + 1)
            }
            reader.endObject()
            ObjectValue(fields)
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            val values = mutableListOf<JsonValue>()
            while (reader.hasNext()) values.add(readValue(reader, depth + 1))
            reader.endArray()
            ArrayValue(values)
        }
        JsonToken.STRING -> TextValue(reader.nextString())
        JsonToken.NUMBER -> NumberValue(reader.nextString())
        JsonToken.BOOLEAN -> BooleanValue(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); NullValue }
        else -> throw SkinFormatException("清单包含无效的 JSON")
    }
}

private fun JsonValue?.asObject(key: String): ObjectValue =
    this as? ObjectValue ?: throw SkinFormatException("$key 必须是 JSON 对象")

private fun JsonValue?.asList(key: String): List<JsonValue> =
    (this as? ArrayValue)?.values ?: throw SkinFormatException("$key 必须是数组")

private fun JsonValue?.asInt(key: String, range: IntRange): Int {
    val number = (this as? NumberValue)?.value?.toIntOrNull()
    return number?.takeIf { it in range } ?: throw SkinFormatException("$key 不是有效的整数")
}

private fun JsonValue?.asString(key: String, range: IntRange): String =
    (this as? TextValue)?.value?.takeIf { it.length in range }
        ?: throw SkinFormatException("$key 不是有效的字符串")
