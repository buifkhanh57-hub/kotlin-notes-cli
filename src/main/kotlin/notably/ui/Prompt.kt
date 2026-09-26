package notably.ui

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.Locale

/**
 * Interactive input helpers: prompts, yes/no confirmation, secret entry via
 * the real console (no echo) with a safe fallback, and the external-editor
 * flow used by `add` / `edit`.
 *
 * Every reader method tolerates EOF (`null` from [BufferedReader.readLine])
 * by returning the configured default instead of crashing — the CLI must
 * degrade gracefully when stdin is closed (piped scripts, CI).
 */
class Prompt(
    input: InputStream = System.`in`,
    private val output: PrintStream = System.out
) {

    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

    /**
     * Reads a line of free-form input. Blank input returns [default] when
     * provided, otherwise an empty string.
     */
    fun ask(message: String, default: String? = null): String {
        val suffix = if (default.isNullOrEmpty()) "" else " [$default]"
        output.print("$message$suffix: ")
        output.flush()
        val line = reader.readLine()?.trim()
        return when {
            line == null -> default ?: ""
            line.isEmpty() -> default ?: ""
            else -> line
        }
    }

    /**
     * Asks a yes/no question. Accepted answers: `y`, `yes`, `n`, `no` (case
     * insensitive); bare Enter picks [default]. Invalid answers re-prompt up
     * to [MAX_ATTEMPTS] times, then fall back to [default].
     */
    fun confirm(message: String, default: Boolean = false): Boolean {
        val hint = if (default) "[Y/n]" else "[y/N]"
        repeat(MAX_ATTEMPTS) {
            output.print("$message $hint: ")
            output.flush()
            val answer = reader.readLine()?.trim()?.lowercase(Locale.ROOT)
            when {
                answer == null -> return default               // EOF
                answer.isEmpty() -> return default
                answer == "y" || answer == "yes" -> return true
                answer == "n" || answer == "no" -> return false
                else -> output.println("  please answer y or n")
            }
        }
        return default
    }

    /**
     * Reads a secret without echo using `System.console().readPassword`.
     * When no console is attached (IDE run panels, piped input) the method
     * degrades to an echoed prompt with an explicit warning.
     */
    fun askSecret(message: String): CharArray {
        val console = System.console()
        if (console != null) {
            val chars = console.readPassword("$message: ")
            if (chars != null) return chars
        }
        output.println("(no hidden console available — input will be echoed)")
        return ask(message).toCharArray()
    }

    /**
     * Captures multi-line note text.
     *
     * 1. If an editor command is resolvable (parameter, `$VISUAL`, `$EDITOR`
     *    — in that order), a temp file seeded with [initial] is opened in the
     *    editor (inheriting the terminal) and read back on exit.
     * 2. If the editor is missing, fails, or exits non-zero, the method falls
     *    back to reading stdin until a line containing only `.` (or EOF).
     *
     * The result is the raw text; a trailing newline introduced by the editor
     * is trimmed to keep `edit` diffs meaningful.
     */
    fun readMultiline(title: String, initial: String = "", editorCommand: String? = null): String {
        val editor = resolveEditor(editorCommand)
        if (editor != null) {
            val temp = File.createTempFile("notably-", ".md")
            try {
                temp.writeText(initial, Charsets.UTF_8)
                val parts = editor.trim().split(WHITESPACE).filter { it.isNotEmpty() } + temp.absolutePath
                val process = ProcessBuilder(parts).inheritIO().start()
                val exit = process.waitFor()
                if (exit == 0) {
                    val text = temp.readText(Charsets.UTF_8)
                    return normalizeTrailing(text)
                }
                output.println("(editor exited with code $exit — falling back to inline input)")
            } catch (_: Exception) {
                output.println("(could not launch editor '$editor' — falling back to inline input)")
            } finally {
                temp.delete()
            }
        }
        return readMultilineInline(initial)
    }

    /** Resolves the editor: explicit argument wins, then $VISUAL, then $EDITOR. */
    fun resolveEditor(configured: String?): String? {
        configured?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("VISUAL")?.takeIf { it.isNotBlank() }?.let { return it }
        return System.getenv("EDITOR")?.takeIf { it.isNotBlank() }
    }

    /** Stdin fallback: read until a lone `.` line or EOF. */
    private fun readMultilineInline(initial: String): String {
        if (initial.isNotBlank()) {
            output.println("--- current text (end input with a line containing only '.') ---")
            output.println(initial.trimEnd())
            output.println("---------------------------------------------------------------")
        } else {
            output.println("Type the note body. Finish with a line containing only '.'")
        }
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.trim() == TERMINATOR) break
            sb.append(line).append('\n')
        }
        return normalizeTrailing(sb.toString())
    }

    private fun normalizeTrailing(text: String): String =
        text.trimEnd('\n', '\r')

    companion object {
        const val TERMINATOR = "."
        const val MAX_ATTEMPTS = 3
        private val WHITESPACE = Regex("\\s+")
    }
}
