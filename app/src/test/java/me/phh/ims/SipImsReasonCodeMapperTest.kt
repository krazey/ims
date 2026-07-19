// SPDX-License-Identifier: GPL-2.0
package me.phh.ims

import android.telephony.ims.ImsReasonInfo
import org.junit.Test

class SipImsReasonCodeMapperTest {
    @Test
    fun `remote busy and decline keep their SIP meanings`() {
        require(
            SipImsReasonCodeMapper.fromStatusCode(486) ==
                ImsReasonInfo.CODE_SIP_BUSY,
        )
        require(
            SipImsReasonCodeMapper.fromStatusCode(603) ==
                ImsReasonInfo.CODE_SIP_USER_REJECTED,
        )
    }

    @Test
    fun `unknown final responses keep their SIP class`() {
        require(
            SipImsReasonCodeMapper.fromStatusCode(499) ==
                ImsReasonInfo.CODE_SIP_CLIENT_ERROR,
        )
        require(
            SipImsReasonCodeMapper.fromStatusCode(599) ==
                ImsReasonInfo.CODE_SIP_SERVER_ERROR,
        )
        require(
            SipImsReasonCodeMapper.fromStatusCode(699) ==
                ImsReasonInfo.CODE_SIP_GLOBAL_ERROR,
        )
    }
}
