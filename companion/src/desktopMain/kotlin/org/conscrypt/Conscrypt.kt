package org.conscrypt

import org.bouncycastle.jsse.BCExtendedSSLSession
import org.bouncycastle.jsse.BCSSLSocket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Kadb looks this class up by reflection and calls [exportKeyingMaterial] after
 * the ADB pairing handshake. The method is static so the JVM signature is
 * `(Ljavax/net/ssl/SSLSocket;Ljava/lang/String;[BI)[B`.
 *
 * Bouncy Castle 1.85's [BCExtendedSSLSession.exportKeyingMaterialData] is a
 * stub, and it destroys the exporter secret before the handshake call
 * returns. [AdbExporter] keeps a copy of that secret for the RFC 5705 /
 * RFC 8446 export Kadb asks for afterwards.
 */
object Conscrypt {
    @JvmStatic
    fun exportKeyingMaterial(
        socket: SSLSocket,
        label: String,
        context: ByteArray?,
        length: Int,
    ): ByteArray {
        if (socket !is BCSSLSocket) {
            throw SSLException("Not a Bouncy Castle TLS socket: ${socket.javaClass.name}")
        }
        val session = socket.session
        if (session is BCExtendedSSLSession) {
            try {
                return session.exportKeyingMaterialData(label, context, length)
            } catch (_: UnsupportedOperationException) {
                // bctls 1.85 does not implement the session method. Fall through.
            }
        }
        return AdbExporter.export(label, context, length)
            ?: throw SSLException("ADB keying material is not available for this handshake")
    }
}
