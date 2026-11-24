package org.conscrypt

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider

/**
 * Kadb looks this class up by reflection (`org.conscrypt.OpenSSLProvider`) and
 * builds its ADB TLS context from a no-arg instance.
 *
 * The TLS stack is Bouncy Castle JSSE. Its crypto is a Bouncy Castle provider
 * held here, not installed into [java.security.Security], so other TLS in the
 * app keeps the JDK default. ADB pairing needs RSA-PSS, which that JDK
 * provider does not offer under the name Bouncy Castle asks for.
 */
class OpenSSLProvider : BouncyCastleJsseProvider(
    RememberingProvider(BouncyCastleProvider()),
)
