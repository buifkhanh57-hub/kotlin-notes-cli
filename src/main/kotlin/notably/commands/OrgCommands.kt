package notably.commands

import notably.AppContext
import notably.model.ExitCodes
import notably.model.NoteStatus
import notably.model.NotFoundException
import notably.model.UsageException
import notably.model.ValidationException

/**
 * Organizational commands: tags, notebooks, pinning, archive and trash
 * lifecycle. Batch operations (pin/archive/trash/restore) accept multiple
 * references, keep going on individual failures, and print a summary —
 * the exit code reflects whether *anything* failed.
 */
class OrgCommands(private val ctx: AppContext) {

    // ------------------------------------------------------------------
    // notably tag
    // ------------------------------------------------------------------

    /** `notably tag add|rm <id> <tags...>` and `notably tag list`. */
    fun cmdTag(args: List<String>): Int {
        val a = CliArgs(args)
        val sub = a.requirePositional(0, "tag subcommand", "add | rm | list")
        return when (sub.lowercase()) {
            "add", "set" -> cmdTagAdd(a)
            "rm", "remove", "unset" -> cmdTagRemove(a)
            "list", "ls" -> cmdTagList(a)
            else -> throw UsageException("unknown tag subcommand '$sub' (use: add, rm, list)")
        }
    }

    private fun cmdTagAdd(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id")
        val rawTags = a.positionals.drop(2).flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        if (rawTags.isEmpty()) throw UsageException("tag add needs at least one tag")
        val note = ctx.notes.resolve(ref)
        val updated = ctx.notes.addTags(note, rawTags)
        println(ctx.renderer.green("✓") + " tags on ${updated.id}: ${ctx.renderer.tagList(updated.tags)}")
        return ExitCodes.OK
    }

    private fun cmdTagRemove(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id")
        val rawTags = a.positionals.drop(2).flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        if (rawTags.isEmpty()) throw UsageException("tag rm needs at least one tag")
        val note = ctx.notes.resolve(ref)
        val updated = ctx.notes.removeTags(note, rawTags)
        if (updated.tags.isEmpty()) {
            println(ctx.renderer.dim("note ${updated.id} has no tags left"))
        } else {
            println(ctx.renderer.green("✓") + " tags on ${updated.id}: ${ctx.renderer.tagList(updated.tags)}")
        }
        return ExitCodes.OK
    }

    private fun cmdTagList(a: CliArgs): Int {
        val usage = ctx.notes.tagsInUse()
        if (usage.isEmpty()) {
            println(ctx.renderer.dim("no tags in use yet — add some with: notably tag add <id> kotlin"))
            return ExitCodes.OK
        }
        val colors = ctx.workspace.assignTagColors(usage.keys)
        val max = usage.values.maxOrNull() ?: 0
        val rows = usage.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (tag, count) ->
                val color = colors[tag]
                val paintedTag = if (color != null && ctx.renderer.colors) notably.ui.Ansi.paint(tag, colored(color)) else tag
                listOf(paintedTag, count.toString(), ctx.renderer.bar(count.toLong(), max.toLong(), 18))
            }
        println(
            ctx.renderer.table(
                title = "Tags in use (${usage.size})",
                headers = listOf("Tag", "Notes", "Usage"),
                rows = rows,
                aligns = listOf(notably.ui.Renderer.Align.LEFT, notably.ui.Renderer.Align.RIGHT, notably.ui.Renderer.Align.LEFT)
            )
        )
        return ExitCodes.OK
    }

    private fun colored(hex: String): String {
        // '#'RRGGBB -> nearest xterm-256 SGR body; 3-digit expanded first.
        val rgb = if (hex.length == 4) {
            hex.mapNotNull { ch -> ch.digitToIntOrNull(16)?.let { "$it$it" } }.joinToString("")
        } else {
            hex.removePrefix("#")
        }
        val r = rgb.substring(0, 2).toIntOrNull(16) ?: return notably.ui.Ansi.CYAN
        val g = rgb.substring(2, 4).toIntOrNull(16) ?: return notably.ui.Ansi.CYAN
        val b = rgb.substring(4, 6).toIntOrNull(16) ?: return notably.ui.Ansi.CYAN
        val index = 16 + 36 * (r * 5 / 255) + 6 * (g * 5 / 255) + (b * 5 / 255)
        return "38;5;$index"
    }

    // ------------------------------------------------------------------
    // notably notebook
    // ------------------------------------------------------------------

    /** `notably notebook create|list|move|rename|describe|rm ...`. */
    fun cmdNotebook(args: List<String>): Int {
        val a = CliArgs(args)
        val sub = a.requirePositional(0, "notebook subcommand", "create | list | move | rename | describe | rm")
        return when (sub.lowercase()) {
            "create", "new" -> cmdNotebookCreate(a)
            "list", "ls" -> cmdNotebookList()
            "move", "mv" -> cmdNotebookMove(a)
            "rename" -> cmdNotebookRename(a)
            "describe", "desc" -> cmdNotebookDescribe(a)
            "rm", "remove", "delete" -> cmdNotebookRemove(a)
            else -> throw UsageException("unknown notebook subcommand '$sub'")
        }
    }

    private fun cmdNotebookCreate(a: CliArgs): Int {
        val name = a.requirePositional(1, "notebook name")
        val description = a.value("desc", "d") ?: ""
        val notebook = ctx.notebooks.create(name, description, a.value("color"))
        println(ctx.renderer.green("✓") + " created notebook '${notebook.name}'")
        println("  add notes with: notably add \"Title\" --notebook ${notebook.name}")
        return ExitCodes.OK
    }

    private fun cmdNotebookList(): Int {
        val notebooks = ctx.notebooks.all()
        if (notebooks.isEmpty()) {
            println(ctx.renderer.dim("no notebooks — create one with: notably notebook create Projects"))
            return ExitCodes.OK
        }
        val counts = ctx.notebooks.usageCounts()
        val rows = notebooks.map { notebook ->
            listOf(
                notebook.name,
                (counts[notebook.key] ?: 0).toString(),
                ctx.renderer.relativeTime(notebook.createdAt),
                notebook.description
            )
        }
        println(
            ctx.renderer.table(
                title = "Notebooks",
                headers = listOf("Name", "Notes", "Created", "Description"),
                rows = rows,
                aligns = listOf(notably.ui.Renderer.Align.LEFT, notably.ui.Renderer.Align.RIGHT, notably.ui.Renderer.Align.RIGHT, notably.ui.Renderer.Align.LEFT)
            )
        )
        return ExitCodes.OK
    }

    private fun cmdNotebookMove(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id")
        val target = a.requirePositional(2, "target notebook")
        val note = ctx.notes.resolve(ref)
        val notebook = ctx.notebooks.ensureExists(target)
        val updated = ctx.notes.changeNotebook(note, notebook.name)
        println(ctx.renderer.green("✓") + " moved '${updated.title}' → '${updated.notebook}'")
        return ExitCodes.OK
    }

    private fun cmdNotebookRename(a: CliArgs): Int {
        val oldName = a.requirePositional(1, "current name")
        val newName = a.requirePositional(2, "new name")
        val renamed = ctx.notebooks.rename(oldName, newName)
        println(ctx.renderer.green("✓") + " renamed notebook to '${renamed.name}' (notes updated)")
        return ExitCodes.OK
    }

    private fun cmdNotebookDescribe(a: CliArgs): Int {
        val name = a.requirePositional(1, "notebook name")
        val description = a.value("desc", "d") ?: a.joinedFrom(2)
        val updated = ctx.notebooks.describe(name, description)
        println(ctx.renderer.green("✓") + " description of '${updated.name}': ${updated.description.ifEmpty { "(cleared)" }}")
        return ExitCodes.OK
    }

    private fun cmdNotebookRemove(a: CliArgs): Int {
        val name = a.requirePositional(1, "notebook name")
        val removed = ctx.notebooks.remove(name)
        println(ctx.renderer.yellow("✕") + " removed notebook '${removed.name}'")
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // Pinning
    // ------------------------------------------------------------------

    /** `notably pin <id>...` — batch pin. */
    fun cmdPin(args: List<String>): Int = batchStatus(args, operation = "pin") { note ->
        ctx.notes.setPinned(note, true)
    }

    /** `notably unpin <id>...` — batch unpin. */
    fun cmdUnpin(args: List<String>): Int = batchStatus(args, operation = "unpin") { note ->
        ctx.notes.setPinned(note, false)
    }

    // ------------------------------------------------------------------
    // Archive lifecycle
    // ------------------------------------------------------------------

    /** `notably archive <id>...` — move active notes out of the main view. */
    fun cmdArchive(args: List<String>): Int = batchStatus(args, operation = "archive") { note ->
        ctx.notes.setStatus(note, NoteStatus.ARCHIVED)
    }

    /** `notably unarchive <id>...` — back to active. */
    fun cmdUnarchive(args: List<String>): Int = batchStatus(args, operation = "unarchive") { note ->
        ctx.notes.setStatus(note, NoteStatus.ACTIVE)
    }

    /** `notably trash <id>...` and `notably trash --empty [--days N]`. */
    fun cmdTrash(args: List<String>): Int {
        val a = CliArgs(args)
        if (a.bool("empty", "e")) {
            val days = a.value("days")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val purged = ctx.notes.purgeTrash(days)
            println(if (purged == 0) ctx.renderer.dim("trash is already empty") else "purged $purged note(s) from the trash")
            return ExitCodes.OK
        }
        return batchStatus(args, operation = "trash") { note ->
            ctx.notes.setStatus(note, NoteStatus.TRASHED)
        }
    }

    /** `notably restore <id>...` — from trash (or archive) back to active. */
    fun cmdRestore(args: List<String>): Int = batchStatus(args, operation = "restore") { note ->
        ctx.notes.setStatus(note, NoteStatus.ACTIVE)
    }

    // ------------------------------------------------------------------
    // Batch plumbing
    // ------------------------------------------------------------------

    /**
     * Applies [operation] to every positional reference. Failures for
     * individual notes are reported on stderr without aborting the batch.
     * Returns [ExitCodes.OK] when at least one note succeeded, otherwise
     * the first failure's exit code (or [ExitCodes.USAGE] with no args).
     */
    private fun batchStatus(args: List<String>, operation: String, apply: (note: notably.model.Note) -> Unit): Int {
        val refs = args.filter { !it.startsWith("-") }
        if (refs.isEmpty()) {
            throw UsageException("usage: notably $operation <id>... (one or more note ids/prefixes)")
        }
        var succeeded = 0
        var firstFailure: Int? = null
        for (ref in refs) {
            try {
                val note = ctx.notes.resolve(ref)
                apply(note)
                succeeded++
                println(ctx.renderer.green("✓") + " $operation '${note.title}' (${note.id})")
            } catch (e: NotFoundException) {
                System.err.println(ctx.renderer.red("✕") + " $operation $ref: ${e.message}")
                if (firstFailure == null) firstFailure = e.exitCode
            } catch (e: ValidationException) {
                System.err.println(ctx.renderer.red("✕") + " $operation $ref: ${e.message}")
                if (firstFailure == null) firstFailure = e.exitCode
            }
        }
        val skipped = refs.size - succeeded
        if (skipped > 0) {
            println(ctx.renderer.dim("$succeeded succeeded, $skipped skipped"))
        }
        return firstFailure ?: ExitCodes.OK
    }
}
