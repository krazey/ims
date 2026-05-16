//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

data class SipCommonHeaderUpdate(
    val contact: String,
    val headers: SipHeadersMap,
)

object SipCommonHeaderBuilder {
    fun build(
        socket: SipConnection,
        serverPort: Int,
        imei: String,
        imsi: String,
    ): SipCommonHeaderUpdate {
        val local = SipContactHeaders.localEndpoint(socket, serverPort)
        val sipInstance = SipContactHeaders.sipInstanceFromImei(imei)
        val transport = SipContactHeaders.transport(socket)
        val contact = SipContactHeaders.mmtelContact(
            userPart = imsi,
            localEndpoint = local,
            transport = transport,
            sipInstance = sipInstance,
        )
        return SipCommonHeaderUpdate(
            contact = contact,
            headers = SipContactHeaders.viaHeaders(socket, local),
        )
    }
}
