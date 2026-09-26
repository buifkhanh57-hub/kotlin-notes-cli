package notably.service

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import notably.model.ExportException
import notably.model.Note
import notably.storage.Json
import notably.storage.jsonArray
import notably.storage.jsonBoolean
import notably.storage.jsonString

/**
 * Turns notes into portable artifacts. Three families are supported:
 *
 *  * **markdown bundle** — one `NNNN-slug.md` file per note with YAML-ish
 *    front matter, plus an `index.md` table of contents grouped by notebook;
 *  * **single markdown** — one document with every note inline;
 *  * **CSV** — one row per note, RFC-4180 quoting (commas, quotes and
 *    newlines inside fields are handled correctly);
 *  * **JSON** — the raw note array, pretty-printed with the same writer the
 *    workspace uses, so an export can be re-imported by diffing tools.
 *
 * Exports never touch the workspace itself. Existing targets are only
 * overwritten when the caller explicitly passes `force`.
 */
class ExportService(private val workspace: notably.storage.Workspace) {

    /** Result descriptor printed by the CLI after an export. */
    data class ExportResult(
        val format: String,
        val target: File,
        val count: Int,
        val bytes: Long,
        val bundled: Boolean
    )

    /**
     * Exports [notes] as a markdown bundle into directory [target]
     * (created on demand). Returns the result with `bundled = true`.
     */
    fun exportMarkdownBundle(notes: List<Note>, target: File, force: Boolean): ExportResult {
        if (target.exists() && !target.isDirectory) {
            throw ExportException("export target exists and is not a directory: ${target.path}")
        }
        if (target.isDirectory && target.list { _, name -> name.endsWith(".md") }?.isNotEmpty() == true && !force) {
            throw ExportException("directory ${target.path} already contains markdown files — pass --force to overwrite")
        }
        if (!target.exists() && !target.mkdirs() && !target.isDirectory) {
            throw ExportException("cannot create export directory ${target.path}")
        }
        val sorted = notes.sortedWith(compareBy({ it.notebookKey }, { it.title.lowercase() }))
        val index = StringBuilder()
        index.append("# Notably export\n\n")
        index.append("_Generated ").append(LocalDateTime.now().format(TIMESTAMP_DISPLAY)).append(" — ")
        index.append(sorted.size).append(" note(s)_\n\n")
        var written = 0
        var currentNotebook: String? = null
        sorted.forEachIndexed { position, note ->
            val fileName = fileBaseName(note, position)
            val file = File(target, fileName)
            val content = frontMatter(note) + "\n" + note.body.trimEnd() + "\n"
            file.writeText(content, Charsets.UTF_8)
            written++
            if (note.notebook != currentNotebook) {
                currentNotebook = note.notebook
                index.append("## ").append(note.notebook).append("\n\n")
            }
            index.append("- [").append(escapeMarkdownLinkText(note.title)).append("](")
            index.append(fileName).append(") — _").append(note.status.id).append("_, updated ")
            index.append(note.updatedAt.toString()).append("\n")
        }
        index.append("\n---\n\n_Notably — a professional note-taking CLI. Generated markdown bundle._\n")
        File(target, "index.md").writeText(index.toString(), Charsets.UTF_8)
        return ExportResult(
            format = FORMAT_BUNDLE,
            target = target,
            count = written,
            bytes = directorySize(target),
            bundled = true
        )
    }

    /** Exports [notes] as one combined markdown document. */
    fun exportSingleMarkdown(notes: List<Note>, target: File, force: Boolean): ExportResult {
        ensureWritableFile(target, force)
        val out = StringBuilder()
        out.append("# Notably export\n\n")
        out.append("_").append(notes.size).append(" note(s) — generated ")
        out.append(LocalDateTime.now().format(TIMESTAMP_DISPLAY)).append("_\n\n")
        notes.sortedWith(compareBy({ it.notebookKey }, { it.title.lowercase() })).forEach { note ->
            out.append("## ").append(note.title).append("\n\n")
            out.append("> id `").append(note.id).append("` · ").append(note.notebook)
            if (note.tags.isNotEmpty()) out.append(" · ").append(note.tags.joinToString(", "))
            out.append(" · ").append(note.status.id)
            out.append(" · created ").append(note.createdAt.toString()).append("\n\n")
            if (note.body.isBlank()) {
                out.append("_(empty body)_\n\n")
            } else {
                out.append(note.body.trimEnd()).append("\n\n")
            }
            out.append("---\n\n")
        }
        target.writeText(out.toString(), Charsets.UTF_8)
        return ExportResult(FORMAT_MD, target, notes.size, target.length(), bundled = false)
    }

    /** Exports [notes] as RFC-4180 CSV. */
    fun exportCsv(notes: List<Note>, target: File, force: Boolean): ExportResult {
        ensureWritableFile(target, force)
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\r\n")
        for (note in notes) {
            val row = listOf(
                note.id,
                note.title,
                note.notebook,
                note.tags.sorted().joinToString("|"),
                note.status.id,
                note.pinned.toString(),
                note.createdAt.toString(),
                note.updatedAt.toString(),
                note.body
            )
            sb.append(row.joinToString(",") { csvField(it) }).append("\r\n")
        }
        target.writeText(sb.toString(), Charsets.UTF_8)
        return ExportResult(FORMAT_CSV, target, notes.size, target.length(), bundled = false)
    }

    /** Exports [notes] as a pretty-printed JSON array (workspace schema). */
    fun exportJson(notes: List<Note>, target: File, force: Boolean): ExportResult {
        ensureWritableFile(target, force)
        val arr = jsonArray(notes.map { it.toJson() })
        target.writeText(Json.write(arr, pretty = true), Charsets.UTF_8)
        return ExportResult(FORMAT_JSON, target, notes.size, target.length(), bundled = false)
    }

    /** Exports [notes] as a pretty-printed JSON array (workspace schema). */
    fun exportJsonWithStatus(notes: List<Note>, target: File, force: Boolean, includeMetadata: Boolean): ExportResult {
        if (!includeMetadata) return exportJson(notes, target, force)
        ensureWritableFile(target, force)
        val payload = jsonArray(notes.map { note ->
            val obj = note.toJson()
            obj.put("statusLabel" to jsonString(note.status.display))
            obj.put("pinnedShown" to jsonBoolean(note.pinned))
            obj
        })
        target.writeText(Json.write(payload, pretty = true), Charsets.UTF_8)
        return ExportResult(FORMAT_JSON, target, notes.size, target.length(), bundled = false)
    }

    /**
     * Computes the default export target when the user did not pass `--out`:
     * `<workspace>/exports/<kind>-<timestamp>.<ext>` (directories for bundles).
     */
    fun defaultTarget(format: String, explicit: String?): File {
        if (!explicit.isNullOrBlank()) return File(expanded(explicit))
        val stamp = LocalDateTime.now().format(TIMESTAMP_FILE)
        val name = when (format) {
            FORMAT_JSON -> "notes-$stamp.json"
            FORMAT_CSV -> "notes-$stamp.csv"
            FORMAT_MD -> "notes-$stamp.md"
            FORMAT_BUNDLE -> "notes-bundle-$stamp"
            else -> throw ExportException("unknown export format '$format'")
        }
        return File(workspace.exportsDir, name)
    }

    /** Validates and normalizes a `--format` value. */
    fun normalizeFormat(raw: String?): String {
        val cleaned = raw?.trim()?.lowercase() ?: FORMAT_BUNDLE
        return when (cleaned) {
            FORMAT_BUNDLE, FORMAT_MD, FORMAT_CSV, FORMAT_JSON -> cleaned
            "markdown" -> FORMAT_MD
            else -> throw ExportException("unknown export format '$raw' (use: $FORMAT_BUNDLE, $FORMAT_MD, $FORMAT_CSV, $FORMAT_JSON)")
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun ensureWritableFile(target: File, force: Boolean) {
        if (target.isDirectory) {
            throw ExportException("export target is a directory: ${target.path}")
        }
        if (target.exists() && !force) {
            throw ExportException("refusing to overwrite ${target.path} — pass --force")
        }
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw ExportException("cannot create directory ${parent.path}")
        }
    }

    private fun frontMatter(note: Note): String = buildString {
        appendLine("---")
        appendLine("id: ${note.id}")
        appendLine("title: ${yamlQuote(note.title)}")
        appendLine("notebook: ${yamlQuote(note.notebook)}")
        appendLine("tags: [${note.tags.sorted().joinToString(", ") { yamlQuote(it) }}]")
        appendLine("status: ${note.status.id}")
        appendLine("pinned: ${note.pinned}")
        appendLine("created: ${note.createdAt}")
        appendLine("updated: ${note.updatedAt}")
        appendLine("---")
    }

    private fun fileBaseName(note: Note, position: Int): String {
        val ordinal = (position + 1).toString().padStart(4, '0')
        return "$ordinal-${note.id}-${note.slug()}.md"
    }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun csvField(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun yamlQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun escapeMarkdownLinkText(value: String): String =
        value.replace("[", "\\[").replace("]", "\\]")

    private fun expanded(path: String): String = when {
        path == "~" -> System.getProperty("user.home")
        path.startsWith("~/") -> File(System.getProperty("user.home"), path.removePrefix("~/")).path
        else -> path
    }

    companion object {
        const val FORMAT_BUNDLE = "bundle"
        const val FORMAT_MD = "md"
        const val FORMAT_CSV = "csv"
        const val FORMAT_JSON = "json"

        /** CSV column order — stable API for spreadsheet consumers. */
        const val CSV_HEADER = "id,title,notebook,tags,status,pinned,created_at,updated_at,body"

        private val TIMESTAMP_FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val TIMESTAMP_DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
