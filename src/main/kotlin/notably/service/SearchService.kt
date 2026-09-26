package notably.service

import java.time.Duration
import java.time.Instant
import notably.model.Note
import notably.model.ValidationException

/**
 * Scored full-text search across notes.
 *
 * ## Scoring model
 *
 * The query is tokenized (lower-cased, split on non letter/number runs,
 * tokens shorter than [MIN_TOKEN_LENGTH] dropped) and every note is scored
 * by summing field-weighted token hits:
 *
 * | Field    | Boundary hit | Substring hit |
 * |----------|-------------:|--------------:|
 * | title    | 5.0          | 2.0           |
 * | tags     | 3.0          | 1.5           |
 * | notebook | 1.5          | —             |
 * | body     | 1.0          | 0.5           |
 *
 * "Boundary hit" means the token appears as a whole word (`\b`-anchored);
 * substring hits score at half weight. Additionally:
 *
 *  * the full query, when ≥ 3 chars and present verbatim, earns a phrase
 *    bonus in title ([PHRASE_TITLE_BONUS]) or body ([PHRASE_BODY_BONUS]);
 *  * a note whose *entire* title equals the query earns [EXACT_TITLE_BONUS];
 *  * recently updated notes get a small recency boost, decaying linearly to
 *    zero after one year, capped at [RECENCY_MAX];
 *  * notes with zero matched tokens are excluded.
 *
 * Results are ordered by score, then pinned-ness, then recency.
 *
 * ## Snippets
 *
 * [snippet] extracts a window of the body around the earliest token match
 * and wraps matches with `U+0001` / `U+0002` marker characters. The renderer
 * ([notably.ui.Renderer.highlight]) converts markers to bold/yellow ANSI or
 * strips them entirely when colors are disabled.
 */
class SearchService {

    /** Prepared query: tokens plus their precompiled boundary matchers. */
    data class Prepared(val tokens: List<String>, val phrase: String, val boundaries: List<Regex>)

    /** One scored result. */
    data class SearchHit(
        val note: Note,
        val score: Double,
        val snippet: String,
        val matchedTerms: Set<String>
    )

    /** Prepares a query for scoring: tokenization + boundary regexes. */
    fun prepare(query: String): Prepared {
        val tokens = tokenize(query)
        val boundaries = tokens.map { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE) }
        return Prepared(tokens, query.trim().lowercase(), boundaries)
    }

    /** Splits [query] into normalized search tokens. */
    fun tokenize(query: String): List<String> =
        query.lowercase()
            .split(TOKEN_SPLIT)
            .filter { it.length >= MIN_TOKEN_LENGTH }
            .distinct()

    /**
     * Scores [note] against [prepared]. Returns the score and the set of
     * matched terms; a note with no matched terms scores 0 and is excluded
     * from results by [search].
     */
    fun score(note: Note, prepared: Prepared): Pair<Double, Set<String>> {
        var total = 0.0
        val matched = LinkedHashSet<String>()
        if (prepared.tokens.isEmpty()) {
            return 0.0 to matched
        }
        val title = note.title.lowercase()
        val body = note.body.lowercase()
        val notebook = note.notebook.lowercase()
        for ((index, token) in prepared.tokens.withIndex()) {
            val boundary = prepared.boundaries[index]
            var hit = false
            if (boundary.containsMatchIn(title)) {
                total += WEIGHT_TITLE
                hit = true
            } else if (title.contains(token)) {
                total += WEIGHT_TITLE * CONTAINS_FACTOR
                hit = true
            }
            if (note.tags.contains(token)) {
                total += WEIGHT_TAG
                hit = true
            } else if (note.tags.any { it.contains(token) }) {
                total += WEIGHT_TAG * CONTAINS_FACTOR
                hit = true
            }
            if (boundary.containsMatchIn(notebook)) {
                total += WEIGHT_NOTEBOOK
                hit = true
            }
            if (boundary.containsMatchIn(body)) {
                total += WEIGHT_BODY
                hit = true
            } else if (body.contains(token)) {
                total += WEIGHT_BODY * CONTAINS_FACTOR
                hit = true
            }
            if (hit) matched.add(token)
        }
        val phrase = prepared.phrase
        if (phrase.length >= MIN_PHRASE_LENGTH) {
            when {
                title == phrase -> {
                    total += PHRASE_TITLE_BONUS + EXACT_TITLE_BONUS
                    matched.add(phrase)
                }
                title.contains(phrase) -> {
                    total += PHRASE_TITLE_BONUS
                    matched.add(phrase)
                }
                body.contains(phrase) -> {
                    total += PHRASE_BODY_BONUS
                    matched.add(phrase)
                }
            }
        }
        if (matched.isNotEmpty()) total += recencyBoost(note.updatedAt)
        return total to matched
    }

    /**
     * Recency boost: full [RECENCY_MAX] for notes updated today, decaying
     * linearly to zero exactly one year later.
     */
    fun recencyBoost(updatedAt: Instant, now: Instant = Instant.now()): Double {
        val days = Duration.between(updatedAt, now).toDays().coerceIn(0, 365)
        return RECENCY_MAX * (1.0 - days / 365.0)
    }

    /**
     * Searches [notes], returning up to [limit] hits ordered by score
     * (desc), pinned (desc), then update time (desc). Returns an empty list
     * when the query has no usable tokens.
     */
    fun search(notes: List<Note>, query: String, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val prepared = prepare(query)
        if (prepared.tokens.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (note in notes) {
            val (value, matched) = score(note, prepared)
            if (value > 0.0 && matched.isNotEmpty()) {
                hits.add(SearchHit(note, value, snippet(note, prepared.tokens), matched))
            }
        }
        return hits.sortedWith(
            compareByDescending<SearchHit> { it.score }
                .thenByDescending { it.note.pinned }
                .thenByDescending { it.note.updatedAt.toEpochMilli() }
        ).take(limit.coerceAtLeast(1))
    }

    /**
     * Builds a one-line snippet of the note body around the earliest token
     * match, wrapping matches in highlight markers. Falls back to the head
     * of the body when no token occurs.
     */
    fun snippet(note: Note, tokens: List<String>, width: Int = SNIPPET_WIDTH): String {
        val flat = note.body.replace(WHITESPACE, " ").trim()
        if (flat.isEmpty() || tokens.isEmpty()) return ""
        val lower = flat.lowercase()
        var firstIndex = -1
        for (token in tokens) {
            val index = lower.indexOf(token)
            if (index >= 0 && (firstIndex < 0 || index < firstIndex)) firstIndex = index
        }
        val window: String = if (firstIndex < 0) {
            if (flat.length <= width) flat else flat.take(width - 1) + ELLIPSIS
        } else {
            val start = (firstIndex - width / 4).coerceAtLeast(0)
            val end = (start + width).coerceAtMost(flat.length)
            val prefix = if (start > 0) ELLIPSIS else ""
            val suffix = if (end < flat.length) ELLIPSIS else ""
            prefix + flat.substring(start, end) + suffix
        }
        var highlighted = window
        for (token in tokens) {
            highlighted = Regex(Regex.escape(token), RegexOption.IGNORE_CASE)
                .replace(highlighted) { match -> MARK_START + match.value + MARK_END }
        }
        return highlighted
    }

    /**
     * Regex search across [notes]. Every match in the title scores
     * [REGEX_WEIGHT_TITLE], every tag whose text matches scores
     * [REGEX_WEIGHT_TAG], every match in the body scores [REGEX_WEIGHT_BODY];
     * the hit records the total match count. Results are ordered by score
     * (desc), then update time (desc), and capped at [limit].
     *
     * @throws ValidationException when [pattern] is not a valid regex
     */
    fun searchRegex(
        notes: List<Note>,
        pattern: String,
        limit: Int = DEFAULT_LIMIT,
        ignoreCase: Boolean = true
    ): List<RegexSearchHit> {
        val regex = compileRegex(pattern, ignoreCase)
        if (limit < 1) return emptyList()
        val hits = mutableListOf<RegexSearchHit>()
        for (note in notes) {
            val titleMatches = regex.findAll(note.title).count()
            val bodyMatches = regex.findAll(note.body).count()
            val tagMatches = note.tags.count { regex.containsMatchIn(it) }
            val total = titleMatches + bodyMatches + tagMatches
            if (total == 0) continue
            val score = titleMatches * REGEX_WEIGHT_TITLE +
                tagMatches * REGEX_WEIGHT_TAG +
                bodyMatches * REGEX_WEIGHT_BODY
            hits.add(RegexSearchHit(note, score, total, regexSnippet(note, regex)))
        }
        return hits.sortedWith(
            compareByDescending<RegexSearchHit> { it.score }
                .thenByDescending { it.note.updatedAt.toEpochMilli() }
        ).take(limit)
    }

    /** One regex-search result: note, weighted score, total match count, snippet. */
    data class RegexSearchHit(
        val note: Note,
        val score: Double,
        val matchCount: Int,
        val snippet: String
    )

    /**
     * Compiles a user-supplied regular expression, translating invalid
     * patterns into a [ValidationException] (exit code VALIDATION) instead of
     * leaking [java.util.regex.PatternSyntaxException].
     */
    fun compileRegex(pattern: String, ignoreCase: Boolean = true): Regex {
        if (pattern.isBlank()) throw ValidationException("empty regex")
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return try {
            Regex(pattern, options)
        } catch (e: java.util.regex.PatternSyntaxException) {
            val reason = e.message?.lineSequence()?.firstOrNull() ?: "syntax error"
            throw ValidationException("invalid regex: $reason")
        }
    }

    /**
     * Snippet for regex hits: window around the first body match with every
     * visible match wrapped in highlight markers. Empty when the pattern
     * never occurs in the body (title/tag-only matches).
     */
    private fun regexSnippet(note: Note, regex: Regex, width: Int = SNIPPET_WIDTH): String {
        val flat = note.body.replace(WHITESPACE, " ").trim()
        val match = regex.find(flat) ?: return ""
        val start = (match.range.first - width / 4).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(flat.length)
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < flat.length) ELLIPSIS else ""
        val window = flat.substring(start, end)
        return prefix + regex.replace(window) { m -> MARK_START + m.value + MARK_END } + suffix
    }

    /**
     * Suggests known tags matching [query] (substring match, shortest
     * first) — used for "did you mean" hints on tag commands.
     */
    fun suggestTags(available: Collection<String>, query: String, max: Int = 5): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return available.filter { it.contains(q) }.sortedWith(compareBy({ it.length }, { it })).take(max)
    }

    companion object {
        const val WEIGHT_TITLE = 5.0
        const val WEIGHT_TAG = 3.0
        const val WEIGHT_NOTEBOOK = 1.5
        const val WEIGHT_BODY = 1.0
        const val REGEX_WEIGHT_TITLE = 4.0
        const val REGEX_WEIGHT_TAG = 2.5
        const val REGEX_WEIGHT_BODY = 1.0
        const val CONTAINS_FACTOR = 0.5
        const val PHRASE_TITLE_BONUS = 6.0
        const val PHRASE_BODY_BONUS = 3.0
        const val EXACT_TITLE_BONUS = 8.0
        const val RECENCY_MAX = 1.5
        const val MIN_TOKEN_LENGTH = 2
        const val MIN_PHRASE_LENGTH = 3
        const val SNIPPET_WIDTH = 120
        const val DEFAULT_LIMIT = 20

        const val MARK_START = "\u0001"
        const val MARK_END = "\u0002"
        const val ELLIPSIS = "…"

        private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val WHITESPACE = Regex("\\s+")
    }
}
