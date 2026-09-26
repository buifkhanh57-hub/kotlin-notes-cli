package notably.storage

import notably.model.ValidationException

/**
 * Minimal, dependency-free JSON object model used for every persistence file
 * in notably (notes index, notebooks registry, config, metadata, encrypted
 * envelopes). The model is deliberately tiny — six node types — and maps
 * directly onto the JSON grammar.
 */
sealed class JsonValue {

    /** JSON `null`. */
    object JsonNull : JsonValue() {
        override fun toString(): String = "null"
    }

    /** JSON `true` / `false`. */
    data class JsonBoolean(val value: Boolean) : JsonValue()

    /**
     * A JSON number kept in its *raw textual form* so serialization is
     * loss-free (e.g. `1.50` round-trips exactly). Interpret lazily via
     * [toLong] / [toDouble].
     */
    data class JsonNumber(val raw: String) : JsonValue() {

        /** Interpret as [Long]; falls back to truncating a double form. */
        fun toLong(): Long? = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.let { if (it.isFinite()) it.toLong() else null }

        /** Interpret as [Double]; `0.0` when the raw text is malformed. */
        fun toDouble(): Double = raw.toDoubleOrNull() ?: 0.0

        /** True when the literal has no fraction/exponent part. */
        fun isIntegral(): Boolean = raw.none { it == '.' || it == 'e' || it == 'E' }
    }

    /** JSON string. The stored [value] is the *decoded* text. */
    data class JsonString(val value: String) : JsonValue()

    /** JSON array with mutable item list (handy while building documents). */
    class JsonArray(val items: MutableList<JsonValue> = mutableListOf()) : JsonValue() {

        val size: Int get() = items.size

        operator fun get(index: Int): JsonValue = items[index]

        fun add(value: JsonValue): JsonArray {
            items.add(value)
            return this
        }

        override fun toString(): String = Json.write(this, pretty = false)
    }

    /** JSON object with insertion-ordered fields. */
    class JsonObject(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue() {

        operator fun get(key: String): JsonValue? = fields[key]

        operator fun set(key: String, value: JsonValue): JsonObject {
            fields[key] = value
            return this
        }

        fun put(key: String, value: JsonValue): JsonObject {
            fields[key] = value
            return this
        }

        fun has(key: String): Boolean = fields.containsKey(key)

        val keys: Set<String> get() = fields.keys

        // ---- typed accessors (return null on absence or type mismatch) ----

        fun str(key: String): String? = (fields[key] as? JsonString)?.value

        fun bool(key: String): Boolean? = (fields[key] as? JsonBoolean)?.value

        fun long(key: String): Long? = (fields[key] as? JsonNumber)?.toLong()

        fun double(key: String): Double? = (fields[key] as? JsonNumber)?.toDouble()

        fun obj(key: String): JsonObject? = fields[key] as? JsonObject

        fun array(key: String): JsonArray? = fields[key] as? JsonArray

        /** Reads [key] as a list of strings, skipping non-string entries. */
        fun stringList(key: String): List<String> =
            (fields[key] as? JsonArray)?.items?.mapNotNull { (it as? JsonString)?.value } ?: emptyList()

        override fun toString(): String = Json.write(this, pretty = false)
    }
}

// ---------------------------------------------------------------------------
// Builder helpers — keep model code terse and typo-safe.
// ---------------------------------------------------------------------------

/** Builds a [JsonValue.JsonObject] from key/value pairs in order. */
fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonValue.JsonObject {
    val obj = JsonValue.JsonObject()
    for ((k, v) in pairs) obj.put(k, v)
    return obj
}

/** Builds a [JsonValue.JsonArray] from an iterable of values. */
fun jsonArray(items: Iterable<JsonValue>): JsonValue.JsonArray {
    val arr = JsonValue.JsonArray()
    for (item in items) arr.add(item)
    return arr
}

/** Convenience: wraps [value] into a JSON string node. */
fun jsonString(value: String): JsonValue.JsonString = JsonValue.JsonString(value)

/** Convenience: wraps [value] into an integral JSON number node. */
fun jsonNumber(value: Long): JsonValue.JsonNumber = JsonValue.JsonNumber(value.toString())

/**
 * Convenience: wraps [value] into a JSON number node. Non-finite doubles
 * (`NaN`, infinities) cannot be represented in JSON and are rejected.
 */
fun jsonNumber(value: Double): JsonValue.JsonNumber {
    if (!value.isFinite()) throw ValidationException("cannot serialize non-finite number to JSON")
    return JsonValue.JsonNumber(value.toString())
}

/** Convenience: wraps [value] into a JSON boolean node. */
fun jsonBoolean(value: Boolean): JsonValue.JsonBoolean = JsonValue.JsonBoolean(value)

/** The singleton JSON null node. */
fun jsonNull(): JsonValue.JsonNull = JsonValue.JsonNull

// ---------------------------------------------------------------------------
// Writer
// ---------------------------------------------------------------------------

/**
 * Serializes [JsonValue] trees to JSON text. Escaping is implemented with a
 * per-character loop (no regexes) so it is fast and safe for arbitrary user
 * content: quotes, backslashes and control characters are escaped, non-ASCII
 * text is emitted verbatim (the output is UTF-8 encoded by the caller).
 */
object JsonWriter {

    private val ESCAPES: Map<Char, String> = mapOf(
        '"' to "\\\"",
        '\\' to "\\\\",
        '\n' to "\\n",
        '\r' to "\\r",
        '\t' to "\\t",
        '\b' to "\\b",
        '\u000C' to "\\f"
    )

    /** Renders [value]; when [pretty] is true output is indented with [indentUnit]. */
    fun write(value: JsonValue, pretty: Boolean, indentUnit: String = "  "): String {
        val sb = StringBuilder(256)
        writeValue(sb, value, pretty, 0, indentUnit)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: JsonValue, pretty: Boolean, depth: Int, indentUnit: String) {
        when (value) {
            is JsonValue.JsonNull -> sb.append("null")
            is JsonValue.JsonBoolean -> sb.append(if (value.value) "true" else "false")
            is JsonValue.JsonNumber -> sb.append(value.raw)
            is JsonValue.JsonString -> writeString(sb, value.value)
            is JsonValue.JsonArray -> writeArray(sb, value, pretty, depth, indentUnit)
            is JsonValue.JsonObject -> writeObject(sb, value, pretty, depth, indentUnit)
        }
    }

    private fun writeArray(sb: StringBuilder, arr: JsonValue.JsonArray, pretty: Boolean, depth: Int, indentUnit: String) {
        if (arr.items.isEmpty()) {
            sb.append("[]")
            return
        }
        sb.append('[')
        arr.items.forEachIndexed { index, item ->
            if (index > 0) sb.append(',')
            if (pretty) {
                sb.append('\n').append(indentUnit.repeat(depth + 1))
            }
            writeValue(sb, item, pretty, depth + 1, indentUnit)
        }
        if (pretty) sb.append('\n').append(indentUnit.repeat(depth))
        sb.append(']')
    }

    private fun writeObject(sb: StringBuilder, obj: JsonValue.JsonObject, pretty: Boolean, depth: Int, indentUnit: String) {
        if (obj.fields.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        var index = 0
        for ((key, value) in obj.fields) {
            if (index > 0) sb.append(',')
            if (pretty) {
                sb.append('\n').append(indentUnit.repeat(depth + 1))
            }
            writeString(sb, key)
            sb.append(':')
            if (pretty) sb.append(' ')
            writeValue(sb, value, pretty, depth + 1, indentUnit)
            index++
        }
        if (pretty) sb.append('\n').append(indentUnit.repeat(depth))
        sb.append('}')
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            val escaped = ESCAPES[ch]
            if (escaped != null) {
                sb.append(escaped)
            } else if (ch.code < 0x20) {
                sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(ch)
            }
        }
        sb.append('"')
    }
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

/**
 * Raised by [JsonParser] for any malformed input. [line]/[column] are
 * 1-based positions of the offending character, suitable for user feedback
 * on hand-edited workspace files.
 */
class JsonParseException(
    message: String,
    val line: Int,
    val column: Int
) : RuntimeException("JSON parse error at line $line, column $column: $message")

/**
 * Hand-rolled recursive-descent JSON parser (RFC 8259 subset used by notably).
 *
 * Design notes:
 *  - no regexes and no lazy substrings — a single pass over the input;
 *  - a hard [MAX_DEPTH] guard prevents stack exhaustion on hostile input;
 *  - numbers are validated against the grammar (no leading zeros, mandatory
 *    fraction/exponent digits) and kept in raw form;
 *  - `Actual Data` in comments refers to the raw input char stream.
 */
class JsonParser(private val source: String) {

    private var pos = 0

    /** Parses the whole document; fails when trailing content remains. */
    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (pos != source.length) fail("unexpected trailing content")
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("maximum nesting depth of $MAX_DEPTH exceeded")
        val c = peek() ?: fail("unexpected end of input")
        return when {
            c == '{' -> parseObject(depth)
            c == '[' -> parseArray(depth)
            c == '"' -> JsonValue.JsonString(parseString())
            c == 't' -> parseKeyword("true", JsonValue.JsonBoolean(true))
            c == 'f' -> parseKeyword("false", JsonValue.JsonBoolean(false))
            c == 'n' -> parseKeyword("null", JsonValue.JsonNull)
            c == '-' || c.isDigit() -> parseNumber()
            else -> fail("unexpected character '${c}'")
        }
    }

    private fun parseObject(depth: Int): JsonValue.JsonObject {
        expect('{')
        val obj = JsonValue.JsonObject()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return obj
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected string key in object")
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            obj.put(key, parseValue(depth + 1))
            skipWhitespace()
            val next = peek() ?: fail("unterminated object")
            when (next) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return obj
                }
                else -> fail("expected ',' or '}' in object but found '${next}'")
            }
        }
    }

    private fun parseArray(depth: Int): JsonValue.JsonArray {
        expect('[')
        val arr = JsonValue.JsonArray()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return arr
        }
        while (true) {
            skipWhitespace()
            arr.add(parseValue(depth + 1))
            skipWhitespace()
            val next = peek() ?: fail("unterminated array")
            when (next) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return arr
                }
                else -> fail("expected ',' or ']' in array but found '${next}'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= source.length) fail("unterminated string")
            val c = source[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= source.length) fail("unterminated escape sequence")
                    when (val esc = source[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> sb.append(parseUnicodeEscape())
                        else -> fail("invalid escape '\\$esc'")
                    }
                }
                else -> {
                    if (c.code < 0x20) fail("unescaped control character inside string")
                    sb.append(c)
                }
            }
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (pos + 4 > source.length) fail("incomplete \\u escape")
        var code = 0
        repeat(4) { offset ->
            val digit = source[pos + offset].digitToIntOrNull(16)
                ?: fail("invalid hex digit in \\u escape")
            code = code * 16 + digit
        }
        pos += 4
        return code.toChar()
    }

    private fun parseNumber(): JsonValue.JsonNumber {
        val start = pos
        if (peek() == '-') pos++
        if (pos >= source.length) fail("unexpected end of number")
        if (peek() == '0') {
            pos++
        } else {
            val digits = consumeDigits()
            if (digits == 0) fail("expected digit in number")
        }
        if (peek() == '.') {
            pos++
            val fracDigits = consumeDigits()
            if (fracDigits == 0) fail("expected digit after decimal point")
        }
        val expStart = peek()
        if (expStart == 'e' || expStart == 'E') {
            pos++
            val sign = peek()
            if (sign == '+' || sign == '-') pos++
            if (consumeDigits() == 0) fail("expected exponent digits")
        }
        val raw = source.substring(start, pos)
        if (!isValidNumberShape(raw)) fail("malformed number '$raw'")
        return JsonValue.JsonNumber(raw)
    }

    private fun consumeDigits(): Int {
        var count = 0
        while (pos < source.length && source[pos].isDigit()) {
            pos++
            count++
        }
        return count
    }

    private fun parseKeyword(word: String, result: JsonValue): JsonValue {
        if (pos + word.length > source.length || source.substring(pos, pos + word.length) != word) {
            fail("invalid literal (expected '$word')")
        }
        pos += word.length
        return result
    }

    private fun skipWhitespace() {
        while (pos < source.length && source[pos] in " \t\r\n") pos++
    }

    private fun peek(): Char? = if (pos < source.length) source[pos] else null

    private fun expect(expected: Char) {
        val c = peek()
        if (c != expected) fail("expected '$expected' but found ${c?.let { "'$it'" } ?: "end of input"}")
        pos++
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message, lineAt(pos), columnAt(pos))

    private fun lineAt(index: Int): Int {
        var line = 1
        val bound = index.coerceAtMost(source.length)
        for (i in 0 until bound) {
            if (source[i] == '\n') line++
        }
        return line
    }

    private fun columnAt(index: Int): Int {
        if (index <= 0) return 1
        val lastNewline = source.lastIndexOf('\n', index - 1)
        return if (lastNewline < 0) index + 1 else index - lastNewline
    }

    companion object {
        /** Guards against stack overflow on deeply nested adversarial input. */
        const val MAX_DEPTH = 128

        /** Strict JSON number grammar check (no leading zeros, etc.). */
        fun isValidNumberShape(raw: String): Boolean {
            var i = 0
            if (i < raw.length && raw[i] == '-') i++
            if (i >= raw.length) return false
            if (raw[i] == '0') {
                i++
            } else if (raw[i] in '1'..'9') {
                while (i < raw.length && raw[i].isDigit()) i++
            } else {
                return false
            }
            if (i < raw.length && raw[i] == '.') {
                i++
                val fracStart = i
                while (i < raw.length && raw[i].isDigit()) i++
                if (i == fracStart) return false
            }
            if (i < raw.length && (raw[i] == 'e' || raw[i] == 'E')) {
                i++
                if (i < raw.length && (raw[i] == '+' || raw[i] == '-')) i++
                val expStart = i
                while (i < raw.length && raw[i].isDigit()) i++
                if (i == expStart) return false
            }
            return i == raw.length
        }
    }
}

// ---------------------------------------------------------------------------
// Nullable extensions — make lenient JSON reading terse and null-safe.
// ---------------------------------------------------------------------------

/** Casts a value to a JSON object node, or `null` when absent/mismatched. */
fun JsonValue?.asObjectOrNull(): JsonValue.JsonObject? = this as? JsonValue.JsonObject

/** Casts a value to a JSON array node, or `null` when absent/mismatched. */
fun JsonValue?.asArrayOrNull(): JsonValue.JsonArray? = this as? JsonValue.JsonArray

/** Casts a value to a JSON string node, or `null` when absent/mismatched. */
fun JsonValue?.asStringOrNull(): String? = (this as? JsonValue.JsonString)?.value

/** Single entry point used across the codebase: [Json.parse] / [Json.write]. */
object Json {

    /** Parses [text] into a [JsonValue] tree, throwing [JsonParseException]. */
    fun parse(text: String): JsonValue = JsonParser(text).parse()

    /** Renders [value]; see [JsonWriter.write] for formatting options. */
    fun write(value: JsonValue, pretty: Boolean = true, indentUnit: String = "  "): String =
        JsonWriter.write(value, pretty, indentUnit)

    /**
     * Structural equality helper used by tests: compares two trees ignoring
     * object field order but respecting array order and number raw text.
     */
    fun deepEquals(a: JsonValue?, b: JsonValue?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return when {
            a is JsonValue.JsonNull && b is JsonValue.JsonNull -> true
            a is JsonValue.JsonBoolean && b is JsonValue.JsonBoolean -> a.value == b.value
            a is JsonValue.JsonNumber && b is JsonValue.JsonNumber ->
                if (a.isIntegral() && b.isIntegral()) a.raw == b.raw
                else a.toDouble() == b.toDouble()
            a is JsonValue.JsonString && b is JsonValue.JsonString -> a.value == b.value
            a is JsonValue.JsonArray && b is JsonValue.JsonArray ->
                a.items.size == b.items.size && a.items.zip(b.items).all { (x, y) -> deepEquals(x, y) }
            a is JsonValue.JsonObject && b is JsonValue.JsonObject ->
                a.fields.size == b.fields.size &&
                    a.fields.all { (k, v) -> b.fields.containsKey(k) && deepEquals(v, b.fields[k]) }
            else -> false
        }
    }
}
