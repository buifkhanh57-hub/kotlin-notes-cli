package notably.ui

/**
 * Renders note bodies (markdown subset) to ANSI-styled terminal text and
 * derives plain-text previews for listings and search results.
 *
 * Supported block constructs:
 *  - fenced code blocks (``` or ~~~) rendered dim with a rule;
 *  - ATX headings `#`..`######` (bold, hue by level);
 *  - thematic breaks `---`, `***`, `___`;
 *  - block quotes `>` (magenta bar);
 *  - task lists `- [ ]` / `- [x]`;
 *  - unordered lists `-`, `*`, `+` (cyan bullet, indentation preserved);
 *  - ordered lists `1.`, `1)`.
 *
 * Supported inline constructs: `**bold**`, `*italic*`, `_italic_`,
 * `` `code` ``, `~~strike~~`, `[text](url)` links and `![alt](url)` images.
 *
 * Every paint call is local to this file: [render] takes an explicit
 * `colors` flag instead of consulting [Ansi] so tests can run with stable,
 * escape-free output.
 */
object MarkdownRenderer {

    // ---- block patterns -------------------------------------------------

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val HR = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val TASK = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$")
    private val ULIST = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val OLIST = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val FENCE_TOKENS = listOf("```", "~~~")

    // ---- inline patterns ------------------------------------------------

    private val LINK = Regex("\\[([^\\]]*)]\\(([^)\\s]*)\\)")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]*)\\)")

    /**
     * Renders a full note body. `colors == false` yields clean plain text
     * (structure markers are normalized but no escape sequences are emitted).
     */
    fun render(body: String, colors: Boolean): String {
        fun paint(text: String, code: String): String =
            if (colors && text.isNotEmpty()) Ansi.ESC_PREFIX + code + text + Ansi.RESET else text

        val out = StringBuilder(body.length + 64)
        var inCode = false
        for (rawLine in body.lines()) {
            val trimmedStart = rawLine.trimStart()
            if (inCode) {
                if (FENCE_TOKENS.any { trimmedStart.startsWith(it) }) {
                    inCode = false
                    out.append(paint("▔▔▔▔▔", Ansi.DIM_CODE))
                } else {
                    out.append(paint(rawLine, Ansi.DIM_CODE))
                }
                out.append('\n')
                continue
            }
            if (FENCE_TOKENS.any { trimmedStart.startsWith(it) }) {
                inCode = true
                out.append(paint("▁▁▁▁▁", Ansi.DIM_CODE))
                out.append('\n')
                continue
            }
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> out.append('\n')
                HR.matches(line) -> out.append(paint("────────────────────────", Ansi.DIM_CODE))
                HEADING.matches(line) -> {
                    val match = HEADING.find(line)!!
                    val level = match.groupValues[1].length
                    val text = match.groupValues[2]
                    out.append(
                        when (level) {
                            1 -> paint(text, Ansi.BOLD_CYAN_CODE)
                            2 -> paint(text, Ansi.BOLD_CODE)
                            3 -> paint(text, Ansi.BOLD_CODE)
                            else -> paint(text, Ansi.DIM_CODE)
                        }
                    )
                }
                QUOTE.matches(line) -> {
                    val content = QUOTE.find(line)!!.groupValues[1]
                    out.append(paint("▏ ", Ansi.MAGENTA_CODE) + inline(content, colors))
                }
                TASK.matches(line) -> {
                    val match = TASK.find(line)!!
                    val indent = match.groupValues[1]
                    val done = match.groupValues[2] != " "
                    val box = if (done) paint("☑", Ansi.GREEN_CODE) else paint("☐", Ansi.YELLOW_CODE)
                    out.append(indent + box + " " + inline(match.groupValues[3], colors))
                }
                ULIST.matches(line) -> {
                    val match = ULIST.find(line)!!
                    out.append(match.groupValues[1] + paint("•", Ansi.CYAN_CODE) + " " + inline(match.groupValues[2], colors))
                }
                OLIST.matches(line) -> {
                    val match = OLIST.find(line)!!
                    out.append(match.groupValues[1] + paint(match.groupValues[2] + ".", Ansi.CYAN_CODE) + " " + inline(match.groupValues[3], colors))
                }
                else -> out.append(inline(line, colors))
            }
            out.append('\n')
        }
        return out.toString().trimEnd('\n')
    }

    /**
     * Inline pass: code spans, bold, italic, strike-through, links and
     * images. Unmatched delimiters are emitted literally.
     */
    private fun inline(src: String, colors: Boolean): String {
        fun paint(text: String, code: String): String =
            if (colors && text.isNotEmpty()) Ansi.ESC_PREFIX + code + text + Ansi.RESET else text

        val out = StringBuilder(src.length + 16)
        var i = 0
        val n = src.length
        while (i < n) {
            val rest = src.substring(i)
            val image = IMAGE.find(rest)
            if (image != null && image.range.first == 0) {
                val alt = image.groupValues[1]
                val url = image.groupValues[2]
                out.append(paint("🖼 $alt", Ansi.UNDERLINE_CODE))
                if (url.isNotEmpty()) out.append(" " + paint("($url)", Ansi.DIM_CODE))
                i += image.value.length
                continue
            }
            val link = LINK.find(rest)
            if (link != null && link.range.first == 0) {
                out.append(paint(link.groupValues[1], Ansi.UNDERLINE_CODE))
                val url = link.groupValues[2]
                if (url.isNotEmpty()) out.append(" " + paint("($url)", Ansi.DIM_CODE))
                i += link.value.length
                continue
            }
            val ch = src[i]
            when {
                ch == '`' -> {
                    val close = src.indexOf('`', i + 1)
                    if (close > i) {
                        out.append(paint(src.substring(i + 1, close), Ansi.YELLOW_CODE))
                        i = close + 1
                    } else {
                        out.append(ch)
                        i++
                    }
                }
                src.startsWith("**", i) -> {
                    val close = src.indexOf("**", i + 2)
                    if (close > i + 1) {
                        out.append(paint(inline(src.substring(i + 2, close), colors), Ansi.BOLD_CODE))
                        i = close + 2
                    } else {
                        out.append("**")
                        i += 2
                    }
                }
                src.startsWith("~~", i) -> {
                    val close = src.indexOf("~~", i + 2)
                    if (close > i + 1) {
                        out.append(paint(inline(src.substring(i + 2, close), colors), Ansi.STRIKE_CODE))
                        i = close + 2
                    } else {
                        out.append("~~")
                        i += 2
                    }
                }
                ch == '*' || ch == '_' -> {
                    val close = src.indexOf(ch, i + 1)
                    if (close > i + 1) {
                        out.append(paint(inline(src.substring(i + 1, close), colors), Ansi.ITALIC_CODE))
                        i = close + 1
                    } else {
                        out.append(ch)
                        i++
                    }
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Plain-text preview for list rows and search hits: markdown syntax is
     * stripped, whitespace collapsed, result capped at [maxChars] with an
     * ellipsis. Never throws on any input.
     */
    fun plainPreview(body: String, maxChars: Int = 140): String {
        var text = body
        text = text.replace(FENCED, " [code] ")
        text = IMAGE.replace(text) { match -> match.groupValues[1] }
        text = LINK.replace(text) { match -> match.groupValues[1] }
        text = text.replace(EMPHASIS_MARKS, "")
        text = text.replace(HEADING_PREFIX, "")
        text = text.replace(QUOTE_PREFIX, "")
        text = text.replace(LIST_PREFIX, "• ")
        text = text.replace(WHITESPACE_RUN, " ").trim()
        if (text.length <= maxChars) return text
        return text.take((maxChars - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    // ---- regex constants used by plainPreview ---------------------------

    private val FENCED = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~")
    private val EMPHASIS_MARKS = Regex("\\*\\*|__|~~|`|\\*|_")
    private val HEADING_PREFIX = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
    private val QUOTE_PREFIX = Regex("^>\\s?", RegexOption.MULTILINE)
    private val LIST_PREFIX = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE)
    private val WHITESPACE_RUN = Regex("\\s+")
}
