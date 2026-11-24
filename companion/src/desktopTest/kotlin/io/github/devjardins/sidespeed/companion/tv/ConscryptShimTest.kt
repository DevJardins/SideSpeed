package io.github.devjardins.sidespeed.companion.tv

import com.flyfishxu.kadb.cert.KadbCertPolicy
import org.bouncycastle.jsse.BCX509ExtendedKeyManager
import org.bouncycastle.jsse.BCX509Key
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Kadb loads `org.conscrypt.OpenSSLProvider` and `org.conscrypt.Conscrypt.exportKeyingMaterial`
 * by reflection. On the desktop those names are a pure-Java stand-in, so pairing does not
 * depend on a Conscrypt native library.
 */
class ConscryptShimTest {
    @Test
    fun reflectedProviderBuildsTls13Context() {
        val provider = newReflectedProvider()
        assertTrue(provider is Provider)
        val context = SSLContext.getInstance("TLSv1.3", provider)
        assertEquals("TLSv1.3", context.protocol)
    }

    @Test
    fun reflectedExportMethodIsStatic() {
        val method = exportMethod()
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(ByteArray::class.java, method.returnType)
    }

    @Test
    fun loopbackExportsMatchForAdbLabel() {
        val provider = newReflectedProvider() as Provider
        val serverContext = context(provider, newKeyMaterial())
        val clientContext = context(provider, newKeyMaterial())

        val server = serverContext.serverSocketFactory.createServerSocket(
            0,
            1,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        server.use {
            server.enabledProtocols = arrayOf("TLSv1.3")
            server.needClientAuth = true
            server.soTimeout = 10_000

            val exported = arrayOfNulls<ByteArray>(3)
            val failure = arrayOfNulls<Throwable>(1)
            val thread = Thread {
                try {
                    val socket = server.accept() as SSLSocket
                    socket.use { accepted ->
                        accepted.soTimeout = 10_000
                        accepted.startHandshake()
                        exported[0] = export(accepted, ADB_LABEL, EMPTY)
                        exported[1] = export(accepted, OTHER_LABEL, EMPTY)
                        exported[2] = export(accepted, ADB_LABEL, null)
                    }
                } catch (t: Throwable) {
                    failure[0] = t
                }
            }
            thread.isDaemon = true
            thread.start()

            val client = clientContext.socketFactory.createSocket(
                "127.0.0.1",
                server.localPort,
            ) as SSLSocket
            client.use {
                client.enabledProtocols = arrayOf("TLSv1.3")
                client.soTimeout = 10_000
                client.startHandshake()
                val clientBytes = export(client, ADB_LABEL, EMPTY)
                val other = export(client, OTHER_LABEL, EMPTY)
                val clientNull = export(client, ADB_LABEL, null)
                thread.join(10_000)
                failure[0]?.let { throw it }
                val serverBytes = exported[0] ?: error("server did not export")
                val serverOther = exported[1] ?: error("server did not export the other label")
                val serverNull = exported[2] ?: error("server did not export a null context")
                assertEquals(64, clientBytes.size)
                assertTrue(clientBytes.any { it != 0.toByte() })
                assertContentEquals(serverBytes, clientBytes)
                assertContentEquals(serverOther, other)
                assertTrue(!clientBytes.contentEquals(other))
                // Kadb passes a null context. TLS 1.3 treats that as an empty context.
                assertContentEquals(serverNull, clientNull)
                assertContentEquals(clientBytes, clientNull)
            }
        }
    }

    @Test
    fun exportOnSunJsseSocketThrowsSslException() {
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(null, arrayOf<TrustManager>(trustAll()), SecureRandom())
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.use {
            assertFailsWith<SSLException> {
                export(socket, ADB_LABEL, EMPTY)
            }
        }
    }

    @Test
    fun shimDoesNotChangeGlobalProviders() {
        val before = providerNames()
        val provider = newReflectedProvider() as Provider
        val context = SSLContext.getInstance("TLSv1.3", provider)
        context.init(null, arrayOf<TrustManager>(trustAll()), SecureRandom())
        assertEquals(before, providerNames())
    }

    @Test
    fun kadbSslContextUsesTheShim() {
        val utils = Class.forName("com.flyfishxu.kadb.pair.SslUtils").getField("INSTANCE").get(null)
        val getSslContext = utils.javaClass.methods.first { method ->
            method.name == "getSslContext" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0].simpleName == "AdbKeyPair"
        }
        val context = getSslContext.invoke(utils, newAdbKeyPair()) as SSLContext
        val custom = utils.javaClass.getMethod("getCustomConscrypt").invoke(utils) as Boolean
        assertTrue(custom)
        assertEquals("org.conscrypt.OpenSSLProvider", context.provider.javaClass.name)
    }

    private fun newReflectedProvider(): Any =
        Class.forName("org.conscrypt.OpenSSLProvider")
            .getDeclaredConstructor()
            .newInstance()

    private fun exportMethod() = Class.forName("org.conscrypt.Conscrypt").getMethod(
        "exportKeyingMaterial",
        SSLSocket::class.java,
        String::class.java,
        ByteArray::class.java,
        Int::class.javaPrimitiveType,
    )

    private fun export(socket: SSLSocket, label: String, context: ByteArray?): ByteArray =
        try {
            exportMethod().invoke(null, socket, label, context, 64) as ByteArray
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }

    /**
     * CertUtils and AdbKeyPair are internal to Kadb. The JVM methods are public, so the test
     * calls them by reflection and keeps the key pair as [Any].
     */
    private fun newAdbKeyPair(): Any {
        val utils = Class.forName("com.flyfishxu.kadb.cert.CertUtils").getField("INSTANCE").get(null)
        val identity = utils.javaClass
            .getMethod("generateNewIdentity", KadbCertPolicy::class.java)
            .invoke(utils, KadbCertPolicy())
        return identity.javaClass.getMethod("getKeyPair").invoke(identity)
    }

    private fun newKeyMaterial(): Pair<PrivateKey, Certificate> {
        val keyPair = newAdbKeyPair()
        val privateKey = keyPair.javaClass.getMethod("getPrivateKey").invoke(keyPair) as PrivateKey
        val certificate = keyPair.javaClass.getMethod("getCertificate").invoke(keyPair) as Certificate
        return privateKey to certificate
    }

    private fun context(provider: Provider, material: Pair<PrivateKey, Certificate>): SSLContext {
        val managers = arrayOf<KeyManager>(
            FixedKeyManager(material.first, material.second as X509Certificate),
        )
        return SSLContext.getInstance("TLSv1.3", provider).apply {
            init(managers, arrayOf<TrustManager>(trustAll()), SecureRandom())
        }
    }

    /**
     * Kadb installs its own key manager and accepts any peer certificate. Bouncy Castle's
     * stock key manager rejects an ADB certificate that has no key-usage extension, so this
     * test does the same kind of manager: always present the key, trust every peer.
     */
    private class FixedKeyManager(
        private val key: PrivateKey,
        private val certificate: X509Certificate,
    ) : BCX509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = if (keyType.orEmpty().any(::acceptable)) ALIAS else null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
            if (acceptable(keyType)) ALIAS else null

        override fun getCertificateChain(alias: String?) = arrayOf(certificate)

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

        override fun getPrivateKey(alias: String?) = key

        override fun getKeyBC(keyType: String, alias: String): BCX509Key = object : BCX509Key {
            override fun getCertificateChain() = arrayOf(certificate)
            override fun getKeyType() = keyType
            override fun getPrivateKey() = key
        }

        /** rsa_pss_rsae uses the key type "RSA". "RSASSA-PSS" is only for PSS-restricted keys. */
        private fun acceptable(keyType: String?) = keyType == "RSA"

        private companion object {
            const val ALIAS = "key"
        }
    }

    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun providerNames(): List<String> = Security.getProviders().map { "${it.name}:${it.javaClass.name}" }

    private companion object {
        const val ADB_LABEL = "adb-label\u0000"
        const val OTHER_LABEL = "other-label\u0000"
        val EMPTY = ByteArray(0)
    }
}
