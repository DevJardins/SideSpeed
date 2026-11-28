package io.github.devjardins.sidespeed.companion.install

/**
 * Minimal bounded reader for the base APK's `resources.arsc`.
 *
 * Resolves one string resource id (for example an `<application android:label>`
 * reference such as `0x7f010000`) to its default-config string value, falling back
 * to the `en` config and then to any config. Follows at most [MAX_REFERENCE_DEPTH]
 * levels of reference indirection and ignores anything that is not a plain string.
 *
 * This parses untrusted files, so every count and offset is capped and range-checked.
 * Returns null on any malformation and never throws.
 */
object ArscLabelResolver {
    /** Cap on `resources.arsc` bytes read; larger tables fall back to the file-stem label. */
    const val MAX_ARSC_BYTES = 16 * 1024 * 1024

    private const val MAX_STRING_COUNT = 200_000
    private const val MAX_PACKAGES = 16
    private const val MAX_ENTRIES_PER_TYPE = 200_000
    private const val MAX_REFERENCE_DEPTH = 2

    private const val CHUNK_TABLE = 0x0002
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_PACKAGE = 0x0200
    private const val CHUNK_TYPE = 0x0201

    private const val FLAG_UTF8 = 0x00000100
    private const val NO_ENTRY = 0xFFFF_FFFFL

    private const val TYPE_STRING = 0x03
    private const val TYPE_REFERENCE = 0x01
    private const val ENTRY_FLAG_COMPLEX = 0x0001

    fun resolve(arscBytes: ByteArray, resId: Int): String? {
        if (resId == 0) return null
        if (arscBytes.size < 12 || arscBytes.size > MAX_ARSC_BYTES) return null
        return try {
            resolveOrNull(arscBytes, resId)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveOrNull(bytes: ByteArray, resId: Int): String? {
        if (u16(bytes, 0) != CHUNK_TABLE) return null
        if (u16(bytes, 2) < 12) return null
        val tableSize = u32(bytes, 4)
        if (tableSize < 12L || tableSize > bytes.size.toLong()) return null
        val tableEnd = tableSize.toInt()
        if (u32(bytes, 8) > MAX_PACKAGES) return null

        val packageId = (resId ushr 24) and 0xFF
        val typeId = (resId ushr 16) and 0xFF
        val entryIndex = resId and 0xFFFF
        if (typeId == 0) return null

        // Order-independent walk: the global string pool may sit anywhere at top level.
        var pool: StringPool? = null
        var pos = 12
        var packagesSeen = 0
        val candidates = ArrayList<Candidate>()
        while (pos + 8 <= tableEnd) {
            val chunkType = u16(bytes, pos)
            val headerSize = u16(bytes, pos + 2)
            val chunkSize = u32(bytes, pos + 4)
            if (headerSize < 8 || chunkSize < headerSize || pos.toLong() + chunkSize > tableEnd.toLong()) {
                return null
            }
            val chunkEnd = (pos.toLong() + chunkSize).toInt()
            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    if (pool == null) {
                        pool = parseStringPool(bytes, pos, headerSize, chunkEnd) ?: return null
                    }
                }
                CHUNK_PACKAGE -> {
                    packagesSeen++
                    if (packagesSeen > MAX_PACKAGES) return null
                    collectCandidates(bytes, pos, headerSize, chunkEnd, packageId, typeId, entryIndex, candidates)
                        ?: return null
                }
            }
            pos = chunkEnd
        }
        val stringPool = pool ?: return null
        for (order in 0..2) {
            for (candidate in candidates) {
                if (candidate.order != order) continue
                resolveValue(bytes, stringPool, candidate.dataType, candidate.data, MAX_REFERENCE_DEPTH)
                    ?.let { return it }
            }
        }
        return null
    }

    private data class Candidate(val order: Int, val dataType: Int, val data: Int)

    private enum class EntryLookup {
        /** This chunk carries the target entry; the candidate (if any) was recorded. */
        DONE,

        /** Not a matching chunk, or the entry is simply absent here; keep scanning. */
        SKIP,

        /** A matching chunk whose structure does not hold up; fail the lookup. */
        BROKEN,
    }

    /**
     * Reads the target entry's value from every string-type chunk of the matching package,
     * tagging each with its config order (default 0, `en` 1, anything else 2). Appends to
     * [candidates] in file order. Returns false on structural malformation.
     */
    private fun collectCandidates(
        bytes: ByteArray,
        pkgStart: Int,
        pkgHeaderSize: Int,
        pkgEnd: Int,
        packageId: Int,
        typeId: Int,
        entryIndex: Int,
        candidates: MutableList<Candidate>,
    ): Boolean {
        if (pkgHeaderSize < 12 || pkgStart + 12 > pkgEnd) return false
        if (u32(bytes, pkgStart + 8) != packageId.toLong()) return true
        var pos = pkgStart + pkgHeaderSize
        while (pos + 8 <= pkgEnd) {
            val chunkType = u16(bytes, pos)
            val headerSize = u16(bytes, pos + 2)
            val chunkSize = u32(bytes, pos + 4)
            if (headerSize < 8 || chunkSize < headerSize || pos.toLong() + chunkSize > pkgEnd.toLong()) {
                return false
            }
            val chunkEnd = (pos.toLong() + chunkSize).toInt()
            if (chunkType == CHUNK_TYPE) {
                when (readTargetEntry(bytes, pos, headerSize, chunkEnd, typeId, entryIndex, candidates)) {
                    EntryLookup.DONE, EntryLookup.SKIP -> Unit
                    EntryLookup.BROKEN -> return false
                }
            }
            pos = chunkEnd
        }
        return true
    }

    /**
     * Records the target entry's value (with its config order) when this chunk is a matching
     * type chunk that carries the entry ([EntryLookup.DONE]). Returns [EntryLookup.SKIP] when
     * the chunk is not ours or simply lacks the entry, and [EntryLookup.BROKEN] when a
     * matching chunk's structure does not hold up.
     */
    private fun readTargetEntry(
        bytes: ByteArray,
        chunkStart: Int,
        headerSize: Int,
        chunkEnd: Int,
        typeId: Int,
        entryIndex: Int,
        candidates: MutableList<Candidate>,
    ): EntryLookup {
        if (headerSize < 20 || chunkStart + 20 > chunkEnd) return EntryLookup.SKIP
        if ((bytes[chunkStart + 8].toInt() and 0xFF) != typeId) return EntryLookup.SKIP
        val entryCount = i32(bytes, chunkStart + 12)
        val entriesStart = i32(bytes, chunkStart + 16)
        if (entryCount < 0 || entryCount > MAX_ENTRIES_PER_TYPE) return EntryLookup.BROKEN
        // The offsets array starts at the header size; entriesStart points past it to the
        // entry data, and each offset is relative to entriesStart.
        val offsetsBytes = entryCount.toLong() * 4L
        if (headerSize.toLong() + offsetsBytes > (chunkEnd - chunkStart).toLong()) {
            return EntryLookup.BROKEN
        }
        if (entriesStart.toLong() < headerSize.toLong() + offsetsBytes) return EntryLookup.BROKEN
        if (entriesStart > chunkEnd - chunkStart) return EntryLookup.BROKEN
        val offsetsStart = chunkStart + headerSize
        if (entryIndex >= entryCount) return EntryLookup.SKIP

        val configLen = headerSize - 20
        val order = localeOrder(bytes, chunkStart + 20, configLen, chunkEnd)

        val rawOffset = u32(bytes, offsetsStart + entryIndex * 4)
        if (rawOffset == NO_ENTRY) return EntryLookup.SKIP
        val entryPos = chunkStart.toLong() + entriesStart.toLong() + rawOffset
        if (entryPos < offsetsStart.toLong() || entryPos + 16 > chunkEnd.toLong()) return EntryLookup.SKIP
        val at = entryPos.toInt()
        // ResTable_entry.size covers the 8-byte entry header only; the Res_value follows it.
        if (u16(bytes, at) < 8) return EntryLookup.SKIP
        if ((u16(bytes, at + 2) and ENTRY_FLAG_COMPLEX) != 0) return EntryLookup.SKIP
        if (u16(bytes, at + 8) < 8) return EntryLookup.SKIP
        val dataType = bytes[at + 11].toInt() and 0xFF
        val data = i32(bytes, at + 12)
        candidates += Candidate(order, dataType, data)
        return EntryLookup.DONE
    }

    private fun localeOrder(bytes: ByteArray, configStart: Int, configLen: Int, chunkEnd: Int): Int {
        if (configLen < 12 || configStart + 12 > chunkEnd) return 2
        val lang0 = bytes[configStart + 8]
        val lang1 = bytes[configStart + 9]
        val country0 = bytes[configStart + 10]
        val country1 = bytes[configStart + 11]
        if (lang0.toInt() == 0 && lang1.toInt() == 0 && country0.toInt() == 0 && country1.toInt() == 0) {
            return 0
        }
        if (lang0 == 'e'.code.toByte() && lang1 == 'n'.code.toByte()) return 1
        return 2
    }

    private fun resolveValue(
        bytes: ByteArray,
        pool: StringPool,
        dataType: Int,
        data: Int,
        remaining: Int,
    ): String? {
        return when (dataType) {
            TYPE_STRING -> pool.get(data)
            TYPE_REFERENCE -> {
                if (remaining <= 0 || data == 0) return null
                val target = lookupValue(bytes, data) ?: return null
                resolveValue(bytes, pool, target.first, target.second, remaining - 1)
            }
            else -> null
        }
    }

    /** Re-walks the table for a referenced id; null when missing or malformed. Order-agnostic: first resolvable config wins. */
    private fun lookupValue(bytes: ByteArray, resId: Int): Pair<Int, Int>? {
        val tableEnd = u32(bytes, 4).toInt()
        val packageId = (resId ushr 24) and 0xFF
        val typeId = (resId ushr 16) and 0xFF
        val entryIndex = resId and 0xFFFF
        if (typeId == 0) return null
        var pool: StringPool? = null
        var pos = 12
        var packagesSeen = 0
        // Prefer the default config for references too; collect and pick in order.
        val found = ArrayList<Candidate>()
        while (pos + 8 <= tableEnd) {
            val chunkType = u16(bytes, pos)
            val headerSize = u16(bytes, pos + 2)
            val chunkSize = u32(bytes, pos + 4)
            if (headerSize < 8 || chunkSize < headerSize || pos.toLong() + chunkSize > tableEnd.toLong()) {
                return null
            }
            val chunkEnd = (pos.toLong() + chunkSize).toInt()
            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    if (pool == null) {
                        pool = parseStringPool(bytes, pos, headerSize, chunkEnd) ?: return null
                    }
                }
                CHUNK_PACKAGE -> {
                    packagesSeen++
                    if (packagesSeen > MAX_PACKAGES) return null
                    collectCandidates(bytes, pos, headerSize, chunkEnd, packageId, typeId, entryIndex, found)
                        ?: return null
                }
            }
            pos = chunkEnd
        }
        if (pool == null) return null
        return found.sortedBy { it.order }.firstOrNull()?.let { it.dataType to it.data }
    }

    private fun parseStringPool(bytes: ByteArray, pos: Int, headerSize: Int, chunkEnd: Int): StringPool? {
        if (headerSize < 28 || pos + 28 > chunkEnd) return null
        val stringCount = u32(bytes, pos + 8)
        val styleCount = u32(bytes, pos + 12)
        val flags = i32(bytes, pos + 16)
        val stringsStart = u32(bytes, pos + 20)
        if (stringCount > MAX_STRING_COUNT) return null
        val maxOffsetEntries = (chunkEnd - pos - headerSize) / 4L
        if (stringCount > maxOffsetEntries || styleCount > maxOffsetEntries - stringCount) return null
        if (stringsStart < headerSize.toLong() || pos.toLong() + stringsStart > chunkEnd.toLong()) return null
        return StringPool(
            bytes = bytes,
            offsetsStart = pos + headerSize,
            stringCount = stringCount.toInt(),
            poolStart = (pos.toLong() + stringsStart).toInt(),
            chunkEnd = chunkEnd,
            utf8 = (flags and FLAG_UTF8) != 0,
        )
    }

    private class StringPool(
        private val bytes: ByteArray,
        private val offsetsStart: Int,
        private val stringCount: Int,
        private val poolStart: Int,
        private val chunkEnd: Int,
        private val utf8: Boolean,
    ) {
        private val cache = arrayOfNulls<String>(stringCount)

        fun get(index: Int): String? {
            if (index < 0 || index >= stringCount) return null
            cache[index]?.let { return it }
            val relOffset = u32(bytes, offsetsStart + index * 4)
            val start = poolStart.toLong() + relOffset
            if (start < poolStart.toLong() || start >= chunkEnd.toLong()) return null
            val decoded = if (utf8) decodeUtf8(start.toInt()) else decodeUtf16(start.toInt())
            if (decoded != null) cache[index] = decoded
            return decoded
        }

        private fun decodeUtf8(start: Int): String? {
            val afterCharLen = readUtf8Length(start)?.second ?: return null
            val byteLenPair = readUtf8Length(afterCharLen) ?: return null
            val byteLen = byteLenPair.first
            val dataStart = byteLenPair.second
            if (byteLen < 0 || dataStart.toLong() + byteLen.toLong() > chunkEnd.toLong()) return null
            return String(bytes, dataStart, byteLen, Charsets.UTF_8)
        }

        private fun readUtf8Length(pos: Int): Pair<Int, Int>? {
            if (pos >= chunkEnd) return null
            val b0 = bytes[pos].toInt() and 0xFF
            return if ((b0 and 0x80) != 0) {
                if (pos + 1 >= chunkEnd) return null
                val b1 = bytes[pos + 1].toInt() and 0xFF
                (((b0 and 0x7F) shl 8) or b1) to (pos + 2)
            } else {
                b0 to (pos + 1)
            }
        }

        private fun decodeUtf16(start: Int): String? {
            if (start + 2 > chunkEnd) return null
            val u0 = u16(bytes, start)
            val charLen: Int
            val dataStart: Int
            if ((u0 and 0x8000) != 0) {
                if (start + 4 > chunkEnd) return null
                val u1 = u16(bytes, start + 2)
                charLen = ((u0 and 0x7FFF) shl 16) or u1
                dataStart = start + 4
            } else {
                charLen = u0
                dataStart = start + 2
            }
            val byteLen = charLen.toLong() * 2L
            if (charLen < 0 || dataStart.toLong() + byteLen > chunkEnd.toLong()) return null
            return String(bytes, dataStart, byteLen.toInt(), Charsets.UTF_16LE)
        }
    }

    private fun u16(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    private fun i32(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)

    private fun u32(bytes: ByteArray, off: Int): Long = i32(bytes, off).toLong() and 0xFFFF_FFFFL
}
