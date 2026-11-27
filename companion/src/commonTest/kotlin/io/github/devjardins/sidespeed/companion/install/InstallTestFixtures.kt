package io.github.devjardins.sidespeed.companion.install

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object InstallTestFixtures {
    data class AxmlSpec(
        val packageName: String = "com.example.app",
        val includePackage: Boolean = true,
        val versionCode: Int? = 1,
        val versionCodeMajor: Int? = null,
        val versionName: String? = "1.0",
        val splitName: String? = null,
        val configForSplit: String? = null,
        val isFeatureSplit: Boolean? = null,
        val appLabelLiteral: String? = null,
        val appLabelResId: Int? = null,
        val nestedActivityLabel: String? = null,
        val utf8: Boolean = false,
        val obfuscateResourceAttrNames: Boolean = false,
    )

    fun buildAxml(spec: AxmlSpec): ByteArray {
        val strings = ArrayList<String>()
        val resIds = ArrayList<Int>()

        fun addAttrName(name: String, resId: Int): Int {
            val idx = strings.size
            strings += if (spec.obfuscateResourceAttrNames) "" else name
            resIds += resId
            return idx
        }

        val versionCodeNameIdx = addAttrName("versionCode", 0x0101021b)
        val versionCodeMajorNameIdx = addAttrName("versionCodeMajor", 0x01010576)
        val versionNameNameIdx = addAttrName("versionName", 0x0101021c)
        val isFeatureSplitNameIdx = addAttrName("isFeatureSplit", 0x0101055b)
        val labelNameIdx = addAttrName("label", 0x01010001)

        fun intern(s: String): Int {
            val existing = strings.indexOf(s)
            if (existing >= 0) return existing
            strings += s
            return strings.lastIndex
        }

        val androidPrefixIdx = intern("android")
        val androidNsIdx = intern("http://schemas.android.com/apk/res/android")
        val manifestTagIdx = intern("manifest")
        val applicationTagIdx = intern("application")
        val activityTagIdx = intern("activity")
        val packageNameAttrIdx = intern("package")
        val splitAttrIdx = intern("split")
        val configForSplitAttrIdx = intern("configForSplit")

        data class Attr(
            val ns: Int,
            val name: Int,
            val rawValue: Int,
            val dataType: Int,
            val data: Int,
        )

        val manifestAttrs = ArrayList<Attr>()
        if (spec.includePackage) {
            val pkgStrIdx = intern(spec.packageName)
            manifestAttrs += Attr(-1, packageNameAttrIdx, pkgStrIdx, 0x03, pkgStrIdx)
        }
        if (spec.versionCode != null) {
            manifestAttrs += Attr(androidNsIdx, versionCodeNameIdx, -1, 0x10, spec.versionCode)
        }
        if (spec.versionCodeMajor != null) {
            manifestAttrs += Attr(androidNsIdx, versionCodeMajorNameIdx, -1, 0x10, spec.versionCodeMajor)
        }
        if (spec.versionName != null) {
            val vNameIdx = intern(spec.versionName)
            manifestAttrs += Attr(androidNsIdx, versionNameNameIdx, vNameIdx, 0x03, vNameIdx)
        }
        if (spec.splitName != null) {
            val sIdx = intern(spec.splitName)
            manifestAttrs += Attr(-1, splitAttrIdx, sIdx, 0x03, sIdx)
        }
        if (spec.configForSplit != null) {
            val cIdx = intern(spec.configForSplit)
            manifestAttrs += Attr(-1, configForSplitAttrIdx, cIdx, 0x03, cIdx)
        }
        if (spec.isFeatureSplit != null) {
            manifestAttrs += Attr(
                androidNsIdx,
                isFeatureSplitNameIdx,
                -1,
                0x12,
                if (spec.isFeatureSplit) -1 else 0,
            )
        }

        val appAttrs = ArrayList<Attr>()
        if (spec.appLabelLiteral != null) {
            val lIdx = intern(spec.appLabelLiteral)
            appAttrs += Attr(androidNsIdx, labelNameIdx, lIdx, 0x03, lIdx)
        } else if (spec.appLabelResId != null) {
            appAttrs += Attr(androidNsIdx, labelNameIdx, -1, 0x01, spec.appLabelResId)
        }

        val activityAttrs = ArrayList<Attr>()
        if (spec.nestedActivityLabel != null) {
            val actIdx = intern(spec.nestedActivityLabel)
            activityAttrs += Attr(androidNsIdx, labelNameIdx, actIdx, 0x03, actIdx)
        }

        val stringPoolChunk = encodeStringPool(strings, spec.utf8)
        val resMapChunk = encodeResourceMap(resIds)
        val startNsChunk = encodeNamespace(0x0100, androidPrefixIdx, androidNsIdx)
        val endNsChunk = encodeNamespace(0x0101, androidPrefixIdx, androidNsIdx)
        val startManifest = encodeStartElement(manifestTagIdx, manifestAttrs.map { listOf(it.ns, it.name, it.rawValue, it.dataType, it.data) })
        val endManifest = encodeEndElement(manifestTagIdx)

        val body = ByteArrayOutputStream()
        body.write(stringPoolChunk)
        body.write(resMapChunk)
        body.write(startNsChunk)
        body.write(startManifest)
        if (appAttrs.isNotEmpty() || activityAttrs.isNotEmpty()) {
            body.write(encodeStartElement(applicationTagIdx, appAttrs.map { listOf(it.ns, it.name, it.rawValue, it.dataType, it.data) }))
            if (activityAttrs.isNotEmpty()) {
                body.write(encodeStartElement(activityTagIdx, activityAttrs.map { listOf(it.ns, it.name, it.rawValue, it.dataType, it.data) }))
                body.write(encodeEndElement(activityTagIdx))
            }
            body.write(encodeEndElement(applicationTagIdx))
        }
        body.write(endManifest)
        body.write(endNsChunk)

        val inner = body.toByteArray()
        val out = leBuffer(8 + inner.size)
        out.putShort(0x0003) // RES_XML_TYPE
        out.putShort(8)
        out.putInt(8 + inner.size)
        out.put(inner)
        return out.array()
    }

    private fun encodeStringPool(strings: List<String>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = data.size()
            val s = strings[i]
            if (utf8) {
                val utf16Len = s.length
                val bytes = s.toByteArray(Charsets.UTF_8)
                writeUtf8Length(data, utf16Len)
                writeUtf8Length(data, bytes.size)
                data.write(bytes)
                data.write(0)
            } else {
                val utf16Len = s.length
                writeUtf16Length(data, utf16Len)
                data.write(s.toByteArray(Charsets.UTF_16LE))
                data.write(0)
                data.write(0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val rawStrings = data.toByteArray()
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val chunkSize = stringsStart + rawStrings.size
        val buf = leBuffer(chunkSize)
        buf.putShort(0x0001) // RES_STRING_POOL_TYPE
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(if (utf8) 0x00000100 else 0)
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        for (off in offsets) buf.putInt(off)
        buf.put(rawStrings)
        return buf.array()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write(((len shr 8) or 0x80) and 0xFF)
            out.write(len and 0xFF)
        } else {
            out.write(len and 0x7F)
        }
    }

    private fun writeUtf16Length(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7FFF) {
            val high = ((len shr 16) or 0x8000) and 0xFFFF
            val low = len and 0xFFFF
            out.write(high and 0xFF)
            out.write((high shr 8) and 0xFF)
            out.write(low and 0xFF)
            out.write((low shr 8) and 0xFF)
        } else {
            out.write(len and 0xFF)
            out.write((len shr 8) and 0xFF)
        }
    }

    private fun encodeResourceMap(resIds: List<Int>): ByteArray {
        val chunkSize = 8 + resIds.size * 4
        val buf = leBuffer(chunkSize)
        buf.putShort(0x0180)
        buf.putShort(8)
        buf.putInt(chunkSize)
        for (id in resIds) buf.putInt(id)
        return buf.array()
    }

    private fun encodeNamespace(type: Int, prefixIdx: Int, uriIdx: Int): ByteArray {
        val buf = leBuffer(24)
        buf.putShort(type.toShort())
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(1)
        buf.putInt(-1)
        buf.putInt(prefixIdx)
        buf.putInt(uriIdx)
        return buf.array()
    }

    private fun encodeStartElement(tagNameIdx: Int, attrs: List<List<Int>>): ByteArray {
        val size = 16 + 20 + attrs.size * 20
        val buf = leBuffer(size)
        buf.putShort(0x0102)
        buf.putShort(16)
        buf.putInt(size)
        buf.putInt(1)
        buf.putInt(-1)
        buf.putInt(-1) // ns
        buf.putInt(tagNameIdx)
        buf.putShort(20) // attributeStart
        buf.putShort(20) // attributeSize
        buf.putShort(attrs.size.toShort())
        buf.putShort(0) // idIndex
        buf.putShort(0) // classIndex
        buf.putShort(0) // styleIndex
        for (attr in attrs) {
            buf.putInt(attr[0]) // ns
            buf.putInt(attr[1]) // name
            buf.putInt(attr[2]) // rawValue
            buf.putShort(8) // typedValue size
            buf.put(0) // res0
            buf.put(attr[3].toByte()) // dataType
            buf.putInt(attr[4]) // data
        }
        return buf.array()
    }

    private fun encodeEndElement(tagNameIdx: Int): ByteArray {
        val buf = leBuffer(24)
        buf.putShort(0x0103)
        buf.putShort(16)
        buf.putInt(24)
        buf.putInt(1)
        buf.putInt(-1)
        buf.putInt(-1)
        buf.putInt(tagNameIdx)
        return buf.array()
    }

    private fun leBuffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun buildZipBytes(
        entries: List<Pair<String, ByteArray>>,
        storedUncompressed: Boolean = false,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name.endsWith("/")) {
                    zos.putNextEntry(entry)
                    zos.closeEntry()
                } else {
                    if (storedUncompressed) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    fun buildApkBytes(
        spec: AxmlSpec,
        dummyEntriesBeforeManifest: Int = 0,
        dummyEntriesAfterManifest: Int = 0,
        rawManifestOverride: ByteArray? = null,
        storedManifest: Boolean = false,
    ): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        val dummyPayload = byteArrayOf(1, 2, 3, 4)
        for (i in 0 until dummyEntriesBeforeManifest) {
            entries += "res/raw/r_$i.bin" to dummyPayload
        }
        entries += "AndroidManifest.xml" to (rawManifestOverride ?: buildAxml(spec))
        for (i in 0 until dummyEntriesAfterManifest) {
            entries += "res/raw/a_$i.bin" to dummyPayload
        }
        if (!storedManifest) {
            return buildZipBytes(entries)
        }
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name == "AndroidManifest.xml") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    class ByteArrayInstallSource(
        override val displayName: String,
        private val bytes: ByteArray,
        override val sizeBytes: Long? = bytes.size.toLong(),
    ) : InstallSource {
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
        override fun asZipFile(): ZipFile? = null
        override fun close() = Unit
    }

    class TempZipInstallSource private constructor(
        override val displayName: String,
        private val file: File,
        private val zipFile: ZipFile,
        override val sizeBytes: Long?,
    ) : InstallSource {
        override fun openStream(): InputStream = file.inputStream()
        override fun asZipFile(): ZipFile = zipFile
        override fun close() {
            runCatching { zipFile.close() }
            runCatching { file.delete() }
        }

        companion object {
            fun fromBytes(displayName: String, bytes: ByteArray, sizeOverride: Long? = bytes.size.toLong()): TempZipInstallSource {
                val file = File.createTempFile("sidespeed-install-test-", ".zip").apply {
                    deleteOnExit()
                    writeBytes(bytes)
                }
                return TempZipInstallSource(displayName, file, ZipFile(file), sizeOverride)
            }
        }
    }
}
