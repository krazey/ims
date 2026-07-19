// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import java.net.InetAddress
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
        registrationExpiresSeconds = 3600,
        services = setOf("mmtel", "smsip"),
        networks = setOf("lte", "wifi"),
        minSeSeconds = 120,
        sessionExpiresSeconds = 2400,
        inviteTimeoutSeconds = 180,
        ringingTimerSeconds = 90,
        ringbackTimerSeconds = 90,
        keepAliveModeMo = "alerting",
        keepAliveModeMt = "incoming",
        keepAliveIntervalMs = 2_000L,
        mssSize = 1300,
        pcscfPreference = 0,
        sosUrnRequired = true,
        blockDeregistrationOnSrvcc = true,
        lastPaniHeader = "Cellular-Network-Info",
        supportedGeolocationPhase = 1,
        audioCodecs = listOf("AMR-WB", "AMR", "DTMF"),
        enableEvsCodec = false,
    )

    @Test
    fun `Samsung IP and transport values keep distinct semantics`() {
        val ipv4 = InetAddress.getByName("192.0.2.1")
        val ipv6 = InetAddress.getByName("2001:db8::1")

        require(SipIpVersionPolicy.fromSamsung("ipv4").accepts(ipv4))
        require(!SipIpVersionPolicy.fromSamsung("ipv4").accepts(ipv6))
        require(SipIpVersionPolicy.fromSamsung("ipv6").accepts(ipv6))
        require(SipTransportPolicy.fromSamsung("udp") == SipTransportPolicy.UDP)
        require(
            SipTransportPolicy.fromSamsung("udp-preferred") ==
                SipTransportPolicy.UDP_PREFERRED,
        )
        require(!SipTransportPolicy.UDP_PREFERRED.requiresUdp)
    }

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
    fun `SIM group identifiers select the qualified MVNO mapping`() {
        val generic = SipCarrierDatabaseMapping("310260", "310260", "Generic_US")
        val gid1Mvno = SipCarrierDatabaseMapping(
            sourcePlmn = "310260",
            canonicalMccMnc = "310260",
            mnoName = "Gid1_MVNO",
            gid1 = "BA",
        )
        val gid2Mvno = SipCarrierDatabaseMapping(
            sourcePlmn = "310260",
            canonicalMccMnc = "310260",
            mnoName = "Gid2_MVNO",
            gid1 = "BA",
            gid2 = "10",
        )

        require(
            SipCarrierDatabaseSelector.select(
                listOf(generic, gid1Mvno, gid2Mvno),
                SipCarrierDatabaseQuery("310260", gid1 = "ba01", gid2 = "10ff"),
            ) == gid2Mvno,
        )
        require(
            SipCarrierDatabaseSelector.select(
                listOf(generic, gid1Mvno, gid2Mvno),
                SipCarrierDatabaseQuery("310260", gid1 = "ba01"),
            ) == gid1Mvno,
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
        require(resolved.transportPolicy == SipTransportPolicy.UDP_PREFERRED)
        require(resolved.ipVersionPolicy == SipIpVersionPolicy.IPV4V6)
        require(resolved.ipsecSupported)
        require(resolved.preconditionPolicy.cellular)
        require(resolved.preconditionPolicy.iwlan)
        require(resolved.roamingSupported)
        require(resolved.supportedNetworks == setOf("lte", "wifi"))
        require(resolved.voiceEnabled(REGISTRATION_TECH_LTE))
        require(resolved.voiceEnabled(REGISTRATION_TECH_IWLAN))
        require(resolved.smsIpEnabled(REGISTRATION_TECH_LTE))
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
        require(resolved.registrationExpiresSeconds == 3600)
        require(resolved.mssSize == 1300)
        require(resolved.pcscfPreference == 0)
        require(resolved.sosUrnRequired)
        require(resolved.blockDeregistrationOnSrvcc)
        require(resolved.lastPaniHeader == "Cellular-Network-Info")
        require(resolved.supportedGeolocationPhase == 1)
        require(resolved.allowsAudioCodec("AMR-WB"))
        require(!resolved.allowsAudioCodec("EVS"))
        require(resolved.registrationRecoveryPolicy.retryBaseMs == 12_000L)
        require(resolved.registrationRecoveryPolicy.retryMaxMs == 345_000L)
        require(
            resolved.registrationRecoveryPolicy.forbiddenPcscfPolicy ==
                RegistrationForbiddenPcscfPolicy.NEXT_PCSCF,
        )
        require(resolved.callSetupTimerPolicy.inviteTimeoutMs == 180_000L)
        require(resolved.callSetupTimerPolicy.ringingTimeoutMs == 90_000L)
        require(resolved.callSetupTimerPolicy.ringbackTimeoutMs == 90_000L)
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
        require(
            resolved.outgoingTargetUri(
                telUri = "tel:+00000000000",
                realm = "ims.example.test",
                registeredSipUri = "sip:+00000000001@ims.operator.test",
            ) == "sip:+00000000000@ims.operator.test;user=phone",
        )
    }

    @Test
    fun `firmware sentinels retain safe runtime defaults`() {
        val base = SipCarrierPolicy.defaultFor("001", "001")
        val profile = voiceProfile().copy(
            registrationRetryBaseSeconds = 0,
            registrationRetryMaxSeconds = -1,
            registrationPcscfPolicyOn403 = "perm_stop",
            minSeSeconds = 0,
            sessionExpiresSeconds = 0,
            inviteTimeoutSeconds = 0,
            ringingTimerSeconds = -1,
            ringbackTimerSeconds = -1,
            keepAliveIntervalMs = 0,
            registrationExpiresSeconds = 0,
            mssSize = 20,
            pcscfPreference = 99,
        )
        val resolved = SipCarrierDatabaseRecord(
            mapping = SipCarrierDatabaseMapping("00101", "001001", "Test_MNO"),
            profiles = listOf(profile),
            serviceSwitches = emptyMap(),
            csfbStatusRules = emptySet(),
            voiceCsfbStatusRules = emptySet(),
            emergencyDomain = null,
        ).applyTo(base)

        require(resolved.minSeSeconds == base.minSeSeconds)
        require(resolved.sessionExpiresSeconds == base.sessionExpiresSeconds)
        require(resolved.registrationExpiresSeconds == base.registrationExpiresSeconds)
        require(resolved.mssSize == base.mssSize)
        require(resolved.pcscfPreference == base.pcscfPreference)
        require(
            resolved.registrationRecoveryPolicy ==
                base.registrationRecoveryPolicy,
        )
        require(resolved.callSetupTimerPolicy == base.callSetupTimerPolicy)
        require(
            resolved.callSignalingKeepAlivePolicy.intervalMs ==
                base.callSignalingKeepAlivePolicy.intervalMs,
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
