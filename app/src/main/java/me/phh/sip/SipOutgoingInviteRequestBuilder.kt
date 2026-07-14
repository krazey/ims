package me.phh.sip

import android.telephony.Rlog

internal data class OutgoingInviteRequestContext(
    val request: SipRequest,
    val baseHeaders: Map<String, List<String>>,
    val targetUri: String,
    val telUri: String,
    val normalizedPhoneNumber: String,
)

private data class OutgoingInviteBaseRequestContext(
    val normalizedPhoneNumber: String,
    val telUri: String,
    val sipInstance: String,
    val localEndpoint: String,
    val transport: String,
    val baseHeaders: Map<String, List<String>>,
)

private data class OutgoingInviteCarrierRequestShape(
    val targetUri: String,
    val headers: Map<String, List<String>>,
)

internal object SipOutgoingInviteRequestBuilder {

    // Scoped short service TEL URI policy.
    //
    // Local TEL numbers need a phone-context. Some IMS cores reject plain local
    // short-code targets such as tel:121 with "Local phone number without phone
    // context". Keep E.164 targets unchanged, and keep the known Vodafone TR
    // service-number exception plain because that carrier rejected the generic
    // MCC/MNC context for 542. The actual phone-context value is resolved by
    // SipCarrierSettings so carrier policy remains centralized.
    private fun shortServiceTelUri(
        normalizedPhoneNumber: String,
        carrierSettings: SipCarrierSettings,
        realm: String,
    ): String? {
        if (!carrierSettings.isLocalShortCode(normalizedPhoneNumber)) {
            return null
        }

        // If an emergency-like code reaches this normal MMTel path anyway, do
        // not add a phone-context here. The real emergency path should handle it.
        if (carrierSettings.isFallbackEmergencyDialString(normalizedPhoneNumber)) {
            return "tel:$normalizedPhoneNumber"
        }

        if (carrierSettings.shouldKeepShortServicePlainTel(normalizedPhoneNumber)) {
            return "tel:$normalizedPhoneNumber"
        }

        return "tel:$normalizedPhoneNumber;phone-context=${carrierSettings.phoneContextForLocalTelUri(realm)}"
    }

    fun build(
        logTag: String,
        phoneNumber: String,
        outgoingInviteBody: ByteArray,
        normalizedPhoneNumber: String,
        carrierSettings: SipCarrierSettings,
        realm: String,
        registrationTech: Int,
        mySip: String,
        myTel: String,
        imsi: String,
        imei: String,
        commonHeaders: Map<String, List<String>>,
        localEndpoint: String,
        transport: String,
        sessionExpiresSeconds: Int,
        minSeSeconds: Int,
        generatedCallIdHeaders: Map<String, List<String>>,
        singtelStockOutgoingCarrier: Boolean,
        singtelPublicSipUri: (String) -> String,
    ): OutgoingInviteRequestContext {
        val baseRequestContext = buildBaseRequestContext(
            logTag = logTag,
            phoneNumber = phoneNumber,
            normalizedPhoneNumber = normalizedPhoneNumber,
            carrierSettings = carrierSettings,
            realm = realm,
            registrationTech = registrationTech,
            mySip = mySip,
            myTel = myTel,
            imei = imei,
            commonHeaders = commonHeaders,
            localEndpoint = localEndpoint,
            transport = transport,
            sessionExpiresSeconds = sessionExpiresSeconds,
            minSeSeconds = minSeSeconds,
            generatedCallIdHeaders = generatedCallIdHeaders,
        )
        val carrierRequestShape = buildCarrierRequestShape(
            normalizedPhoneNumber = baseRequestContext.normalizedPhoneNumber,
            telUri = baseRequestContext.telUri,
            baseHeaders = baseRequestContext.baseHeaders,
            sipInstance = baseRequestContext.sipInstance,
            localEndpoint = baseRequestContext.localEndpoint,
            transport = baseRequestContext.transport,
            myTel = myTel,
            imsi = imsi,
            commonHeaders = commonHeaders,
            singtelStockOutgoingCarrier = singtelStockOutgoingCarrier,
            singtelPublicSipUri = singtelPublicSipUri,
        )
        return buildRequestContext(
            outgoingInviteBody = outgoingInviteBody,
            baseRequestContext = baseRequestContext,
            carrierRequestShape = carrierRequestShape,
        )
    }

    private fun buildBaseRequestContext(
        logTag: String,
        phoneNumber: String,
        normalizedPhoneNumber: String,
        carrierSettings: SipCarrierSettings,
        realm: String,
        registrationTech: Int,
        mySip: String,
        myTel: String,
        imei: String,
        commonHeaders: Map<String, List<String>>,
        localEndpoint: String,
        transport: String,
        sessionExpiresSeconds: Int,
        minSeSeconds: Int,
        generatedCallIdHeaders: Map<String, List<String>>,
    ): OutgoingInviteBaseRequestContext {
        val to = shortServiceTelUri(
            normalizedPhoneNumber = normalizedPhoneNumber,
            carrierSettings = carrierSettings,
            realm = realm,
        ) ?: if (normalizedPhoneNumber.startsWith("+")) {
            // Global TEL URIs must stand on their own. Adding phone-context to +E.164
            // numbers makes some IMS cores drop the INVITE without any SIP response.
            "tel:$normalizedPhoneNumber"
        } else {
            // Short service numbers were handled above. Other local numbers
            // keep the generic IMS phone-context.
            "tel:$normalizedPhoneNumber;phone-context=${carrierSettings.phoneContextForLocalTelUri(realm)}"
        }
        Rlog.d(
            logTag,
            "Built outgoing dial target global=${normalizedPhoneNumber.startsWith("+")} " +
                "shortCode=${carrierSettings.isLocalShortCode(normalizedPhoneNumber)}",
        )
        val sipInstance = "<urn:gsma:imei:${imei.substring(0, 8)}-${imei.substring(8, 14)}-0>"
        val contactTel =
            """<sip:$myTel@$localEndpoint;transport=$transport>;expires=7200;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""
        val carrierPaniHeaders = carrierSettings.outgoingPaniHeaders(registrationTech)

        val myHeaders = commonHeaders +
            """
                From: <$mySip>
                To: <$to>
                P-Preferred-Identity: <$mySip>
                P-Asserted-Identity: <$mySip>
                Expires: 7200
                Require: sec-agree
                Proxy-Require: sec-agree
                Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                P-Early-Media: supported
                Content-Type: application/sdp
                Session-Expires: ${SipSessionTimerNegotiation.outgoingRequestValue(sessionExpiresSeconds)}
                Supported: 100rel, replaces, timer, precondition
                Accept: application/sdp
                Min-SE: $minSeSeconds
                Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
                P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                Contact: $contactTel
                """.toSipHeadersMap() + carrierPaniHeaders +
            generatedCallIdHeaders - "p-asserted-identity"
        // P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
        // Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"

        return OutgoingInviteBaseRequestContext(
            normalizedPhoneNumber = normalizedPhoneNumber,
            telUri = to,
            sipInstance = sipInstance,
            localEndpoint = localEndpoint,
            transport = transport,
            baseHeaders = myHeaders,
        )
    }

    private fun buildCarrierRequestShape(
        normalizedPhoneNumber: String,
        telUri: String,
        baseHeaders: Map<String, List<String>>,
        sipInstance: String,
        localEndpoint: String,
        transport: String,
        myTel: String,
        imsi: String,
        commonHeaders: Map<String, List<String>>,
        singtelStockOutgoingCarrier: Boolean,
        singtelPublicSipUri: (String) -> String,
    ): OutgoingInviteCarrierRequestShape {
        val singtelStockOutgoingTargetUri = if (singtelStockOutgoingCarrier) {
            singtelPublicSipUri(normalizedPhoneNumber)
        } else {
            telUri
        }

        val singtelStockOutgoingHeaders = if (singtelStockOutgoingCarrier) {
            val singtelStockIdentity = singtelPublicSipUri(myTel)
            val singtelStockFromTag = baseHeaders["from"]?.firstOrNull()
                ?.substringAfter(";tag=", missingDelimiterValue = "")
                ?.substringBefore(";")
                ?.takeIf { it.isNotBlank() }
                ?: "phh${System.currentTimeMillis().toString(16)}"
            val singtelStockContact = "<sip:$imsi@$localEndpoint;transport=$transport>;expires=7200;" +
                "+sip.instance=\"$sipInstance\";audio;+g.3gpp.accesstype=\"cellular\";" +
                "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";+g.3gpp.smsip"
            val singtelCompactContact = "<sip:$imsi@$localEndpoint;transport=$transport>"
            val singtelStockPaniValue = commonHeaders.entries
                .firstOrNull { it.key.equals("p-access-network-info", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?: "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=5250102C6B611D01"

            val singtelStockBaseHeaders = baseHeaders.filterKeys { key ->
                key.equals("via", ignoreCase = true) ||
                    key.equals("max-forwards", ignoreCase = true) ||
                    key.equals("user-agent", ignoreCase = true) ||
                    key.equals("route", ignoreCase = true) ||
                    key.equals("call-id", ignoreCase = true) ||
                    key.equals("security-verify", ignoreCase = true) ||
                    key.equals("proxy-require", ignoreCase = true)
            }

            // Direct stock-like SingTel INVITE: whitelist only the dynamic dialog and
            // security headers, then add the originating MMTEL shape explicitly. Do not
            // carry the generic TEL-URI identity headers from main.
            /*
             * Keep the originating SingTel header set intentionally small.
             * Security-Verify and Content-Type are required/accepted, but
             * optional identity/access/capability headers make the first
             * protected INVITE large enough to be dropped by this IMS path.
             */
            singtelStockBaseHeaders + """
                From: <$singtelStockIdentity>;tag=$singtelStockFromTag
                To: <$singtelStockOutgoingTargetUri>
                Contact: $singtelCompactContact
                P-Preferred-Identity: <$singtelStockIdentity>
                Expires: 7200
                Require: sec-agree
                Proxy-Require: sec-agree
                Content-Type: application/sdp
                Allow: INVITE, ACK, CANCEL, BYE, OPTIONS
                Supported: sec-agree
                Request-Disposition: no-fork
                P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                CSeq: 1 INVITE
            """.toSipHeadersMap()
        } else {
            baseHeaders
        }

        return OutgoingInviteCarrierRequestShape(
            targetUri = singtelStockOutgoingTargetUri,
            headers = singtelStockOutgoingHeaders,
        )
    }

    private fun buildRequestContext(
        outgoingInviteBody: ByteArray,
        baseRequestContext: OutgoingInviteBaseRequestContext,
        carrierRequestShape: OutgoingInviteCarrierRequestShape,
    ): OutgoingInviteRequestContext {
        val request =
            SipRequest(
                SipMethod.INVITE,
                carrierRequestShape.targetUri,
                carrierRequestShape.headers,
                outgoingInviteBody
            )

        return OutgoingInviteRequestContext(
            request = request,
            baseHeaders = baseRequestContext.baseHeaders,
            targetUri = carrierRequestShape.targetUri,
            telUri = baseRequestContext.telUri,
            normalizedPhoneNumber = baseRequestContext.normalizedPhoneNumber,
        )
    }
}
