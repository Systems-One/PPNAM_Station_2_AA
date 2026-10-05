package com.mitas.ppnam.station2aa.data.mqtt

import java.security.MessageDigest

/**
 * Contract §2/§9: the SHA-256 of the exact UTF-8 request bytes as 64 uppercase hex characters.
 * Station 2 echoes it as `requestFingerprint`; `recover` sends it back as
 * `originalRequestFingerprint`. Hash the bytes that were published — a re-serialisation of the
 * same payload is a different request.
 */
object RequestFingerprint {
    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
}
