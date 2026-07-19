// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertSame
import org.junit.Test

class SipRemoteDialogTerminationTest {
    @Test
    fun `incoming dialog writer wins over dormant main flow`() {
        val incomingWriter = ByteArrayOutputStream()
        val registeredWriter = ByteArrayOutputStream()
        var fallbackCalled = false

        val selected = SipRemoteDialogTermination.localDialogRequestWriter(
            incomingResponseWriter = incomingWriter,
            registeredDialogWriter = registeredWriter,
            fallbackWriter = {
                fallbackCalled = true
                ByteArrayOutputStream()
            },
        )

        assertSame(incomingWriter, selected)
        check(!fallbackCalled)
    }

    @Test
    fun `registered dialog writer wins when call has no stored writer`() {
        val registeredWriter = ByteArrayOutputStream()

        val selected = SipRemoteDialogTermination.localDialogRequestWriter(
            incomingResponseWriter = null,
            registeredDialogWriter = registeredWriter,
            fallbackWriter = { ByteArrayOutputStream() },
        )

        assertSame(registeredWriter, selected)
    }

    @Test
    fun `main flow is used only without a dialog writer`() {
        val fallbackWriter = ByteArrayOutputStream()

        val selected = SipRemoteDialogTermination.localDialogRequestWriter(
            incomingResponseWriter = null,
            registeredDialogWriter = null,
            fallbackWriter = { fallbackWriter },
        )

        assertSame(fallbackWriter, selected)
    }
}
