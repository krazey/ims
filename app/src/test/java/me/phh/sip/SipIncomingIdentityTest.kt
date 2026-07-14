// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import org.junit.Test

class SipIncomingIdentityTest {
    @Test
    fun `anonymous from never leaks asserted identity`() {
        val identity = SipIncomingIdentityResolver.resolve(
            mapOf(
                "from" to listOf("\"Display\" <sip:anonymous@example.test>"),
                "p-asserted-identity" to listOf("<tel:+4912345>"),
                "privacy" to listOf("none"),
            ),
        )

        // An anonymous From is still treated as restricted. Do not leak a PAI
        // merely because a malformed peer also sent Privacy: none.
        require(identity.presentationRestricted)
        require(identity.identityHeader == null)
    }

    @Test
    fun `public asserted identity is preferred over from`() {
        val identity = SipIncomingIdentityResolver.resolve(
            mapOf(
                "from" to listOf("<tel:+491111>"),
                "p-asserted-identity" to listOf("<tel:+492222>"),
            ),
        )

        require(!identity.presentationRestricted)
        require(identity.identityHeader == "<tel:+492222>")
    }

    @Test
    fun `privacy id suppresses asserted identity`() {
        val identity = SipIncomingIdentityResolver.resolve(
            mapOf(
                "from" to listOf("<tel:+491111>"),
                "p-asserted-identity" to listOf("<tel:+492222>"),
                "privacy" to listOf("id;header"),
            ),
        )

        require(identity.presentationRestricted)
        require(identity.identityHeader == null)
    }
}
