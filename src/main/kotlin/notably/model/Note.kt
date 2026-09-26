package notably.model

import java.time.Instant
import java.time.format.DateTimeParseException
import notably.storage.JsonValue
import notably.storage.jsonArray
import notably.storage.jsonBoolean
import notably.storage.jsonObject
import notably.storage.jsonString

/**
 * The central domain object: a single note inside the workspace.
 *
 * A [Note] is immutable; every mutation performed by [notably.service.NoteService]
 * produces a new instance via `copy(...)` and bumps [updatedAt]. Instances are
 * validated on construction (including after `copy`), so an invalid note can
 * never enter the system — neither from user input nor from a corrupted file.
 *
 * @property id        short unique id (12 lowercase base-36 chars), used for
 *                     addressing notes on the command line (prefix matching ok)
 * @property title     human title, 1..[MAX_TITLE_LENGTH] chars after trimming
 * @property body      markdown body, up to [MAX_BODY_LENGTH] chars, may be empty
 * @property notebook  display name of the owning notebook (never blank)
 * @property tags      normalized tag set, see [Tag.normalize]
 * @property pinned    pinned notes float to the top of listings
 * @property status    lifecycle state, see [NoteStatus]
 * @property color     optional `#RRGGBB` accent color
 * @property createdAt creation timestamp (UTC instant)
 * @property updatedAt last mutation timestamp
 * @property pinnedAt  when the note was pinned, `null` when not pinned
 * @property trashedAt when the note entered the trash, `null` otherwise
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val notebook: String,
    val tags: Set<String>,
    val pinned: Boolean,
    val status: NoteStatus,
    val color: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val pinnedAt: Instant?,
    val trashedAt: Instant?
) {

    init {
        validate()
    }

    /** Normalized notebook key used for comparisons and grouping. */
    val notebookKey: String get() = Notebook.normalizeKey(notebook)

    /** True when the note has no meaningful body text. */
    val isBodyEmpty: Boolean get() = body.isBlank()

    private fun validate() {
        if (id.isBlank()) throw ValidationException("note id must not be blank")
        if (id.length > MAX_ID_LENGTH) throw ValidationException("note id exceeds $MAX_ID_LENGTH characters")
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) throw ValidationException("note title must not be blank")
        if (cleanTitle.length > MAX_TITLE_LENGTH) {
            throw ValidationException("note title exceeds $MAX_TITLE_LENGTH characters")
        }
        if (body.length > MAX_BODY_LENGTH) {
            throw ValidationException("note body exceeds $MAX_BODY_LENGTH characters")
        }
        if (notebook.isBlank()) throw ValidationException("note notebook must not be blank")
        if (pinned && pinnedAt == null) {
            throw ValidationException("pinned note '${cleanTitle}' must record pinnedAt")
        }
        if (!pinned && pinnedAt != null) {
            throw ValidationException("unpinned note '${cleanTitle}' must not record pinnedAt")
        }
        if (status == NoteStatus.TRASHED && trashedAt == null) {
            throw ValidationException("trashed note '${cleanTitle}' must record trashedAt")
        }
        if (status != NoteStatus.TRASHED && trashedAt != null) {
            throw ValidationException("non-trashed note '${cleanTitle}' must not record trashedAt")
        }
    }

    /** True when [raw] normalizes to one of the tags carried by this note. */
    fun hasTag(raw: String): Boolean = tags.contains(Tag.normalize(raw))

    /**
     * Returns a filesystem-friendly slug derived from the title, used by the
     * markdown bundle exporter: lowercase, ASCII alphanumerics and dashes.
     */
    fun slug(): String {
        val cleaned = title.lowercase()
            .replace(NON_ALNUM, "-")
            .trim('-')
            .take(MAX_SLUG_LENGTH)
            .trim('-')
        return cleaned.ifEmpty { "note" }
    }

    /** Consistent multi-line header used by `show`, exports and tests. */
    fun headerLine(): String = "[$id] $title"

    /**
     * Serializes the note to a JSON object. The format is versionless on
     * purpose: unknown extra keys are ignored on read (forward compatible),
     * and every field is optional except `id` (see [fromJson]).
     */
    fun toJson(): JsonValue.JsonObject {
        val fields = mutableListOf(
            "id" to jsonString(id),
            "title" to jsonString(title),
            "body" to jsonString(body),
            "notebook" to jsonString(notebook),
            "tags" to jsonArray(tags.sorted().map { jsonString(it) }),
            "pinned" to jsonBoolean(pinned),
            "status" to jsonString(status.id),
            "createdAt" to jsonString(createdAt.toString()),
            "updatedAt" to jsonString(updatedAt.toString())
        )
        color?.let { fields.add("color" to jsonString(it)) }
        pinnedAt?.let { fields.add("pinnedAt" to jsonString(it.toString())) }
        trashedAt?.let { fields.add("trashedAt" to jsonString(it.toString())) }
        return jsonObject(*fields.toTypedArray())
    }

    companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_BODY_LENGTH = 100_000
        const val MAX_ID_LENGTH = 64
        const val MAX_SLUG_LENGTH = 48

        private val NON_ALNUM = Regex("[^a-z0-9]+")

        /**
         * Factory used by the service layer. Trims inputs, normalizes tags,
         * validates the color and stamps timestamps with [now] (injectable
         * for deterministic tests).
         */
        fun create(
            title: String,
            body: String = "",
            notebook: String = Notebook.DEFAULT_NAME,
            tags: Collection<String> = emptyList(),
            pinned: Boolean = false,
            color: String? = null,
            id: String,
            now: Instant = Instant.now()
        ): Note {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) throw ValidationException("note title must not be blank")
            if (cleanTitle.length > MAX_TITLE_LENGTH) {
                throw ValidationException("note title exceeds $MAX_TITLE_LENGTH characters")
            }
            if (body.length > MAX_BODY_LENGTH) throw ValidationException("note body exceeds $MAX_BODY_LENGTH characters")
            val cleanNotebook = notebook.trim()
            if (cleanNotebook.isEmpty()) throw ValidationException("notebook must not be blank")
            val cleanColor = Colors.normalizeOrNull(color)
            val normalizedTags = Tag.normalizeAll(tags)
            val pinnedAt = if (pinned) now else null
            return Note(
                id = id,
                title = cleanTitle,
                body = body,
                notebook = cleanNotebook,
                tags = normalizedTags,
                pinned = pinned,
                status = NoteStatus.ACTIVE,
                color = cleanColor,
                createdAt = now,
                updatedAt = now,
                pinnedAt = pinnedAt,
                trashedAt = null
            )
        }

        /**
         * Deserializes a note from JSON. Missing/blank `id` is a hard error;
         * everything else degrades sensibly so hand-edited files still load.
         * Unknown status ids throw via [NoteStatus.fromId] to avoid silently
         * resurrecting trashed notes.
         */
        fun fromJson(obj: JsonValue.JsonObject): Note {
            val id = obj.str("id")?.trim().orEmpty()
            if (id.isEmpty()) throw ValidationException("stored note is missing its id")
            val title = obj.str("title")?.trim().orEmpty()
            if (title.isEmpty()) throw ValidationException("stored note '$id' is missing its title")
            val body = obj.str("body") ?: ""
            if (body.length > MAX_BODY_LENGTH) throw ValidationException("stored note '$id' body is too large")
            val notebook = obj.str("notebook")?.trim().orEmpty().ifEmpty { Notebook.DEFAULT_NAME }
            val tags = LinkedHashSet<String>()
            for (raw in obj.stringList("tags")) {
                if (tags.size >= Tag.MAX_PER_NOTE) break
                tags.add(Tag.normalize(raw))
            }
            val pinned = obj.bool("pinned") ?: false
            val status = NoteStatus.fromId(obj.str("status") ?: NoteStatus.ACTIVE.id)
            val createdAt = parseInstant(obj.str("createdAt")) ?: Instant.now()
            val updatedAt = parseInstant(obj.str("updatedAt")) ?: createdAt
            val pinnedAt = if (pinned) parseInstant(obj.str("pinnedAt")) ?: updatedAt else null
            val trashedAt = if (status == NoteStatus.TRASHED) parseInstant(obj.str("trashedAt")) ?: updatedAt else null
            return Note(
                id = id,
                title = title,
                body = body,
                notebook = notebook,
                tags = tags,
                pinned = pinned,
                status = status,
                color = Colors.normalizeOrNull(obj.str("color")),
                createdAt = createdAt,
                updatedAt = updatedAt,
                pinnedAt = pinnedAt,
                trashedAt = trashedAt
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
    }
}
