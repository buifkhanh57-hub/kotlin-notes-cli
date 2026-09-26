package notably.model

import java.time.Instant
import java.time.format.DateTimeParseException
import notably.storage.JsonValue
import notably.storage.jsonObject
import notably.storage.jsonString

/**
 * A notebook is a named container for notes (think "folder", but lighter:
 * a note belongs to exactly one notebook, referenced by display name).
 *
 * Notebooks are stored in their own registry file so metadata (description,
 * color, creation date) survives even when a notebook is temporarily empty.
 * Lookup/matching always goes through [normalizeKey], so `Work`, `work` and
 * `"  work "` all address the same notebook.
 *
 * @property name        display name as entered by the user (trimmed)
 * @property description optional free-form description (≤ [MAX_DESC_LENGTH])
 * @property color       optional `#RGB`/`#RRGGBB` accent color
 * @property createdAt   when the notebook was registered
 */
data class Notebook(
    val name: String,
    val description: String = "",
    val color: String? = null,
    val createdAt: Instant = Instant.now()
) {

    init {
        validate()
    }

    /** Normalized lookup key: trimmed, lower-cased, whitespace collapsed. */
    val key: String get() = normalizeKey(name)

    private fun validate() {
        val clean = name.trim()
        if (clean.isEmpty()) throw ValidationException("notebook name must not be blank")
        if (clean.length > MAX_NAME_LENGTH) {
            throw ValidationException("notebook name exceeds $MAX_NAME_LENGTH characters")
        }
        if (clean.any { it == '/' || it == '\\' || it.code < 0x20 }) {
            throw ValidationException("notebook name must not contain slashes or control characters")
        }
        if (clean.startsWith(".")) throw ValidationException("notebook name must not start with a dot")
        if (description.length > MAX_DESC_LENGTH) {
            throw ValidationException("notebook description exceeds $MAX_DESC_LENGTH characters")
        }
    }

    /**
     * Filesystem-friendly slug used by export bundles: lowercase ASCII and
     * dashes only, capped at [MAX_SLUG_LENGTH] characters.
     */
    fun slug(): String {
        val cleaned = name.lowercase()
            .replace(NON_ALNUM, "-")
            .trim('-')
            .take(MAX_SLUG_LENGTH)
            .trim('-')
        return cleaned.ifEmpty { "notebook" }
    }

    /** One-line rendering used by listings: `name — description`. */
    fun summaryLine(): String =
        if (description.isBlank()) name else "$name — ${description.take(80)}"

    /** Serializes the notebook registry entry to JSON. */
    fun toJson(): JsonValue.JsonObject {
        val fields = mutableListOf(
            "name" to jsonString(name),
            "description" to jsonString(description),
            "createdAt" to jsonString(createdAt.toString())
        )
        color?.let { fields.add("color" to jsonString(it)) }
        return jsonObject(*fields.toTypedArray())
    }

    companion object {
        const val DEFAULT_NAME = "Inbox"
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESC_LENGTH = 280
        const val MAX_SLUG_LENGTH = 48

        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /** Normalizes [raw] into the canonical comparison key. Never throws. */
        fun normalizeKey(raw: String): String =
            raw.trim().lowercase().replace(WHITESPACE, " ")

        /**
         * Validated factory. [name] is trimmed, [color] is normalized via
         * [Colors.normalizeOrNull] and timestamps default to [now].
         */
        fun create(
            name: String,
            description: String = "",
            color: String? = null,
            now: Instant = Instant.now()
        ): Notebook {
            val cleanName = name.trim()
            if (cleanName.isEmpty()) throw ValidationException("notebook name must not be blank")
            if (cleanName.length > MAX_NAME_LENGTH) {
                throw ValidationException("notebook name exceeds $MAX_NAME_LENGTH characters")
            }
            return Notebook(
                name = cleanName,
                description = description.trim(),
                color = Colors.normalizeOrNull(color),
                createdAt = now
            )
        }

        /**
         * Lenient deserializer: missing description defaults to empty, an
         * unparseable creation date falls back to "now", unknown colors are
         * dropped (not rejected) so hand-edited registries still load.
         */
        fun fromJson(obj: JsonValue.JsonObject): Notebook {
            val name = obj.str("name")?.trim().orEmpty()
            if (name.isEmpty()) throw ValidationException("stored notebook is missing its name")
            val createdAt = parseInstant(obj.str("createdAt")) ?: Instant.now()
            val colorRaw = obj.str("color")
            return Notebook(
                name = name,
                description = obj.str("description")?.trim() ?: "",
                color = colorRaw?.let { if (Colors.isValid(it)) it.lowercase() else null },
                createdAt = createdAt
            )
        }

        private fun parseInstant(raw: String?): Instant? {
            if (raw.isNullOrBlank()) return null
            return try {
                Instant.parse(raw.trim())
            } catch (_: DateTimeParseException) {
                null
            }
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
