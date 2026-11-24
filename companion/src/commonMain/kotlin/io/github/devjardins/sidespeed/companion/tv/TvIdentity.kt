package io.github.devjardins.sidespeed.companion.tv

import io.github.devjardins.sidespeed.companion.store.SavedTv
import java.io.File
import java.security.SecureRandom

/** The TV did not prove it holds its secret. The session is already closed. */
class TvUnproven : Exception("This TV didn't prove it's the one you added")

/** The first serial did not match the saved TV. The session is already closed. */
class TvWrongDevice : Exception("A different device answered at that address")

/** A saved TV has no secret yet, and this call was not allowed to enroll it. Nothing was sent. */
class NeedsConfirmation : Exception("Confirm this TV before SideSpeed uses it")

/** Writing or proving the secret failed, so the TV was not added. The session is already closed. */
class TvCheckNotSaved : Exception("SideSpeed couldn't save its check on this TV")

/** What [TvIdentity.prepare] decided before any command the caller asked for. */
sealed class Prepared {
    /** [enrolled] is true when this call wrote a secret the caller still has to store. */
    data class Ready(val secret: String, val enrolled: Boolean) : Prepared()

    /** The serial did not match, so nothing was written. The session is closed. */
    data object WrongDevice : Prepared()

    /** The proof failed and the session is closed. */
    data object Failed : Prepared()

    /** No secret is saved, and this call was not allowed to write one. The session is closed. Nothing was sent. */
    data object NeedsConfirm : Prepared()
}

/**
 * Proves a TV holds the secret this companion left there, before any other command.
 * [companionId] is [CompanionKeys.companionId] for this companion's ADB public key.
 */
class TvIdentity(
    val companionId: String,
    private val nextBytes: () -> ByteArray = { ByteArray(32).also { random.nextBytes(it) } },
) {
    init {
        require(companionId.matches(ID)) { "companion id must be 16 lowercase hex characters" }
    }

    /** Proves [secretHex] is in this companion's file. Does not close [session]. */
    suspend fun prove(session: TvSession, secretHex: String): Boolean {
        val nonce = nextHex()
        val output = session.run(TvCommands.proveSecret(companionId, nonce))
        return TvProof.accepts(secretHex, nonce, output)
    }

    /**
     * Writes a new secret and proves it. Returns the secret, or null after closing [session]
     * when the proof does not succeed.
     */
    suspend fun enroll(session: TvSession): String? {
        val secret = nextHex()
        session.run(TvCommands.writeSecret(companionId, secret))
        if (!prove(session, secret)) {
            session.close()
            return null
        }
        return secret
    }

    suspend fun forget(session: TvSession) {
        session.run(TvCommands.forgetSecret(companionId))
    }

    /**
     * The check for a TV we already mean to talk to.
     * A saved secret is proved before anything else, including [TvCommands.SERIAL].
     * No secret yet, and [mayEnroll] is false: nothing is sent, the session is closed, and the result is
     * [Prepared.NeedsConfirm]. No secret yet, and [mayEnroll] is true: the serial must still match, and only
     * then is a secret written and proved.
     */
    suspend fun prepare(session: TvSession, tv: SavedTv, mayEnroll: Boolean = false): Prepared {
        if (tv.unproven && tv.identitySecret == null) {
            session.close()
            return Prepared.Failed
        }
        val secret = tv.identitySecret
        if (secret != null) {
            if (!prove(session, secret)) {
                session.close()
                return Prepared.Failed
            }
            return Prepared.Ready(secret, enrolled = false)
        }
        if (!mayEnroll) {
            session.close()
            return Prepared.NeedsConfirm
        }
        val serial = session.run(TvCommands.SERIAL).trim()
        if (serial != tv.serial) {
            session.close()
            return Prepared.WrongDevice
        }
        val created = enroll(session) ?: return Prepared.Failed
        return Prepared.Ready(created, enrolled = true)
    }

    /**
     * A host whose serial already matched. Proves the saved secret, or writes one when [mayEnroll] is true
     * and there is none. A TV with no secret and [mayEnroll] false is closed with [Prepared.NeedsConfirm]
     * and nothing is sent.
     */
    suspend fun acceptMatched(session: TvSession, tv: SavedTv, mayEnroll: Boolean = false): Prepared {
        if (tv.unproven && tv.identitySecret == null) {
            session.close()
            return Prepared.Failed
        }
        val secret = tv.identitySecret
        if (secret != null) {
            if (!prove(session, secret)) {
                session.close()
                return Prepared.Failed
            }
            return Prepared.Ready(secret, enrolled = false)
        }
        if (!mayEnroll) {
            session.close()
            return Prepared.NeedsConfirm
        }
        val created = enroll(session) ?: return Prepared.Failed
        return Prepared.Ready(created, enrolled = true)
    }

    private fun nextHex(): String {
        val bytes = nextBytes()
        require(bytes.size == 32) { "identity random source must return 32 bytes" }
        return bytes.toHex()
    }

    companion object {
        private val ID = Regex("^[0-9a-f]{16}$")
        private val random = SecureRandom()

        /** The identity for the companion key in [keyDir], creating that key if it is not there yet. */
        fun forKeys(keyDir: File): TvIdentity {
            CompanionKeys.ensureReady(keyDir)
            return TvIdentity(CompanionKeys.currentCompanionId())
        }
    }
}
