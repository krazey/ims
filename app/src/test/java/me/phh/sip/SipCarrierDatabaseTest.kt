// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipCarrierDatabaseTest {
    private fun voiceProfile(
        remoteUriType: String = "sip",
        transport: String = "udp-preferred",
        enableGruu: Boolean = false,
    ) = SipCarrierDatabaseProfile(
        name = "Test IMS",
        mnoName = "Test_MNO",
        representativePlmn = "00101",
        pdn = "ims",
        emergency = false,
        remoteUriType = remoteUriType,
        ipVersion = "ipv4v6",
        transport = transport,
        supportIpsec = true,
        usePrecondition = true,
        wifiPrecondition = true,
        supportRoaming = true,
        authAlgorithms = listOf("hmac-md5-96", "unsupported"),
        encryptionAlgorithms = listOf("null", "des-ede3-cbc", "aes-cbc"),
        subscribeForReg = false,
        enableGruu = enableGruu,
        registrationRetryBaseSeconds = 12,
        registrationRetryMaxSeconds = 345,
        registrationPcscfPolicyOn403 = "next_pcscf",
        services = setOf("mmtel", "smsip"),
        networks = setOf("lte", "wifi"),
        minSeSeconds = 120,
        sessionExpiresSeconds = 2400,
        ringingTimerSeconds = 90,
        ringbackTimerSeconds = 90,
        keepAliveModeMo = "alerting",
        keepAliveModeMt = "incoming",
        keepAliveIntervalMs = 2_000L,
        mssSize = 1300,
    )

    @Test
    fun `canonical PLMN keeps two and three digit MNCs distinct`() {
        require(SipCarrierDatabaseSelector.canonicalMccMnc("40177") == "401077")
        require(SipCarrierDatabaseSelector.canonicalMccMnc("40107") == "401007")
        require(SipCarrierDatabaseSelector.canonicalMccMnc("21910") == "219010")
        require(SipCarrierDatabaseSelector.canonicalMccMnc("525001") == "525001")
    }

    @Test
    fun `qualified mapping wins over generic PLMN mapping`() {
        val generic = SipCarrierDatabaseMapping("20404", "204004", "Vodafone_NL")
        val mvno = SipCarrierDatabaseMapping(
            sourcePlmn = "20404",
            canonicalMccMnc = "204004",
            mnoName = "USCC_US",
            spn = "U.S. Cellular",
        )

        require(
            SipCarrierDatabaseSelector.select(
                listOf(generic, mvno),
                SipCarrierDatabaseQuery("204004", spn = "U.S. Cellular"),
            ) == mvno,
        )
        require(
            SipCarrierDatabaseSelector.select(
                listOf(generic, mvno),
                SipCarrierDatabaseQuery("204004"),
            ) == generic,
        )
    }

    @Test
    fun `IMSI subset is matched after source PLMN`() {
        val generic = SipCarrierDatabaseMapping("23430", "234030", "EE_GB")
        val mvno = SipCarrierDatabaseMapping(
            sourcePlmn = "23430",
            canonicalMccMnc = "234030",
            mnoName = "EE_GB:MVNO",
            subset = "1234",
        )

        require(
            SipCarrierDatabaseSelector.select(
                listOf(generic, mvno),
                SipCarrierDatabaseQuery("234030", imsi = "234301234999999"),
            ) == mvno,
        )
    }

    @Test
    fun `firmware profile activates supported runtime policy`() {
        val record = SipCarrierDatabaseRecord(
            mapping = SipCarrierDatabaseMapping("00101", "001001", "Test_MNO"),
            profiles = listOf(voiceProfile()),
            serviceSwitches = mapOf("enableServiceVolte" to true),
            csfbStatusRules = setOf("380", "5xx"),
            voiceCsfbStatusRules = setOf("403"),
            emergencyDomain = "PS",
        )

        val resolved = record.applyTo(SipCarrierPolicy.defaultFor("001", "001"))

        require(!resolved.isControlSocketUdp)
        require(!resolved.subscribeRegEvent)
        require(!resolved.registerGruuSupported)
        require(
            resolved.outgoingTargetUriType ==
                SipCarrierPolicy.OutgoingTargetUriType.SIP_USER_PHONE,
        )
        require(resolved.securityClientAlgs == listOf("hmac-md5-96"))
        require(resolved.securityClientEalgs == listOf("null", "aes-cbc"))
        require(resolved.minSeSeconds == 120)
        require(resolved.sessionExpiresSeconds == 2400)
        require(resolved.registrationRecoveryPolicy.retryBaseMs == 12_000L)
        require(resolved.registrationRecoveryPolicy.retryMaxMs == 345_000L)
        require(
            resolved.registrationRecoveryPolicy.forbiddenPcscfPolicy ==
                RegistrationForbiddenPcscfPolicy.NEXT_PCSCF,
        )
        require(resolved.callSignalingKeepAlivePolicy.startsForOutgoing(180))
        require(!resolved.callSignalingKeepAlivePolicy.startsForOutgoing(100))
        require(resolved.callSignalingKeepAlivePolicy.startsForIncoming)
        require(resolved.callSignalingKeepAlivePolicy.intervalMs == 2_000L)
        require(resolved.inviteFailurePolicy.csfbStatusCodes == setOf(380, 403))
        require(resolved.inviteFailurePolicy.shouldFallbackToCs(503))
        require(
            resolved.outgoingTargetUri(
                "tel:12345;phone-context=ims.example.test",
                "ims.example.test",
            ) == "sip:12345;phone-context=ims.example.test@ims.example.test;user=phone",
        )
    }

    @Test
    fun `manual overlay remains authoritative over firmware profile`() {
        val firmware = SipCarrierDatabaseRecord(
            mapping = SipCarrierDatabaseMapping("00101", "001001", "Test_MNO"),
            profiles = listOf(voiceProfile()),
            serviceSwitches = emptyMap(),
            csfbStatusRules = setOf("503"),
            voiceCsfbStatusRules = emptySet(),
            emergencyDomain = null,
        ).applyTo(SipCarrierPolicy.defaultFor("001", "001"))

        val overridden = SipCarrierPolicyOverlay(
            booleans = mapOf("control_socket_udp" to false),
            strings = mapOf("outgoing_target_uri_type" to "tel"),
            stringArrays = mapOf(
                "security_client_algs" to listOf("hmac-sha-1-96"),
                "invite_csfb_status_codes" to emptyList(),
            ),
        ).applyTo(firmware)

        require(!overridden.isControlSocketUdp)
        require(
            overridden.outgoingTargetUriType == SipCarrierPolicy.OutgoingTargetUriType.TEL,
        )
        require(overridden.securityClientAlgs == listOf("hmac-sha-1-96"))
        require(overridden.inviteFailurePolicy.csfbStatusCodes.isEmpty())
        require(!overridden.inviteFailurePolicy.shouldFallbackToCs(503))
    }
}
