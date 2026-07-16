// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

data class SipCarrierDatabaseQuery(
    val mccMnc: String,
    val imsi: String = "",
    val gid1: String = "",
    val gid2: String = "",
    val spn: String = "",
)

data class SipCarrierDatabaseMapping(
    val sourcePlmn: String,
    val canonicalMccMnc: String,
    val mnoName: String,
    val subset: String = "",
    val gid1: String = "",
    val gid2: String = "",
    val spn: String = "",
    val blockGc: Boolean = false,
) {
    val specificity: Int
        get() = listOf(subset, gid1, gid2, spn).count { it.isNotEmpty() }

    fun matches(query: SipCarrierDatabaseQuery): Boolean {
        if (canonicalMccMnc != query.mccMnc) return false
        if (subset.isNotEmpty()) {
            val imsiTail = query.imsi.drop(sourcePlmn.length)
            if (query.imsi.isEmpty() || !imsiTail.startsWith(subset)) return false
        }
        if (gid1.isNotEmpty() && !query.gid1.startsWith(gid1, ignoreCase = true)) return false
        if (gid2.isNotEmpty() && !query.gid2.startsWith(gid2, ignoreCase = true)) return false
        if (spn.isNotEmpty() && !query.spn.equals(spn, ignoreCase = true)) return false
        return true
    }
}

data class SipCarrierDatabaseProfile(
    val name: String,
    val mnoName: String,
    val representativePlmn: String,
    val pdn: String,
    val emergency: Boolean,
    val remoteUriType: String,
    val ipVersion: String,
    val transport: String,
    val supportIpsec: Boolean,
    val usePrecondition: Boolean,
    val wifiPrecondition: Boolean,
    val supportRoaming: Boolean,
    val authAlgorithms: List<String>,
    val encryptionAlgorithms: List<String>,
    val subscribeForReg: Boolean,
    val enableGruu: Boolean,
    val registrationRetryBaseSeconds: Int?,
    val registrationRetryMaxSeconds: Int?,
    val registrationPcscfPolicyOn403: String?,
    val services: Set<String>,
    val networks: Set<String>,
    val minSeSeconds: Int?,
    val sessionExpiresSeconds: Int?,
    val ringingTimerSeconds: Int?,
    val ringbackTimerSeconds: Int?,
    val keepAliveModeMo: String,
    val keepAliveModeMt: String,
    val keepAliveIntervalMs: Long?,
    val mssSize: Int?,
)

data class SipCarrierDatabaseRecord(
    val mapping: SipCarrierDatabaseMapping,
    val profiles: List<SipCarrierDatabaseProfile>,
    val serviceSwitches: Map<String, Boolean>,
    val csfbStatusRules: Set<String>,
    val voiceCsfbStatusRules: Set<String>,
    val emergencyDomain: String?,
    val source: String = "Samsung S26 imsservice",
    val verification: String = "firmware_reference",
) {
    val voiceProfile: SipCarrierDatabaseProfile?
        get() = profiles.firstOrNull {
            !it.emergency && it.pdn == "ims" && "mmtel" in it.services
        }

    fun applyTo(base: SipCarrierPolicy): SipCarrierPolicy {
        val profile = voiceProfile ?: return base
        val supportedAuthAlgorithms = profile.authAlgorithms.filter {
            it == "hmac-sha-1-96" || it == "hmac-md5-96"
        }
        val supportedEncryptionAlgorithms = profile.encryptionAlgorithms.filter {
            it == "null" || it == "aes-cbc"
        }
        val uriType = when (profile.remoteUriType.lowercase()) {
            "sip" -> SipCarrierPolicy.OutgoingTargetUriType.SIP_USER_PHONE
            "tel" -> SipCarrierPolicy.OutgoingTargetUriType.TEL
            else -> base.outgoingTargetUriType
        }

        return base.copy(
            subscribeRegEvent = profile.subscribeForReg,
            registerGruuSupported = profile.enableGruu,
            outgoingTargetUriType = uriType,
            securityClientAlgs = supportedAuthAlgorithms.ifEmpty { base.securityClientAlgs },
            securityClientEalgs = supportedEncryptionAlgorithms.ifEmpty {
                base.securityClientEalgs
            },
            minSeSeconds = profile.minSeSeconds ?: base.minSeSeconds,
            sessionExpiresSeconds = profile.sessionExpiresSeconds
                ?: base.sessionExpiresSeconds,
            registrationRecoveryPolicy = base.registrationRecoveryPolicy.copy(
                retryBaseMs = profile.registrationRetryBaseSeconds
                    ?.coerceAtLeast(1)?.times(1_000L)
                    ?: base.registrationRecoveryPolicy.retryBaseMs,
                retryMaxMs = profile.registrationRetryMaxSeconds
                    ?.coerceAtLeast(1)?.times(1_000L)
                    ?: base.registrationRecoveryPolicy.retryMaxMs,
                forbiddenPcscfPolicy = RegistrationForbiddenPcscfPolicy.fromSamsung(
                    profile.registrationPcscfPolicyOn403,
                ) ?: base.registrationRecoveryPolicy.forbiddenPcscfPolicy,
            ),
            callSignalingKeepAlivePolicy = SipCallSignalingKeepAlivePolicy(
                outgoingMode = profile.keepAliveModeMo,
                incomingMode = profile.keepAliveModeMt,
                intervalMs = profile.keepAliveIntervalMs ?: 8_000L,
                delayFirstPacket = mapping.canonicalMccMnc.take(3) in
                    setOf("460", "461"),
            ),
            inviteFailurePolicy = base.inviteFailurePolicy.copy(
                csfbStatusCodes = (csfbStatusRules + voiceCsfbStatusRules)
                    .mapNotNull(String::toIntOrNull)
                    .toSet(),
                csfbStatusRules = csfbStatusRules + voiceCsfbStatusRules,
            ),
        )
    }

}

internal object SipCarrierDatabaseSelector {
    fun select(
        mappings: List<SipCarrierDatabaseMapping>,
        query: SipCarrierDatabaseQuery,
    ): SipCarrierDatabaseMapping? = mappings.withIndex()
        .filter { it.value.matches(query) }
        .maxWithOrNull(
            compareBy<IndexedValue<SipCarrierDatabaseMapping>> { it.value.specificity }
                .thenBy { it.index },
        )
        ?.value

    fun canonicalMccMnc(raw: String): String {
        val numeric = raw.trim()
        if (numeric.length !in 5..6 || !numeric.all(Char::isDigit)) return ""
        return numeric.take(3) + numeric.drop(3).padStart(3, '0')
    }
}
