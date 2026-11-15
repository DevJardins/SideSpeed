package io.github.devjardins.sidespeed.adb

import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo

/** Persistent self-signed ADB identity: generated once, then reused so the TV's authorisation sticks. */
class AdbIdentity(private val dir: File) {
    private val keyFile get() = File(dir, "adb_key.pk8")
    private val certFile get() = File(dir, "adb_cert.der")

    fun loadOrCreate(): Pair<PrivateKey, Certificate> {
        if (keyFile.exists() && certFile.exists()) {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            return key to cert
        }
        dir.mkdirs()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = generateCertificate(pair.private, pair.public)
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return pair.private to cert
    }

    private fun generateCertificate(privateKey: PrivateKey, publicKey: PublicKey): Certificate {
        val algorithm = "SHA512withRSA"
        val name = X500Name("CN=SideSpeed")
        val notBefore = Date()
        val notAfter = Date(notBefore.time + TEN_YEARS_MS)
        val extensions = CertificateExtensions().apply {
            set(
                "SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier),
            )
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(name))
            set("key", CertificateX509Key(publicKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(name))
            set("extensions", extensions)
        }
        return X509CertImpl(info).apply { sign(privateKey, algorithm) }
    }

    private companion object {
        const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
