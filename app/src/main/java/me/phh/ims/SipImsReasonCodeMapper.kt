// SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.telephony.ims.ImsReasonInfo

internal object SipImsReasonCodeMapper {
    fun fromStatusCode(statusCode: Int): Int = when (statusCode) {
        403 -> ImsReasonInfo.CODE_SIP_FORBIDDEN
        404 -> ImsReasonInfo.CODE_SIP_NOT_FOUND
        408 -> ImsReasonInfo.CODE_SIP_REQUEST_TIMEOUT
        480 -> ImsReasonInfo.CODE_SIP_TEMPRARILY_UNAVAILABLE
        486 -> ImsReasonInfo.CODE_SIP_BUSY
        487 -> ImsReasonInfo.CODE_SIP_REQUEST_CANCELLED
        488 -> ImsReasonInfo.CODE_SIP_NOT_ACCEPTABLE
        500 -> ImsReasonInfo.CODE_SIP_SERVER_INTERNAL_ERROR
        503 -> ImsReasonInfo.CODE_SIP_SERVICE_UNAVAILABLE
        504 -> ImsReasonInfo.CODE_SIP_SERVER_TIMEOUT
        603 -> ImsReasonInfo.CODE_SIP_USER_REJECTED
        in 300..399 -> ImsReasonInfo.CODE_SIP_REDIRECTED
        in 400..499 -> ImsReasonInfo.CODE_SIP_CLIENT_ERROR
        in 500..599 -> ImsReasonInfo.CODE_SIP_SERVER_ERROR
        in 600..699 -> ImsReasonInfo.CODE_SIP_GLOBAL_ERROR
        else -> ImsReasonInfo.CODE_NETWORK_REJECT
    }
}
