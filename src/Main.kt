// kotlin-notes-cli — a console notes manager in idiomatic Kotlin.
// Run: kotlinc src/Main.kt -include-runtime -d notes.jar && java -jar notes.jar

import java.io.File
import java.time.LocalDateTime

data class Note(
    val id: Int,
    val title: String,
    val body: String,
    val createdAt: String = LocalDateTime.now().toString()
) {
    fun toCsvLine(): String =
        listOf(id, title.replace(';', ','), body.replace(';', ','), createdAt)
            .joinToString(";")

    companion object {
        fun fromCsvLine(line: String): Note? {
            val parts = line.split(";")
            if (parts.size < 4) return null
            val id = parts[0].toIntOrNull() ?: return null
            return Note(id, parts[1], parts[2], parts[3])
        }
    }
}

class NoteBook(private val file: File) {

    private val notes: MutableList<Note> = load()

    private fun load(): MutableList<Note> =
        if (file.exists()) {
            file.readLines().mapNotNull(Note::fromCsvLine).toMutableList()
        } else mutableListOf()

    fun save() {
        file.writeText(notes.joinToString("\n") { it.toCsvLine() })
    }

    fun add(title: String, body: String): Note {
        val nextId = (notes.maxOfOrNull { it.id } ?: 0) + 1
        val note = Note(nextId, title, body)
        notes.add(note)
        save()
        return note
    }

    fun list(): List<Note> = notes.sortedBy { it.id }

    fun find(keyword: String): List<Note> =
        notes.filter {
            it.title.contains(keyword, ignoreCase = true) ||
                it.body.contains(keyword, ignoreCase = true)
        }

    fun delete(id: Int): Boolean {
        val removed = notes.removeIf { it.id == id }
        if (removed) save()
        return removed
    }
}

private fun help() = println(
    """
    Lenh:
      add <tieu de> | <noi dung>   them ghi chu
      list                         liet ke ghi chu
      find <tu khoa>               tim kiem
      del <id>                     xoa ghi chu
      help                         huong dan
      exit                         thoat
    """.trimIndent()
)

fun main(args: Array<String>) {
    val file = File(System.getProperty("user.home"), ".kotlin-notes.csv")
    val book = NoteBook(file)

    println("Kotlin Notes — ghi chep cua ban (${file.absolutePath})")
    help()

    if (args.isNotEmpty()) {
        when (args[0]) {
            "demo" -> {
                book.add("Học Kotlin", "Sealed class, data class, extension function")
                book.add("Việc tuần này", "Đọc xong chương coroutines")
                book.list().forEach { println("${it.id}: ${it.title}") }
            }
            else -> help()
        }
        return
    }

    while (true) {
        print("> ")
        val line = readLine() ?: break
        val tokens = line.trim().split(" ", limit = 2)

        when (tokens.firstOrNull()?.lowercase()) {
            null, "" -> continue
            "exit", "quit" -> {
                println("Tam biet!")
                break
            }
            "help" -> help()
            "add" -> {
                val rest = tokens.getOrNull(1) ?: ""
                val (title, body) = rest.split("|", limit = 2)
                    .map { it.trim() }
                    .let { it[0] to it.getOrElse(1) { "" } }
                if (title.isBlank()) println("Tieu de khong duoc de trong")
                else {
                    val note = book.add(title, body)
                    println("Da them ghi chu #${note.id}")
                }
            }
            "list", "ls" -> {
                val all = book.list()
                if (all.isEmpty()) println("Chua co ghi chu nao")
                else all.forEach {
                    println("${it.id}  [${it.createdAt.take(10)}]  ${it.title}")
                }
            }
            "find" -> {
                val kw = tokens.getOrNull(1) ?: ""
                val found = book.find(kw)
                println("Tim thay ${found.size} ghi chu:")
                found.forEach { println("${it.id}: ${it.title} — ${it.body.take(50)}") }
            }
            "del" -> {
                val id = tokens.getOrNull(1)?.toIntOrNull()
                if (id == null) println("Cu phap: del <id>")
                else println(if (book.delete(id)) "Da xoa #$id" else "Khong tim thay #$id")
            }
            else -> println("Lenh khong hop le. Go 'help' de xem huong dan.")
        }
    }
}
