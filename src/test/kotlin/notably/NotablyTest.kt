package notably

import java.nio.file.Files
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.system.exitProcess
import notably.model.Note
import notably.model.NoteStatus
import notably.model.Notebook
import notably.model.NotFoundException
import notably.model.SearchFilters
import notably.model.Tag
import notably.model.UsageException
import notably.model.ValidationException
import notably.model.CryptoException
import notably.service.CryptoService
import notably.service.ExportService
import notably.service.NoteService
import notably.service.NotebookService
import notably.service.RemindService
import notably.service.SearchService
import notably.storage.AtomicFile
import notably.storage.Json
import notably.storage.JsonParseException
import notably.storage.JsonParser
import notably.storage.JsonValue
import notably.storage.Workspace
import notably.storage.jsonObject
import notably.storage.jsonString
import notably.ui.Ansi
import notably.ui.MarkdownRenderer
import notably.ui.Renderer
import notably.ui.Sparkline

/**
 * Plain `main()`-based test harness for notably — no JUnit, no third-party
 * dependencies (the project is stdlib-only by design).
 *
 * Run with:  `gradle selftest`  (or compile both source sets and run
 * `notably.NotablyTestKt`). Every expectation is hand-computed against the
 * documented behavior of the unit under test; any failure prints the section
 * and check name and the process exits with code 1.
 */

private var passed = 0
private var failed = 0
private var currentSection = ""

/** Starts a new test section (used as the failure prefix). */
fun section(name: String) {
    currentSection = name
}

/** Records one boolean expectation. */
fun check(name: String, condition: Boolean) {
    if (condition) {
        passed++
    } else {
        failed++
        println("FAIL [$currentSection] $name")
    }
}

/** Records that [block] must throw exactly [expected]. */
fun expectThrows(name: String, expected: Class<out Throwable>, block: () -> Unit) {
    val thrown = try {
        block()
        null
    } catch (t: Throwable) {
        t
    }
    check("$name (throws ${expected.simpleName})", thrown != null && expected.isInstance(thrown))
}

/** Fixed clock for deterministic date tests. */
private val NOW: Instant = Instant.parse("2026-03-10T12:00:00Z")
private val UTC: ZoneId = ZoneId.of("UTC")

fun main() {
    Ansi.init(explicit = true)

    jsonModelTests()
    jsonParserTests()
    modelTests()
    searchServiceTests()
    regexSearchTests()
    cryptoTests()
    atomicFileTests()
    workspaceTests()
    noteServiceTests()
    notebookServiceTests()
    remindServiceTests()
    sparklineTests()
    markdownRendererTests()
    rendererTests()

    println()
    println("$passed check(s) passed, $failed failed")
    if (failed > 0) exitProcess(1)
}

// ---------------------------------------------------------------------------
// storage/Json — object model, writer, deepEquals
// ---------------------------------------------------------------------------

private fun jsonModelTests() {
    section("json model")
    val obj = Json.parse("""{"a": 1, "b": [true, null, "x"], "c": {"d": 2.5}}""").asObjectOrNull()!!
    check("long accessor", obj.long("a") == 1L)
    check("stringList skips non-strings", obj.stringList("b") == listOf("x"))
    check("nested object", obj.obj("c")?.double("d") == 2.5)
    check("bool on non-bool is null", obj.bool("a") == null)
    check("has/keys", obj.has("a") && !obj.has("z") && obj.keys.size == 3)

    val pretty = Json.write(Json.parse("""{"a":[]}"""), pretty = true)
    check("pretty shape", pretty == "{\n  \"a\": []\n}")
    val escaped = Json.write(Json.parse("\"quote\\\" back\\\\ nl\\n\""), pretty = false)
    check("escape round-trip", escaped == "\"quote\\\" back\\\\ nl\\n\"")
    check("jsonNumber raw kept", JsonValue.JsonNumber("1.50").toLong() == 1L && JsonValue.JsonNumber("1.50").raw == "1.50")
    check("integral check", JsonValue.JsonNumber("12").isIntegral() && !JsonValue.JsonNumber("1e3").isIntegral())

    val a = Json.parse("""{"a":1,"b":2}""")
    val b = Json.parse("""{"b":2,"a":1}""")
    check("deepEquals ignores object order", Json.deepEquals(a, b))
    check("deepEquals respects array order", !Json.deepEquals(Json.parse("[1,2]"), Json.parse("[2,1]")))
    check("deepEquals null handling", !Json.deepEquals(null, a) && Json.deepEquals(null, null))

    val builder = jsonObject("k" to jsonString("v"))
    builder.put("k2", JsonValue.JsonBoolean(true))
    check("builder put returns this", builder["k2"] != null && builder.fields.size == 2)
}

// ---------------------------------------------------------------------------
// storage/JsonParser — grammar errors, depth guard, number shapes
// ---------------------------------------------------------------------------

private fun jsonParserTests() {
    section("json parser")
    expectThrows("malformed document", JsonParseException::class.java) { Json.parse("{not json") }
    expectThrows("trailing content", JsonParseException::class.java) { Json.parse("{} x") }
    expectThrows("unterminated string", JsonParseException::class.java) { Json.parse("\"abc") }
    expectThrows("leading zero number", JsonParseException::class.java) { Json.parse("01") }

    try {
        Json.parse("{\n  \"a\": }\n")
        check("error carries line/column", false)
    } catch (e: JsonParseException) {
        check("error carries line/column", e.line == 2 && e.column >= 1)
    }

    expectThrows("depth guard", JsonParseException::class.java) { Json.parse("[".repeat(200)) }
    val deep = Json.parse("[".repeat(JsonParser.MAX_DEPTH - 1) + "]".repeat(JsonParser.MAX_DEPTH - 1))
    check("depth guard just below limit", deep is JsonValue.JsonArray)

    check("shape 12", JsonParser.isValidNumberShape("12"))
    check("shape -0", JsonParser.isValidNumberShape("-0"))
    check("shape 1.5", JsonParser.isValidNumberShape("1.5"))
    check("shape 0.5", JsonParser.isValidNumberShape("0.5"))
    check("shape 1.5e3", JsonParser.isValidNumberShape("1.5e3"))
    check("shape 1.", !JsonParser.isValidNumberShape("1."))
    check("shape .5", !JsonParser.isValidNumberShape(".5"))
    check("shape 1e", !JsonParser.isValidNumberShape("1e"))
    check("shape 01", !JsonParser.isValidNumberShape("01"))
}

// ---------------------------------------------------------------------------
// model — Note / Notebook / Tag validation and normalization
// ---------------------------------------------------------------------------

private fun modelTests() {
    section("model")
    check("tag normalize trims+lowercases", Tag.normalize("  Kotlin  ") == "kotlin")
    check("tag normalize collapses whitespace", Tag.normalize("a \t b") == "a b")
    expectThrows("tag blank", ValidationException::class.java) { Tag.normalize("   ") }
    expectThrows("tag too long", ValidationException::class.java) { Tag.normalize("x".repeat(33)) }
    check("tag normalizeAll dedupes", Tag.normalizeAll(listOf("A", "a", "b")).size == 2)
    expectThrows("tag per-note cap", ValidationException::class.java) {
        Tag.normalizeAll((1..33).map { "tag$it" })
    }

    val note = Note.create(
        title = "  Hello, Kotlin World! 123  ",
        body = "body",
        notebook = "Work",
        tags = listOf("Kotlin", "kotlin", "jvm"),
        pinned = false,
        color = "#AABBCC",
        id = "testnote001"
    )
    check("title trimmed", note.title == "Hello, Kotlin World! 123")
    check("tags normalized+deduped", note.tags == setOf("kotlin", "jvm"))
    check("color normalized", note.color == "#aabbcc")
    check("slug", note.slug() == "hello-kotlin-world-123")
    check("header line", note.headerLine() == "[testnote001] Hello, Kotlin World! 123")
    check("hasTag normalizes", note.hasTag("JVM") && !note.hasTag("rust"))

    expectThrows("blank title", ValidationException::class.java) { Note.create(title = "   ", id = "x1") }
    expectThrows("bad color", ValidationException::class.java) { Note.create(title = "t", color = "#12", id = "x2") }

    val t = NOW
    expectThrows("pinned needs pinnedAt", ValidationException::class.java) {
        Note(
            id = "p1", title = "t", body = "", notebook = "Inbox", tags = emptySet(),
            pinned = true, status = NoteStatus.ACTIVE, color = null,
            createdAt = t, updatedAt = t, pinnedAt = null, trashedAt = null
        )
    }
    expectThrows("trashed needs trashedAt", ValidationException::class.java) {
        Note(
            id = "p2", title = "t", body = "", notebook = "Inbox", tags = emptySet(),
            pinned = false, status = NoteStatus.TRASHED, color = null,
            createdAt = t, updatedAt = t, pinnedAt = null, trashedAt = null
        )
    }

    val roundTrip = Note.fromJson(note.toJson())
    check("note json round-trip", roundTrip == note)

    check("notebook key normalize", Notebook.normalizeKey("  Multi  Space ") == "multi space")
    val nb = Notebook.create("  Projects ")
    check("notebook create trims", nb.name == "Projects")
    expectThrows("notebook blank", ValidationException::class.java) { Notebook.create("  ") }
    expectThrows("notebook slash", ValidationException::class.java) { Notebook.create("a/b") }
    val nbRound = Notebook.fromJson(nb.toJson())
    check("notebook json round-trip", nbRound.name == nb.name && nbRound.key == nb.key)

    expectThrows("usage sort id", UsageException::class.java) { notably.model.ListingSort.fromId("nope") }
    check("usage sort fallback", notably.model.ListingSort.fromId(null) == notably.model.ListingSort.UPDATED)

    val filters = SearchFilters(notebook = "Work", tags = setOf("kotlin"))
    check("filters match", filters.matches(note))
    check("filters reject", !filters.matches(note.copy(id = "otherid", tags = setOf("jvm"))))
}

// ---------------------------------------------------------------------------
// service/SearchService — scoring, snippets, suggestions
// ---------------------------------------------------------------------------

private fun searchServiceTests() {
    section("search")
    val search = SearchService()
    val prepared = search.prepare("Kotlin, kotlin!")
    check("tokens distinct", prepared.tokens == listOf("kotlin"))

    val noteA = Note.create(
        title = "Kotlin coroutines",
        body = "Structured concurrency with coroutines on the JVM",
        notebook = "Work",
        tags = listOf("kotlin", "jvm"),
        id = "aaaaaaaaaaaa"
    )
    val noteB = Note.create(title = "Unrelated", body = "nothing here", notebook = "Work", id = "bbbbbbbbbbbb")

    val (score, matched) = search.score(noteA, prepared)
    check("matched terms", matched == setOf("kotlin"))
    check("score = title 5 + tag 3 + recency", score >= 8.0 && score <= 9.5)

    val (scoreB, matchedB) = search.score(noteB, prepared)
    check("no hit scores zero", scoreB == 0.0 && matchedB.isEmpty())

    val full = search.prepare("kotlin coroutines")
    val (scoreFull, matchedFull) = search.score(noteA, full)
    check("phrase bonus included", matchedFull.contains("kotlin coroutines") && scoreFull >= 20.0 && scoreFull <= 21.5)

    check("recency now", search.recencyBoost(Instant.now(), Instant.now()) == 1.5)
    check("recency after a year", search.recencyBoost(Instant.now().minus(Duration.ofDays(365)), Instant.now()) == 0.0)

    val snippet = search.snippet(noteA, listOf("coroutines"))
    check("snippet wraps matches", snippet.contains(SearchService.MARK_START + "coroutines" + SearchService.MARK_END))

    val hits = search.search(listOf(noteA, noteB), "jvm")
    check("search single hit", hits.size == 1 && hits[0].note.id == noteA.id)

    check("empty query no hits", search.search(listOf(noteA), "!!").isEmpty())
    check("suggestTags ordering", search.suggestTags(listOf("kotlinx", "jvm", "kotlin"), "kotlin") == listOf("kotlin", "kotlinx"))
}

// ---------------------------------------------------------------------------
// service/SearchService — regex mode
// ---------------------------------------------------------------------------

private fun regexSearchTests() {
    section("regex search")
    val search = SearchService()
    val noteA = Note.create(
        title = "Kotlin coroutines",
        body = "Structured concurrency with coroutines on the JVM",
        notebook = "Work",
        tags = listOf("kotlin", "jvm"),
        id = "aaaaaaaaaaaa"
    )
    val noteB = Note.create(title = "Unrelated", body = "nothing here", notebook = "Work", id = "bbbbbbbbbbbb")
    val notes = listOf(noteA, noteB)

    val caseInsensitive = search.searchRegex(notes, "\\bJVM\\b", ignoreCase = true)
    check("regex counts title+tag+body", caseInsensitive.size == 1)
    check("regex match count 2 (tag jvm + body JVM)", caseInsensitive[0].matchCount == 2)
    check("regex score 2.5 + 1.0", caseInsensitive[0].score == 3.5)
    check("regex snippet highlighted", caseInsensitive[0].snippet.contains(SearchService.MARK_START + "JVM" + SearchService.MARK_END))

    val caseSensitive = search.searchRegex(notes, "\\bJVM\\b", ignoreCase = false)
    check("regex case-sensitive body only", caseSensitive[0].matchCount == 1 && caseSensitive[0].score == 1.0)

    check("regex no match", search.searchRegex(notes, "zzz-?nope").isEmpty())
    check("regex limit respected", search.searchRegex(notes, "e", limit = 1).size <= 1)
    expectThrows("regex invalid pattern", ValidationException::class.java) { search.compileRegex("(") }
    expectThrows("regex blank pattern", ValidationException::class.java) { search.compileRegex("  ") }
    check("regex compile ok", search.compileRegex("^Kotlin").toString().isNotEmpty())
}

// ---------------------------------------------------------------------------
// service/CryptoService — roundtrips, verifier, PBKDF2
// ---------------------------------------------------------------------------

private fun cryptoTests() {
    section("crypto")
    val secret = "hello notably, this is the note index".toByteArray(Charsets.UTF_8)

    val xorEnvelope = CryptoService.encrypt(secret, "correct horse".toCharArray(), forceXor = true)
    val xorBack = CryptoService.decrypt(xorEnvelope, "correct horse".toCharArray())
    check("xor roundtrip", xorBack.contentEquals(secret))
    expectThrows("xor wrong passphrase", CryptoException::class.java) {
        CryptoService.decrypt(xorEnvelope, "wrong horse".toCharArray())
    }

    val envelope = CryptoService.encrypt(secret, "another-pass".toCharArray())
    check("auto roundtrip", CryptoService.decrypt(envelope, "another-pass".toCharArray()).contentEquals(secret))
    val parsed = Json.parse(envelope).asObjectOrNull()!!
    check("envelope format", parsed.str("format") == CryptoService.FORMAT_ID)
    check("envelope version", parsed.long("version") == 1L)
    check("envelope kdf", parsed.str("kdf") == CryptoService.KDF_ALGO)
    check("envelope cipher known", parsed.str("cipher") == CryptoService.CIPHER_GCM || parsed.str("cipher") == CryptoService.CIPHER_XOR)
    check("envelope iterations", parsed.long("iterations") == CryptoService.ITERATIONS.toLong())
    val decoded = CryptoService.EncryptedEnvelope.fromJson(parsed)
    check("envelope struct parse", decoded.salt.isNotEmpty() && decoded.data.isNotEmpty())

    expectThrows("tampered payload", CryptoException::class.java) {
        val tampered = JsonValue.JsonObject(LinkedHashMap(parsed.fields))
        tampered.put("data", jsonString("!!!not base64!!!"))
        CryptoService.decrypt(Json.write(tampered, pretty = false), "another-pass".toCharArray())
    }

    val salt = ByteArray(16) { it.toByte() }
    val key1 = CryptoService.pbkdf2("pass".toCharArray(), salt, 1000, 128)
    val key2 = CryptoService.pbkdf2("pass".toCharArray(), salt, 1000, 128)
    val key3 = CryptoService.pbkdf2("pass".toCharArray(), salt.copyOf().also { it[0] = 9 }, 1000, 128)
    check("pbkdf2 deterministic", key1.contentEquals(key2))
    check("pbkdf2 salt sensitive", !key1.contentEquals(key3))
    check("pbkdf2 length", key1.size == 16)

    check("verifier is 16 bytes", CryptoService.verifier("abc".toCharArray(), salt).size == 16)
    check("verifier differs per passphrase",
        !CryptoService.verifier("abc".toCharArray(), salt).contentEquals(CryptoService.verifier("abd".toCharArray(), salt)))
    check("encodedSize overhead", CryptoService.encodedSize(0) == 320 && CryptoService.encodedSize(300) == 720)
}

// ---------------------------------------------------------------------------
// storage/AtomicFile — atomic writes, backup rotation, recovery
// ---------------------------------------------------------------------------

private fun atomicFileTests() {
    section("atomic file")
    val dir = Files.createTempDirectory("notably-atomic").toFile()
    try {
        val store = AtomicFile(java.io.File(dir, "data.json"), java.io.File(dir, "bak"), 2)
        check("absent read", store.readText() == null)
        check("absent recovering", store.readTextRecovering() == null)
        store.writeText("one")
        check("read back", store.readText() == "one")
        check("exists", store.exists())
        store.writeText("two")
        store.writeText("three")
        check("backups rotated", store.backups().size == 2)

        val main = store.readTextRecovering()
        check("recovering reads main", main?.content == "three" && !main.usedBackup)

        store.delete()
        val fallback = store.readTextRecovering()
        check("backup fallback newest first", fallback?.usedBackup == true && fallback.content == "two")
    } finally {
        dir.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------
// storage/Workspace — layout, config, reminders registry
// ---------------------------------------------------------------------------

private fun workspaceTests() {
    section("workspace")
    val root = Files.createTempDirectory("notably-ws").toFile()
    try {
        val ws = Workspace(root)
        check("not initialized", !ws.isInitialized())
        ws.initWorkspace("Inbox")
        check("initialized", ws.isInitialized())
        expectThrows("double init", notably.model.WorkspaceAlreadyInitializedException::class.java) {
            ws.initWorkspace("Inbox")
        }
        check("empty note index", ws.loadNotes().isEmpty())
        check("reminders seeded", ws.loadReminderEntries().isEmpty())
        check("not locked", !ws.isLocked())

        val config = ws.loadConfig()
        check("default config", config.defaultNotebook == "Inbox" && config.prettyJson)
        ws.saveConfig(config.copy(editor = "vim", maxBackups = 3))
        val reloaded = ws.loadConfig()
        check("config round-trip", reloaded.editor == "vim" && reloaded.maxBackups == 3)

        val colors = ws.assignTagColors(setOf("kotlin", "jvm"))
        check("tag colors assigned", colors["kotlin"] != null && colors.size >= 2)
        check("tag colors stable", ws.assignTagColors(setOf("kotlin"))["kotlin"] == colors["kotlin"])

        val id = ws.newUniqueId(emptySet())
        check("id shape", id.length == Workspace.ID_LENGTH && id.all { it in Workspace.ID_ALPHABET })
        check("id avoids taken", !ws.newUniqueId(setOf(id)).equals(id))

        val entry = jsonObject(
            "noteId" to jsonString("abc123"),
            "due" to jsonString("2026-04-01T00:00:00Z")
        )
        ws.saveReminderEntries(listOf(entry))
        val loaded = ws.loadReminderEntries()
        check("reminder registry round-trip", loaded.size == 1 && loaded[0].str("noteId") == "abc123")
    } finally {
        root.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------
// service/NoteService — CRUD, lifecycle, resolution
// ---------------------------------------------------------------------------

private fun noteServiceTests() {
    section("note service")
    val root = Files.createTempDirectory("notably-notes").toFile()
    try {
        val ws = Workspace(root)
        ws.initWorkspace("Inbox")
        val notes = NoteService(ws)
        NotebookService(ws, notes).ensureExists("Work")

        val created = notes.create("Kotlin coroutines", "structured concurrency", "Work", listOf("kotlin", "jvm"), pinned = false)
        val flows = notes.create("Kotlin flows", "cold streams", "Work", emptyList(), pinned = false)
        check("created ids", created.id != flows.id && created.notebook == "Work")
        check("notebook auto-seeded", ws.loadNotebooks().any { it.name == "Work" })

        check("resolve by full id", notes.resolve(created.id).id == created.id)
        check("resolve by unique title", notes.resolve("coroutines").id == created.id)
        expectThrows("resolve empty", NotFoundException::class.java) { notes.resolve("  ") }
        expectThrows("resolve ambiguous title", NotFoundException::class.java) { notes.resolve("kotlin") }
        expectThrows("resolve unknown", NotFoundException::class.java) { notes.resolve("zzzzzz") }
        check("suggest finds something", notes.suggest("coroutines").isNotEmpty())

        val archived = notes.setStatus(created, NoteStatus.ARCHIVED)
        check("archived", archived.status == NoteStatus.ARCHIVED && archived.trashedAt == null)
        val back = notes.setStatus(archived, NoteStatus.ACTIVE)
        check("unarchived", back.status == NoteStatus.ACTIVE)

        val trashed = notes.setStatus(flows, NoteStatus.TRASHED)
        check("trashed stamps time", trashed.status == NoteStatus.TRASHED && trashed.trashedAt != null)
        check("purge removes", notes.purgeTrash(0) == 1)
        check("purged gone", notes.all().none { it.id == flows.id })

        val pinned = notes.setPinned(created, true)
        check("pinned", pinned.pinned && pinned.pinnedAt != null)
        check("pin idempotent", notes.setPinned(pinned, true) === pinned)

        val tagged = notes.addTags(created, listOf("urgent", "kotlin"))
        check("addTags merges", tagged.tags == setOf("kotlin", "jvm", "urgent"))
        val untagged = notes.removeTags(tagged, listOf("urgent"))
        check("removeTags", untagged.tags == setOf("kotlin", "jvm"))
        expectThrows("too many tags", ValidationException::class.java) {
            notes.addTags(created, (1..40).map { "bulk$it" })
        }

        val edited = notes.updateContent(created, title = "Kotlin coroutines deep dive")
        check("update bumps title", edited.title == "Kotlin coroutines deep dive")
        check("unchanged content is identity", notes.updateContent(edited) === edited)

        check("totals", notes.totals().total == 1 && notes.totals().pinned == 1)
        check("countByNotebook", notes.countByNotebook()[created.notebookKey] == 1)
        check("tagsInUse", notes.tagsInUse()["kotlin"] == 1)
        check("activityByMonth spans", notes.activityByMonth(6).size == 6)
    } finally {
        root.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------
// service/NotebookService — registry lifecycle
// ---------------------------------------------------------------------------

private fun notebookServiceTests() {
    section("notebook service")
    val root = Files.createTempDirectory("notably-nb").toFile()
    try {
        val ws = Workspace(root)
        ws.initWorkspace("Inbox")
        val notes = NoteService(ws)
        val notebooks = NotebookService(ws, notes)

        val projects = notebooks.create("Projects", "side quests", "#ff0000")
        expectThrows("duplicate notebook", ValidationException::class.java) { notebooks.create("projects") }
        check("exists by key", notebooks.exists("PROJECTS"))
        check("find by key", notebooks.find(" projects ")?.name == "Projects")

        val note = notes.create("Design doc", "", "Projects", emptyList(), pinned = false)
        val moved = notes.changeNotebook(note, projects.name)
        check("changeNotebook same is identity", notes.changeNotebook(moved, "Projects") === moved)

        notebooks.rename("Projects", "Side Projects")
        check("rename cascades", notes.resolve(note.id).notebook == "Side Projects")
        expectThrows("rename to taken", ValidationException::class.java) { notebooks.rename("Side Projects", "INBOX") }

        expectThrows("remove non-empty", ValidationException::class.java) { notebooks.remove("Side Projects") }
        notebooks.create("Empty")
        check("remove empty", notebooks.remove("Empty").name == "Empty")
        check("usageCounts", notebooks.usageCounts()["side projects"] == 1)
        check("ensureExists idempotent", notebooks.ensureExists("inbox").name == "Inbox")
        check("all sorted", notebooks.all().map { it.name } == notebooks.all().map { it.name }.sortedBy { it.lowercase() })
        check("describe", notebooks.describe("Inbox", "default bucket").description == "default bucket")
        check("ensureDefault", notebooks.ensureDefault().key == "inbox")
    } finally {
        root.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------
// service/RemindService — grammar, lifecycle, persistence
// ---------------------------------------------------------------------------

private fun remindServiceTests() {
    section("remind service")
    check("parse offset d", RemindService.parseDue("+3d", NOW, UTC) == Instant.parse("2026-03-13T12:00:00Z"))
    check("parse offset hours", RemindService.parseDue("+6h", NOW, UTC) == Instant.parse("2026-03-10T18:00:00Z"))
    check("parse offset minutes", RemindService.parseDue("+90", NOW, UTC) == Instant.parse("2026-03-10T13:30:00Z"))
    check("parse offset weeks", RemindService.parseDue("+1w", NOW, UTC) == Instant.parse("2026-03-17T12:00:00Z"))
    check("parse now", RemindService.parseDue("now", NOW, UTC) == NOW)
    check("parse iso date is end of day",
        RemindService.parseDue("2026-06-01", NOW, UTC) == LocalDateTime.of(2026, 6, 1, 23, 59).atZone(UTC).toInstant())
    check("parse iso datetime",
        RemindService.parseDue("2026-06-01T09:30", NOW, UTC) == Instant.parse("2026-06-01T09:30:00Z"))
    check("parse space datetime",
        RemindService.parseDue("2026-06-01 09:30", NOW, UTC) == Instant.parse("2026-06-01T09:30:00Z"))
    val sunday = RemindService.parseDue("sunday", NOW, UTC)
    check("parse weekday is future sunday", sunday.isAfter(NOW) && LocalDate.ofInstant(sunday, UTC).dayOfWeek == DayOfWeek.SUNDAY)
    expectThrows("parse blank", ValidationException::class.java) { RemindService.parseDue("   ", NOW, UTC) }
    expectThrows("parse garbage", ValidationException::class.java) { RemindService.parseDue("whenever!", NOW, UTC) }
    expectThrows("parse zero offset", ValidationException::class.java) { RemindService.parseDue("+0d", NOW, UTC) }

    check("duration 30m", RemindService.parseDuration("30m") == Duration.ofMinutes(30))
    check("duration 2d", RemindService.parseDuration("2d") == Duration.ofDays(2))
    check("duration 1w", RemindService.parseDuration("1w") == Duration.ofDays(7))
    check("duration bare minutes", RemindService.parseDuration("+45") == Duration.ofMinutes(45))
    expectThrows("duration zero", ValidationException::class.java) { RemindService.parseDuration("0d") }
    expectThrows("duration garbage", ValidationException::class.java) { RemindService.parseDuration("abc") }
    expectThrows("duration bad unit", ValidationException::class.java) { RemindService.parseDuration("5x") }

    check("describe overdue days", RemindService.describeDue(NOW.minus(Duration.ofDays(2)), NOW) == "overdue 2d")
    check("describe overdue hours", RemindService.describeDue(NOW.minus(Duration.ofHours(5)), NOW) == "overdue 5h")
    check("describe today", RemindService.describeDue(NOW, NOW) == "today")
    check("describe in days", RemindService.describeDue(NOW.plus(Duration.ofDays(3)), NOW) == "in 3d")
    check("priority label clamp", RemindService.priorityLabel(99) == "urgent" && RemindService.priorityLabel(-1) == "low")

    val root = Files.createTempDirectory("notably-remind").toFile()
    try {
        val ws = Workspace(root)
        ws.initWorkspace("Inbox")
        val notes = NoteService(ws)
        val notebooks = NotebookService(ws, notes)
        notebooks.ensureExists("Work")
        val note = notes.create("Review PR", "needs a second look", "Work", emptyList(), pinned = false)
        val reminds = RemindService(ws, notes)

        val reminder = reminds.add(note.id, "+1d", 3, NOW)
        check("reminder added", reminder.isPending && reminder.due == NOW.plus(Duration.ofDays(1)))
        expectThrows("duplicate pending", ValidationException::class.java) { reminds.add(note.id, "+2d", 1, NOW) }
        expectThrows("add to unknown note", NotFoundException::class.java) { reminds.add("missingnote", "+1d", now = NOW) }
        check("dueWithin", reminds.dueWithin(1, NOW).size == 1)
        check("overdue none yet", reminds.overdue(NOW).isEmpty())
        check("counts pending", reminds.counts(NOW).pending == 1)

        val done = reminds.complete(note.id, NOW.plus(Duration.ofHours(2)))
        check("complete stamps doneAt", done.done && done.doneAt == NOW.plus(Duration.ofHours(2)))
        check("pending now empty", reminds.pending().isEmpty())
        check("counts done", reminds.counts(NOW).done == 1)
        expectThrows("complete again", NotFoundException::class.java) { reminds.complete(note.id, NOW) }
        expectThrows("snooze done", ValidationException::class.java) { reminds.snooze(note.id, Duration.ofHours(1), NOW) }

        val reopened = reminds.reopen(note.id, NOW)
        check("reopen keeps future due", !reopened.done && reopened.due == NOW.plus(Duration.ofDays(1)))

        val snoozed = reminds.snooze(note.id, Duration.ofHours(4), NOW)
        check("snooze counts from now", snoozed.due == NOW.plus(Duration.ofHours(4)))

        val later = NOW.plus(Duration.ofDays(2))
        check("overdue later", reminds.overdue(later).map { it.noteId } == listOf(note.id))
        check("forNote", reminds.forNote(note.id).size == 1)

        check("remove", reminds.remove(note.id) == 1)
        expectThrows("remove nothing left", NotFoundException::class.java) { reminds.remove(note.id) }

        reminds.add(note.id, "+2h", 0, NOW)
        reminds.complete(note.id, NOW)
        check("clearDone", reminds.clearDone() == 1)
        check("registry empty", reminds.all().isEmpty())
    } finally {
        root.deleteRecursively()
    }
}

// ---------------------------------------------------------------------------
// ui/Sparkline — sparkline, heatmap, streaks
// ---------------------------------------------------------------------------

private fun sparklineTests() {
    section("sparkline")
    check("empty series", Sparkline.render(emptyList()) == "")
    check("all zero series", Sparkline.render(listOf(0, 0)) == "▁▁")
    check("three levels", Sparkline.render(listOf(1, 2, 3)) == "▃▅█")
    check("block rounding", Sparkline.block(1, 3) == "▃" && Sparkline.block(2, 3) == "▅" && Sparkline.block(3, 3) == "█")
    check("block zero", Sparkline.block(0, 5) == "▁")
    check("block degenerate max", Sparkline.block(4, 0) == "▁")
    check("length preserved", Sparkline.render(listOf(5, 1, 9, 2)).length == 4)

    val today = LocalDate.of(2026, 3, 10)
    val streaks = Sparkline.streaks(setOf(today, today.minusDays(1), today.minusDays(4)), today)
    check("streaks current+longest", streaks.current == 2 && streaks.longest == 2)
    check("streak grace day", Sparkline.streaks(setOf(today.minusDays(1)), today).current == 1)
    check("streak empty", Sparkline.streaks(emptySet(), today) == Sparkline.Streaks(0, 0))
    check("streak longest run", Sparkline.streaks(setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(5)), today).longest == 3)

    val heat = Sparkline.heatmap(mapOf(today to 5), 4, today)
    check("heatmap has 7 rows", heat.size == 7)
    check("heatmap column count", heat.all { it.length == 4 })
    val todayRow = today.dayOfWeek.value - 1
    check("heatmap today is peak", heat[todayRow].last() == '█')
    check("heatmap empty cells", Sparkline.heatmap(emptyMap(), 2, today).all { it == "··" })
    check("heatCell ramp", Sparkline.heatCell(0, 10) == "·" && Sparkline.heatCell(1, 10) == "▁" &&
        Sparkline.heatCell(5, 10) == "▄" && Sparkline.heatCell(9, 10) == "█")
}

// ---------------------------------------------------------------------------
// ui/MarkdownRenderer — subset rendering, plain preview
// ---------------------------------------------------------------------------

private fun markdownRendererTests() {
    section("markdown renderer")
    check("bold inline", MarkdownRenderer.render("**hi**", colors = false) == "hi")
    check("code span", MarkdownRenderer.render("`code`", colors = false) == "code")
    check("no escapes when colors off", !MarkdownRenderer.render("# Head\n\n- item", colors = false).contains('\u001B'))
    check("heading stripped in render", MarkdownRenderer.render("## Head", colors = false) == "Head")
    check("unordered list bullet", MarkdownRenderer.render("- item", colors = false) == "• item")
    check("task list box", MarkdownRenderer.render("- [x] done", colors = false) == "☑ done")
    check("task list open", MarkdownRenderer.render("- [ ] open", colors = false) == "☐ open")

    check("plainPreview strips markup", MarkdownRenderer.plainPreview("# Head\n\n**bold** text") == "Head bold text")
    check("plainPreview link text", MarkdownRenderer.plainPreview("[kotlin](https://kotlinlang.org)") == "kotlin")
    check("plainPreview truncates", MarkdownRenderer.plainPreview("x".repeat(200), maxChars = 140).length == 140)
    check("plainPreview empty", MarkdownRenderer.plainPreview("") == "")
}

// ---------------------------------------------------------------------------
// ui/Renderer + Ansi — tables, formats, bars, colors
// ---------------------------------------------------------------------------

private fun rendererTests() {
    section("renderer")
    val r = Renderer(colors = false)
    val table = r.table("T", listOf("A", "BB"), listOf(listOf("x", "2"), listOf("yyy", "10")))
    check("table line count", table.lines().size == 5)
    check("table title first", table.lines()[0] == "T")
    check("table widths", table.lines()[1] == "A    BB")

    check("bytes KB", r.bytes(2048) == "2.0 KB")
    check("bytes B", r.bytes(512) == "512 B")
    check("bytes MB", r.bytes(3 * 1024 * 1024) == "3.0 MB")
    check("score format", r.score(12.34) == " 12.3")
    check("relative time", r.relativeTime(Instant.now().minus(Duration.ofHours(1)), Instant.now()) == "1h ago")
    check("relative future", r.relativeTime(Instant.now().plus(Duration.ofHours(2)), Instant.now()) == "in 2h")
    check("bar full", r.bar(10, 10, 10) == "█".repeat(10))
    check("bar half", r.bar(5, 10, 10) == "█".repeat(5))
    check("bar zero", r.bar(0, 10, 10) == "·")
    check("bar empty max", r.bar(5, 0, 10) == "·")
    check("keyValue pads", r.keyValue("id", "x").startsWith("id") && r.keyValue("id", "x").endsWith("x"))
    check("flagsOf pinned", r.flagsOf(true, NoteStatus.ACTIVE) == "★")
    check("flagsOf trashed", r.flagsOf(false, NoteStatus.TRASHED) == "t")
    check("tagList sorted", r.tagList(setOf("b", "a")) == "[a] [b]")
    check("statusBadge empty for active", r.statusBadge(NoteStatus.ACTIVE) == "")
    check("formatInstant pattern", r.formatInstant(Instant.parse("2026-03-10T12:00:00Z"), "yyyy").isNotEmpty())

    check("ansi paint", Ansi.paint("x", Ansi.RED) == Ansi.RED + "x" + Ansi.RESET)
    check("ansi strip", Ansi.strip(Ansi.red("abc")) == "abc")
    check("ansi visible width", Ansi.visibleWidth(Ansi.bold("abc")) == 3)
    check("ansi paint empty", Ansi.paint("", Ansi.RED) == "")

    check("exit code contract", notably.model.ExitCodes.OK == 0 && notably.model.ExitCodes.CRYPTO == 7)
    check("about banner", notably.model.About.NAME == "notably")
    expectThrows("export bad format", notably.model.ExportException::class.java) {
        ExportService(Workspace(java.io.File("/tmp/never-used-by-tests"))).normalizeFormat("pdf")
    }
}
