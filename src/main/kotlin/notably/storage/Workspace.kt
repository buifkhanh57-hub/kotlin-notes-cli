package notably.storage

import java.io.File
import java.security.SecureRandom
import java.time.Instant
import notably.model.About
import notably.model.Note
import notably.model.Notebook
import notably.model.StorageException
import notably.model.WorkspaceAlreadyInitializedException
import notably.model.WorkspaceNotInitializedException
import notably.model.LockedException

/**
 * Owns the on-disk workspace layout and all load/save round-trips.
 *
 * Default layout under `~/.notably/` (override with `--workspace DIR` or the
 * `NOTABLY_HOME` environment variable):
 *
 * ```
 * ~/.notably/
 * ├── notes.json         the note index (array of note objects)
 * ├── notes.json.enc     encrypted note index (present while locked)
 * ├── notebooks.json     notebook registry
 * ├── meta.json          schema version, creation date, tag colors
 * ├── config.json        user preferences (default notebook, editor, ...)
 * ├── reminders.json     due-date reminders attached to notes
 * ├── backups/           rolling backups written by [AtomicFile]
 * └── exports/           default destination for `notably export`
 * ```
 *
 * All writes go through [AtomicFile] (temp + fsync + atomic rename + rolling
 * backups). All reads tolerate a missing file (fresh/empty) and recover from
 * backups when the primary file is unreadable.
 */
class Workspace(val root: File) {

    /** The plaintext note index. */
    val notesFile: File = File(root, "notes.json")

    /** The encrypted note index, present while the workspace is locked. */
    val encryptedNotesFile: File = File(root, "notes.json.enc")

    /** Notebook registry file. */
    val notebooksFile: File = File(root, "notebooks.json")

    /** Workspace metadata file. */
    val metaFile: File = File(root, "meta.json")

    /** User preferences file. */
    val configFile: File = File(root, "config.json")

    /** Rolling backups directory. */
    val backupsDir: File = File(root, "backups")

    /** Default export destination directory. */
    val exportsDir: File = File(root, "exports")

    /** Due-date reminder registry file. */
    val remindersFile: File = File(root, "reminders.json")

    /** [AtomicFile] wrapper for [notesFile] with [DEFAULT_BACKUPS] generations. */
    val notesStore: AtomicFile = AtomicFile(notesFile, backupsDir, DEFAULT_BACKUPS)

    /** [AtomicFile] wrapper for [notebooksFile]. */
    val notebooksStore: AtomicFile = AtomicFile(notebooksFile, backupsDir, DEFAULT_BACKUPS)

    /** [AtomicFile] wrapper for [remindersFile]. */
    val remindersStore: AtomicFile = AtomicFile(remindersFile, backupsDir, DEFAULT_BACKUPS)

    private val metaStore = AtomicFile(metaFile, backupsDir, 2)
    private val configStore = AtomicFile(configFile, backupsDir, 2)
    private val rng = SecureRandom()

    /** True when [metaFile] exists, i.e. `notably init` has been run here. */
    fun isInitialized(): Boolean = metaFile.isFile

    /** Throws [WorkspaceNotInitializedException] unless the workspace exists. */
    fun requireInitialized() {
        if (!isInitialized()) {
            throw WorkspaceNotInitializedException(
                "no notably workspace at ${root.absolutePath} — run 'notably init' first"
            )
        }
    }

    /**
     * Creates the directory structure and seeds empty registries.
     * [defaultNotebookName] is registered as the first notebook.
     *
     * @throws WorkspaceAlreadyInitializedException when already initialized
     */
    fun initWorkspace(defaultNotebookName: String) {
        if (isInitialized()) {
            throw WorkspaceAlreadyInitializedException(
                "a notably workspace already exists at ${root.absolutePath}"
            )
        }
        if (!root.exists() && !root.mkdirs() && !root.exists()) {
            throw StorageException("cannot create workspace directory ${root.absolutePath}")
        }
        if (!backupsDir.exists() && !backupsDir.mkdirs()) {
            throw StorageException("cannot create backups directory ${backupsDir.absolutePath}")
        }
        if (!exportsDir.exists() && !exportsDir.mkdirs()) {
            throw StorageException("cannot create exports directory ${exportsDir.absolutePath}")
        }
        val notebook = Notebook.create(defaultNotebookName, "Default notebook")
        notebooksStore.writeText(Json.write(jsonArray(listOf(notebook.toJson())), pretty = true))
        notesStore.writeText(Json.write(jsonArray(emptyList()), pretty = true))
        remindersStore.writeText(Json.write(jsonArray(emptyList()), pretty = true))
        saveMeta(WorkspaceMeta(schemaVersion = About.SCHEMA_VERSION, createdAt = Instant.now(), tagColors = emptyMap()))
        saveConfig(WorkspaceConfig(defaultNotebook = defaultNotebookName))
    }

    /**
     * True when the plaintext index is absent but an encrypted envelope is
     * present — i.e. the workspace has been locked with `notably lock`.
     */
    fun isLocked(): Boolean = !notesFile.isFile && encryptedNotesFile.isFile

    /**
     * Loads and parses the note index. Returns an empty list for a fresh
     * workspace. Throws [LockedException] while locked and [StorageException]
     * when the file is unreadable/unparseable (after backup recovery).
     */
    fun loadNotes(): List<Note> {
        if (isLocked()) {
            throw LockedException("notes are locked — run 'notably lock unlock' to decrypt them first")
        }
        val recovered = notesStore.readTextRecovering() ?: return emptyList()
        val rootValue = try {
            Json.parse(recovered.content)
        } catch (e: JsonParseException) {
            throw StorageException(
                "notes index is corrupted (${recovered.source}): ${e.message}", e
            )
        }
        val arr = rootValue.asArrayOrNull()
            ?: throw StorageException("notes index (${recovered.source}) must be a JSON array")
        val notes = mutableListOf<Note>()
        arr.items.forEachIndexed { index, item ->
            val obj = item.asObjectOrNull()
                ?: throw StorageException("notes index entry #$index is not an object")
            try {
                notes.add(Note.fromJson(obj))
            } catch (e: Exception) {
                throw StorageException("notes index entry #$index is invalid: ${e.message}", e)
            }
        }
        return notes
    }

    /** Persists the note index (pretty-printed according to [WorkspaceConfig.prettyJson]). */
    fun saveNotes(notes: Collection<Note>) {
        val arr = jsonArray(notes.map { it.toJson() })
        notesStore.writeText(Json.write(arr, pretty = loadConfig().prettyJson))
    }

    /** Loads the notebook registry; empty list when the file is absent. */
    fun loadNotebooks(): List<Notebook> {
        val recovered = notebooksStore.readTextRecovering() ?: return emptyList()
        val rootValue = try {
            Json.parse(recovered.content)
        } catch (e: JsonParseException) {
            throw StorageException(
                "notebook registry is corrupted (${recovered.source}): ${e.message}", e
            )
        }
        val arr = rootValue.asArrayOrNull()
            ?: throw StorageException("notebook registry (${recovered.source}) must be a JSON array")
        val result = mutableListOf<Notebook>()
        arr.items.forEachIndexed { index, item ->
            val obj = item.asObjectOrNull()
                ?: throw StorageException("notebook entry #$index is not an object")
            try {
                result.add(Notebook.fromJson(obj))
            } catch (e: Exception) {
                throw StorageException("notebook entry #$index is invalid: ${e.message}", e)
            }
        }
        return result
    }

    /** Persists the notebook registry. */
    fun saveNotebooks(notebooks: Collection<Notebook>) {
        val arr = jsonArray(notebooks.map { it.toJson() })
        notebooksStore.writeText(Json.write(arr, pretty = loadConfig().prettyJson))
    }

    /**
     * Loads the raw reminder registry; empty when the file is absent.
     * Domain-level validation belongs to the service layer, which maps the
     * JSON entries onto reminder objects — storage stays schema-agnostic.
     */
    fun loadReminderEntries(): List<JsonValue.JsonObject> {
        val recovered = remindersStore.readTextRecovering() ?: return emptyList()
        val rootValue = try {
            Json.parse(recovered.content)
        } catch (e: JsonParseException) {
            throw StorageException(
                "reminder registry is corrupted (${recovered.source}): ${e.message}", e
            )
        }
        val arr = rootValue.asArrayOrNull()
            ?: throw StorageException("reminder registry (${recovered.source}) must be a JSON array")
        val result = mutableListOf<JsonValue.JsonObject>()
        arr.items.forEachIndexed { index, item ->
            val obj = item.asObjectOrNull()
                ?: throw StorageException("reminder entry #$index is not an object")
            result.add(obj)
        }
        return result
    }

    /** Persists the reminder registry from service-layer JSON objects. */
    fun saveReminderEntries(entries: Collection<JsonValue.JsonObject>) {
        val arr = jsonArray(entries.map { it as JsonValue })
        remindersStore.writeText(Json.write(arr, pretty = loadConfig().prettyJson))
    }

    /** Loads user preferences, applying defaults for a missing file. */
    fun loadConfig(): WorkspaceConfig {
        val recovered = configStore.readTextRecovering() ?: return WorkspaceConfig()
        return try {
            val value = Json.parse(recovered.content)
            WorkspaceConfig.fromJson(value.asObjectOrNull() ?: return WorkspaceConfig())
        } catch (_: Exception) {
            WorkspaceConfig()
        }
    }

    /** Persists user preferences. */
    fun saveConfig(config: WorkspaceConfig) {
        configStore.writeText(Json.write(config.toJson(), pretty = true))
    }

    /** Loads workspace metadata (schema version, tag colors...). */
    fun loadMeta(): WorkspaceMeta {
        val recovered = metaStore.readTextRecovering() ?: return WorkspaceMeta(
            schemaVersion = About.SCHEMA_VERSION,
            createdAt = Instant.now(),
            tagColors = emptyMap()
        )
        return try {
            val value = Json.parse(recovered.content)
            WorkspaceMeta.fromJson(value.asObjectOrNull() ?: return WorkspaceMeta(About.SCHEMA_VERSION, Instant.now(), emptyMap()))
        } catch (_: Exception) {
            WorkspaceMeta(About.SCHEMA_VERSION, Instant.now(), emptyMap())
        }
    }

    /** Persists workspace metadata. */
    fun saveMeta(meta: WorkspaceMeta) {
        metaStore.writeText(Json.write(meta.toJson(), pretty = true))
    }

    /**
     * Assigns stable accent colors to previously unseen tags, using a fixed
     * 10-color palette. Returns the (possibly extended) full color map.
     */
    fun assignTagColors(tags: Collection<String>): Map<String, String> {
        val meta = loadMeta()
        val colors = LinkedHashMap(meta.tagColors)
        var changed = false
        for (tag in tags) {
            if (tag !in colors) {
                colors[tag] = TAG_PALETTE[colors.size % TAG_PALETTE.size]
                changed = true
            }
        }
        if (changed) saveMeta(meta.copy(tagColors = colors))
        return colors
    }

    /**
     * Generates a short unique id (12 lowercase base-36 chars) not present
     * in [taken]. Throws [StorageException] after repeated collisions (which
     * effectively never happens with 36^12 candidates).
     */
    fun newUniqueId(taken: Set<String>): String {
        repeat(64) {
            val sb = StringBuilder(ID_LENGTH)
            repeat(ID_LENGTH) { sb.append(ID_ALPHABET[rng.nextInt(ID_ALPHABET.length)]) }
            val candidate = sb.toString()
            if (candidate !in taken) return candidate
        }
        throw StorageException("unable to generate a unique note id")
    }

    /**
     * Installs an encrypted envelope as the note index and removes all
     * plaintext copies (the main file *and* its plaintext backups) — locking
     * is only meaningful when every plaintext copy is gone.
     */
    fun lockNotes(envelopeJson: String) {
        AtomicFile(encryptedNotesFile, backupsDir, 0).writeText(envelopeJson)
        if (notesFile.exists()) notesFile.delete()
        removePlaintextNoteBackups()
    }

    /** Restores the plaintext index from [plainJson] and removes the envelope. */
    fun unlockNotes(plainJson: String) {
        notesStore.writeText(plainJson)
        if (encryptedNotesFile.exists()) encryptedNotesFile.delete()
    }

    /** Deletes plaintext backups of the note index left over from earlier saves. */
    fun removePlaintextNoteBackups() {
        for (backup in notesStore.backups()) backup.delete()
    }

    companion object {
        const val DEFAULT_BACKUPS = 5
        const val ID_LENGTH = 12
        const val ENV_WORKSPACE = "NOTABLY_HOME"
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Palette used by [assignTagColors]; restrained terminal-friendly hues. */
        val TAG_PALETTE = listOf(
            "#4fc1ff", "#9ece6a", "#ff9e64", "#bb9af7", "#7dcfff",
            "#c0caf5", "#f7768e", "#e0af68", "#73daca", "#b4f9f8"
        )

        /**
         * Resolves the workspace directory: explicit CLI argument first
         * (with `~` expansion), then `NOTABLY_HOME`, then `~/.notably`.
         */
        fun resolve(explicit: String?): Workspace {
            val raw = when {
                !explicit.isNullOrBlank() -> explicit
                else -> System.getenv(ENV_WORKSPACE)?.takeIf { it.isNotBlank() }
            } ?: return Workspace(File(System.getProperty("user.home"), ".notably"))
            val expanded = if (raw == "~" || raw.startsWith("~/")) {
                File(System.getProperty("user.home"), raw.removePrefix("~/")).path
            } else {
                raw
            }
            return Workspace(File(expanded).absoluteFile)
        }
    }
}

/**
 * User preferences persisted in `config.json`. All fields degrade to defaults
 * when the file is missing or partially corrupted — configuration problems
 * must never make the CLI unusable.
 */
data class WorkspaceConfig(
    /** Notebook created/used when none is specified. */
    val defaultNotebook: String = Notebook.DEFAULT_NAME,

    /** Pretty-print workspace JSON (turn off for marginally faster saves). */
    val prettyJson: Boolean = true,

    /** Rolling backup generations kept per file. */
    val maxBackups: Int = Workspace.DEFAULT_BACKUPS,

    /** Editor command override; falls back to `$VISUAL`, then `$EDITOR`. */
    val editor: String? = null,

    /** `DateTimeFormatter` pattern for displaying timestamps. */
    val dateFormat: String = DEFAULT_DATE_FORMAT
) {
    /** Serializes preferences to JSON. */
    fun toJson(): JsonValue.JsonObject {
        val fields = mutableListOf(
            "defaultNotebook" to jsonString(defaultNotebook),
            "prettyJson" to jsonBoolean(prettyJson),
            "maxBackups" to jsonNumber(maxBackups.toLong()),
            "dateFormat" to jsonString(dateFormat)
        )
        editor?.let { fields.add("editor" to jsonString(it)) }
        return jsonObject(*fields.toTypedArray())
    }

    companion object {
        const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm"

        /** Lenient reader: any missing/invalid field falls back to its default. */
        fun fromJson(obj: JsonValue.JsonObject): WorkspaceConfig = WorkspaceConfig(
            defaultNotebook = obj.str("defaultNotebook")?.trim().takeUnless { it.isNullOrEmpty() }
                ?: Notebook.DEFAULT_NAME,
            prettyJson = obj.bool("prettyJson") ?: true,
            maxBackups = (obj.long("maxBackups") ?: Workspace.DEFAULT_BACKUPS.toLong()).toInt()
                .coerceIn(0, 50),
            editor = obj.str("editor")?.takeIf { it.isNotBlank() },
            dateFormat = obj.str("dateFormat")?.takeIf { it.isNotBlank() } ?: DEFAULT_DATE_FORMAT
        )
    }
}

/** Workspace metadata persisted in `meta.json`. */
data class WorkspaceMeta(
    /** Storage schema version, see [About.SCHEMA_VERSION]. */
    val schemaVersion: Int,

    /** When the workspace was initialized. */
    val createdAt: Instant,

    /** Stable tag → color assignments assigned on first use. */
    val tagColors: Map<String, String>
) {
    /** Serializes metadata to JSON. */
    fun toJson(): JsonValue.JsonObject {
        val colors = jsonObject(*tagColors.entries.sortedBy { it.key }
            .map { it.key to jsonString(it.value) }.toTypedArray())
        return jsonObject(
            "schemaVersion" to jsonNumber(schemaVersion.toLong()),
            "createdAt" to jsonString(createdAt.toString()),
            "tagColors" to colors
        )
    }

    companion object {
        /** Lenient reader used by [Workspace.loadMeta]. */
        fun fromJson(obj: JsonValue.JsonObject): WorkspaceMeta = WorkspaceMeta(
            schemaVersion = (obj.long("schemaVersion") ?: About.SCHEMA_VERSION.toLong()).toInt(),
            createdAt = try {
                Instant.parse(obj.str("createdAt") ?: "") 
            } catch (_: Exception) {
                Instant.now()
            },
            tagColors = obj.obj("tagColors")?.fields
                ?.mapNotNull { (k, v) -> (v as? JsonValue.JsonString)?.value?.let { k to it } }
                ?.toMap() ?: emptyMap()
        )
    }
}
