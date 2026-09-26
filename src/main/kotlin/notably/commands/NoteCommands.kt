package notably.commands

import notably.AppContext
import notably.model.ExitCodes
import notably.model.ListingSort
import notably.model.Note
import notably.model.NoteStatus
import notably.model.SearchFilters
import notably.model.Tag
import notably.model.UsageException
import notably.model.ValidationException
import notably.ui.MarkdownRenderer
import notably.ui.Renderer

/**
 * A tiny command-line argument scanner shared by all command groups.
 *
 * Grammar:
 *  - `--flag value` / `-f value` consume the next token as the value,
 *    unless it starts with `-` (then the flag counts as boolean);
 *  - `--flag=value` assigns inline;
 *  - bare `--flag` / `-f` are boolean flags (empty-string value);
 *  - `--` stops flag parsing; everything after is positional.
 */
internal class CliArgs(raw: List<String>) {

    val positionals: MutableList<String> = mutableListOf()
    private val options: LinkedHashMap<String, MutableList<String>> = LinkedHashMap()

    init {
        var index = 0
        var terminated = false
        while (index < raw.size) {
            val token = raw[index]
            when {
                terminated -> positionals.add(token)
                token == "--" -> terminated = true
                token.startsWith("--") && token.length > 2 -> {
                    val eq = token.indexOf('=')
                    if (eq > 2) {
                        addOption(token.substring(2, eq), token.substring(eq + 1))
                    } else {
                        val next = raw.getOrNull(index + 1)
                        if (next != null && !next.startsWith("-")) {
                            addOption(token.substring(2), next)
                            index++
                        } else {
                            addOption(token.substring(2), "")
                        }
                    }
                }
                token.startsWith("-") && token.length > 1 -> {
                    val next = raw.getOrNull(index + 1)
                    if (next != null && !next.startsWith("-")) {
                        addOption(token.substring(1), next)
                        index++
                    } else {
                        addOption(token.substring(1), "")
                    }
                }
                else -> positionals.add(token)
            }
            index++
        }
    }

    private fun addOption(name: String, value: String) {
        options.getOrPut(name) { mutableListOf() }.add(value)
    }

    /** First value of the named option(s), `null` when absent. */
    fun value(vararg names: String): String? {
        for (name in names) {
            val values = options[name]
            if (values != null && values.isNotEmpty()) return values[0]
        }
        return null
    }

    /** True when any of the named flags was given. */
    fun bool(vararg names: String): Boolean = names.any { options.containsKey(it) }

    /** All values of repeatable options (e.g. multiple `--tag` flags). */
    fun values(vararg names: String): List<String> =
        names.flatMap { options[it].orEmpty() }.filter { it.isNotEmpty() }

    /** Repeatable options split on commas — handy for `--tag a,b`. */
    fun csv(vararg names: String): List<String> =
        values(*names).flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }

    /** Positional at [index], or `null`. */
    fun positional(index: Int): String? = positionals.getOrNull(index)

    /** Positional at [index] or a [UsageException]. */
    fun requirePositional(index: Int, what: String, hint: String = ""): String {
        val value = positionals.getOrNull(index)
        if (value.isNullOrBlank()) {
            val suffix = if (hint.isEmpty()) "" else " ($hint)"
            throw UsageException("missing <$what>$suffix")
        }
        return value
    }

    /** Number of positional arguments. */
    val positionalCount: Int get() = positionals.size

    /** All positionals from [index] joined with spaces (search queries). */
    fun joinedFrom(index: Int): String = positionals.drop(index).joinToString(" ").trim()
}

/** Command group implementing `add`, `show`, `edit`, `rm`, `list`, `search`. */
class NoteCommands(private val ctx: AppContext) {

    // ------------------------------------------------------------------
    // notably add
    // ------------------------------------------------------------------

    /**
     * `notably add "Title" [--notebook N] [--tag t]... [--pin] [--color #hex]`
     * Body text comes from `--body`, `--file`, the configured editor, or
     * inline stdin — in that order of precedence.
     */
    fun cmdAdd(args: List<String>): Int {
        val a = CliArgs(args)
        val title = a.positional(0) ?: ctx.prompt.ask("Title")
        if (title.isBlank()) throw ValidationException("note title must not be blank")
        if (title.trim().length > Note.MAX_TITLE_LENGTH) {
            throw ValidationException("note title exceeds ${Note.MAX_TITLE_LENGTH} characters")
        }

        val body = when {
            a.bool("body", "b") || a.value("body", "b") != null -> a.value("body", "b") ?: ""
            a.value("file") != null -> readBodyFile(a.value("file")!!)
            else -> ctx.prompt.readMultiline(
                title = "note body",
                editorCommand = ctx.workspace.loadConfig().editor
            )
        }

        val config = ctx.workspace.loadConfig()
        val notebookName = (a.value("notebook", "n") ?: config.defaultNotebook).trim()
        if (notebookName.isEmpty()) throw ValidationException("notebook must not be blank")
        val created = ctx.notebooks.ensureExists(notebookName)

        val tags = parseTags(a.csv("tag", "t"))
        val pinned = a.bool("pin", "p")
        val color = a.value("color")

        val note = ctx.notes.create(
            title = title.trim(),
            body = body,
            notebookName = created.name,
            tags = tags,
            pinned = pinned,
            color = color
        )
        println(ctx.renderer.green("✓") + " created note ${ctx.renderer.bold(note.id)} in '${note.notebook}'")
        if (tags.isNotEmpty()) println("  tags: ${ctx.renderer.tagList(note.tags)}")
        if (pinned) println("  " + ctx.renderer.yellow("pinned — floats to the top of listings"))
        println("  show it with: notably show ${note.id}")
        return ExitCodes.OK
    }

    private fun readBodyFile(path: String): String {
        val file = java.io.File(expandTilde(path))
        if (!file.isFile) throw ValidationException("body file not found: ${file.path}")
        if (file.length() > Note.MAX_BODY_LENGTH * 2L) {
            throw ValidationException("body file is too large (max ${Note.MAX_BODY_LENGTH} chars of content)")
        }
        return try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw ValidationException("cannot read body file: ${e.message}")
        }
    }

    private fun expandTilde(path: String): String = when {
        path == "~" -> System.getProperty("user.home")
        path.startsWith("~/") -> java.io.File(System.getProperty("user.home"), path.removePrefix("~/")).path
        else -> path
    }

    // ------------------------------------------------------------------
    // notably show
    // ------------------------------------------------------------------

    /** `notably show <id> [--raw]` — pretty (default) or raw markdown view. */
    fun cmdShow(args: List<String>): Int {
        val a = CliArgs(args)
        val ref = a.requirePositional(0, "note id", "a full id or unique prefix")
        val note = ctx.notes.resolve(ref)
        val r = ctx.renderer
        println(r.bold(note.title) + (if (note.pinned) " " + r.yellow("★") else ""))
        println(r.dim("─".repeat(48)))
        println(r.keyValue("id", note.id))
        println(r.keyValue("notebook", note.notebook))
        if (note.tags.isNotEmpty()) println(r.keyValue("tags", r.tagList(note.tags)))
        if (note.color != null) println(r.keyValue("color", note.color))
        val badge = r.statusBadge(note.status)
        if (badge.isNotEmpty()) println(r.keyValue("status", badge))
        val format = ctx.workspace.loadConfig().dateFormat
        println(r.keyValue("created", "${r.formatInstant(note.createdAt, format)} (${r.relativeTime(note.createdAt)})"))
        println(r.keyValue("updated", "${r.formatInstant(note.updatedAt, format)} (${r.relativeTime(note.updatedAt)})"))
        println(r.dim("─".repeat(48)))
        if (a.bool("raw", "r")) {
            println(note.body)
        } else if (note.isBodyEmpty) {
            println(r.dim("(empty body — add text with: notably edit ${note.id} --body)"))
        } else {
            println(MarkdownRenderer.render(note.body, ctx.renderer.colors))
        }
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // notably edit
    // ------------------------------------------------------------------

    /**
     * `notably edit <id> [--title "New"] [--body]` — `--title` replaces the
     * title inline; `--body` (or no flags at all) opens the editor seeded
     * with the current body.
     */
    fun cmdEdit(args: List<String>): Int {
        val a = CliArgs(args)
        val ref = a.requirePositional(0, "note id")
        val note = ctx.notes.resolve(ref)
        val newTitle = a.value("title", "t")
        val editBody = a.bool("body", "b") || newTitle == null

        var updated = note
        if (newTitle != null) {
            updated = ctx.notes.updateContent(updated, title = newTitle)
        }
        if (editBody) {
            val newBody = ctx.prompt.readMultiline(
                title = updated.title,
                initial = updated.body,
                editorCommand = ctx.workspace.loadConfig().editor
            )
            updated = ctx.notes.updateContent(updated, body = newBody)
        }
        if (updated === note) {
            println(ctx.renderer.dim("note unchanged"))
        } else {
            println(ctx.renderer.green("✓") + " updated note ${ctx.renderer.bold(updated.id)} — '${updated.title}'")
        }
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // notably rm
    // ------------------------------------------------------------------

    /** `notably rm <id> [--hard] [-y]` — trash by default, purge with `--hard`. */
    fun cmdRm(args: List<String>): Int {
        val a = CliArgs(args)
        val ref = a.requirePositional(0, "note id")
        val note = ctx.notes.resolve(ref)
        val assumeYes = a.bool("yes", "y")
        val hard = a.bool("hard", "H")
        if (hard) {
            if (!assumeYes && !ctx.prompt.confirm("Permanently delete '${note.title}'? This cannot be undone")) {
                println("cancelled")
                return ExitCodes.OK
            }
            ctx.notes.deleteForever(note)
            println(ctx.renderer.red("✕") + " deleted forever: ${note.id}")
        } else {
            if (note.status == NoteStatus.TRASHED) {
                println(ctx.renderer.dim("note ${note.id} is already in the trash"))
                println("purge it with: notably rm ${note.id} --hard")
                return ExitCodes.OK
            }
            if (!assumeYes && !ctx.prompt.confirm("Move '${note.title}' to trash?")) {
                println("cancelled")
                return ExitCodes.OK
            }
            ctx.notes.setStatus(note, NoteStatus.TRASHED)
            println(ctx.renderer.yellow("↺") + " moved to trash: ${note.id}")
            println("restore it with: notably restore ${note.id}")
        }
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // notably list
    // ------------------------------------------------------------------

    /**
     * `notably list [--notebook N] [--tag t]... [--pinned] [--archived]
     * [--trash] [--all] [--sort updated|created|title|notebook] [--limit N]
     * [-q query]` — table listing with filters; with `-q` it prints scored
     * results with snippets instead.
     */
    fun cmdList(args: List<String>): Int {
        val a = CliArgs(args)
        val filters = filtersFromArgs(a, defaultLimit = 100)
        val sort = ListingSort.fromId(a.value("sort"), ListingSort.UPDATED)
        val notes = ctx.notes.filter(filters)
        val query = a.value("query", "q") ?: a.joinedFrom(0).ifEmpty { null }

        if (query != null) {
            val hits = ctx.search.search(notes, query, filters.limit)
            if (hits.isEmpty()) {
                println(ctx.renderer.dim("no notes match '${query}'${filters.describe()}"))
                return ExitCodes.OK
            }
            println(ctx.renderer.bold("Search '${query}'") + ctx.renderer.dim(filters.describe()))
            for (hit in hits) {
                printSearchHit(hit)
            }
            println(ctx.renderer.dim("${hits.size} result(s)"))
            return ExitCodes.OK
        }

        val sorted = notes.sortedWith(
            notably.model.NoteSort.comparator(sort, pinnedFirst = !filters.trashedOnly && !filters.allStatuses)
        ).take(filters.limit)
        if (sorted.isEmpty()) {
            println(ctx.renderer.dim("no notes found${filters.describe()} — create one with: notably add \"My note\""))
            return ExitCodes.OK
        }
        val rows = sorted.map { note ->
            listOf(
                ctx.renderer.dim(note.id),
                ctx.renderer.flagsOf(note.pinned, note.status),
                truncateTitle(note.title),
                plainTags(note),
                note.notebook,
                ctx.renderer.relativeTime(note.updatedAt)
            )
        }
        println(
            ctx.renderer.table(
                title = "Notes${filters.describe()}",
                headers = listOf("ID", "Flags", "Title", "Tags", "Notebook", "Updated"),
                rows = rows,
                aligns = listOf(Renderer.Align.LEFT, Renderer.Align.LEFT, Renderer.Align.LEFT, Renderer.Align.LEFT, Renderer.Align.LEFT, Renderer.Align.RIGHT)
            )
        )
        println(ctx.renderer.dim("${sorted.size} of ${notes.size} note(s) shown"))
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // notably search
    // ------------------------------------------------------------------

    /**
     * `notably search <query> [--regex] [--limit N]` — scored full-text
     * search; `--regex`/`-x` treats the query as a regular expression and
     * reports per-note match counts instead of token scores.
     */
    fun cmdSearch(args: List<String>): Int {
        val a = CliArgs(args)
        val query = a.joinedFrom(0)
        if (query.isEmpty()) {
            throw UsageException("usage: notably search <query> [--regex] [--limit N] [--notebook N] [--tag t]")
        }
        val r = ctx.renderer
        val filters = filtersFromArgs(a, defaultLimit = notably.service.SearchService.DEFAULT_LIMIT)
        val notes = ctx.notes.filter(filters)
        println(r.bold("Search results for '${query}'"))
        if (a.bool("regex", "x")) {
            val hits = ctx.search.searchRegex(notes, query, filters.limit)
            if (hits.isEmpty()) {
                println(r.dim("no matches for /$query/${filters.describe()}"))
                return ExitCodes.OK
            }
            for (hit in hits) {
                println(
                    "${r.dim(r.score(hit.score))}  ${r.bold(hit.note.id)}  ${hit.note.title} " +
                        r.dim("· ${hit.matchCount} match(es)")
                )
                if (hit.snippet.isNotEmpty()) {
                    println("      ${r.highlight(hit.snippet)}")
                }
            }
            println(r.dim("${hits.size} note(s) matched /$query/"))
            return ExitCodes.OK
        }
        val hits = ctx.search.search(notes, query, filters.limit)
        if (hits.isEmpty()) {
            println(r.dim("no matches for '${query}'${filters.describe()}"))
            return ExitCodes.OK
        }
        for (hit in hits) {
            printSearchHit(hit)
        }
        println(r.dim("${hits.size} match(es), best score ${r.score(hits[0].score)}"))
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /** Builds [SearchFilters] from standard list/search/export flags. */
    internal fun filtersFromArgs(a: CliArgs, defaultLimit: Int): SearchFilters {
        val limitRaw = a.value("limit")
        val limit = limitRaw?.toIntOrNull()?.coerceIn(1, 10_000) ?: defaultLimit
        val tags = a.csv("tag", "t").map { Tag.normalize(it) }.toSet()
        return SearchFilters(
            notebook = a.value("notebook", "n"),
            tags = tags,
            pinnedOnly = a.bool("pinned", "p"),
            includeArchived = a.bool("archived") || a.bool("all", "a"),
            archivedOnly = a.bool("archived-only"),
            trashedOnly = a.bool("trash", "trashed"),
            allStatuses = a.bool("all", "a"),
            limit = limit
        )
    }

    private fun parseTags(raw: List<String>): List<String> = raw

    private fun printSearchHit(hit: notably.service.SearchService.SearchHit) {
        val r = ctx.renderer
        val flag = if (hit.note.pinned) r.yellow("★") else " "
        println("${r.dim(r.score(hit.score))}  ${r.bold(hit.note.id)} $flag${hit.note.title}")
        if (hit.snippet.isNotEmpty()) {
            println("      ${r.highlight(hit.snippet)}")
        }
    }

    private fun truncateTitle(title: String): String =
        if (title.length <= TITLE_COLUMN) title else title.take(TITLE_COLUMN - 1) + "…"

    private fun plainTags(note: Note): String =
        if (note.tags.isEmpty()) "" else note.tags.sorted().joinToString(" ") { "[$it]" }
}
