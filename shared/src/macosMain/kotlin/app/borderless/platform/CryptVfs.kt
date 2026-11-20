@file:OptIn(ExperimentalForeignApi::class)

package app.borderless.platform

import sqlite3.sqlite3_file
import sqlite3.sqlite3_io_methods
import sqlite3.sqlite3_vfs
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import kotlin.concurrent.Volatile
import sqlite3.SQLITE_CANTOPEN
import sqlite3.SQLITE_CORRUPT
import sqlite3.SQLITE_IOERR
import sqlite3.SQLITE_IOERR_READ
import sqlite3.SQLITE_IOERR_SHORT_READ
import sqlite3.SQLITE_IOERR_WRITE
import sqlite3.SQLITE_OK
import sqlite3.sqlite3_initialize
import sqlite3.sqlite3_vfs_find
import sqlite3.sqlite3_vfs_register

/**
 * The statistics database's encryption at rest (R1, F-DATA-0038-1) — the documented equivalent of Android's
 * SQLCipher, as `local/macos/PLAN.md` maps it ("SQLCipher for macOS … same schema, same StatsDb code"): a
 * SQLite VFS that seals every 4096-byte block of the database file (and its `-wal` / `-journal`) with
 * AES-256-GCM under the data key, so the file — live, checkpointed or in a disk backup — holds only sealed
 * blocks. The plan's own SQLCipher build needs a bundled native library; this does the same page-at-a-time
 * authenticated encryption in the platform layer, with no new dependency and the same failure semantics
 * (a blob that fails to authenticate is read as corruption, never silently decrypted).
 *
 * Physical layout of a sealed file:
 *   [0,64)        header: "BLDB" | u16 version=1 (BE) | u32 block size (BE) | 16-byte salt | reserved
 *   [64, ...)     per 4096-byte logical block: 12-byte nonce | ciphertext | 16-byte tag  (=4124 bytes)
 * Block `i` is sealed with AAD = salt || block-index (BE), so blocks can neither be forged nor moved.
 * Unwritten regions read as zeros (SQLite's own contract for reads past the end); a short physical block
 * is corruption, not a truncated page.
 *
 * The `-shm` file is delegated to the parent VFS untouched: SQLite memory-maps it, which would bypass any
 * content hooks; it holds only the wal-index (page numbers, salts, checksums) — metadata, no payload.
 *
 * One key per process ([key], set by [MacSql.openSealed] before opening): the VFS is for the statistics
 * database only — other files must not go through it.
 */
object CryptVfs {
    /** The VFS name for `sqlite3_open_v2(path, db, flags, NAME)`. */
    const val NAME = "borders"

    /** The 32-byte data key files opened through this VFS seal under ([MacSql.openSealed] sets it). */
    @Volatile
    internal var key: ByteArray? = null

    private const val HEADER = 64L
    private const val BLOCK = 4096
    private const val NONCE = 12
    private const val TAG = 16
    private const val PHYS_BLOCK = BLOCK + NONCE + TAG
    private val MAGIC = "BLDB".encodeToByteArray()

    /** Our file's memory layout: sqlite3_file | ctx (key+salt, 64 B) | the parent's file struct (16-aligned). */
    private val FILE_SIZE: Long get() = sizeOf<sqlite3_file>()
    private const val CTX_KEY = 0
    private const val CTX_SALT = 32
    private const val CTX_SIZE = 64L
    private val INNER_OFF: Long get() = (FILE_SIZE + CTX_SIZE + 15) / 16 * 16

    private val lock = Lock()
    private var registered = false
    private var parent: CPointer<sqlite3_vfs>? = null
    private var methods: CPointer<sqlite3_io_methods>? = null

    /** Registers the VFS once. Throws if SQLite cannot be initialized. */
    fun ensureRegistered() {
        lock.withLock {
            if (registered) return
            check(sqlite3_initialize() == SQLITE_OK) { "sqlite: cannot initialize" }
            val p = sqlite3_vfs_find(null) ?: error("sqlite: no default VFS")
            parent = p
            val iom = nativeHeap.alloc<sqlite3_io_methods>()
            // iVersion 2: WAL needs the xShm* methods on the *database* file (the wal-index is shared
            // memory, opened by the parent's own shm code on the inner file — see vfsOpen's -shm branch);
            // without them a WAL-mode database reports "unable to open database file" on its first read.
            // Still no xFetch/xUnfetch (v3 slots stay untouched): an mmap'd page would bypass decryption.
            iom.iVersion = 2
            iom.xClose = staticCFunction { f -> ioClose(f) }
            iom.xRead = staticCFunction { f, b, a, o -> ioRead(f, b?.reinterpret<ByteVar>(), a, o) }
            iom.xWrite = staticCFunction { f, b, a, o -> ioWrite(f, b?.reinterpret<ByteVar>(), a, o) }
            iom.xTruncate = staticCFunction { f, s -> ioTruncate(f, s) }
            iom.xSync = staticCFunction { f, fl -> ioMethods(inner(f)).xSync!!.invoke(inner(f), fl) }
            iom.xFileSize = staticCFunction { f, s -> ioFileSize(f, s?.reinterpret<LongVar>()) }
            iom.xLock = staticCFunction { f, l -> ioMethods(inner(f)).xLock!!.invoke(inner(f), l) }
            iom.xUnlock = staticCFunction { f, l -> ioMethods(inner(f)).xUnlock!!.invoke(inner(f), l) }
            iom.xCheckReservedLock = staticCFunction { f, r -> ioMethods(inner(f)).xCheckReservedLock!!.invoke(inner(f), r) }
            iom.xFileControl = staticCFunction { f, op, a -> ioMethods(inner(f)).xFileControl!!.invoke(inner(f), op, a) }
            iom.xSectorSize = staticCFunction { f -> ioMethods(inner(f)).xSectorSize!!.invoke(inner(f)) }
            iom.xDeviceCharacteristics = staticCFunction { f -> ioMethods(inner(f)).xDeviceCharacteristics!!.invoke(inner(f)) }
            // The wal-index is not file content — none of it goes through the sealed blocks. The parent
            // runs it on the inner file exactly as for an unsealed database (it opens the -shm itself).
            // A missing parent slot is an IO error, not an exception thrown across the C boundary.
            iom.xShmMap = staticCFunction { f, pg, sz, ext, out -> ioMethods(inner(f)).xShmMap?.invoke(inner(f), pg, sz, ext, out) ?: SQLITE_IOERR }
            iom.xShmLock = staticCFunction { f, off, n, fl -> ioMethods(inner(f)).xShmLock?.invoke(inner(f), off, n, fl) ?: SQLITE_IOERR }
            iom.xShmBarrier = staticCFunction { f -> ioMethods(inner(f)).xShmBarrier?.invoke(inner(f)) ?: Unit }
            iom.xShmUnmap = staticCFunction { f, del -> ioMethods(inner(f)).xShmUnmap?.invoke(inner(f), del) ?: SQLITE_IOERR }
            methods = iom.ptr

            val vfs = nativeHeap.alloc<sqlite3_vfs>()
            vfs.iVersion = 2
            vfs.szOsFile = (INNER_OFF + p.pointed.szOsFile).toInt()
            vfs.mxPathname = p.pointed.mxPathname
            vfs.pNext = null
            vfs.zName = nativeHeap.allocArrayOf(NAME.encodeToByteArray() + 0.toByte())
            vfs.pAppData = null // the parent is the object's own field — the ctx needs no pointer back
            vfs.xOpen = staticCFunction { _, z, f, fl, out -> vfsOpen(z?.reinterpret<ByteVar>(), f, fl, out?.reinterpret<IntVar>()) }
            // Everything that is not an open delegates to the parent with the parent's own pointer.
            // NOTE: staticCFunction bodies may not capture locals — `parent` is the object field, not `p`.
            vfs.xDelete = staticCFunction { _, z, s -> parent!!.pointed.xDelete!!.invoke(parent!!, z, s) }
            vfs.xAccess = staticCFunction { _, z, fl, out -> parent!!.pointed.xAccess!!.invoke(parent!!, z, fl, out) }
            vfs.xFullPathname = staticCFunction { _, z, n, out -> parent!!.pointed.xFullPathname!!.invoke(parent!!, z, n, out) }
            // iVersion 2 carries the dynamic-loading slots between xFullPathname and xRandomness; the heap
            // allocation is not zeroed, so they get explicit no-op stubs (nothing here loads extensions).
            vfs.xDlOpen = staticCFunction { _, _ -> null }
            vfs.xDlError = staticCFunction { _, _, _ -> }
            vfs.xDlSym = staticCFunction { _, _, _ -> null }
            vfs.xDlClose = staticCFunction { _, _ -> }
            vfs.xRandomness = staticCFunction { _, n, out -> parent!!.pointed.xRandomness!!.invoke(parent!!, n, out) }
            vfs.xSleep = staticCFunction { _, us -> parent!!.pointed.xSleep!!.invoke(parent!!, us) }
            vfs.xCurrentTime = staticCFunction { _, t -> parent!!.pointed.xCurrentTime!!.invoke(parent!!, t) }
            vfs.xGetLastError = staticCFunction { _, n, out -> parent!!.pointed.xGetLastError?.invoke(parent!!, n, out) ?: 0 }
            vfs.xCurrentTimeInt64 = staticCFunction { _, t -> parent!!.pointed.xCurrentTimeInt64!!.invoke(parent!!, t) }
            check(sqlite3_vfs_register(vfs.ptr, 0) == SQLITE_OK) { "sqlite: cannot register the $NAME VFS" }
            registered = true
        }
    }

    // ------------------------------------------------------------------ file plumbing

    /** The parent's real file, stored right after our ctx inside the same allocation. */
    private fun inner(file: CPointer<sqlite3_file>?): CPointer<sqlite3_file> =
        interpretCPointer<sqlite3_file>(file!!.rawValue + INNER_OFF)!!

    private fun ctx(file: CPointer<sqlite3_file>?): CPointer<ByteVar> =
        interpretCPointer<ByteVar>(file!!.rawValue + FILE_SIZE)!!

    private fun ioMethods(inner: CPointer<sqlite3_file>) = inner.pointed.pMethods!!.pointed

    /** The parent's physical file size through the inner file's methods. */
    private fun physSize(inner: CPointer<sqlite3_file>): Long = memScoped {
        val out = alloc<LongVar>()
        check(ioMethods(inner).xFileSize!!.invoke(inner, out.ptr) == SQLITE_OK)
        out.value
    }

    // ------------------------------------------------------------------ open

    private fun vfsOpen(zName: CPointer<ByteVar>?, file: CPointer<sqlite3_file>?, flags: Int, outFlags: CPointer<IntVar>?): Int {
        return try {
            val p = parent ?: return SQLITE_CANTOPEN
            val name = zName?.toKString()
            // The wal-index is memory-mapped — content hooks would never see its pages. Delegated; it
            // holds only index metadata (see the file's doc). Everything else seals.
            if (name != null && name.endsWith("-shm"))
                return p.pointed.xOpen!!.invoke(p, zName, file, flags, outFlags)
            val k = key ?: return SQLITE_CANTOPEN // only reached if opened outside MacSql.openSealed
            val c = ctx(file)
            for (i in 0 until 32) c[CTX_KEY + i] = k[i]
            val inn = inner(file)
            val rc = p.pointed.xOpen!!.invoke(p, zName, inn, flags, outFlags)
            if (rc != SQLITE_OK) return rc
            file!!.pointed.pMethods = methods
            val phys = physSize(inn)
            if (phys == 0L) {
                // A fresh file: a random salt and the format header — the only unsealed bytes it will ever have.
                val salt = secureRandom(16)
                for (i in 0 until 16) c[CTX_SALT + i] = salt[i]
                val header = ByteArray(HEADER.toInt())
                MAGIC.copyInto(header)
                header[4] = 0.toByte(); header[5] = 1.toByte() // version 1, BE
                for (i in 0 until 4) header[6 + i] = (BLOCK shr (24 - i * 8)).toByte()
                salt.copyInto(header, 10)
                val wrc = header.usePinned { ioMethods(inn).xWrite!!.invoke(inn, it.addressOf(0).reinterpret(), HEADER.toInt(), 0L) }
                if (wrc != SQLITE_OK) { ioMethods(inn).xClose!!.invoke(inn); return SQLITE_IOERR }
            } else {
                val header = ByteArray(HEADER.toInt())
                val rrc = header.usePinned { ioMethods(inn).xRead!!.invoke(inn, it.addressOf(0).reinterpret(), HEADER.toInt(), 0L) }
                val ok = rrc == SQLITE_OK && header.copyOfRange(0, 4).contentEquals(MAGIC) &&
                    header[4].toInt() == 0 && header[5].toInt() == 1 &&
                    header[6].toInt() == 0 && header[7].toInt() == 0 &&
                    header[8].toInt() == (BLOCK shr 8) && header[9].toInt() == (BLOCK and 0xFF)
                if (!ok) {
                    // Not our format — a plain database must have been migrated before this VFS opens it
                    // (MacSql.openSealed does); anything else is damage the caller keeps aside.
                    ioMethods(inn).xClose!!.invoke(inn)
                    return SQLITE_CANTOPEN
                }
                for (i in 0 until 16) c[CTX_SALT + i] = header[10 + i]
            }
            SQLITE_OK
        } catch (e: Throwable) {
            SQLITE_CANTOPEN
        }
    }

    // ------------------------------------------------------------------ blocks

    private class Corrupt(message: String) : Exception(message)
    private class IoFail(message: String) : Exception(message)

    /** The per-block AAD: the file's salt plus the block index, so blocks can neither be forged nor moved. */
    private fun aad(file: CPointer<sqlite3_file>?, bi: Long): ByteArray {
        val c = ctx(file)
        return ByteArray(24).also { a ->
            for (i in 0 until 16) a[i] = c[CTX_SALT + i]
            for (i in 0 until 8) a[16 + i] = (bi ushr (56 - i * 8)).toByte()
        }
    }

    /** One physical block → its plaintext, null past EOF. Throws [Corrupt] on damage, [IoFail] on io errors. */
    private fun readBlock(file: CPointer<sqlite3_file>?, inner: CPointer<sqlite3_file>, bi: Long, phys: Long): ByteArray? {
        val at = HEADER + bi * PHYS_BLOCK
        if (at >= phys) return null
        val buf = ByteArray(PHYS_BLOCK)
        val rc = buf.usePinned { ioMethods(inner).xRead!!.invoke(inner, it.addressOf(0).reinterpret(), PHYS_BLOCK, at) }
        if (rc == SQLITE_IOERR_SHORT_READ) throw Corrupt("torn block $bi")
        if (rc != SQLITE_OK) throw IoFail("read block $bi ($rc)")
        val c = ctx(file)
        val key = ByteArray(32) { c[CTX_KEY + it] }
        val nonce = buf.copyOfRange(0, NONCE)
        return AesGcm.open(key, nonce, aad(file, bi), buf.copyOfRange(NONCE, PHYS_BLOCK)) ?: throw Corrupt("block $bi")
    }

    /** Seals [plain] (a full 4096-byte block, zero-padded past the logical end) over block [bi]. */
    private fun writeBlock(file: CPointer<sqlite3_file>?, inner: CPointer<sqlite3_file>, bi: Long, plain: ByteArray) {
        val c = ctx(file)
        val key = ByteArray(32) { c[CTX_KEY + it] }
        val nonce = secureRandom(NONCE)
        val blob = nonce + AesGcm.seal(key, nonce, aad(file, bi), plain)
        val rc = blob.usePinned { ioMethods(inner).xWrite!!.invoke(inner, it.addressOf(0).reinterpret(), PHYS_BLOCK, HEADER + bi * PHYS_BLOCK) }
        if (rc != SQLITE_OK) throw IoFail("write block $bi ($rc)")
    }

    private fun ioRead(file: CPointer<sqlite3_file>?, buf: CPointer<ByteVar>?, amt: Int, ofst: Long): Int = try {
        val inner = inner(file)
        val phys = physSize(inner)
        var done = 0
        while (done < amt) {
            val pos = ofst + done
            val bi = pos / BLOCK
            val off = (pos % BLOCK).toInt()
            val n = minOf(BLOCK - off, amt - done)
            val plain = readBlock(file, inner, bi, phys)
            if (plain == null) for (i in 0 until n) buf!![done + i] = 0.toByte()
            else for (i in 0 until n) buf!![done + i] = plain[off + i]
            done += n
        }
        SQLITE_OK
    } catch (e: Corrupt) {
        SQLITE_CORRUPT
    } catch (e: Throwable) {
        SQLITE_IOERR_READ
    }

    private fun ioWrite(file: CPointer<sqlite3_file>?, buf: CPointer<ByteVar>?, amt: Int, ofst: Long): Int = try {
        val inner = inner(file)
        val phys = physSize(inner)
        var done = 0
        while (done < amt) {
            val pos = ofst + done
            val bi = pos / BLOCK
            val off = (pos % BLOCK).toInt()
            val n = minOf(BLOCK - off, amt - done)
            // Read-modify-write at block granularity: the whole block is re-sealed with a fresh nonce.
            val plain = readBlock(file, inner, bi, phys) ?: ByteArray(BLOCK)
            for (i in 0 until n) plain[off + i] = buf!![done + i]
            writeBlock(file, inner, bi, plain)
            done += n
        }
        SQLITE_OK
    } catch (e: Corrupt) {
        SQLITE_CORRUPT
    } catch (e: Throwable) {
        SQLITE_IOERR_WRITE
    }

    private fun ioTruncate(file: CPointer<sqlite3_file>?, size: Long): Int = try {
        val inner = inner(file)
        val nBlocks = (size + BLOCK - 1) / BLOCK
        val phys = HEADER + nBlocks * PHYS_BLOCK
        val rc = ioMethods(inner).xTruncate!!.invoke(inner, phys)
        if (rc == SQLITE_OK && size % BLOCK != 0L) {
            // The last block now holds stale ciphertext past the new end: zero the tail and re-seal, so a
            // later read of that region can't see old bytes.
            val bi = size / BLOCK
            val plain = readBlock(file, inner, bi, phys) ?: ByteArray(BLOCK)
            plain.fill(0.toByte(), (size % BLOCK).toInt(), BLOCK)
            writeBlock(file, inner, bi, plain)
        }
        rc
    } catch (e: Corrupt) {
        SQLITE_CORRUPT
    } catch (e: Throwable) {
        SQLITE_IOERR
    }

    private fun ioFileSize(file: CPointer<sqlite3_file>?, out: CPointer<LongVar>?): Int = try {
        val phys = physSize(inner(file))
        out!!.pointed.value = if (phys <= HEADER) 0L else (phys - HEADER) / PHYS_BLOCK * BLOCK
        SQLITE_OK
    } catch (e: Throwable) {
        SQLITE_IOERR
    }

    private fun ioClose(file: CPointer<sqlite3_file>?): Int =
        runCatching { ioMethods(inner(file)).xClose!!.invoke(inner(file)) }.getOrDefault(SQLITE_IOERR)
}
