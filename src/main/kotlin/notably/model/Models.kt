package notably.model

/**
 * Global, immutable metadata about the application. Kept in one place so the
 * CLI banner, `--version` output and export headers always agree.
 */
object About {
    const val NAME = "notably"
    const val VERSION = "1.0.0"
    const val AUTHOR = "Bui Bao Khanh"
    const val DESCRIPTION = "A professional note-taking workspace for the terminal."
    const val SCHEMA_VERSION = 1
}

/**
 * Process exit codes returned by the CLI. They are stable API surface:
 * shell scripts may rely on them to detect *why* a command failed.
 */
object ExitCodes {
    /** Command completed successfully. */
    const val OK = 0

    /** Generic, unexpected failure. */
    const val ERROR = 1

    /** Bad command line usage (unknown command, missing/invalid flags). */
    const val USAGE = 2

    /** A referenced entity (note, notebook, tag) does not exist. */
    const val NOT_FOUND = 3

    /** Input failed validation (blank title, bad color, too many tags...). */
    const val VALIDATION = 4

    /** The notes store is locked; unlock before reading notes. */
    const val LOCKED = 5

    /** Filesystem / JSON / export I/O failure. */
    const val IO = 6

    /** Passphrase wrong, envelope corrupted, or crypto subsystem failure. */
    const val CRYPTO = 7
}

/**
 * Base type for every expected failure inside notably. Carries the process
 * exit code so [notably.Main] can translate exceptions into shell-friendly
 * status codes without `instanceof` chains.
 */
open class NotablyException(
    message: String,
    val exitCode: Int = ExitCodes.ERROR,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/** Command line was used incorrectly. */
class UsageException(message: String) : NotablyException(message, ExitCodes.USAGE)

/** A value supplied by the user (or a stored file) failed validation. */
class ValidationException(message: String) : NotablyException(message, ExitCodes.VALIDATION)

/** A requested note / notebook / tag could not be resolved. */
class NotFoundException(message: String) : NotablyException(message, ExitCodes.NOT_FOUND)

/** The notes store is encrypted; reading notes is refused until unlock. */
class LockedException(message: String) : NotablyException(message, ExitCodes.LOCKED)

/** Low-level storage failure (read/write/parse of workspace files). */
class StorageException(message: String, cause: Throwable? = null) : NotablyException(message, ExitCodes.IO, cause)

/** The workspace directory is missing or malformed. */
open class WorkspaceException(message: String) : NotablyException(message, ExitCodes.IO)

/** `notably init` was run on a directory that is already a workspace. */
class WorkspaceAlreadyInitializedException(message: String) : WorkspaceException(message)

/** A workspace is required but was never initialized. */
class WorkspaceNotInitializedException(message: String) : WorkspaceException(message)

/** Export (markdown bundle / CSV / JSON) failed. */
class ExportException(message: String) : NotablyException(message, ExitCodes.IO)

/** Encryption / decryption failure, including wrong passphrase. */
class CryptoException(message: String, cause: Throwable? = null) : NotablyException(message, ExitCodes.CRYPTO, cause)

/**
 * Lifecycle state of a note. The state machine is intentionally simple:
 *
 * ```
 *   ACTIVE --> ARCHIVED --> ACTIVE
 *      |  \                 ^
 *      v   +---- restore ---+
 *   TRASHED (purge = permanent deletion)
 * ```
 *
 * Notes may move ACTIVE -> TRASHED and ARCHIVED -> TRASHED; restoring always
 * returns the note to ACTIVE. Trashed notes can be purged, which removes them
 * from disk forever.
 */
enum class NoteStatus(val id: String, val display: String) {
    ACTIVE("active", "active"),
    ARCHIVED("archived", "archived"),
    TRASHED("trashed", "trashed");

    /** True when the note is a delete candidate (trash). */
    val isTrashed: Boolean get() = this == TRASHED

    companion object {
        /**
         * Parses a status id, throwing [ValidationException] for unknown
         * values so that corrupted storage never silently changes state.
         */
        fun fromId(raw: String?): NoteStatus {
            val cleaned = raw?.trim()?.lowercase()
            return values().firstOrNull { it.id == cleaned }
                ?: throw ValidationException(
                    "unknown note status '$raw' (expected one of: ${values().joinToString(", ") { it.id }})"
                )
        }
    }
}

/** Helpers for validating optional `#RRGGBB` color annotations. */
object Colors {
    private val HEX = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    /** True when [raw] is a valid 3- or 6-digit hex color. */
    fun isValid(raw: String): Boolean = HEX.matches(raw)

    /**
     * Normalizes a color to lowercase or returns `null` for blank input;
     * throws [ValidationException] for malformed non-blank input.
     */
    fun normalizeOrNull(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        if (!isValid(trimmed)) throw ValidationException("invalid color '$raw' (use #RGB or #RRGGBB hex)")
        return trimmed.lowercase()
    }
}

/**
 * A tag attached to one or more notes. Tags are stored normalized
 * (trimmed, lower-cased, whitespace collapsed) so that `Work` and `work`
 * are the same tag.
 */
data class Tag(val name: String, val color: String? = null) {

    /** Normalized lookup key of this tag. */
    val key: String get() = normalize(name)

    companion object {
        const val MAX_NAME_LENGTH = 32

        /** Hard cap on how many distinct tags a single note may carry. */
        const val MAX_PER_NOTE = 32

        /**
         * Normalizes [raw] into canonical tag form. Blank input, names that
         * are too long, or names containing control characters are rejected.
         */
        fun normalize(raw: String): String {
            val collapsed = raw.trim().lowercase().replace(WHITESPACE, " ")
            if (collapsed.isEmpty()) throw ValidationException("tag must not be blank")
            if (collapsed.length > MAX_NAME_LENGTH) {
                throw ValidationException("tag '${collapsed.take(MAX_NAME_LENGTH)}…' exceeds $MAX_NAME_LENGTH characters")
            }
            if (collapsed.any { it.code < 0x20 }) throw ValidationException("tag must not contain control characters")
            return collapsed
        }

        /**
         * Normalizes a whole collection, de-duplicating while preserving the
         * original order, and enforcing [MAX_PER_NOTE].
         */
        fun normalizeAll(raw: Collection<String>): LinkedHashSet<String> {
            val result = LinkedHashSet<String>()
            for (item in raw) {
                val normalized = normalize(item)
                if (result.add(normalized) && result.size > MAX_PER_NOTE) {
                    throw ValidationException("a note may carry at most $MAX_PER_NOTE tags")
                }
            }
            return result
        }

        private val WHITESPACE = Regex("\\s+")
    }
}

/** One row of a tag-frequency report (used by `notably tag list` / stats). */
data class TagUsage(val tag: String, val count: Int)

/**
 * Sort keys accepted by `notably list --sort`. [SCORE] is only meaningful
 * for ranked output (search); for plain listings it degrades to [UPDATED].
 */
enum class ListingSort(val id: String, val display: String) {
    UPDATED("updated", "last updated"),
    CREATED("created", "date created"),
    TITLE("title", "title A→Z"),
    NOTEBOOK("notebook", "notebook name"),
    SCORE("score", "relevance score");

    companion object {
        /** Parses a sort id, falling back to [fallback] for `null` input and
         *  throwing [UsageException] for unknown non-null input. */
        fun fromId(raw: String?, fallback: ListingSort = UPDATED): ListingSort {
            val cleaned = raw?.trim()?.lowercase() ?: return fallback
            return values().firstOrNull { it.id == cleaned }
                ?: throw UsageException(
                    "unknown sort '${raw}' (expected one of: ${values().joinToString(", ") { it.id }})"
                )
        }
    }
}

/**
 * Declarative filter set shared by `list`, `export` and `search`. A note is
 * included only when it matches *every* active constraint.
 */
data class SearchFilters(
    /** Restrict to one notebook (matched on its normalized key). */
    val notebook: String? = null,

    /** Note must carry *all* of these tags. */
    val tags: Set<String> = emptySet(),

    /** Only pinned notes. */
    val pinnedOnly: Boolean = false,

    /** Include archived notes *in addition to* active ones. */
    val includeArchived: Boolean = false,

    /** Show archived notes instead of active ones. */
    val archivedOnly: Boolean = false,

    /** Show trashed notes instead of active ones. */
    val trashedOnly: Boolean = false,

    /** Show everything, including trashed notes. */
    val allStatuses: Boolean = false,

    /** Maximum number of results to return. */
    val limit: Int = DEFAULT_LIMIT
) {
    /** The set of [NoteStatus] values this filter selects. */
    fun targetStatuses(): Set<NoteStatus> = when {
        allStatuses -> NoteStatus.values().toSet()
        trashedOnly -> setOf(NoteStatus.TRASHED)
        archivedOnly -> setOf(NoteStatus.ARCHIVED)
        includeArchived -> setOf(NoteStatus.ACTIVE, NoteStatus.ARCHIVED)
        else -> setOf(NoteStatus.ACTIVE)
    }

    /** Returns true when [note] satisfies every constraint of this filter. */
    fun matches(note: Note): Boolean {
        if (note.status !in targetStatuses()) return false
        if (pinnedOnly && !note.pinned) return false
        notebook?.let { wanted ->
            if (Notebook.normalizeKey(wanted) != note.notebookKey) return false
        }
        for (wanted in tags) {
            if (wanted !in note.tags) return false
        }
        return true
    }

    /** Human-readable summary used in list headers, e.g. `in Work, tagged kotlin`. */
    fun describe(): String {
        val parts = mutableListOf<String>()
        notebook?.let { parts.add("in '$it'") }
        if (tags.isNotEmpty()) parts.add("tagged ${tags.joinToString("+")}")
        if (pinnedOnly) parts.add("pinned")
        if (archivedOnly) parts.add("archived")
        if (trashedOnly) parts.add("trashed")
        if (allStatuses) parts.add("all statuses")
        return if (parts.isEmpty()) "" else " ${parts.joinToString(", ")}"
    }

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}

/** Sorting helpers shared by listings and exports. */
object NoteSort {

    /**
     * Builds a comparator for [sort]. When [pinnedFirst] is true, pinned
     * notes are hoisted above everything else within the same sort order.
     */
    fun comparator(sort: ListingSort, pinnedFirst: Boolean): Comparator<Note> {
        val base = when (sort) {
            ListingSort.UPDATED -> compareByDescending<Note> { it.updatedAt.toEpochMilli() }
            ListingSort.CREATED -> compareByDescending<Note> { it.createdAt.toEpochMilli() }
            ListingSort.TITLE -> compareBy<Note> { it.title.lowercase() }.thenBy { it.title }
            ListingSort.NOTEBOOK -> compareBy<Note> { it.notebookKey }.thenByDescending { it.updatedAt.toEpochMilli() }
            ListingSort.SCORE -> compareByDescending<Note> { it.updatedAt.toEpochMilli() }
        }
        return if (pinnedFirst) {
            compareByDescending<Note> { it.pinned }.then(base)
        } else {
            base
        }
    }
}

/** A single month bucket used by the activity chart in `notably stats`. */
data class MonthActivity(val month: java.time.YearMonth, val created: Int, val updated: Int)
