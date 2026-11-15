package io.github.devjardins.sidespeed.adb

import java.security.interfaces.RSAPrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbIdentityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun keyIsPersistedAndStable() {
        val identity = AdbIdentity(tmp.root)
        val first = identity.loadOrCreate().first
        val second = AdbIdentity(tmp.root).loadOrCreate().first
        assertArrayEquals(first.encoded, second.encoded)
    }

    @Test
    fun keyIsRsa2048() {
        val key = AdbIdentity(tmp.root).loadOrCreate().first
        assertEquals("RSA", key.algorithm)
        assertEquals(2048, (key as RSAPrivateKey).modulus.bitLength())
    }

    @Test
    fun certIsSelfSignedSideSpeed() {
        val cert = AdbIdentity(tmp.root).loadOrCreate().second as java.security.cert.X509Certificate
        assertEquals("CN=SideSpeed", cert.subjectX500Principal.name)
    }
}
