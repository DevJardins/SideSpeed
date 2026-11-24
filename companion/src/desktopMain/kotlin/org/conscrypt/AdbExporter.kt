package org.conscrypt

import java.security.MessageDigest
import java.security.Provider
import javax.crypto.Mac
import javax.crypto.MacSpi
import javax.crypto.spec.SecretKeySpec

/**
 * Bouncy Castle 1.85 destroys the TLS 1.3 exporter secret before the handshake
 * call returns. Kadb exports after that. This provider wraps Bouncy Castle's
 * MAC just long enough to keep a copy of the exporter secret on the handshake
 * thread. It is not registered with [java.security.Security].
 */
internal class RememberingProvider(private val delegate: Provider) :
    Provider("SideSpeedBc", "1.0", "ADB TLS crypto") {

    override fun getService(type: String?, algorithm: String?): Service? {
        val service = delegate.getService(type, algorithm) ?: return null
        if (type != "Mac") return service
        val macAlgorithm = algorithm ?: return service
        return object : Service(this, type, macAlgorithm, RememberingMacSpi::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?): Any {
                val mac = Mac.getInstance(macAlgorithm, delegate)
                return RememberingMacSpi(delegate, macAlgorithm, mac)
            }
        }
    }
}

internal object AdbExporter {
    private class Saved(val provider: Provider, val macAlgorithm: String, val secret: ByteArray)

    private val current = ThreadLocal<Saved>()

    fun remember(provider: Provider, macAlgorithm: String, secret: ByteArray) {
        current.set(Saved(provider, macAlgorithm, secret.copyOf()))
    }

    fun export(label: String, context: ByteArray?, length: Int): ByteArray? {
        val saved = current.get() ?: return null
        val empty = digest(saved, ByteArray(0))
        val derived = hkdfExpand(
            saved,
            saved.secret,
            hkdfLabel(label, empty, empty.size),
            empty.size,
        )
        val contextHash = if (context == null || context.isEmpty()) empty else digest(saved, context)
        return hkdfExpand(saved, derived, hkdfLabel("exporter", contextHash, length), length)
    }

    private fun digest(saved: Saved, input: ByteArray): ByteArray {
        val name = when {
            saved.macAlgorithm.contains("256") -> "SHA-256"
            saved.macAlgorithm.contains("384") -> "SHA-384"
            saved.macAlgorithm.contains("512") -> "SHA-512"
            else -> throw IllegalStateException("Unsupported MAC ${saved.macAlgorithm}")
        }
        return MessageDigest.getInstance(name, saved.provider).digest(input)
    }

    /** HKDF-Expand, matching Bouncy Castle's one-block-at-a-time HMAC. */
    private fun hkdfExpand(saved: Saved, prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance(saved.macAlgorithm, saved.provider)
        hmac.init(SecretKeySpec(prk, saved.macAlgorithm))
        val hashLen = hmac.macLength
        val okm = ByteArray(length)
        val block = ByteArray(hashLen)
        var counter = 0
        var pos = 0
        while (true) {
            hmac.update(info)
            counter += 1
            hmac.update(counter.toByte())
            hmac.doFinal(block, 0)
            val remaining = length - pos
            if (remaining <= hashLen) {
                block.copyInto(okm, pos, 0, remaining)
                return okm
            }
            block.copyInto(okm, pos, 0, hashLen)
            pos += hashLen
            hmac.update(block)
        }
    }

    /**
     * TLS 1.3 HkdfLabel: uint16 length, uint8 label length, "tls13 " + label, uint8 context length, context.
     */
    private fun hkdfLabel(label: String, context: ByteArray, length: Int): ByteArray {
        val prefix = "tls13 ".encodeToByteArray()
        val labelBytes = ByteArray(label.length) { label[it].code.toByte() }
        val expanded = prefix.size + labelBytes.size
        val out = ByteArray(2 + 1 + expanded + 1 + context.size)
        out[0] = (length ushr 8).toByte()
        out[1] = length.toByte()
        out[2] = expanded.toByte()
        prefix.copyInto(out, 3)
        labelBytes.copyInto(out, 3 + prefix.size)
        out[3 + expanded] = context.size.toByte()
        context.copyInto(out, 4 + expanded)
        return out
    }
}

/** Copies the TLS 1.3 exporter master secret out of HKDF-Expand. */
internal class RememberingMacSpi(
    private val provider: Provider,
    private val macAlgorithm: String,
    private val delegate: Mac,
) : MacSpi() {
    private val seen = java.io.ByteArrayOutputStream()

    override fun engineGetMacLength(): Int = delegate.macLength

    override fun engineInit(key: java.security.Key, params: java.security.spec.AlgorithmParameterSpec?) {
        seen.reset()
        if (params == null) delegate.init(key) else delegate.init(key, params)
    }

    override fun engineUpdate(input: Byte) {
        seen.write(input.toInt())
        delegate.update(input)
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        seen.write(input, offset, len)
        delegate.update(input, offset, len)
    }

    override fun engineDoFinal(): ByteArray {
        val out = delegate.doFinal()
        val info = seen.toByteArray()
        seen.reset()
        if (isExporterMaster(info)) {
            AdbExporter.remember(provider, macAlgorithm, out)
        }
        return out
    }

    override fun engineReset() {
        seen.reset()
        delegate.reset()
    }

    private fun isExporterMaster(info: ByteArray): Boolean {
        if (info.size < 3 + EXPORTER_MASTER.size) return false
        if ((info[2].toInt() and 0xff) != EXPORTER_MASTER.size) return false
        for (i in EXPORTER_MASTER.indices) {
            if (info[3 + i] != EXPORTER_MASTER[i]) return false
        }
        return true
    }

    private companion object {
        val EXPORTER_MASTER = "tls13 exp master".encodeToByteArray()
    }
}
