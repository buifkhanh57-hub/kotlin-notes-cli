package notably.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import notably.model.StorageException

/**
 * Crash-safe file writer used for every persistence file in the workspace.
 *
 * Guarantee: a reader will observe either the full old content or the full
 * new content of the file, never a torn write. The strategy is the classic
 * *temp file + atomic rename*:
 *
 *  1. serialize payload to `<target>.tmp-<stamp>` in the same directory,
 *  2. write + flush + `fsync` the temp file,
 *  3. `rename(2)` it over the target (atomic on POSIX; falls back to a
 *     `REPLACE_EXISTING` move, and finally to a copy when the filesystem
 *     refuses both),
 *  4. before overwriting, rotate the previous content into [backupDir] as
 *     `<name>.bak.<timestamp>`, keeping at most [maxBackups] generations.
 *
 * Readers should use [readTextRecovering]: if the main file is missing or
 * unreadable, the newest readable backup is transparently substituted.
 */
class AtomicFile(
    val target: File,
    val backupDir: File? = null,
    private val maxBackups: Int = 0
) {

    private val rng = SecureRandom()

    /** True when the main file currently exists on disk. */
    fun exists(): Boolean = target.isFile

    /** Last-modified time of the main file, or `0` when absent. */
    fun lastModified(): Long = if (target.isFile) target.lastModified() else 0L

    /**
     * Plain read of the main file; returns `null` when the file is absent.
     * I/O errors propagate to the caller (use [readTextRecovering] when you
     * want automatic backup fallback instead).
     */
    fun readText(): String? {
        if (!target.isFile) return null
        return try {
            target.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw StorageException("cannot read ${target.path}: ${e.message}", e)
        }
    }

    /**
     * Reads the main file, falling back to the newest readable backup when
     * the main file cannot be read. Returns `null` when the file never
     * existed (fresh workspace); throws [StorageException] when the main file
     * exists but is unreadable *and* no backup could be recovered.
     */
    fun readTextRecovering(): Recovered? {
        var mainError: String? = null
        if (target.isFile) {
            try {
                return Recovered(target.readText(Charsets.UTF_8), usedBackup = false, source = target.path, error = null)
            } catch (e: Exception) {
                mainError = e.message
            }
        }
        val backups = backups()
        for (backup in backups) {
            try {
                return Recovered(backup.readText(Charsets.UTF_8), usedBackup = true, source = backup.path, error = mainError)
            } catch (_: Exception) {
                // try the next-older backup
            }
        }
        if (mainError != null) {
            throw StorageException("cannot read ${target.path} ($mainError) and no usable backup exists")
        }
        return null
    }

    /**
     * Atomically replaces the target with [content] (UTF-8). Rotates a backup
     * of the previous content first when a [backupDir] is configured.
     */
    fun writeText(content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw StorageException("cannot create directory ${parent.path}")
        }
        if (backupDir != null && target.isFile) rotateBackup()
        val tmp = File(parent ?: File("."), "${target.name}.tmp-${stamp()}")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Last-resort fallback: plain copy (still better than losing data).
            try {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            } catch (fallback: Exception) {
                throw StorageException("atomic write failed for ${target.path}: ${e.message}", e)
            }
        }
    }

    /** Deletes the target file if present; returns true when it was removed. */
    fun delete(): Boolean = if (target.exists()) target.delete() else false

    /**
     * Copies the current content into [backupDir] with a timestamped name and
     * prunes generations beyond [maxBackups]. Failures here are deliberately
     * swallowed: a broken backup must never prevent the primary write.
     */
    private fun rotateBackup() {
        val dir = backupDir ?: return
        try {
            if (!dir.exists() && !dir.mkdirs()) return
            val destination = File(dir, "${target.name}.bak.${stamp()}")
            target.copyTo(destination, overwrite = true)
            val all = backups()
            if (all.size > maxBackups) {
                for (stale in all.drop(maxBackups)) stale.delete()
            }
        } catch (_: Exception) {
            // backup best-effort by design
        }
    }

    /** Backup generations for this target, newest first. */
    fun backups(): List<File> {
        val dir = backupDir ?: return emptyList()
        return backupFilesFor(target, dir)
    }

    /** Unique-ish stamp for temp/backup file names: ms precision + 4 hex chars. */
    private fun stamp(): String {
        val random = ByteArray(2)
        rng.nextBytes(random)
        val hex = random.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return "${System.currentTimeMillis()}-$hex"
    }

    companion object {
        /**
         * Lists backup files named `<target.name>.bak.*` inside [dir],
         * sorted newest first (the timestamp format sorts lexicographically).
         */
        fun backupFilesFor(target: File, dir: File): List<File> {
            val prefix = "${target.name}.bak."
            val list = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) } ?: return emptyList()
            return list.sortedByDescending { it.name }
        }
    }
}

/** Result envelope of [AtomicFile.readTextRecovering]. */
data class Recovered(
    /** The recovered text. */
    val content: String,

    /** True when [content] came from a backup, not the main file. */
    val usedBackup: Boolean,

    /** Path of the file the content was actually read from. */
    val source: String,

    /** Error message from the failed main-file read, when any. */
    val error: String?
)
