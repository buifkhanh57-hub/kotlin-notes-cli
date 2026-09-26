package notably.commands

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import notably.AppContext
import notably.model.CryptoException
import notably.model.ExitCodes
import notably.model.ListingSort
import notably.model.LockedException
import notably.model.NoteStatus
import notably.model.UsageException
import notably.model.ValidationException
import notably.model.WorkspaceAlreadyInitializedException
import notably.service.CryptoService
import notably.storage.Json
import notably.storage.jsonArray
import notably.ui.Sparkline

/**
 * Utility commands: workspace bootstrap (`init`), data portability
 * (`export`), insight reports (`stats`) and at-rest encryption (`lock`).
 */
class UtilCommands(private val ctx: AppContext) {

    // ------------------------------------------------------------------
    // notably init
    // ------------------------------------------------------------------

    /** `notably init [--notebook Inbox]` — create a fresh workspace. */
    fun cmdInit(args: List<String>): Int {
        val a = CliArgs(args)
        val notebookName = a.value("notebook", "n") ?: notably.model.Notebook.DEFAULT_NAME
        val workspace = ctx.workspace
        try {
            workspace.initWorkspace(notebookName.trim())
        } catch (e: WorkspaceAlreadyInitializedException) {
            throw e
        }
        val home = workspace.root.path
        println(ctx.renderer.green("✓") + " workspace initialized at ${ctx.renderer.bold(home)}")
        println()
        println("  $home")
        println("  ├── notes.json          note index")
        println("  ├── notebooks.json      notebook registry")
        println("  ├── meta.json           schema version + tag colors")
        println("  ├── config.json         preferences (editor, date format, ...)")
        println("  ├── reminders.json      due-date reminders")
        println("  ├── backups/            rolling backups of every file")
        println("  └── exports/            default export destination")
        println()
        println("Try your first note:")
        println("  notably add \"Welcome to notably\" --tag meta")
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // notably export
    // ------------------------------------------------------------------

    /**
     * `notably export [--format bundle|md|csv|json] [--out PATH] [--force]`
     * plus the standard list filters (`--notebook`, `--tag`, `--archived`,
     * `--trash`, `--all`, `--pinned`, `--limit`). Active notes are exported
     * by default; the target defaults to a timestamped path in `exports/`.
     */
    fun cmdExport(args: List<String>): Int {
        val a = CliArgs(args)
        val exporter = ctx.exporter
        val format = exporter.normalizeFormat(a.value("format", "f"))
        val out = a.value("out", "o")
        val force = a.bool("force")
        val filters = NoteCommands(ctx).filtersFromArgs(a, defaultLimit = 10_000)
        val sort = ListingSort.fromId(a.value("sort"), ListingSort.UPDATED)
        val notes = ctx.notes.filterSorted(filters, sort, pinnedFirst = false)
        if (notes.isEmpty()) {
            println(ctx.renderer.dim("nothing to export${filters.describe()}"))
            return ExitCodes.OK
        }
        val target = exporter.defaultTarget(format, out)
        val result = when (format) {
            notably.service.ExportService.FORMAT_BUNDLE -> exporter.exportMarkdownBundle(notes, target, force)
            notably.service.ExportService.FORMAT_MD -> exporter.exportSingleMarkdown(notes, target, force)
            notably.service.ExportService.FORMAT_CSV -> exporter.exportCsv(notes, target, force)
            notably.service.ExportService.FORMAT_JSON -> exporter.exportJson(notes, target, force)
            else -> throw UsageException("unknown export format '$format'")
        }
        val kind = if (result.bundled) "bundle directory" else "file"
        println(ctx.renderer.green("✓") + " exported ${result.count} note(s) as ${result.format} $kind")
        println("  target: ${ctx.renderer.bold(result.target.path)}")
        println("  size:   ${ctx.renderer.bytes(result.bytes)}")
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // notably stats
    // ------------------------------------------------------------------

    /** `notably stats [--months N]` — per-notebook table, tag cloud, activity. */
    fun cmdStats(args: List<String>): Int {
        val a = CliArgs(args)
        val months = a.value("months")?.toIntOrNull()?.coerceIn(1, 60) ?: 12
        val r = ctx.renderer
        val totals = ctx.notes.totals()

        println(r.bold("Workspace statistics"))
        println(r.dim("─".repeat(52)))

        // 1. per-notebook table
        val notebooks = ctx.notebooks.all()
        val counts = ctx.notes.countByNotebook()
        val rows = notebooks.map { notebook ->
            val inNotebook = ctx.notes.all().filter { it.notebookKey == notebook.key }
            listOf(
                notebook.name,
                (counts[notebook.key] ?: 0).toString(),
                inNotebook.count { it.status == NoteStatus.ACTIVE }.toString(),
                inNotebook.count { it.status == NoteStatus.ARCHIVED }.toString(),
                inNotebook.count { it.status == NoteStatus.TRASHED }.toString(),
                inNotebook.count { it.pinned }.toString()
            )
        }
        println(
            r.table(
                title = "Notebooks",
                headers = listOf("Name", "Total", "Active", "Archived", "Trashed", "Pinned"),
                rows = rows,
                aligns = listOf(
                    notably.ui.Renderer.Align.LEFT, notably.ui.Renderer.Align.RIGHT, notably.ui.Renderer.Align.RIGHT,
                    notably.ui.Renderer.Align.RIGHT, notably.ui.Renderer.Align.RIGHT, notably.ui.Renderer.Align.RIGHT
                )
            )
        )

        // 2. tag cloud data
        val usage = ctx.notes.tagsInUse()
        if (usage.isNotEmpty()) {
            println()
            println(r.bold("Top tags"))
            val max = usage.values.maxOrNull() ?: 0
            usage.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(TAG_ROWS)
                .forEach { (tag, count) ->
                    val bar = r.bar(count.toLong(), max.toLong(), 24)
                    println("  ${tag.padEnd(20)} ${bar.padEnd(26)} $count")
                }
        }

        // 3. activity by month
        println()
        println(r.bold("Notes created — last $months month(s)"))
        val activity = ctx.notes.activityByMonth(months)
        val maxMonth = (activity.maxOfOrNull { it.second } ?: 0).toLong()
        for ((month, count) in activity) {
            val label = month.toString()
            val bar = r.bar(count.toLong(), maxMonth, 28)
            println("  $label  ${bar.padEnd(30)} $count")
        }

        // 3b. compact sparkline + writing streaks (visual summary of the same data)
        println()
        println(r.bold("Activity sparkline"))
        println("  " + Sparkline.render(activity.map { it.second }))
        println("  " + r.dim("${activity.first().first} → ${activity.last().first}"))
        val zone = ZoneId.systemDefault()
        val activeDays = ctx.notes.all()
            .filter { it.status != NoteStatus.TRASHED }
            .map { LocalDate.ofInstant(it.createdAt, zone) }
            .toSet()
        val streaks = Sparkline.streaks(activeDays, LocalDate.now(zone))
        println(r.keyValue("streak", "current ${streaks.current}d · longest ${streaks.longest}d"))

        // 3c. optional day heatmap: `notably stats --heat [weeks]`
        val heatRaw = a.value("heat")
        val heatWeeks = when {
            heatRaw == null -> 0
            heatRaw.isEmpty() -> DEFAULT_HEAT_WEEKS
            else -> heatRaw.toIntOrNull()?.coerceIn(1, 26)
                ?: throw UsageException("--heat expects a number of weeks (1..26)")
        }
        if (heatWeeks > 0) {
            println()
            println(r.bold("Creation heatmap — last $heatWeeks week(s) (Mon → Sun, oldest → newest)"))
            val perDay = ctx.notes.all()
                .filter { it.status != NoteStatus.TRASHED }
                .groupBy { LocalDate.ofInstant(it.createdAt, zone) }
                .mapValues { entry -> entry.value.size }
            for (line in Sparkline.heatmap(perDay, heatWeeks, LocalDate.now(zone))) {
                println("  $line")
            }
            println(r.dim("  · none   ▁ low   ▄ medium   ▆ high   █ peak"))
        }

        // 4. totals
        println()
        println(r.bold("Totals"))
        println(r.keyValue("notes", "${totals.total} (${totals.active} active, ${totals.archived} archived, ${totals.trashed} trashed)"))
        println(r.keyValue("pinned", totals.pinned.toString()))
        println(r.keyValue("tags", usage.size.toString()))
        println(r.keyValue("words", totals.words.toString()))
        val remindCounts = ctx.reminds.counts(Instant.now())
        println(r.keyValue("reminders", "${remindCounts.pending} pending (${remindCounts.overdue} overdue, ${remindCounts.done} done)"))
        val busiest = activity.maxByOrNull { it.second }
        if (busiest != null && busiest.second > 0) {
            println(r.keyValue("busiest", "${busiest.first} (${busiest.second} notes)"))
        }
        val oldest = ctx.notes.all().minByOrNull { it.createdAt }
        if (oldest != null) {
            println(r.keyValue("oldest", "'${oldest.title}' from ${r.relativeTime(oldest.createdAt)}"))
        }
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // notably lock / unlock / status / explain
    // ------------------------------------------------------------------

    /**
     * `notably lock lock|unlock|status|explain [--force-xor]`.
     *
     * Locking replaces `notes.json` with an encrypted envelope (see
     * [CryptoService] for the scheme and its honest limitations) and deletes
     * plaintext copies including rolling backups. Unlocking validates the
     * decrypted payload is a proper note array before writing it back.
     */
    fun cmdLock(args: List<String>): Int {
        val a = CliArgs(args)
        val sub = a.requirePositional(0, "lock subcommand", "lock | unlock | status | explain")
        return when (sub.lowercase()) {
            "lock", "on" -> cmdLockLock(a)
            "unlock", "off" -> cmdLockUnlock()
            "status" -> cmdLockStatus()
            "explain" -> cmdLockExplain()
            else -> throw UsageException("unknown lock subcommand '$sub' (use: lock, unlock, status, explain)")
        }
    }

    private fun cmdLockLock(a: CliArgs): Int {
        val workspace = ctx.workspace
        if (workspace.isLocked()) {
            throw LockedException("notes are already locked — nothing to do")
        }
        workspace.requireInitialized()
        val plain = workspace.notesStore.readTextRecovering()?.content ?: jsonArray(emptyList()).let { Json.write(it, pretty = true) }
        // Sanity check: refuse to encrypt something we could not read back.
        validateNoteArray(plain)

        val passphrase = readNewPassphrase()
        val envelope = CryptoService.encrypt(plain.toByteArray(Charsets.UTF_8), passphrase, forceXor = a.bool("force-xor"))
        workspace.lockNotes(envelope)
        println(ctx.renderer.green("✓") + " notes locked (${CryptoService.KDF_ALGO}, ${CryptoService.ITERATIONS} iterations)")
        println("  plaintext notes.json and its backups were removed")
        println(ctx.renderer.yellow("!") + " remember: exports, editor temp files and any copies you made stay plaintext")
        println("  unlock with: notably lock unlock")
        return ExitCodes.OK
    }

    private fun cmdLockUnlock(): Int {
        val workspace = ctx.workspace
        if (!workspace.isLocked()) {
            throw ValidationException("notes are not locked — nothing to unlock")
        }
        val envelope = workspace.encryptedNotesFile.readText(Charsets.UTF_8)
        val passphrase = ctx.prompt.askSecret("Passphrase")
        val plainBytes = CryptoService.decrypt(envelope, passphrase)
        val plain = String(plainBytes, Charsets.UTF_8)
        validateNoteArray(plain)
        workspace.unlockNotes(plain)
        println(ctx.renderer.green("✓") + " notes unlocked — workspace is readable again")
        return ExitCodes.OK
    }

    private fun cmdLockStatus(): Int {
        val workspace = ctx.workspace
        if (!workspace.isLocked()) {
            println("notes: " + ctx.renderer.green("unlocked"))
            println("  encryption available: notably lock lock")
            return ExitCodes.OK
        }
        println("notes: " + ctx.renderer.yellow("locked"))
        val envelope = try {
            Json.parse(workspace.encryptedNotesFile.readText(Charsets.UTF_8)).asObjectOrNull()
        } catch (_: Exception) {
            null
        }
        if (envelope == null) {
            println("  " + ctx.renderer.red("envelope unreadable or corrupted"))
        } else {
            println("  format:     ${envelope.str("format")} v${envelope.long("version")}")
            println("  cipher:     ${envelope.str("cipher")}")
            println("  kdf:        ${envelope.str("kdf")} × ${envelope.long("iterations")}")
            println("  locked at:  ${envelope.str("createdAt")}")
        }
        return ExitCodes.OK
    }

    /** Prints the honest scheme documentation (also mirrored in the README). */
    private fun cmdLockExplain(): Int {
        println(
            """
            |Encryption scheme used by `notably lock`
            |
            |  KDF      PBKDF2-HMAC-SHA256 (${CryptoService.ITERATIONS} iterations, ${CryptoService.KEY_BITS}-bit key)
            |  Cipher   ${CryptoService.CIPHER_GCM} (128-bit auth tag) when the JVM provides 256-bit AES,
            |           otherwise a SHA-256 keystream XOR fallback (${CryptoService.CIPHER_XOR})
            |  Storage  notes.json.enc — self-describing JSON envelope with salt, IV,
            |           passphrase verifier and base64 ciphertext
            |
            |Honest limitations
            |
            |  * PBKDF2 is not memory-hard; GPUs brute-force it faster than Argon2.
            |  * The XOR fallback is a home-made stream cipher: no integrity, not vetted.
            |  * A passphrase verifier is stored, enabling unlimited offline guessing.
            |  * Locking protects notes.json at rest only — exports, editor temp files
            |    and previously rotated backups can still hold plaintext.
            """.trimMargin()
        )
        return ExitCodes.OK
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun readNewPassphrase(): CharArray {
        val first = ctx.prompt.askSecret("Set passphrase (min ${CryptoService.MIN_PASSPHRASE_LENGTH} chars)")
        if (first.size < CryptoService.MIN_PASSPHRASE_LENGTH) {
            throw ValidationException("passphrase must be at least ${CryptoService.MIN_PASSPHRASE_LENGTH} characters")
        }
        val second = ctx.prompt.askSecret("Confirm passphrase")
        if (!first.contentEquals(second)) {
            second.fill(' ')
            throw ValidationException("passphrases do not match")
        }
        return first
    }

    /** Verifies [plain] parses as a JSON array (of objects). */
    private fun validateNoteArray(plain: String) {
        try {
            val parsed = Json.parse(plain)
            if (parsed.asArrayOrNull() == null) throw CryptoException("decrypted payload is not a note array")
        } catch (e: notably.storage.JsonParseException) {
            throw CryptoException("payload is not valid JSON: ${e.message}", e)
        }
    }

    companion object {
        /** Rows shown in the `stats` tag cloud. */
        const val TAG_ROWS = 10

        /** Default span of `notably stats --heat` (no explicit week count). */
        const val DEFAULT_HEAT_WEEKS = 12
    }
}
