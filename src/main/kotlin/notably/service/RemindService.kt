package notably.service

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import notably.model.NotFoundException
import notably.model.StorageException
import notably.model.ValidationException
import notably.storage.JsonValue
import notably.storage.Workspace
import notably.storage.jsonBoolean
import notably.storage.jsonNumber
import notably.storage.jsonObject
import notably.storage.jsonString

/**
 * A due date attached to one note. Reminders live in their own registry
 * file (`reminders.json`) so notes stay immutable value objects; the
 * lifecycle is intentionally tiny:
 *
 * ```
 *   pending --> done          (`remind done`)
 *      ^    \--- reopen ------'
 *      | purge via `remind rm` / `remind clear-done`
 * ```
 *
 * Invariants (checked on construction):
 *  - `noteId` is never blank;
 *  - `priority` stays inside [PRIORITY_RANGE];
 *  - a completed reminder carries `doneAt`, a pending one does not get it
 *    back-filled silently (lenient reads may leave it null).
 *
 * @property noteId    id of the reminded note (full id, never a prefix)
 * @property due       the instant the note should be looked at again
 * @property priority  0=low .. 3=urgent, see [PRIORITY_LABELS]
 * @property done      true once the reminder has been completed
 * @property createdAt when the reminder was created
 * @property doneAt    when the reminder was completed, `null` while pending
 */
data class Reminder(
    val noteId: String,
    val due: Instant,
    val priority: Int = DEFAULT_PRIORITY,
    val done: Boolean = false,
    val createdAt: Instant,
    val doneAt: Instant? = null
) {
    init {
        if (noteId.isBlank()) throw ValidationException("reminder noteId must not be blank")
        if (priority !in PRIORITY_RANGE) {
            throw ValidationException("reminder priority must be within $PRIORITY_RANGE")
        }
    }

    /** True while this reminder still needs attention. */
    val isPending: Boolean get() = !done

    /** True when the due date has passed and the reminder is still open. */
    fun isOverdue(now: Instant = Instant.now()): Boolean = isPending && due.isBefore(now)

    /** Serializes the reminder to its JSON registry form. */
    fun toJson(): JsonValue.JsonObject {
        val fields = mutableListOf(
            "noteId" to jsonString(noteId),
            "due" to jsonString(due.toString()),
            "priority" to jsonNumber(priority.toLong()),
            "done" to jsonBoolean(done),
            "createdAt" to jsonString(createdAt.toString())
        )
        doneAt?.let { fields.add("doneAt" to jsonString(it.toString())) }
        return jsonObject(*fields.toTypedArray())
    }

    companion object {
        const val DEFAULT_PRIORITY = 1

        /** Accepted priority span (inclusive). */
        val PRIORITY_RANGE = 0..3

        /** Parses and validates one stored reminder; [index] for error messages. */
        fun fromJson(obj: JsonValue.JsonObject, index: Int): Reminder {
            val noteId = obj.str("noteId")?.trim().orEmpty()
            if (noteId.isEmpty()) throw ValidationException("reminder entry #$index is missing its noteId")
            val dueRaw = obj.str("due")
            val due = parseStoredInstant(dueRaw)
                ?: throw ValidationException("reminder entry #$index has an unreadable due date '$dueRaw'")
            val priority = (obj.long("priority") ?: DEFAULT_PRIORITY.toLong()).toInt()
            val done = obj.bool("done") ?: false
            val createdAt = parseStoredInstant(obj.str("createdAt")) ?: Instant.now()
            val doneAt = parseStoredInstant(obj.str("doneAt"))
            return Reminder(
                noteId = noteId,
                due = due,
                priority = priority.coerceIn(PRIORITY_RANGE.first, PRIORITY_RANGE.last),
                done = done,
                createdAt = createdAt,
                doneAt = doneAt
            )
        }

        private fun parseStoredInstant(raw: String?): Instant? {
            if (raw.isNullOrBlank()) return null
            return try {
                Instant.parse(raw.trim())
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/** Aggregate reminder counters used by `notably stats`. */
data class ReminderCounts(
    val pending: Int,
    val overdue: Int,
    val done: Int
)

/**
 * Due-date reminders on top of the note store.
 *
 * The service owns `reminders.json` (through [Workspace], so every write is
 * atomic and backed up like the other registries). Note references given by
 * the user are resolved through [NoteService.resolve]; reminders reference
 * notes by their full id, so prefix edits and renames never detach them.
 *
 * ## Due-date grammar
 *
 * See [parseDue] for the accepted expressions. Date-only forms land at the
 * end of the local day (23:59) so "today" never means "already overdue at
 * breakfast". Relative offsets keep the current clock time.
 */
class RemindService(
    private val workspace: Workspace,
    private val notes: NoteService
) {

    private val reminders: MutableList<Reminder>
    private val zone: ZoneId = ZoneId.systemDefault()

    init {
        val stored = workspace.loadReminderEntries()
        reminders = try {
            stored.mapIndexed { index, obj -> Reminder.fromJson(obj, index) }.toMutableList()
        } catch (e: ValidationException) {
            throw StorageException("reminder registry is invalid: ${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Every reminder, pending first, each group sorted by due date. */
    fun all(): List<Reminder> =
        reminders.sortedWith(compareBy({ it.done }, { it.due.toEpochMilli() }))

    /** Pending reminders, soonest first. */
    fun pending(): List<Reminder> =
        reminders.filter { it.isPending }.sortedBy { it.due.toEpochMilli() }

    /** Completed reminders (kept for history until cleared). */
    fun completed(): List<Reminder> =
        reminders.filter { !it.isPending }.sortedBy { it.doneAt?.toEpochMilli() ?: 0L }

    /** Pending reminders whose due date has already passed. */
    fun overdue(now: Instant = Instant.now()): List<Reminder> =
        pending().filter { it.due.isBefore(now) }

    /** Pending reminders falling due between [now] and [days] days later. */
    fun dueWithin(days: Int, now: Instant = Instant.now()): List<Reminder> {
        val horizon = now.plus(Duration.ofDays(days.toLong().coerceAtLeast(0)))
        return pending().filter { !it.due.isBefore(now) && !it.due.isAfter(horizon) }
    }

    /** All reminders (any state) attached to one note id. */
    fun forNote(noteId: String): List<Reminder> = reminders.filter { it.noteId == noteId }

    /** Aggregate counters for the stats view. */
    fun counts(now: Instant = Instant.now()): ReminderCounts = ReminderCounts(
        pending = reminders.count { it.isPending },
        overdue = reminders.count { it.isOverdue(now) },
        done = reminders.count { !it.isPending }
    )

    // ------------------------------------------------------------------
    // Mutations (each persists through the workspace)
    // ------------------------------------------------------------------

    /**
     * Attaches a pending reminder to the note behind [ref] (id, unique
     * prefix or title substring). The due date uses the [parseDue] grammar.
     * A note may hold at most one pending reminder at a time.
     *
     * @throws NotFoundException        when [ref] matches no note
     * @throws ValidationException      on a bad due expression, priority or
     *                                  when a pending reminder already exists
     */
    fun add(
        ref: String,
        dueExpression: String,
        priority: Int = Reminder.DEFAULT_PRIORITY,
        now: Instant = Instant.now()
    ): Reminder {
        val note = notes.resolve(ref)
        val existing = forNote(note.id).firstOrNull { it.isPending }
        if (existing != null) {
            throw ValidationException(
                "note ${note.id} already has a pending reminder due " +
                    describeDue(existing.due, now) + " — complete or remove it first"
            )
        }
        val due = parseDue(dueExpression, now)
        val reminder = Reminder(
            noteId = note.id,
            due = due,
            priority = priority.coerceIn(Reminder.PRIORITY_RANGE.first, Reminder.PRIORITY_RANGE.last),
            done = false,
            createdAt = now,
            doneAt = null
        )
        reminders.add(reminder)
        save()
        return reminder
    }

    /** Marks the pending reminder of [ref] as done. Returns the updated row. */
    fun complete(ref: String, now: Instant = Instant.now()): Reminder {
        val index = pendingIndexOf(ref)
        val done = reminders[index].copy(done = true, doneAt = now)
        reminders[index] = done
        save()
        return done
    }

    /**
     * Reopens the most recent completed reminder of [ref]. A due date in the
     * past is lifted to [now] so a reopened reminder is never instantly
     * overdue.
     */
    fun reopen(ref: String, now: Instant = Instant.now()): Reminder {
        val note = notes.resolve(ref)
        val index = reminders.indexOfLast { it.noteId == note.id && !it.isPending }
        if (index < 0) {
            throw NotFoundException("note '${note.title}' (${note.id}) has no completed reminder to reopen")
        }
        val currentDue = reminders[index].due
        val opened = reminders[index].copy(
            done = false,
            doneAt = null,
            due = if (currentDue.isAfter(now)) currentDue else now
        )
        reminders[index] = opened
        save()
        return opened
    }

    /**
     * Pushes the pending reminder of [ref] to [duration] from [now] —
     * deliberately "from now" (not "from the old due date") so repeated
     * snoozes stay predictable.
     */
    fun snooze(ref: String, duration: Duration, now: Instant = Instant.now()): Reminder {
        if (duration.isZero || duration.isNegative) {
            throw ValidationException("snooze duration must be positive")
        }
        val index = pendingIndexOf(ref)
        val snoozed = reminders[index].copy(due = now.plus(duration))
        reminders[index] = snoozed
        save()
        return snoozed
    }

    /** Removes every reminder of [ref]; returns how many rows were deleted. */
    fun remove(ref: String): Int {
        val note = notes.resolve(ref)
        val doomed = reminders.filter { it.noteId == note.id }
        if (doomed.isEmpty()) {
            throw NotFoundException("note '${note.title}' (${note.id}) has no reminders")
        }
        reminders.removeAll(doomed)
        save()
        return doomed.size
    }

    /** Purges all completed reminders; returns how many rows were deleted. */
    fun clearDone(): Int {
        val doomed = reminders.filter { !it.isPending }
        if (doomed.isEmpty()) return 0
        reminders.removeAll(doomed)
        save()
        return doomed.size
    }

    /** Persists the registry (rarely needed — mutations auto-save). */
    fun save() {
        workspace.saveReminderEntries(reminders.map { it.toJson() })
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Index of the single pending reminder of [ref], or NotFound. */
    private fun pendingIndexOf(ref: String): Int {
        val note = notes.resolve(ref)
        val index = reminders.indexOfFirst { it.noteId == note.id && it.isPending }
        if (index < 0) {
            throw NotFoundException("note '${note.title}' (${note.id}) has no pending reminder")
        }
        return index
    }

    companion object {
        /** Local clock time assigned to date-only due expressions. */
        val END_OF_DAY: LocalTime = LocalTime.of(23, 59)

        /** Display labels for priorities 0..3. */
        val PRIORITY_LABELS = listOf("low", "normal", "high", "urgent")

        /** Label for a (clamped) priority value. */
        fun priorityLabel(priority: Int): String =
            PRIORITY_LABELS[priority.coerceIn(Reminder.PRIORITY_RANGE.first, Reminder.PRIORITY_RANGE.last)]

        /** Weekday names (with common abbreviations) → [DayOfWeek]. */
        val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
            "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
            "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY,
            "thurs" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY
        )

        /** Relative-offset form: optional `+`, amount, optional unit. */
        private val OFFSET = Regex("^\\+?(\\d+)(m|h|d|w)?$", RegexOption.IGNORE_CASE)

        /** Hard sanity cap for offsets (~1000 years in minutes). */
        private const val MAX_OFFSET_AMOUNT = 550_000_000L

        /**
         * Parses a human due-date expression into an [Instant].
         *
         * | Expression            | Meaning                                    |
         * |-----------------------|--------------------------------------------|
         * | `now`                 | exactly [now]                              |
         * | `today`               | today at 23:59 local time                  |
         * | `tomorrow`            | tomorrow at 23:59 local time               |
         * | `next week`           | 7 days from today at 23:59                 |
         * | `next month`          | same day next month at 23:59               |
         * | `mon` … `sunday`      | next occurrence of that weekday at 23:59   |
         * | `+30`, `+30m`         | 30 minutes from [now]                      |
         * | `+6h`                 | 6 hours from [now]                         |
         * | `+3d`                 | 3 days from [now] (clock time preserved)   |
         * | `+2w`                 | 14 days from [now]                         |
         * | `2026-03-01`          | that date at 23:59 local time              |
         * | `2026-03-01T09:30`    | that local date-time                       |
         * | `2026-03-01 09:30`    | same, with a space instead of `T`          |
         *
         * All keyword forms are case-insensitive; surrounding whitespace is
         * ignored.
         *
         * @throws ValidationException for anything the grammar does not accept
         */
        fun parseDue(expression: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant {
            val raw = expression.trim()
            if (raw.isEmpty()) throw ValidationException("due date must not be blank")
            val lowered = raw.lowercase()
            return when {
                lowered == "now" -> now
                lowered == "today" ->
                    LocalDate.now(zone).atTime(END_OF_DAY).atZone(zone).toInstant()
                lowered == "tomorrow" ->
                    LocalDate.now(zone).plusDays(1).atTime(END_OF_DAY).atZone(zone).toInstant()
                lowered == "next week" ->
                    LocalDate.now(zone).plusDays(7).atTime(END_OF_DAY).atZone(zone).toInstant()
                lowered == "next month" ->
                    LocalDate.now(zone).plusMonths(1).atTime(END_OF_DAY).atZone(zone).toInstant()
                WEEKDAYS.containsKey(lowered) -> nextWeekday(WEEKDAYS.getValue(lowered), zone)
                else -> parseExplicit(raw, lowered, now, zone)
            }
        }

        /**
         * Parses a relative duration: `30m`, `4h`, `2d`, `1w`, or a bare
         * number of minutes; a leading `+` is accepted and ignored.
         *
         * @throws ValidationException for malformed, zero or oversized input
         */
        fun parseDuration(expression: String): Duration {
            val raw = expression.trim().lowercase()
            if (raw.isEmpty()) throw ValidationException("duration must not be blank")
            val match = OFFSET.matchEntire(raw)
                ?: throw ValidationException("cannot parse duration '$raw' — use forms like 30m, 4h, 2d or 1w")
            val amount = match.groupValues[1].toLongOrNull()
                ?: throw ValidationException("cannot parse duration '$raw'")
            if (amount <= 0L) throw ValidationException("duration must be greater than zero")
            if (amount > MAX_OFFSET_AMOUNT) throw ValidationException("duration '$raw' is too large")
            return when (match.groupValues[2]) {
                "", "m" -> Duration.ofMinutes(amount)
                "h" -> Duration.ofHours(amount)
                "d" -> Duration.ofDays(amount)
                "w" -> Duration.ofDays(amount * 7)
                else -> throw ValidationException("invalid duration unit in '$raw' (use m, h, d or w)")
            }
        }

        /**
         * Human description of a due instant relative to [now]: `overdue 2d`,
         * `overdue 5h`, `today`, `tomorrow`, `in 3d` and, for anything more
         * than a week out, the plain ISO date.
         */
        fun describeDue(due: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
            if (due.isBefore(now)) {
                val overdueDays = Duration.between(due, now).toDays()
                if (overdueDays <= 0) {
                    val hours = Duration.between(due, now).toHours()
                    return "overdue ${hours}h"
                }
                return "overdue ${overdueDays}d"
            }
            val today = LocalDate.ofInstant(now, zone)
            val date = LocalDate.ofInstant(due, zone)
            return when {
                date == today -> "today"
                date == today.plusDays(1) -> "tomorrow"
                Duration.between(now, due).toDays() <= 7 -> "in ${Duration.between(now, due).toDays()}d"
                else -> date.toString()
            }
        }

        /** Next occurrence of [day] strictly after today, at 23:59 local. */
        private fun nextWeekday(day: DayOfWeek, zone: ZoneId): Instant {
            var date = LocalDate.now(zone).plusDays(1)
            while (date.dayOfWeek != day) date = date.plusDays(1)
            return date.atTime(END_OF_DAY).atZone(zone).toInstant()
        }

        /** Offset and ISO forms handled outside the keyword table. */
        private fun parseExplicit(raw: String, lowered: String, now: Instant, zone: ZoneId): Instant {
            if (OFFSET.matchEntire(lowered) != null) {
                return now.plus(parseDuration(raw))
            }
            parseIsoDateTime(raw, zone)?.let { return it }
            throw ValidationException(
                "cannot parse due date '$raw' — use today, tomorrow, +3d, a weekday, " +
                    "YYYY-MM-DD or YYYY-MM-DDTHH:MM"
            )
        }

        /** ISO date / date-time forms; `null` when neither matches. */
        private fun parseIsoDateTime(raw: String, zone: ZoneId): Instant? {
            val normalized = raw.replace(' ', 'T')
            return try {
                LocalDateTime.parse(normalized).atZone(zone).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(normalized).atTime(END_OF_DAY).atZone(zone).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }
}
