//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

object SipRegistrationDigestFactory {
    fun create(
        user: String,
        realm: String,
        uri: String,
        nonceB64: String,
        opaque: String?,
        akaResult: SipAkaResult,
        useNonsessAka: Boolean,
    ): String {
        // Use non-sess digest when server does not offer qop (no cnonce/nc in response).
        return if (useNonsessAka) {
            SipAkaDigest(
                user = user,
                realm = realm,
                uri = uri,
                nonceB64 = nonceB64,
                opaque = opaque,
                akaResult = akaResult,
            ).toString()
        } else {
            SipAkaDigestSess(
                user = user,
                realm = realm,
                uri = uri,
                nonceB64 = nonceB64,
                opaque = opaque,
                akaResult = akaResult,
            ).toString()
        }
    }

    fun createSynchronizationFailure(
        user: String,
        realm: String,
        uri: String,
        nonceB64: String,
        opaque: String?,
        auts: ByteArray,
        useNonsessAka: Boolean,
    ): String {
        return if (useNonsessAka) {
            SipAkaSynchronizationDigest(
                user = user,
                realm = realm,
                uri = uri,
                nonceB64 = nonceB64,
                opaque = opaque,
                auts = auts,
            ).toString()
        } else {
            SipAkaSynchronizationDigestSess(
                user = user,
                realm = realm,
                uri = uri,
                nonceB64 = nonceB64,
                opaque = opaque,
                auts = auts,
            ).toString()
        }
    }

}
