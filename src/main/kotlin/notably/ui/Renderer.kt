package notably.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ANSI escape handling with automatic deactivation. Colors are disabled when
 * any of the following holds (first match wins):
 *
 *  1. the caller passed an explicit `--color` / `--no-color` flag;
 *  2. `NO_COLOR` is set in the environment (https://no-color.org);
 *  3. `TERM=dumb`;
 *  4. otherwise colors stay on.
 */
object Ansi {
    private const val ESC = "\u001B"

    const val RESET = ESC + "[" + "0m"
    const val BOLD = ESC + "[" + "1m"
    const val DIM = ESC + "[" + "2m"
    const val ITALIC = ESC + "[" + "3m"
    const val UNDERLINE = ESC + "[" + "4m"
    const val RED = ESC + "[" + "31m"
    const val GREEN = ESC + "[" + "32m"
    const val YELLOW = ESC + "[" + "33m"
    const val BLUE = ESC + "[" + "34m"
    const val MAGENTA = ESC + "[" + "35m"
    const val CYAN = ESC + "[" + "36m"

    /** Full SGR sequences used by snippet highlighting (self-contained). */
    const val YELLOW_SGR = ESC + "[" + "1;33m"
    const val RESET_SGR = ESC + "[" + "0m"

    // Raw SGR bodies exposed for MarkdownRenderer's local paint() helper.
    const val ESC_PREFIX = ESC
    const val BOLD_CODE = "[" + "1m"
    const val DIM_CODE = "[" + "2m"
    const val ITALIC_CODE = "[" + "3m"
    const val UNDERLINE_CODE = "[" + "4m"
    const val STRIKE_CODE = "[" + "9m"
    const val GREEN_CODE = "[" + "32m"
    const val YELLOW_CODE = "[" + "33m"
    const val CYAN_CODE = "[" + "36m"
    const val MAGENTA_CODE = "[" + "35m"
    const val BOLD_CYAN_CODE = "[" + "1;36m"

    private var enabledState = true

    /** Current global color state (read once by the renderer). */
    val enabled: Boolean get() = enabledState

    /**
     * Initializes the global state. [explicit] comes from CLI flags and
     * always wins over environment heuristics.
     */
    fun init(explicit: Boolean?) {
        enabledState = when {
            explicit != null -> explicit
            System.getenv("NO_COLOR") != null -> false
            System.getenv("TERM") == "dumb" -> false
            else -> true
        }
    }

    /** Wraps [text] in [code]…[RESET] when colors are enabled. */
    fun paint(text: String, code: String): String =
        if (enabledState && text.isNotEmpty()) ESC + code + text + RESET else text

    fun bold(text: String): String = paint(text, BOLD)
    fun dim(text: String): String = paint(text, DIM)
    fun italic(text: String): String = paint(text, ITALIC)
    fun underline(text: String): String = paint(text, UNDERLINE)
    fun red(text: String): String = paint(text, RED)
    fun green(text: String): String = paint(text, GREEN)
    fun yellow(text: String): String = paint(text, YELLOW)
    fun blue(text: String): String = paint(text, BLUE)
    fun magenta(text: String): String = paint(text, MAGENTA)
    fun cyan(text: String): String = paint(text, CYAN)

    private val STRIP = Regex("\u001B\\[[0-9;]*m")

    /** Removes every SGR sequence from [text] (used for width math). */
    fun strip(text: String): String = STRIP.replace(text, "")

    /** Visible width of [text], ignoring ANSI escape bytes. */
    fun visibleWidth(text: String): Int = strip(text).length
}

/** Column alignment inside [Renderer.table]. */
enum class Align { LEFT, RIGHT }

/**
 * Terminal presentation layer: tables, badges, relative timestamps, byte
 * sizes, bar charts and search-snippet highlighting.
 *
 * All cell content is measured on its *visible* width (ANSI sequences
 * excluded) so colored columns align with plain ones. Cells that would
 * overflow the column cap are truncated; a truncated colored cell falls back
 * to its plain-text form to avoid cutting escape sequences mid-way.
 */
class Renderer(private val colors: Boolean = Ansi.enabled) {

    // ------------------------------------------------------------------
    // Generic table
    // ------------------------------------------------------------------

    /**
     * Renders an ASCII table. [headers] and [rows] must have the same
     * column count; short rows are padded with empty cells. Returns the
     * table without a trailing newline.
     */
    fun table(
        title: String?,
        headers: List<String>,
        rows: List<List<String>>,
        aligns: List<Align> = emptyList(),
        maxWidth: Int = DEFAULT_COLUMN_WIDTH
    ): String {
        require(headers.isNotEmpty()) { "table needs at least one column" }
        val columnCount = headers.size
        val normalized = rows.map { row ->
            (0 until columnCount).map { column -> row.getOrElse(column) { "" } }
        }
        val widths = IntArray(columnCount) { c -> headers[c].length }
        for (row in normalized) {
            for (c in 0 until columnCount) {
                widths[c] = maxOf(widths[c], Ansi.visibleWidth(row[c]))
            }
        }
        for (c in 0 until columnCount) widths[c] = minOf(widths[c], maxWidth)

        val fitted = normalized.map { row ->
            (0 until columnCount).map { c -> truncateCell(row[c], widths[c]) }
        }

        val sb = StringBuilder()
        if (!title.isNullOrBlank()) {
            sb.append(if (colors) Ansi.bold(title) else title).append('\n')
        }
        val headerLine = (0 until columnCount).joinToString(GUTTER) { c ->
            pad(headers[c], widths[c], alignAt(aligns, c))
        }
        sb.append(if (colors) Ansi.bold(headerLine) else headerLine).append('\n')
        sb.append((0 until columnCount).joinToString(GUTTER) { c -> "-".repeat(widths[c]) }).append('\n')
        for (row in fitted) {
            sb.append((0 until columnCount).joinToString(GUTTER) { c ->
                pad(row[c], widths[c], alignAt(aligns, c))
            }).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    private fun alignAt(aligns: List<Align>, column: Int): Align = aligns.getOrElse(column) { Align.LEFT }

    private fun pad(cell: String, width: Int, align: Align): String {
        val padding = (width - Ansi.visibleWidth(cell)).coerceAtLeast(0)
        val spaces = " ".repeat(padding)
        return when (align) {
            Align.LEFT -> cell + spaces
            Align.RIGHT -> spaces + cell
        }
    }

    private fun truncateCell(cell: String, width: Int): String {
        if (Ansi.visibleWidth(cell) <= width) return cell
        val plain = Ansi.strip(cell)
        return if (plain.length != cell.length || Ansi.visibleWidth(cell) > width) {
            truncatePlain(plain, width)
        } else {
            truncatePlain(cell, width)
        }
    }

    private fun truncatePlain(text: String, width: Int): String =
        if (width <= 1) text.take(width) else text.take(width - 1) + "…"

    // ------------------------------------------------------------------
    // Domain-specific renderings
    // ------------------------------------------------------------------

    /** Flags column: `★` pinned, `a` archived, `t` trashed. */
    fun flagsOf(pinned: Boolean, status: notably.model.NoteStatus): String {
        var flags = ""
        if (pinned) flags += "★"
        when (status) {
            notably.model.NoteStatus.ARCHIVED -> flags += "a"
            notably.model.NoteStatus.TRASHED -> flags += "t"
            notably.model.NoteStatus.ACTIVE -> {}
        }
        return flags
    }

    /** Renders a tag set as `[tag] [tag]` (cyan when colors are on). */
    fun tagList(tags: Collection<String>): String =
        if (tags.isEmpty()) "" else tags.sorted().joinToString(" ") { if (colors) Ansi.cyan("[$it]") else "[$it]" }

    /** Status badge for non-active notes; empty string for active notes. */
    fun statusBadge(status: notably.model.NoteStatus): String = when (status) {
        notably.model.NoteStatus.ACTIVE -> ""
        notably.model.NoteStatus.ARCHIVED -> if (colors) Ansi.yellow("[archived]") else "[archived]"
        notably.model.NoteStatus.TRASHED -> if (colors) Ansi.red("[trashed]") else "[trashed]"
    }

    /** `key : value` meta line with a fixed-width key column. */
    fun keyValue(key: String, value: String): String {
        val label = key.padEnd(KEY_COLUMN_WIDTH)
        return if (colors) "${Ansi.dim(label)} $value" else "$label $value"
    }

    /** Relative timestamp: `just now`, `5m ago`, `3h ago`, `2d ago`, ... */
    fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
        val delta = Duration.between(instant, now)
        val past = !delta.isNegative
        val seconds = kotlin.math.abs(delta.seconds)
        val unit = when {
            seconds < 45L -> null
            seconds < 3600L -> "${seconds / 60L}m"
            seconds < 86_400L -> "${seconds / 3_600L}h"
            seconds < 86_400L * 7 -> "${seconds / 86_400L}d"
            seconds < 86_400L * 30 -> "${seconds / (86_400L * 7)}w"
            seconds < 86_400L * 365 -> "${seconds / (86_400L * 30)}mo"
            else -> "${seconds / (86_400L * 365)}y"
        } ?: return "just now"
        return if (past) "$unit ago" else "in $unit"
    }

    /** Absolute timestamp formatted with the workspace date format. */
    fun formatInstant(instant: Instant, pattern: String): String {
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
        } catch (_: IllegalArgumentException) {
            DateTimeFormatter.ofPattern(notably.storage.WorkspaceConfig.DEFAULT_DATE_FORMAT).withZone(ZoneId.systemDefault())
        }
        return formatter.format(instant)
    }

    /** Human byte size: `512 B`, `2.0 KB`, `3.4 MB`, ... */
    fun bytes(count: Long): String {
        if (count < 1024) return "$count B"
        val kb = count / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
        return String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0)
    }

    /**
     * Horizontal bar for stats charts: scaled to [max] within [width]
     * columns, using full/partial block glyphs. Zero values render as `·`.
     */
    fun bar(value: Long, max: Long, width: Int): String {
        if (max <= 0 || value <= 0) return "·"
        val exact = value.toDouble() / max.toDouble() * width
        val full = exact.toInt().coerceIn(0, width)
        val fraction = exact - full
        var out = "█".repeat(full)
        if (full < width) {
            out += when {
                fraction >= 0.875 -> "▉"
                fraction >= 0.75 -> "▊"
                fraction >= 0.625 -> "▋"
                fraction >= 0.5 -> "▌"
                fraction >= 0.375 -> "▍"
                fraction >= 0.25 -> "▎"
                fraction >= 0.125 -> "▏"
                else -> ""
            }
        }
        return if (out.isEmpty()) "▏" else out
    }

    /**
     * Converts search snippet markers (`U+0001`…`U+0002`) into bold/yellow
     * ANSI runs, or strips them when [colors] is false.
     */
    fun highlight(text: String): String {
        if (!colors) return text.replace(Scriptum.START, "").replace(Scriptum.END, "")
        return text
            .replace(Scriptum.START, Ansi.YELLOW_SGR)
            .replace(Scriptum.END, Ansi.RESET_SGR)
    }

    /** Score cell formatting used by `search`: `12.3` with one decimal. */
    fun score(value: Double): String = String.format(java.util.Locale.ROOT, "%5.1f", value)

    /** Dim helper that respects the renderer's own color switch. */
    fun dim(text: String): String = if (colors) Ansi.dim(text) else text

    /** Bold helper that respects the renderer's own color switch. */
    fun bold(text: String): String = if (colors) Ansi.bold(text) else text

    /** Green helper that respects the renderer's own color switch. */
    fun green(text: String): String = if (colors) Ansi.green(text) else text

    /** Red helper that respects the renderer's own color switch. */
    fun red(text: String): String = if (colors) Ansi.red(text) else text

    /** Yellow helper that respects the renderer's own color switch. */
    fun yellow(text: String): String = if (colors) Ansi.yellow(text) else text

    /** Cyan helper that respects the renderer's own color switch. */
    fun cyan(text: String): String = if (colors) Ansi.cyan(text) else text

    companion object {
        const val DEFAULT_COLUMN_WIDTH = 48
        const val KEY_COLUMN_WIDTH = 12
        const val GUTTER = "  "

        /** Indirection so the highlight markers live with the search service. */
        private object Scriptum {
            val START = notably.service.SearchService.MARK_START
            val END = notably.service.SearchService.MARK_END
        }
    }
}
