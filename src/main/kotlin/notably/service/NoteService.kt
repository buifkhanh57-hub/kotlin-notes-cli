package notably.service

import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import notably.model.ListingSort
import notably.model.Note
import notably.model.NoteSort
import notably.model.NoteStatus
import notably.model.Notebook
import notably.model.NotFoundException
import notably.model.StorageException
import notably.model.SearchFilters
import notably.model.Tag
import notably.model.ValidationException
import notably.storage.Workspace

/**
 * All note CRUD plus the lifecycle operations (pin, archive, trash, restore,
 * purge). The service keeps the note index in memory, mutates it through
 * validated `copy(...)` transitions and persists through [Workspace] after
 * every mutating call — the CLI is single-user, so write-through is the
 * simplest correct durability story.
 *
 * Notebooks referenced by notes are *not* auto-created here: the command
 * layer decides (via [NotebookService.ensureExists]) whether typing a new
 * notebook name implicitly registers it.
 */
class NoteService(private val workspace: Workspace) {

    private val notes: MutableList<Note>
    private val ids: MutableSet<String>

    init {
        val loaded = workspace.loadNotes()
        notes = loaded.toMutableList()
        ids = loaded.map { it.id }.toMutableSet()
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Immutable snapshot of every note in every state. */
    fun all(): List<Note> = notes.toList()

    /** All notes currently in [status]. */
    fun byStatus(status: NoteStatus): List<Note> = notes.filter { it.status == status }

    /** Every note matching the declarative [filters]. */
    fun filter(filters: SearchFilters): List<Note> = notes.filter { filters.matches(it) }

    /** Same as [filter] but additionally sorted (pinned first by default). */
    fun filterSorted(filters: SearchFilters, sort: ListingSort = ListingSort.UPDATED, pinnedFirst: Boolean = true): List<Note> =
        filter(filters).sortedWith(NoteSort.comparator(sort, pinnedFirst)).take(filters.limit)

    /** Number of notes grouped by normalized notebook key. */
    fun countByNotebook(): Map<String, Int> = notes.groupBy { it.notebookKey }.mapValues { it.value.size }

    /** Tag usage counts across notes in any state. */
    fun tagsInUse(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (note in notes) {
            for (tag in note.tags) {
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        return counts
    }

    /** Notes created per month over the last [months] months (system zone). */
    fun activityByMonth(months: Int): List<Pair<YearMonth, Int>> {
        val zone = ZoneId.systemDefault()
        val counts = HashMap<YearMonth, Int>()
        for (note in notes) {
            val month = YearMonth.from(note.createdAt.atZone(zone))
            counts[month] = (counts[month] ?: 0) + 1
        }
        val current = YearMonth.now(zone)
        val result = mutableListOf<Pair<YearMonth, Int>>()
        for (offset in months - 1 downTo 0) {
            val month = current.minusMonths(offset.toLong())
            result.add(month to (counts[month] ?: 0))
        }
        return result
    }

    /** Workspace-wide aggregate numbers for `notably stats`. */
    fun totals(): Totals {
        val active = notes.count { it.status == NoteStatus.ACTIVE }
        val archived = notes.count { it.status == NoteStatus.ARCHIVED }
        val trashed = notes.count { it.status == NoteStatus.TRASHED }
        val pinned = notes.count { it.pinned && it.status != NoteStatus.TRASHED }
        val words = notes.filter { it.status != NoteStatus.TRASHED }.sumOf { countWords(it.body) }
        return Totals(total = notes.size, active = active, archived = archived, trashed = trashed, pinned = pinned, words = words)
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Resolves a user-supplied reference (full id, unique id prefix, or a
     * unique title substring) to a note. Prefixes shorter than
     * [MIN_REF_LENGTH] are rejected to avoid surprising full-table scans.
     *
     * @throws NotFoundException with actionable suggestions when ambiguous
     */
    fun resolve(ref: String): Note {
        val cleaned = ref.trim()
        if (cleaned.isEmpty()) throw NotFoundException("empty note reference")
        notes.firstOrNull { it.id == cleaned }?.let { return it }
        if (cleaned.length >= MIN_REF_LENGTH) {
            val prefixMatches = notes.filter { it.id.startsWith(cleaned) }
            when {
                prefixMatches.size == 1 -> return prefixMatches[0]
                prefixMatches.size > 1 -> throw NotFoundException(
                    "ambiguous note reference '$cleaned' matches ${prefixMatches.size} notes: " +
                        prefixMatches.take(5).joinToString(", ") { it.id }
                )
            }
        }
        val lowered = cleaned.lowercase()
        val titleMatches = notes.filter { it.title.lowercase().contains(lowered) }
        if (titleMatches.size == 1) return titleMatches[0]
        if (titleMatches.size > 1) {
            throw NotFoundException(
                "ambiguous note reference '$cleaned' matches ${titleMatches.size} titles; use an id instead"
            )
        }
        val suggestions = suggest(cleaned)
        val hint = if (suggestions.isEmpty()) "" else " Did you mean: ${suggestions.joinToString(", ") { "'${it.title}'" }}?"
        throw NotFoundException("no note matches '$cleaned'.$hint")
    }

    /** Up to [max] closest notes by title similarity (cheap heuristic). */
    fun suggest(ref: String, max: Int = 3): List<Note> {
        val lowered = ref.lowercase()
        return notes.sortedBy { it.title.lowercase() }
            .sortedWith(
                compareBy<Note> { note -> levenshtein(lowered, note.title.lowercase().take(lowered.length + 4)) }
            )
            .filter { levenshtein(lowered, it.title.lowercase()) <= 8 || it.title.lowercase().startsWith(lowered.take(3)) }
            .take(max)
    }

    // ------------------------------------------------------------------
    // Mutations (each persists through the workspace)
    // ------------------------------------------------------------------

    /**
     * Creates and stores a new ACTIVE note. Validates content, assigns a
     * fresh id and registers tag colors in workspace metadata.
     */
    fun create(
        title: String,
        body: String,
        notebookName: String,
        tags: Collection<String>,
        pinned: Boolean,
        color: String? = null
    ): Note {
        val id = workspace.newUniqueId(ids)
        val note = Note.create(
            title = title,
            body = body,
            notebook = notebookName,
            tags = tags,
            pinned = pinned,
            color = color,
            id = id
        )
        notes.add(note)
        ids.add(id)
        registerTags(note.tags)
        save()
        return note
    }

    /**
     * Updates title and/or body. Only supplied (and actually changed) fields
     * bump [Note.updatedAt].
     */
    fun updateContent(note: Note, title: String? = null, body: String? = null): Note {
        var updated = note
        if (title != null && title.trim() != note.title) {
            updated = updated.copy(title = title.trim())
        }
        if (body != null && body != note.body) {
            updated = updated.copy(body = body)
        }
        if (updated !== note) {
            updated = updated.copy(updatedAt = Instant.now())
            replace(updated)
            save()
        }
        return updated
    }

    /** Moves a note to [notebookName] (the notebook must already exist). */
    fun changeNotebook(note: Note, notebookName: String): Note {
        val clean = notebookName.trim()
        if (clean.isEmpty()) throw ValidationException("notebook must not be blank")
        if (Notebook.normalizeKey(clean) == note.notebookKey) return note
        val updated = note.copy(notebook = clean, updatedAt = Instant.now())
        replace(updated)
        save()
        return updated
    }

    /** Bulk notebook rename used by [NotebookService.rename]. Returns count. */
    fun renameNotebookReferences(oldKey: String, newDisplayName: String): Int {
        var changed = 0
        val now = Instant.now()
        for (index in notes.indices) {
            val note = notes[index]
            if (note.notebookKey == oldKey) {
                notes[index] = note.copy(notebook = newDisplayName, updatedAt = now)
                changed++
            }
        }
        if (changed > 0) save()
        return changed
    }

    /** Adds tags to a note (normalized, de-duplicated, capped by [Tag.MAX_PER_NOTE]). */
    fun addTags(note: Note, tags: Collection<String>): Note {
        if (tags.isEmpty()) return note
        val merged = LinkedHashSet(note.tags)
        for (raw in tags) merged.add(Tag.normalize(raw))
        if (merged.size > Tag.MAX_PER_NOTE) {
            throw ValidationException("a note may carry at most ${Tag.MAX_PER_NOTE} tags")
        }
        if (merged == note.tags) return note
        val updated = note.copy(tags = merged, updatedAt = Instant.now())
        replace(updated)
        registerTags(merged - note.tags)
        save()
        return updated
    }

    /** Removes tags from a note; unknown tags are ignored silently. */
    fun removeTags(note: Note, tags: Collection<String>): Note {
        if (tags.isEmpty()) return note
        val removed = tags.map { Tag.normalize(it) }.toSet()
        val remaining = note.tags - removed
        if (remaining == note.tags) return note
        val updated = note.copy(tags = remaining, updatedAt = Instant.now())
        replace(updated)
        save()
        return updated
    }

    /** Pins or unpins a note. Pinned notes sort first and are never purged. */
    fun setPinned(note: Note, pinned: Boolean): Note {
        if (note.status == NoteStatus.TRASHED && pinned) {
            throw ValidationException("cannot pin a trashed note — restore it first")
        }
        if (note.pinned == pinned) return note
        val updated = note.copy(
            pinned = pinned,
            pinnedAt = if (pinned) Instant.now() else null,
            updatedAt = Instant.now()
        )
        replace(updated)
        save()
        return updated
    }

    /**
     * Transitions a note to [status], maintaining the invariants checked by
     * [Note.validate] (pinnedAt cleared in trash, trashedAt stamped/removed).
     */
    fun setStatus(note: Note, status: NoteStatus): Note {
        if (note.status == status) return note
        val now = Instant.now()
        var updated = note.copy(status = status, updatedAt = now)
        updated = when (status) {
            NoteStatus.TRASHED -> updated.copy(trashedAt = now, pinned = false, pinnedAt = null)
            NoteStatus.ACTIVE -> updated.copy(trashedAt = null)
            NoteStatus.ARCHIVED -> updated.copy(trashedAt = null, pinned = false, pinnedAt = null)
        }
        replace(updated)
        save()
        return updated
    }

    /** Hard-deletes a note (usually called on trashed notes). Returns true when removed. */
    fun deleteForever(note: Note): Boolean {
        val removed = notes.removeIf { it.id == note.id }
        if (removed) {
            ids.remove(note.id)
            save()
        }
        return removed
    }

    /**
     * Purges trashed notes older than [olderThanDays] (0 = all trashed).
     * Pinned notes are never purged. Returns the number of removed notes.
     */
    fun purgeTrash(olderThanDays: Int): Int {
        if (notes.none { it.status == NoteStatus.TRASHED && !it.pinned }) return 0
        val cutoff = Instant.now().minus(Duration.ofDays(olderThanDays.toLong().coerceAtLeast(0)))
        val doomed = notes.filter {
            it.status == NoteStatus.TRASHED && !it.pinned && (it.trashedAt ?: it.updatedAt).isBefore(cutoff)
        }
        if (doomed.isEmpty()) return 0
        val doomedIds = doomed.map { it.id }.toSet()
        notes.removeIf { it.id in doomedIds }
        ids.removeAll(doomedIds)
        save()
        return doomed.size
    }

    /** Flushes the in-memory index to disk (used after batch operations). */
    fun save() {
        workspace.saveNotes(notes)
    }

    /** Snapshot of ids currently allocated — used by workspace id generation. */
    fun allocatedIds(): Set<String> = ids.toSet()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun replace(updated: Note) {
        val index = notes.indexOfFirst { it.id == updated.id }
        if (index < 0) throw StorageException("internal error: note ${updated.id} vanished from the index")
        notes[index] = updated
    }

    private fun registerTags(tags: Collection<String>) {
        if (tags.isNotEmpty()) workspace.assignTagColors(tags)
    }

    /** Classic dynamic-programming edit distance (bounded, for suggestions). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        return previous[b.length]
    }

    /** Word count helper shared with stats; treats non-letters as separators. */
    private fun countWords(text: String): Int =
        text.split(NON_WORD).count { it.isNotEmpty() }

    companion object {
        /** Shortest id prefix accepted by [resolve]. */
        const val MIN_REF_LENGTH = 2

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}

/** Aggregate counters for [NoteService.totals]. */
data class Totals(
    val total: Int,
    val active: Int,
    val archived: Int,
    val trashed: Int,
    val pinned: Int,
    val words: Int
)

/**
 * Registry for notebooks: validated creation, listing with usage counts,
 * rename (cascading to notes via [NoteService.renameNotebookReferences]) and
 * safe removal of empty notebooks only.
 */
class NotebookService(
    private val workspace: Workspace,
    private val noteService: NoteService
) {

    private val notebooks: MutableList<Notebook> = workspace.loadNotebooks().toMutableList()

    /** All notebooks sorted by normalized name. */
    fun all(): List<Notebook> = notebooks.sortedBy { it.key }

    /** True when a notebook with this key exists. */
    fun exists(name: String): Boolean = notebooks.any { it.key == Notebook.normalizeKey(name) }

    /** Finds a notebook by name/key, or `null`. */
    fun find(name: String): Notebook? = notebooks.firstOrNull { it.key == Notebook.normalizeKey(name) }

    /**
     * Returns the notebook with this name, creating (and persisting) it on
     * first use — the implicit "mentioning a notebook creates it" behavior.
     */
    fun ensureExists(name: String): Notebook {
        val key = Notebook.normalizeKey(name)
        notebooks.firstOrNull { it.key == key }?.let { return it }
        val created = Notebook.create(name)
        notebooks.add(created)
        workspace.saveNotebooks(notebooks)
        return created
    }

    /** Explicit creation; rejects duplicates instead of returning the existing one. */
    fun create(name: String, description: String = "", color: String? = null): Notebook {
        val notebook = Notebook.create(name, description, color)
        if (exists(notebook.name)) {
            throw ValidationException("notebook '${notebook.name}' already exists")
        }
        notebooks.add(notebook)
        workspace.saveNotebooks(notebooks)
        return notebook
    }

    /** Live usage counts keyed by normalized notebook name. */
    fun usageCounts(): Map<String, Int> = noteService.countByNotebook()

    /**
     * Renames a notebook and cascades the change to every referencing note.
     *
     * @throws NotFoundException when [oldName] does not exist
     * @throws ValidationException when the new name is already taken
     */
    fun rename(oldName: String, newName: String): Notebook {
        val target = find(oldName)
            ?: throw NotFoundException("no notebook named '$oldName' (have: ${all().joinToString(", ") { it.name }})")
        val newKey = Notebook.normalizeKey(newName)
        if (newKey != target.key && exists(newName)) {
            throw ValidationException("notebook '${newName}' already exists")
        }
        val renamed = target.copy(name = newName.trim())
        notebooks.removeIf { it.key == target.key }
        notebooks.add(renamed)
        workspace.saveNotebooks(notebooks)
        if (newKey != target.key) {
            noteService.renameNotebookReferences(target.key, renamed.name)
        }
        return renamed
    }

    /** Updates the description of an existing notebook. */
    fun describe(name: String, description: String): Notebook {
        val target = find(name) ?: throw NotFoundException("no notebook named '$name'")
        val updated = target.copy(description = description.trim())
        notebooks.removeIf { it.key == target.key }
        notebooks.add(updated)
        workspace.saveNotebooks(notebooks)
        return updated
    }

    /**
     * Removes an empty notebook from the registry. Notes themselves are never
     * touched — the caller must move them elsewhere first.
     *
     * @throws ValidationException when the notebook still contains notes
     */
    fun remove(name: String): Notebook {
        val target = find(name) ?: throw NotFoundException("no notebook named '$name'")
        val usage = noteService.countByNotebook()[target.key] ?: 0
        if (usage > 0) {
            throw ValidationException("notebook '${target.name}' still contains $usage note(s) — move them first")
        }
        if (notebooks.size == 1) {
            throw ValidationException("cannot remove the last remaining notebook")
        }
        notebooks.removeIf { it.key == target.key }
        workspace.saveNotebooks(notebooks)
        return target
    }

    /** Persists the current registry (rarely needed — mutations auto-save). */
    fun save() {
        workspace.saveNotebooks(notebooks)
    }

    /** Ensures the default notebook required by schema v[About.SCHEMA_VERSION] exists. */
    fun ensureDefault(): Notebook = ensureExists(Notebook.DEFAULT_NAME)
}
