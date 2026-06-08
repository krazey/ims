//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.Rlog
import android.telephony.TelephonyManager
import android.util.Base64

data class SipAkaResult(val res: ByteArray, val ck: ByteArray, val ik: ByteArray)

sealed class SipAkaChallengeResult {
    data class Success(val akaResult: SipAkaResult) : SipAkaChallengeResult()
    data class SynchronizationFailure(val auts: ByteArray) : SipAkaChallengeResult()
}

private const val TAG = "PHH SipChallenge"

fun sipAkaChallenge(tm: TelephonyManager, nonceB64: String): SipAkaResult {
    return when (val result = sipAkaChallengeForRegistration(tm, nonceB64)) {
        is SipAkaChallengeResult.Success -> result.akaResult
        is SipAkaChallengeResult.SynchronizationFailure -> {
            val autsHex = result.auts.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            throw Exception("AKA Challenge from SIP returned synchronization failure AUTS=$autsHex")
        }
    }
}

fun sipAkaChallengeForRegistration(
    tm: TelephonyManager,
    nonceB64: String,
): SipAkaChallengeResult {
    val nonce = Base64.decode(nonceB64, Base64.DEFAULT)
    val rand = nonce.take(16)
    val autn = nonce.drop(16).take(16)
    // val mac = nonce.drop(32)

    val challengeBytes = listOf(rand.size.toByte()) + rand + autn.size.toByte() + autn
    val challengeArray = challengeBytes.toByteArray()
    val challenge = Base64.encodeToString(challengeArray, Base64.NO_WRAP)
    Rlog.d(TAG, "Challenge B64 is $challenge")
    val responseB64 = tm.getIccAuthentication(
        TelephonyManager.APPTYPE_USIM,
        TelephonyManager.AUTHTYPE_EAP_AKA,
        challenge
    ) ?: throw Exception("AKA Challenge from SIP returned null response")
    val response = Base64.decode(responseB64, Base64.DEFAULT)

    if (response.isEmpty()) {
        throw Exception("AKA Challenge from SIP returned empty response")
    }

    val responseTag = response[0].toInt() and 0xff
    if (responseTag != 0xdb) {
        val responseHex = response.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        Rlog.w(
            TAG,
            "AKA challenge from SIP failed tag=0x${responseTag.toString(16)} " +
                    "len=${response.size} response=$responseHex",
        )

        if (responseTag == 0xdc) {
            if (response.size < 2) {
                throw Exception("AKA synchronization failure response missing AUTS length")
            }
            val autsLen = response[1].toInt() and 0xff
            if (response.size < 2 + autsLen) {
                throw Exception(
                    "AKA synchronization failure response truncated " +
                            "autsLen=$autsLen len=${response.size}",
                )
            }
            val auts = response.copyOfRange(2, 2 + autsLen)
            val autsHex = auts.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val trailing = response.copyOfRange(2 + autsLen, response.size)
            val trailingHex = trailing.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            Rlog.w(
                TAG,
                "AKA synchronization failure/AUTS returned by USIM " +
                        "autsLen=$autsLen auts=$autsHex trailing=$trailingHex",
            )
            return SipAkaChallengeResult.SynchronizationFailure(auts)
        }

        throw Exception("AKA Challenge from SIP failed tag=0x${responseTag.toString(16)}")
    }

    val responseStream = response.iterator()
    // 0xdb
    responseStream.nextByte()
    val resLen = responseStream.nextByte().toInt()
    Rlog.d(TAG, "resLen $resLen")
    val res = (0 until resLen).map { responseStream.nextByte() }.toList()
    val ckLen = responseStream.nextByte().toInt()
    Rlog.d(TAG, "ckLen $ckLen")
    val ck = (0 until ckLen).map { responseStream.nextByte() }.toList()
    val ikLen = responseStream.nextByte().toInt()
    Rlog.d(TAG, "ikLen $ikLen")
    val ik = (0 until ikLen).map { responseStream.nextByte() }.toList()

    Rlog.d(TAG, "Got res $res ck $ck ik $ik")
    return SipAkaChallengeResult.Success(
        SipAkaResult(res = res.toByteArray(), ck = ck.toByteArray(), ik = ik.toByteArray())
    )
}

data class SipAkaDigestSess(
    val user: String,
    val realm: String,
    val uri: String,
    val nonceB64: String,
    val opaque: String?,
    private val akaResult: SipAkaResult
) {
    var nonceCount: String = "0"
    var cnonce: String = ""
    private val H1 = ("$user:$realm:".toByteArray() + akaResult.res).toMD5()
    private val H2 = "REGISTER:$uri".toMD5()
    var digest: String = ""

    init {
        Rlog.d(TAG, "H1 = $H1, H2 = REGISTER:$uri = $H2")
        increment()
    }

    fun increment() {
        nonceCount = "%08d".format(nonceCount.toInt() + 1)
        cnonce = randomBytes(8).toHex() // 16 bytes on some traces
        digest = "$H1:$nonceB64:$nonceCount:$cnonce:auth:$H2".toMD5()
        Rlog.d(TAG, "chall $H1:$nonceB64:$nonceCount:$cnonce:auth:$H2 $digest")
    }

    override fun toString(): String =
        """Digest username="$user",realm="$realm",nonce="$nonceB64",uri="$uri",response="$digest",algorithm=AKAv1-MD5,cnonce="$cnonce",qop=auth,nc=$nonceCount""" +
            (if (opaque != null) ",opaque=$opaque" else "")
}

data class SipAkaSynchronizationDigestSess(
    val user: String,
    val realm: String,
    val uri: String,
    val nonceB64: String,
    val opaque: String?,
    private val auts: ByteArray,
) {
    var nonceCount: String = "0"
    var cnonce: String = ""
    private val autsB64 = Base64.encodeToString(auts, Base64.NO_WRAP)
    private val H1 = "$user:$realm:".toMD5()
    private val H2 = "REGISTER:$uri".toMD5()
    var digest: String = ""

    init {
        Rlog.d(TAG, "AUTS H1 = $H1, H2 = REGISTER:$uri = $H2")
        increment()
    }

    fun increment() {
        nonceCount = "%08d".format(nonceCount.toInt() + 1)
        cnonce = randomBytes(8).toHex()
        // RFC 3310 section 3.4: when auts is present, calculate the
        // credentials using an empty password instead of RES.
        digest = "$H1:$nonceB64:$nonceCount:$cnonce:auth:$H2".toMD5()
        Rlog.d(TAG, "AUTS chall $H1:$nonceB64:$nonceCount:$cnonce:auth:$H2 $digest")
    }

    override fun toString(): String =
        "Digest username=\"$user\",realm=\"$realm\",nonce=\"$nonceB64\",uri=\"$uri\"," +
                "response=\"$digest\",algorithm=AKAv1-MD5,cnonce=\"$cnonce\",qop=auth,nc=$nonceCount" +
                (if (opaque != null) ",opaque=$opaque" else "") +
                ",auts=\"$autsB64\""
}


data class SipAkaDigest(
    val user: String,
    val realm: String,
    val uri: String,
    val nonceB64: String,
    val opaque: String?,
    private val akaResult: SipAkaResult
) {
    private val H1 = ("$user:$realm:".toByteArray() + akaResult.res).toMD5()
    private val H2 = "REGISTER:$uri".toMD5()
    var digest: String = ""

    init {
        Rlog.d(TAG, "H1 = $H1, H2 = REGISTER:$uri = $H2")
        increment()
    }

    fun increment() {
        digest = "$H1:$nonceB64:$H2".toMD5()
        Rlog.d(TAG, "chall $H1:$nonceB64:$H2 $digest")
    }

    override fun toString(): String =
        """Digest username="$user",realm="$realm",nonce="$nonceB64",uri="$uri",response="$digest",algorithm=AKAv1-MD5""" +
            (if (opaque != null) ",opaque=$opaque" else "")
}

data class SipAkaSynchronizationDigest(
    val user: String,
    val realm: String,
    val uri: String,
    val nonceB64: String,
    val opaque: String?,
    private val auts: ByteArray,
) {
    private val autsB64 = Base64.encodeToString(auts, Base64.NO_WRAP)
    private val H1 = "$user:$realm:".toMD5()
    private val H2 = "REGISTER:$uri".toMD5()
    var digest: String = ""

    init {
        Rlog.d(TAG, "AUTS H1 = $H1, H2 = REGISTER:$uri = $H2")
        increment()
    }

    fun increment() {
        // RFC 3310 section 3.4: when auts is present, calculate the
        // credentials using an empty password instead of RES.
        digest = "$H1:$nonceB64:$H2".toMD5()
        Rlog.d(TAG, "AUTS chall $H1:$nonceB64:$H2 $digest")
    }

    override fun toString(): String =
        "Digest username=\"$user\",realm=\"$realm\",nonce=\"$nonceB64\",uri=\"$uri\"," +
                "response=\"$digest\",algorithm=AKAv1-MD5" +
                (if (opaque != null) ",opaque=$opaque" else "") +
                ",auts=\"$autsB64\""
}

