@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package app.borderless.platform

import platform.posix.chmod

/** Preserve pre-migration bytes durably. Backups are never pruned with Repo's broken-file pile. */
internal fun preserveLegacyFile(path: String): String {
    val raw = Files.readBytes(path) ?: error("can't read $path for migration backup")
    var backup = "$path.encrypted-backup"
    if (fileExists(backup)) {
        if (Files.readBytes(backup)?.contentEquals(raw) == true) return backup
        var n = 0
        do { backup = "$path.encrypted-backup-${nowMs()}-${n++}" } while (fileExists(backup))
    }
    val tmp = "$backup.tmp"
    Files.writeBytes(tmp, raw)
    chmod(tmp, 0x180u)
    check(Files.readBytes(tmp)?.contentEquals(raw) == true) { "can't verify migration backup for $path" }
    check(Files.rename(tmp, backup)) { "cannot preserve $path" }
    return backup
}
