package io.github.devjardins.sidespeed.companion.install

import io.github.devjardins.sidespeed.helper.PackageNames

/**
 * Pure-Kotlin parser for Android binary XML (`AndroidManifest.xml` / AXML).
 * Reads `<manifest>` metadata and literal `<application android:label>` without native dependencies.
 */
object AxmlManifestParser {
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_XML_START_ELEMENT = 0x0102
    private const val CHUNK_XML_END_ELEMENT = 0x0103

    private const val FLAG_UTF8 = 0x00000100

    private const val ATTR_MIN_SDK = 0x0101020c
    private const val ATTR_LABEL = 0x01010001
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_IS_FEATURE_SPLIT = 0x0101055b
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    fun parse(bytes: ByteArray): ApkManifestInfo? {
        if (bytes.size < 8) return null
        val rootType = u16(bytes, 0)
        val rootHeaderSize = u16(bytes, 2)
        val rootSize = u32(bytes, 4)
        if (rootType != CHUNK_XML || rootHeaderSize < 8 || rootSize < rootHeaderSize || rootSize > bytes.size.toLong()) {
            return null
        }

        val xmlEnd = rootSize.toInt()
        var pos = rootHeaderSize
        var stringPool: StringPool? = null
        var resourceIds = IntArray(0)

        var depth = 0
        var seenManifest = false
        var seenApplication = false
        var packageName: String? = null
        var hasVersionCode = false
        var versionCodeLow = 0
        var versionCodeMajor = 0
        var versionName: String? = null
        var splitName: String? = null
        var configForSplit: String? = null
        var isFeatureSplit = false
        var minSdkVersion: Int? = null
        var literalLabel: String? = null

        while (pos + 8 <= xmlEnd) {
            val chunkType = u16(bytes, pos)
            val headerSize = u16(bytes, pos + 2)
            val chunkSize = u32(bytes, pos + 4)
            if (headerSize < 8 || chunkSize < headerSize || pos.toLong() + chunkSize > xmlEnd.toLong()) {
                return null
            }
            val chunkEnd = (pos.toLong() + chunkSize).toInt()

            when (chunkType) {
                CHUNK_STRING_POOL -> {
                    stringPool = parseStringPool(bytes, pos, headerSize, chunkEnd) ?: return null
                }
                CHUNK_RESOURCE_MAP -> {
                    val dataStart = pos + headerSize
                    if (dataStart > chunkEnd) return null
                    val count = (chunkEnd - dataStart) / 4
                    resourceIds = IntArray(count) { i -> i32(bytes, dataStart + i * 4) }
                }
                CHUNK_XML_START_ELEMENT -> {
                    val pool = stringPool ?: return null
                    val attrExt = pos + headerSize
                    if (attrExt + 20 > chunkEnd) return null
                    val elemNameIdx = i32(bytes, attrExt + 4)
                    val attrStart = u16(bytes, attrExt + 8)
                    val attrSize = u16(bytes, attrExt + 10)
                    val attrCount = u16(bytes, attrExt + 12)
                    val elemName = pool.get(elemNameIdx) ?: return null

                    depth++
                    if (depth == 1) {
                        if (elemName != "manifest" || seenManifest) return null
                        seenManifest = true
                    }

                    val inspectManifest = depth == 1 && elemName == "manifest"
                    val inspectApplication = depth == 2 && elemName == "application" && !seenApplication
                    if (inspectApplication) seenApplication = true
                    val inspectUsesSdk = depth == 2 && elemName == "uses-sdk"

                    if (inspectManifest || inspectApplication || inspectUsesSdk) {
                        if (attrCount > 0 && attrSize < 20) return null
                        val attrsEnd = attrExt.toLong() + attrStart.toLong() + attrCount.toLong() * attrSize.toLong()
                        if (attrsEnd > chunkEnd.toLong()) return null

                        for (i in 0 until attrCount) {
                            val aOff = attrExt + attrStart + i * attrSize
                            val attrNsIdx = i32(bytes, aOff)
                            val attrNameIdx = i32(bytes, aOff + 4)
                            val dataType = bytes[aOff + 15].toInt() and 0xFF
                            val data = i32(bytes, aOff + 16)
                            val resId = if (attrNameIdx in resourceIds.indices) resourceIds[attrNameIdx] else 0
                            val attrName = pool.get(attrNameIdx)
                            val ns = if (attrNsIdx >= 0) pool.get(attrNsIdx) else null

                            if (inspectManifest) {
                                when {
                                    resId == ATTR_VERSION_CODE || (resId == 0 && ns == ANDROID_NAMESPACE && attrName == "versionCode") -> {
                                        if (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) {
                                            versionCodeLow = data
                                            hasVersionCode = true
                                        }
                                    }
                                    resId == ATTR_VERSION_CODE_MAJOR || (resId == 0 && ns == ANDROID_NAMESPACE && attrName == "versionCodeMajor") -> {
                                        if (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) {
                                            versionCodeMajor = data
                                        }
                                    }
                                    resId == ATTR_VERSION_NAME || (resId == 0 && ns == ANDROID_NAMESPACE && attrName == "versionName") -> {
                                        if (dataType == TYPE_STRING) {
                                            versionName = pool.get(data)?.takeIf { it.isNotEmpty() }
                                        }
                                    }
                                    resId == ATTR_IS_FEATURE_SPLIT || (resId == 0 && ns == ANDROID_NAMESPACE && attrName == "isFeatureSplit") -> {
                                        if (dataType == TYPE_INT_BOOLEAN || dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) {
                                            isFeatureSplit = data != 0
                                        }
                                    }
                                    attrNsIdx < 0 && attrName == "package" -> {
                                        if (dataType == TYPE_STRING) {
                                            packageName = pool.get(data)
                                        }
                                    }
                                    attrNsIdx < 0 && attrName == "split" -> {
                                        if (dataType == TYPE_STRING) {
                                            splitName = pool.get(data)?.takeIf { it.isNotBlank() }
                                        }
                                    }
                                    attrNsIdx < 0 && attrName == "configForSplit" -> {
                                        if (dataType == TYPE_STRING) {
                                            configForSplit = pool.get(data)?.takeIf { it.isNotBlank() }
                                        }
                                    }
                                }
                            } else if (inspectUsesSdk) {
                                if (resId == ATTR_MIN_SDK || (resId == 0 && ns == ANDROID_NAMESPACE && attrName == "minSdkVersion")) {
                                    when (dataType) {
                                        TYPE_INT_DEC, TYPE_INT_HEX -> if (data > 0) minSdkVersion = data
                                        TYPE_STRING -> pool.get(data)?.toIntOrNull()?.takeIf { it > 0 }?.let {
                                            minSdkVersion = it
                                        }
                                    }
                                }
                            } else {
                                if (resId == ATTR_LABEL || (resId == 0 && ns == ANDROID_NAMESPACE && attrName == "label")) {
                                    if (dataType == TYPE_STRING) {
                                        literalLabel = pool.get(data)?.trim()?.takeIf { it.isNotEmpty() }
                                    }
                                }
                            }
                        }
                    }
                }
                CHUNK_XML_END_ELEMENT -> {
                    depth--
                    if (depth < 0) return null
                    if (depth == 0) {
                        pos = chunkEnd
                        break
                    }
                }
            }

            pos = chunkEnd
        }

        if (!seenManifest || depth != 0) return null
        val pkg = packageName ?: return null
        if (!PackageNames.isValid(pkg) || !hasVersionCode) return null

        val fullVersionCode =
            ((versionCodeMajor.toLong() and 0xFFFF_FFFFL) shl 32) or
                (versionCodeLow.toLong() and 0xFFFF_FFFFL)

        return ApkManifestInfo(
            packageName = pkg,
            versionCode = fullVersionCode,
            versionName = versionName,
            splitName = splitName,
            configForSplit = configForSplit,
            isFeatureSplit = isFeatureSplit,
            literalLabel = literalLabel,
            minSdkVersion = minSdkVersion,
        )
    }

    private fun parseStringPool(bytes: ByteArray, pos: Int, headerSize: Int, chunkEnd: Int): StringPool? {
        if (headerSize < 28 || pos + 28 > chunkEnd) return null
        val stringCountL = u32(bytes, pos + 8)
        val styleCountL = u32(bytes, pos + 12)
        val flags = i32(bytes, pos + 16)
        val stringsStartL = u32(bytes, pos + 20)

        val maxOffsetEntries = (chunkEnd - pos - headerSize) / 4L
        if (stringCountL > maxOffsetEntries || styleCountL > maxOffsetEntries - stringCountL) return null
        if (stringsStartL < headerSize.toLong() || pos.toLong() + stringsStartL > chunkEnd.toLong()) return null

        return StringPool(
            bytes = bytes,
            offsetsStart = pos + headerSize,
            stringCount = stringCountL.toInt(),
            poolStart = (pos.toLong() + stringsStartL).toInt(),
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
            val afterCharLen = skipUtf8Length(start) ?: return null
            val byteLenPair = readUtf8Length(afterCharLen) ?: return null
            val byteLen = byteLenPair.first
            val dataStart = byteLenPair.second
            if (byteLen < 0 || dataStart.toLong() + byteLen.toLong() > chunkEnd.toLong()) return null
            return String(bytes, dataStart, byteLen, Charsets.UTF_8)
        }

        private fun skipUtf8Length(pos: Int): Int? = readUtf8Length(pos)?.second

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
