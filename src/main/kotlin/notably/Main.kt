package notably

import kotlin.system.exitProcess
import notably.commands.NoteCommands
import notably.commands.OrgCommands
import notably.commands.RemindCommands
import notably.commands.UtilCommands
import notably.model.About
import notably.model.ExitCodes
import notably.model.NotablyException
import notably.model.UsageException
import notably.service.ExportService
import notably.service.NoteService
import notably.service.NotebookService
import notably.service.RemindService
import notably.service.SearchService
import notably.storage.JsonParseException
import notably.storage.Workspace
import notably.ui.Ansi
import notably.ui.Prompt
import notably.ui.Renderer

/**
 * Dependency bundle handed to every command group. Built once per process
 * after the workspace has been located (and, for most commands, verified to
 * exist — `init`, `help` and `version` work without a workspace).
 */
class AppContext(
    val workspace: Workspace,
    val notes: NoteService,
    val notebooks: NotebookService,
    val reminds: RemindService,
    val search: SearchService,
    val renderer: Renderer,
    val prompt: Prompt,
    val exporter: ExportService
)

/** Commands that require an initialized workspace (everything except init/help/version). */
val ALL_COMMANDS: List<String> = listOf(
    "init", "add", "show", "edit", "rm", "list", "search",
    "tag", "notebook", "pin", "unpin", "archive", "unarchive", "trash", "restore",
    "export", "remind", "stats", "lock"
)

private const val USAGE =
    """notably — a professional note-taking workspace for the terminal

Usage:
  notably [--workspace DIR] [--no-color|--color] <command> [arguments]

Commands:
  init                     create a workspace in ~/.notably (or --workspace dir)
  add <title>              create a note (editor / --body / --file)
  show <id>                display a note (markdown rendered; --raw for source)
  edit <id>                change title and/or body of a note
  rm <id>                  move a note to the trash (--hard purges)
  list                     filterable table of notes (-q for quick search)
  search <query>           scored full-text search with snippets
  tag add|rm|list          manage tags on notes
  notebook create|list|move|rename|describe|rm
  pin|unpin <id>...        keep notes on top of listings
  archive|unarchive <id>...  tuck notes away without trashing
  trash|restore <id>...    soft delete / bring back (--empty purges trash)
  export                   markdown bundle, single markdown, CSV or JSON
  remind add|list|rm|done|snooze   due dates attached to notes
  stats                    per-notebook counts, tag cloud, monthly activity
  lock lock|unlock|status|explain   at-rest encryption for notes.json
  help [command]           this text, or help for one command
  version                  print version information

Learn more per command: notably help <command>
"""

/** Detailed per-command help (shown by `notably help <command>`). */
private val COMMAND_HELP: Map<String, String> = mapOf(
    "init" to """init — create a workspace

  notably init [--notebook NAME]

Creates ~/.notably (or --workspace DIR) with notes.json, notebooks.json,
meta.json, config.json, backups/ and exports/. Fails politely if a
workspace already exists there.""",
    "add" to """add — create a note

  notably add "Title" [--notebook N] [--tag a,b]... [--pin] [--color #RRGGBB]
                         [--body "text"] [--file path.md]

Body precedence: --body flag > --file contents > configured editor
($VISUAL/$EDITOR or config editor) > inline stdin (finish with '.').

A notebook mentioned for the first time is created automatically.
Examples:
  notably add "Kotlin coroutines" -n Work -t kotlin,deep-dive --pin
  notably add "Groceries" --body "milk, eggs, coffee"
  echo "pasted text" | notably add "From clipboard" --file -""",
    "show" to """show — display one note

  notably show <id> [--raw]

Renders the body (headings, bold, code spans, lists, task boxes, links).
--raw prints the stored markdown verbatim. The id may be a unique prefix.""",
    "edit" to """edit — modify an existing note

  notably edit <id> [--title "New title"] [--body]

--title replaces the title inline. --body (or no flags) opens the editor
seeded with the current text; unchanged content does not bump updatedAt.""",
    "rm" to """rm — remove a note

  notably rm <id> [--hard] [-y]

Default: move to trash (recoverable with `notably restore <id>`).
--hard deletes immediately and irreversibly; -y skips the confirmation.""",
    "list" to """list — filter and browse notes

  notably list [--notebook N] [--tag t]... [--pinned] [--archived|--archived-only]
               [--trash] [--all] [--sort updated|created|title|notebook]
               [--limit N] [-q "query"]

Without -q this prints a table (pinned notes first by default). With -q it
prints scored results with highlighted snippets, like `search`.""",
    "search" to """search — scored full-text search

  notably search "kotlin coroutines" [--limit N] [filters...]

Field weights: title 5.0, tags 3.0, notebook 1.5, body 1.0 (whole-word hits
score double vs substrings). Exact title match and phrases earn bonuses,
recent updates get a small boost. Snippets show the first match in context.

Pass --regex (-x) to treat the query as a regular expression; matches in
title, tags and body are counted and weighted (4.0 / 2.5 / 1.0).""",
    "tag" to """tag — organize notes with tags

  notably tag add <id> kotlin,jvm    add tags (normalized, de-duplicated)
  notably tag rm <id> kotlin         remove tags
  notably tag list                   usage table with bars and colors""",
    "notebook" to """notebook — manage containers

  notably notebook create Projects [--desc "..."] [--color #RRGGBB]
  notably notebook list
  notably notebook move <id> Projects
  notably notebook rename Projects Work      (cascades to all notes)
  notably notebook describe Projects "9-5 stuff"
  notably notebook rm Projects               (only when empty)""",
    "pin" to "pin — keep notes on top\n\n  notably pin <id>... [--]   pinned notes sort first in every listing",
    "unpin" to "unpin — release pinned notes\n\n  notably unpin <id>...",
    "archive" to "archive — tuck notes away\n\n  notably archive <id>...\n\nArchived notes stay out of the default view; see them with `list --archived`.",
    "unarchive" to "unarchive — bring archived notes back\n\n  notably unarchive <id>...",
    "trash" to """trash — soft delete / purge

  notably trash <id>...         move notes to the trash
  notably trash --empty         purge the whole trash (irreversible)
  notably trash --empty --days 30   purge only items older than 30 days""",
    "restore" to "restore — recover trashed notes\n\n  notably restore <id>...",
    "export" to """export — portable copies of your notes

  notably export [--format bundle|md|csv|json] [--out PATH] [--force] [filters...]

  bundle   directory: index.md + one front-mattered .md per note (default)
  md       one combined markdown document
  csv      spreadsheet-friendly, RFC-4180 quoted
  json     same array schema as notes.json (diff/re-import friendly)

Default target: ~/.notably/exports/<kind>-<timestamp>. Existing targets
require --force. Filters: --notebook, --tag, --pinned, --archived, --trash,
--all, --limit.""",
    "remind" to """remind — due dates and nudges for notes

  notably remind add <id> --due <when> [--priority 0..3]
  notably remind list [--all] [--overdue] [--days N]
  notably remind rm <id>
  notably remind done <id>
  notably remind reopen <id>
  notably remind snooze <id> --for <duration>
  notably remind clear-done

Due grammar: today, tomorrow, next week, next month, weekday names
(mon..sun), relative offsets (+30m, +6h, +3d, +2w) and ISO dates
(2026-03-01, 2026-03-01T09:30). Date-only forms land at 23:59 local.

A note holds one pending reminder at a time; completed reminders are kept
as history until `remind clear-done`. Snoozing always counts from now.""",
    "stats" to """stats — insight into the workspace

  notably stats [--months N] [--heat [WEEKS]]

Per-notebook totals, the most-used tags (bar chart), notes created per
month (bars + sparkline, streaks, optional --heat day heatmap), plus
totals: words, pins, reminders, busiest month, oldest note.""",
    "lock" to """lock — at-rest encryption for the note index

  notably lock lock      encrypt notes.json (passphrase prompt, min 8 chars)
  notably lock unlock    decrypt and restore the plaintext index
  notably lock status    show whether notes are encrypted and with what
  notably lock explain   print the scheme, parameters and honest limitations

Scheme: PBKDF2-HmacSHA256 (120k iterations) -> AES-GCM when available,
SHA-256 keystream XOR fallback otherwise. See `notably lock explain` —
this is a convenience lock, not a security boundary against determined
attackers.""",
    "help" to "help — show usage\n\n  notably help          command overview\n  notably help <cmd>    detailed help for one command",
    "version" to "version — print version information"
)

fun main(args: Array<String>) {
    var workspaceArg: String? = null
    var colorPref: Boolean? = null
    val rest = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when (val token = args[i]) {
            "--workspace", "-w" -> {
                val value = args.getOrNull(i + 1)
                if (value == null) usageFail("flag $token needs a directory argument")
                workspaceArg = value
                i++
            }
            "--no-color" -> colorPref = false
            "--color" -> colorPref = true
            "--version", "version" -> {
                println("${About.NAME} ${About.VERSION} — ${About.DESCRIPTION}")
                println("JVM ${System.getProperty("java.version")} · by ${About.AUTHOR}")
                exitProcess(ExitCodes.OK)
            }
            else -> rest.add(token)
        }
        i++
    }

    Ansi.init(colorPref)

    if (rest.isEmpty()) {
        println(USAGE)
        exitProcess(ExitCodes.OK)
    }
    val command = rest.removeAt(0).lowercase()
    if (command == "help") {
        printHelp(rest.firstOrNull())
        exitProcess(ExitCodes.OK)
    }

    try {
        val code = dispatch(command, rest, workspaceArg)
        exitProcess(code)
    } catch (e: NotablyException) {
        System.err.println("${Ansi.red("error:")} ${e.message}")
        if (e is UsageException) System.err.println("run 'notably help' for usage")
        exitProcess(e.exitCode)
    } catch (e: JsonParseException) {
        System.err.println("${Ansi.red("data error:")} ${e.message}")
        System.err.println("a rotated backup may still exist in ~/.notably/backups/")
        exitProcess(ExitCodes.IO)
    } catch (e: Exception) {
        System.err.println("${Ansi.red("unexpected error:")} ${e::class.simpleName}: ${e.message}")
        exitProcess(ExitCodes.ERROR)
    }
}

/** Routes a validated command to its handler. */
private fun dispatch(command: String, args: List<String>, workspaceArg: String?): Int {
    if (command !in ALL_COMMANDS) {
        throw UsageException("unknown command '$command'. ${suggest(command)}")
    }
    val ctx = buildContext(workspaceArg, requireInit = command != "init")
    val noteCommands = NoteCommands(ctx)
    val orgCommands = OrgCommands(ctx)
    val utilCommands = UtilCommands(ctx)
    val remindCommands = RemindCommands(ctx)
    return when (command) {
        "init" -> utilCommands.cmdInit(args)
        "add" -> noteCommands.cmdAdd(args)
        "show" -> noteCommands.cmdShow(args)
        "edit" -> noteCommands.cmdEdit(args)
        "rm" -> noteCommands.cmdRm(args)
        "list" -> noteCommands.cmdList(args)
        "search" -> noteCommands.cmdSearch(args)
        "tag" -> orgCommands.cmdTag(args)
        "notebook" -> orgCommands.cmdNotebook(args)
        "pin" -> orgCommands.cmdPin(args)
        "unpin" -> orgCommands.cmdUnpin(args)
        "archive" -> orgCommands.cmdArchive(args)
        "unarchive" -> orgCommands.cmdUnarchive(args)
        "trash" -> orgCommands.cmdTrash(args)
        "restore" -> orgCommands.cmdRestore(args)
        "export" -> utilCommands.cmdExport(args)
        "remind" -> remindCommands.cmdRemind(args)
        "stats" -> utilCommands.cmdStats(args)
        "lock" -> utilCommands.cmdLock(args)
        else -> throw UsageException("unknown command '$command'")
    }
}

/** Builds the service graph; `requireInit=false` lets `init` bootstrap. */
private fun buildContext(workspaceArg: String?, requireInit: Boolean): AppContext {
    val workspace = Workspace.resolve(workspaceArg)
    if (requireInit) workspace.requireInitialized()
    val notes = NoteService(workspace)
    val notebooks = NotebookService(workspace, notes)
    return AppContext(
        workspace = workspace,
        notes = notes,
        notebooks = notebooks,
        reminds = RemindService(workspace, notes),
        search = SearchService(),
        renderer = Renderer(),
        prompt = Prompt(),
        exporter = ExportService(workspace)
    )
}

/** Prints either the overview or per-command help. */
private fun printHelp(command: String?) {
    if (command == null) {
        println(USAGE)
        return
    }
    val cleaned = command.lowercase().removePrefix("--")
    val text = COMMAND_HELP[cleaned]
    if (text == null) {
        println("no detailed help for '$command'. ${suggest(command)}")
        println(USAGE)
        return
    }
    println(text)
}

/** Closest known command by edit distance, for typo-friendly errors. */
private fun suggest(input: String): String {
    val cleaned = input.lowercase().removePrefix("--")
    val candidates = ALL_COMMANDS + listOf("help", "version")
    val best = candidates.minByOrNull { levenshtein(cleaned, it) }
    val bestDistance = best?.let { levenshtein(cleaned, it) } ?: Int.MAX_VALUE
    return if (best != null && bestDistance <= 3) {
        "Did you mean '$best'? Run 'notably help' for the command list."
    } else {
        "Run 'notably help' for the command list."
    }
}

/** Classic DP edit distance (single rolling row). */
private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    val current = IntArray(b.length + 1)
    for (row in 1..a.length) {
        current[0] = row
        for (column in 1..b.length) {
            val substitution = previous[column - 1] + if (a[row - 1] == b[column - 1]) 0 else 1
            current[column] = minOf(current[column - 1] + 1, previous[column] + 1, substitution)
        }
        System.arraycopy(current, 0, previous, 0, current.size)
    }
    return previous[b.length]
}

/** Hard-fails on malformed global flags before any workspace access. */
private fun usageFail(message: String): Nothing {
    System.err.println("error: $message")
    System.err.println("run 'notably help' for usage")
    exitProcess(ExitCodes.USAGE)
}
