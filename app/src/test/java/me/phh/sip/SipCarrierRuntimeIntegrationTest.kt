// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import java.net.DatagramSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SipCarrierRuntimeIntegrationTest {
    @Test
    fun registrationContactKeepsSmsWhileVoiceIsDisabled() {
        val contact = SipContactHeaders.registrationContact(
            userPart = "user",
            localEndpoint = "192.0.2.1:5060",
            transport = "udp",
            sipInstance = "<urn:gsma:imei:12345678-123456-0>",
            voiceEnabled = false,
        )

        assertTrue(contact.contains("+g.3gpp.smsip"))
        assertFalse(contact.contains("3gpp-service.ims.icsi.mmtel"))
        assertFalse(contact.endsWith(";audio"))
    }

    @Test
    fun registrationContactAdvertisesVoiceWhenEnabled() {
        val contact = SipContactHeaders.registrationContact(
            userPart = "user",
            localEndpoint = "192.0.2.1:5060",
            transport = "udp",
            sipInstance = "<urn:gsma:imei:12345678-123456-0>",
            voiceEnabled = true,
        )

        assertTrue(contact.contains("+g.3gpp.smsip"))
        assertTrue(contact.contains("3gpp-service.ims.icsi.mmtel"))
        assertTrue(contact.endsWith(";audio"))
    }

    @Test
    fun registrationContactCanOmitEveryUnsupportedService() {
        val contact = SipContactHeaders.registrationContact(
            userPart = "user",
            localEndpoint = "192.0.2.1:5060",
            transport = "tcp",
            sipInstance = "<urn:gsma:imei:12345678-123456-0>",
            voiceEnabled = false,
            smsIpEnabled = false,
        )

        assertFalse(contact.contains("mmtel"))
        assertFalse(contact.contains("smsip"))
        assertFalse(contact.endsWith(';'))
    }

    @Test
    fun carrierPreconditionPolicyDiffersBetweenLteAndIwlan() {
        val policy = SipPreconditionPolicy(cellular = true, iwlan = false)

        assertTrue(policy.enabledFor(REGISTRATION_TECH_LTE))
        assertFalse(policy.enabledFor(REGISTRATION_TECH_IWLAN))
    }

    @Test
    fun outgoingSdpOnlyAdvertisesConfiguredPreconditions() {
        DatagramSocket().use { socket ->
            val enabled = SipOutgoingInviteSdp.build(
                logTag = "test",
                rtpSocket = socket,
                localHost = "192.0.2.1",
                ipType = "IP4",
                amrWbMediaCodecAvailable = false,
                singtelStockOutgoingCarrier = false,
                preconditionEnabled = true,
            ).inviteBody.toString(Charsets.US_ASCII)
            val disabled = SipOutgoingInviteSdp.build(
                logTag = "test",
                rtpSocket = socket,
                localHost = "192.0.2.1",
                ipType = "IP4",
                amrWbMediaCodecAvailable = false,
                singtelStockOutgoingCarrier = false,
                preconditionEnabled = false,
            ).inviteBody.toString(Charsets.US_ASCII)

            assertTrue(enabled.contains("a=des:qos mandatory local sendrecv"))
            assertFalse(disabled.contains("a=curr:qos"))
            assertFalse(disabled.contains("a=des:qos"))
        }
    }

    @Test
    fun initialInviteCsfbUsesInitiatingFailureRouting() {
        assertEquals(
            mapOf(
                "callStartFailed" to "true",
                "outgoingCall" to "true",
                "csRetry" to "true",
            ),
            SipOutgoingInviteProgressResponses.outgoingFailureRoutingExtras(
                initialInviteFailed = true,
                csRetry = true,
            ),
        )
    }

    @Test
    fun establishedDialogFailureDoesNotRequestInitiatingFailure() {
        assertTrue(
            SipOutgoingInviteProgressResponses.outgoingFailureRoutingExtras(
                initialInviteFailed = false,
                csRetry = false,
            ).isEmpty(),
        )
    }
}
