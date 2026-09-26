package notably.ui

import java.time.LocalDate

/**
 * Terminal activity visualizations used by `notably stats`.
 *
 *  - [render]  — single-row sparkline (▁▂▃▄▅▆▇█) of a value series;
 *  - [heatmap] — contribution-style day grid (columns = weeks, rows = Mon..Sun);
 *  - [streaks] — current and longest run of consecutive active days.
 *
 * All functions are pure and deterministic: rendering never consults the
 * clock (callers pass `today` explicitly), so output is stable, testable and
 * safe to snapshot. Block glyphs are drawn from the same set for both the
 * sparkline (full 8-level ramp) and the heatmap (quartile ramp).
 */
object Sparkline {

    /** Block glyphs from the lowest to the highest level (8 levels). */
    const val BLOCKS = "▁▂▃▄▅▆▇█"

    /** Glyph for empty heatmap cells (and the zero-width bar). */
    const val EMPTY = "·"

    /** Upper bound for the heatmap window (one year). */
    const val MAX_WEEKS = 53

    /**
     * Renders [values] as a sparkline: the maximum value maps to the tallest
     * block (█), zero maps to the baseline block (▁), and levels in between
     * are rounded to the nearest glyph. A series without any positive value
     * renders as baseline blocks. Each column is exactly one character wide;
     * an empty series renders as an empty string.
     */
    fun render(values: List<Int>): String {
        if (values.isEmpty()) return ""
        val max = values.max()
        if (max <= 0) return BLOCKS[0].toString().repeat(values.size)
        val sb = StringBuilder(values.size)
        for (value in values) {
            sb.append(block(value, max))
        }
        return sb.toString()
    }

    /** Single block glyph for [value] against [max] (nearest-level rounding). */
    fun block(value: Int, max: Int): String {
        if (max <= 0 || value <= 0) return BLOCKS[0].toString()
        val level = ((value.toLong() * (BLOCKS.length - 1) + max / 2) / max).toInt()
        return BLOCKS[level.coerceIn(0, BLOCKS.length - 1)].toString()
    }

    /**
     * Renders a heatmap of the [weeks] weeks ending on [today].
     *
     * Returns exactly 7 lines, one per weekday (Monday first); each column is
     * one week, oldest on the left, aligned so that [today] falls in the last
     * column of its weekday row. Cells use a quartile ramp: [EMPTY], ▁, ▄,
     * ▆, █ — scaled against the busiest day inside the window.
     *
     * @throws IllegalArgumentException when [weeks] is not positive
     */
    fun heatmap(counts: Map<LocalDate, Int>, weeks: Int, today: LocalDate): List<String> {
        require(weeks > 0) { "heatmap needs at least one week" }
        val safeWeeks = weeks.coerceAtMost(MAX_WEEKS)
        // Align the window so `start` is a Monday and today is in the last column.
        val start = today.minusDays((safeWeeks - 1) * 7L + (today.dayOfWeek.value - 1L))
        val max = counts.values.maxOrNull() ?: 0
        return (0 until 7).map { row ->
            (0 until safeWeeks).joinToString("") { column ->
                val date = start.plusDays(column * 7L + row)
                heatCell(counts[date] ?: 0, max)
            }
        }
    }

    /** Quartile cell for the heatmap. */
    fun heatCell(count: Int, max: Int): String = when {
        max <= 0 || count <= 0 -> EMPTY
        count * 4 <= max -> "▁"
        count * 2 <= max -> "▄"
        count * 4 <= max * 3 -> "▆"
        else -> "█"
    }

    /**
     * Current and longest streak over [activeDays].
     *
     * The current streak counts back from [today]; it stays alive when only
     * yesterday was active (a morning run on an unmarked day still reports
     * the streak instead of a demotivating zero). The longest streak is a
     * single scan over the sorted day set.
     */
    fun streaks(activeDays: Set<LocalDate>, today: LocalDate): Streaks {
        if (activeDays.isEmpty()) return Streaks(current = 0, longest = 0)
        val sorted = activeDays.sorted()
        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1].plusDays(1)) run + 1 else 1
            if (run > longest) longest = run
        }
        val current = when {
            activeDays.contains(today) -> countBackwards(activeDays, today)
            activeDays.contains(today.minusDays(1)) -> countBackwards(activeDays, today.minusDays(1))
            else -> 0
        }
        return Streaks(current = current, longest = longest)
    }

    /** Consecutive active days ending on (and including) [from]. */
    private fun countBackwards(days: Set<LocalDate>, from: LocalDate): Int {
        var cursor = from
        var count = 0
        while (days.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** Current and longest consecutive-day streaks, in days. */
    data class Streaks(val current: Int, val longest: Int)
}
