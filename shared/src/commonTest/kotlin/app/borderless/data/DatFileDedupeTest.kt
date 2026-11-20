package app.borderless.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-ROUTING-0102: a `.dat` carrying a category code twice must read as one category — downstream the file is a
 * map and a duplicate code could never be told apart (and made Android's category list crash on duplicate keys).
 */
class DatFileDedupeTest {
    /** One protobuf entry: field 1 = the category code, field 2 = one item per [items] block. */
    private fun entry(code: String, vararg items: ByteArray): ByteArray {
        val c = code.encodeToByteArray()
        val body = byteArrayOf(0x0A, c.size.toByte()) + c +
            items.flatMap { listOf(0x12.toByte(), it.size.toByte()) + it.toList() }.toByteArray()
        return byteArrayOf(0x0A, body.size.toByte()) + body
    }

    @Test
    fun repeatedCategoryCodeKeepsOneEntry() {
        val dat = entry("DUPE", byteArrayOf(0x61)) + entry("dupe", byteArrayOf(0x62), byteArrayOf(0x63))
        val p = DatFile.read(dat) ?: error("not a .dat")
        assertEquals(1, p.categories.size)
        // The last entry wins, as the core's own map decode resolves a duplicate field.
        assertEquals(2, p.categories.single().size)
    }

    @Test
    fun distinctCodesKeepFileOrder() {
        val dat = entry("ONE", byteArrayOf(0x61)) + entry("TWO", byteArrayOf(0x62)) + entry("THREE")
        val p = DatFile.read(dat) ?: error("not a .dat")
        assertEquals(listOf("ONE", "TWO", "THREE"), p.categories.map { it.code })
    }
}
