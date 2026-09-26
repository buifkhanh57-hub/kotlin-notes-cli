package notably.commands

import java.time.Duration
import java.time.Instant
import notably.AppContext
import notably.model.ExitCodes
import notably.model.NotFoundException
import notably.model.UsageException
import notably.service.Reminder
import notably.service.RemindService

/**
 * `notably remind …` — due dates and nudges on top of the note store.
 *
 * Subcommands:
 *  - `add <ref> --due <when> [--priority 0..3]`
 *  - `list [--all] [--overdue] [--days N]`
 *  - `rm <ref>`                remove every reminder of a note
 *  - `done <ref>`              complete the pending reminder
 *  - `reopen <ref>`            undo a completed reminder
 *  - `snooze <ref> --for <dur>` shift the due date by a relative duration
 *  - `clear-done`              purge all completed reminders
 *
 * References go through [notably.service.NoteService.resolve], so ids,
 * unique id prefixes and unique title substrings all work.
 */
class RemindCommands(private val ctx: AppContext) {

    /** Router for the `remind` command group. */
    fun cmdRemind(args: List<String>): Int {
        val a = CliArgs(args)
        val sub = a.requirePositional(0, "remind subcommand", "add | list | rm | done | reopen | snooze | clear-done")
        return when (sub.lowercase()) {
            "add", "set" -> cmdAdd(a)
            "list", "ls" -> cmdList(a)
            "rm", "remove" -> cmdRemove(a)
            "done", "complete" -> cmdDone(a)
            "reopen", "undo" -> cmdReopen(a)
            "snooze" -> cmdSnooze(a)
            "clear-done", "purge" -> cmdClearDone()
            else -> throw UsageException(
                "unknown remind subcommand '$sub' (use: add, list, rm, done, reopen, snooze, clear-done)"
            )
        }
    }

    // ------------------------------------------------------------------
    // remind add
    // ------------------------------------------------------------------

    /** `remind add <ref> --due <when> [--priority 0..3]`. */
    private fun cmdAdd(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id", "a full id, unique prefix or title substring")
        val dueExpression = a.value("due", "d")
            ?: throw UsageException(
                "remind add needs --due <when> (today, tomorrow, +3d, monday, 2026-03-01, ...)"
            )
        val priorityRaw = a.value("priority", "p")
        val priority = when {
            priorityRaw == null -> Reminder.DEFAULT_PRIORITY
            else -> priorityRaw.toIntOrNull()
                ?: throw UsageException("--priority expects a number 0..3")
        }
        val reminder = ctx.reminds.add(ref, dueExpression, priority)
        val note = ctx.notes.resolve(reminder.noteId)
        val now = Instant.now()
        println(
            ctx.renderer.green("✓") + " reminder set for '${note.title}' (${note.id}) — due " +
                ctx.renderer.bold(RemindService.describeDue(reminder.due, now))
        )
        println("  ${reminder.due} · priority ${reminder.priority} (${RemindService.priorityLabel(reminder.priority)})")
        println("  review with: notably remind list")
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // remind list
    // ------------------------------------------------------------------

    /**
     * `remind list [--all] [--overdue] [--days N]` — table of reminders.
     * Default view is every pending reminder, soonest first; `--overdue`
     * narrows to past-due rows, `--days N` to rows falling due within N days,
     * and `--all` adds completed history.
     */
    private fun cmdList(a: CliArgs): Int {
        val now = Instant.now()
        val includeDone = a.bool("all")
        val visible = when {
            includeDone -> ctx.reminds.all()
            a.bool("overdue") -> ctx.reminds.overdue(now)
            a.value("days") != null -> {
                val days = a.value("days")?.toIntOrNull()?.coerceIn(0, 3650)
                    ?: throw UsageException("--days expects a number of days (0..3650)")
                ctx.reminds.dueWithin(days, now)
            }
            else -> ctx.reminds.pending()
        }
        if (visible.isEmpty()) {
            println(
                ctx.renderer.dim(
                    "no reminders${if (includeDone) " at all" else ""} — " +
                        "set one with: notably remind add <id> --due tomorrow"
                )
            )
            return ExitCodes.OK
        }
        val rows = visible.map { reminder ->
            val note = runCatching { ctx.notes.resolve(reminder.noteId) }.getOrNull()
            val title = note?.title ?: "(missing note)"
            val status = when {
                reminder.done -> ctx.renderer.dim("done")
                reminder.isOverdue(now) -> ctx.renderer.red("overdue")
                Duration.between(now, reminder.due).toHours() < 24 -> ctx.renderer.yellow("due soon")
                else -> "scheduled"
            }
            listOf(
                reminder.noteId,
                truncate(title, TITLE_COLUMN),
                RemindService.describeDue(reminder.due, now),
                RemindService.priorityLabel(reminder.priority),
                status
            )
        }
        println(
            ctx.renderer.table(
                title = "Reminders (${visible.size})",
                headers = listOf("Note", "Title", "Due", "Priority", "Status"),
                rows = rows,
                aligns = listOf(
                    Renderer.Align.LEFT, Renderer.Align.LEFT, Renderer.Align.LEFT,
                    Renderer.Align.LEFT, Renderer.Align.LEFT
                )
            )
        )
        val overdueCount = visible.count { it.isOverdue(now) }
        val pendingCount = visible.count { it.isPending }
        println(ctx.renderer.dim("$pendingCount pending, $overdueCount overdue"))
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // remind rm / done / reopen / snooze / clear-done
    // ------------------------------------------------------------------

    /** `remind rm <ref>` — remove every reminder of the note. */
    private fun cmdRemove(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id")
        val removed = ctx.reminds.remove(ref)
        println(ctx.renderer.yellow("✕") + " removed $removed reminder(s) for ${describeNote(ref)}")
        return ExitCodes.OK
    }

    /** `remind done <ref>` — complete the pending reminder. */
    private fun cmdDone(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id")
        val done = ctx.reminds.complete(ref)
        println(ctx.renderer.green("✓") + " reminder done — ${done.noteId} at ${done.doneAt}")
        println("  undo with: notably remind reopen ${done.noteId}")
        return ExitCodes.OK
    }

    /** `remind reopen <ref>` — bring a completed reminder back. */
    private fun cmdReopen(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id")
        val reopened = ctx.reminds.reopen(ref)
        println(
            ctx.renderer.green("✓") + " reminder reopened — due " +
                ctx.renderer.bold(RemindService.describeDue(reopened.due, Instant.now()))
        )
        return ExitCodes.OK
    }

    /** `remind snooze <ref> --for <duration>` — push the due date out. */
    private fun cmdSnooze(a: CliArgs): Int {
        val ref = a.requirePositional(1, "note id")
        val expression = a.value("for", "f")
            ?: throw UsageException("remind snooze needs --for <duration> (e.g. 30m, 4h, 2d, 1w)")
        val duration = RemindService.parseDuration(expression)
        val snoozed = ctx.reminds.snooze(ref, duration)
        println(
            ctx.renderer.green("✓") + " snoozed — now due " +
                ctx.renderer.bold(RemindService.describeDue(snoozed.due, Instant.now()))
        )
        println("  ${snoozed.due}")
        return ExitCodes.OK
    }

    /** `remind clear-done` — purge all completed reminders. */
    private fun cmdClearDone(): Int {
        val removed = ctx.reminds.clearDone()
        if (removed == 0) {
            println(ctx.renderer.dim("no completed reminders to clear"))
        } else {
            println(ctx.renderer.yellow("✕") + " cleared $removed completed reminder(s)")
        }
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Guard so a missing note (purged while reminded) degrades gracefully. */
    private fun describeNote(ref: String): String = try {
        val note = ctx.notes.resolve(ref)
        "'${note.title}' (${note.id})"
    } catch (_: NotFoundException) {
        ref
    }

    private fun truncate(text: String, width: Int): String =
        if (text.length <= width) text else text.take(width - 1) + "…"

    companion object {
        const val TITLE_COLUMN = 32
    }
}
