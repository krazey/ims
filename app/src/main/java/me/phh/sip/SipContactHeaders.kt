//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.net.Inet6Address

object SipContactHeaders {
    private const val MMTEL_CONTACT_FEATURES =
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";+g.3gpp.smsip;audio"
    private const val SMS_ONLY_CONTACT_FEATURES = "+g.3gpp.smsip"

    fun localEndpoint(socket: SipConnection, serverPort: Int): String {
        val address = socket.gLocalAddr()
        return if (address is Inet6Address) {
            "[${address.hostAddress}]:$serverPort"
        } else {
            "${address.hostAddress}:$serverPort"
        }
    }

    fun transport(socket: SipConnection): String =
        if (socket is SipConnectionTcp) "tcp" else "udp"

    fun sipInstanceFromImei(imei: String): String =
        "<urn:gsma:imei:${imei.substring(0, 8)}-${imei.substring(8, 14)}-0>"

    fun mmtelContact(
        userPart: String,
        localEndpoint: String,
        transport: String,
        sipInstance: String,
        smsIpEnabled: Boolean = true,
    ): String {
        val features = if (smsIpEnabled) {
            MMTEL_CONTACT_FEATURES
        } else {
            "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";audio"
        }
        return """<sip:$userPart@$localEndpoint;transport=$transport>;expires=7200;+sip.instance="$sipInstance";$features"""
    }

    fun registrationContact(
        userPart: String,
        localEndpoint: String,
        transport: String,
        sipInstance: String,
        voiceEnabled: Boolean,
        smsIpEnabled: Boolean = true,
        expiresSeconds: Int = 7200,
    ): String {
        val serviceFeatures = when {
            voiceEnabled && smsIpEnabled -> MMTEL_CONTACT_FEATURES
            voiceEnabled ->
                "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";audio"
            smsIpEnabled -> SMS_ONLY_CONTACT_FEATURES
            else -> ""
        }
        return """<sip:$userPart@$localEndpoint;transport=$transport>;expires=$expiresSeconds;+sip.instance="$sipInstance";$serviceFeatures"""
            .trimEnd(';')
    }

    fun viaHeaders(socket: SipConnection, localEndpoint: String): SipHeadersMap {
        val transport = if (socket is SipConnectionTcp) "TCP" else "UDP"
        return """
            Via: SIP/2.0/$transport $localEndpoint;rport
            """.toSipHeadersMap()
    }
}
