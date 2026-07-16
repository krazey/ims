// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipStatusCodeRuleTest {
    @Test
    fun `matches exact and status-class rules`() {
        require(SipStatusCodeRule.matches("503", 503))
        require(!SipStatusCodeRule.matches("503", 500))
        require(SipStatusCodeRule.matches("5xx", 500))
        require(SipStatusCodeRule.matches("5xx", 599))
        require(!SipStatusCodeRule.matches("5xx", 600))
    }

    @Test
    fun `matches Samsung negative status-class rules`() {
        val fourXx = "^(?!(403|404|480|486|487))4xx"
        require(SipStatusCodeRule.matches(fourXx, 400))
        require(SipStatusCodeRule.matches(fourXx, 503).not())
        require(SipStatusCodeRule.matches(fourXx, 403).not())
        require(SipStatusCodeRule.matches(fourXx, 487).not())

        require(SipStatusCodeRule.matches("^(?!381)3xx", 380))
        require(SipStatusCodeRule.matches("^(?!381)3xx", 381).not())
    }

    @Test
    fun `unknown rules fail closed`() {
        require(!SipStatusCodeRule.matches("not-a-rule", 503))
        require(!SipStatusCodeRule.matches("", 503))
    }
}
