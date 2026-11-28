package io.github.devjardins.sidespeed.companion.install

/**
 * Minimal bounded reader for bundletool's `toc.pb` (`BuildApksResult` protobuf).
 *
 * Only the fields SideSpeed needs are understood: variant targeting's
 * `sdk_version_targeting` min SDK plus every variant's `apk_set`/`apk_description` paths.
 * The layout matches bundletool's `commands.proto` (`variant = 1`, `targeting = 1`,
 * `apk_set = 2`, `ApkDescription { targeting = 1; path = 2; }`) and `targeting.proto`
 * (`VariantTargeting.sdk_version_targeting = 1`, `SdkVersionTargeting.value = 1`,
 * `SdkVersion.min = 1` as an `Int32Value` wrapper). Anything else is skipped; anything
 * malformed fails closed with null. The caller caps the input size.
 */
internal object BundletoolToc {
    data class TocVariant(
        val minSdk: Int?,
        val paths: Set<String>,
    )

    private const val MAX_VARIANTS = 256
    private const val MAX_PATHS = 8_192
    private const val MAX_PATH_BYTES = 4_096

    private class Malformed : Exception()

    fun parse(bytes: ByteArray): List<TocVariant>? {
        return try {
            val variants = ArrayList<TocVariant>()
            val reader = Reader(bytes, 0, bytes.size)
            while (!reader.atEnd) {
                val (field, wire) = reader.tag()
                if (field == 1 && wire == Wire.LEN) {
                    if (variants.size >= MAX_VARIANTS) throw Malformed()
                    variants += parseVariant(reader.delimited())
                } else {
                    reader.skip(wire)
                }
            }
            variants
        } catch (_: Malformed) {
            null
        }
    }

    private fun parseVariant(reader: Reader): TocVariant {
        var minSdk: Int? = null
        val paths = LinkedHashSet<String>()
        while (!reader.atEnd) {
            val (field, wire) = reader.tag()
            when {
                field == 1 && wire == Wire.LEN -> {
                    parseVariantTargeting(reader.delimited())?.let { sdk ->
                        minSdk = if (minSdk == null) sdk else maxOf(minSdk!!, sdk)
                    }
                }
                field == 2 && wire == Wire.LEN -> parseApkSet(reader.delimited(), paths)
                else -> reader.skip(wire)
            }
        }
        return TocVariant(minSdk = minSdk, paths = paths)
    }

    private fun parseVariantTargeting(reader: Reader): Int? {
        var best: Int? = null
        while (!reader.atEnd) {
            val (field, wire) = reader.tag()
            if (field == 1 && wire == Wire.LEN) {
                parseSdkVersionTargeting(reader.delimited())?.let { sdk ->
                    best = if (best == null) sdk else maxOf(best!!, sdk)
                }
            } else {
                reader.skip(wire)
            }
        }
        return best
    }

    private fun parseSdkVersionTargeting(reader: Reader): Int? {
        var best: Int? = null
        while (!reader.atEnd) {
            val (field, wire) = reader.tag()
            if (field == 1 && wire == Wire.LEN) {
                parseSdkVersion(reader.delimited())?.let { sdk ->
                    best = if (best == null) sdk else maxOf(best!!, sdk)
                }
            } else {
                reader.skip(wire)
            }
        }
        return best
    }

    private fun parseSdkVersion(reader: Reader): Int? {
        var min: Int? = null
        while (!reader.atEnd) {
            val (field, wire) = reader.tag()
            if (field == 1 && wire == Wire.LEN) {
                parseInt32Value(reader.delimited())?.let { value ->
                    min = if (min == null) value else maxOf(min!!, value)
                }
            } else {
                reader.skip(wire)
            }
        }
        return min
    }

    private fun parseInt32Value(reader: Reader): Int? {
        var value: Int? = null
        while (!reader.atEnd) {
            val (field, wire) = reader.tag()
            if (field == 1 && wire == Wire.VARINT) {
                reader.varint().takeIf { it in 1..100_000 }?.toInt()?.let { sdk ->
                    value = if (value == null) sdk else maxOf(value!!, sdk)
                }
            } else {
                reader.skip(wire)
            }
        }
        return value
    }

    private fun parseApkSet(reader: Reader, paths: MutableSet<String>) {
        while (!reader.atEnd) {
            val (field, wire) = reader.tag()
            if (field == 2 && wire == Wire.LEN) {
                parseApkDescription(reader.delimited())?.let { path ->
                    if (paths.size >= MAX_PATHS) throw Malformed()
                    paths += path
                }
            } else {
                reader.skip(wire)
            }
        }
    }

    private fun parseApkDescription(reader: Reader): String? {
        var path: String? = null
        while (!reader.atEnd) {
            val (field, wire) = reader.tag()
            if (field == 2 && wire == Wire.LEN) {
                path = reader.readString(MAX_PATH_BYTES) ?: throw Malformed()
            } else {
                reader.skip(wire)
            }
        }
        return path
    }

    private object Wire {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LEN = 2
        const val FIXED32 = 5
    }

    private class Reader(
        private val bytes: ByteArray,
        private var pos: Int,
        private val limit: Int,
    ) {
        val atEnd: Boolean get() = pos >= limit

        fun tag(): Pair<Int, Int> {
            val raw = varint()
            val field = (raw ushr 3).toInt()
            if (field <= 0) throw Malformed()
            return field to (raw and 0x7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            repeat(10) {
                if (pos >= limit) throw Malformed()
                val b = bytes[pos++].toInt() and 0xFF
                if (shift == 63 && b and 0x7E != 0) throw Malformed()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw Malformed()
        }

        fun delimited(): Reader {
            val length = varint()
            if (length < 0 || length > (limit - pos).toLong()) throw Malformed()
            val end = pos + length.toInt()
            return Reader(bytes, pos, end).also { pos = end }
        }

        fun skip(wire: Int) {
            when (wire) {
                Wire.VARINT -> varint()
                Wire.FIXED64 -> skipBytes(8)
                Wire.LEN -> delimited()
                Wire.FIXED32 -> skipBytes(4)
                else -> throw Malformed()
            }
        }

        fun readString(maxBytes: Int): String? {
            val sub = delimited()
            val length = sub.limit - sub.pos
            if (length > maxBytes) return null
            return String(sub.bytes, sub.pos, length, Charsets.UTF_8)
        }

        private fun skipBytes(count: Int) {
            if (count < 0 || limit - pos < count) throw Malformed()
            pos += count
        }
    }
}
