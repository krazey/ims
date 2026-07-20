//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import java.net.InetAddress

internal data class SipUdpResponseRoute(
    val bytes: ByteArray,
    val destinationAddress: InetAddress?,
    val destinationHost: String?,
    val destinationPort: Int,
    val diagnostic: String,
)

enum class SipUdpResponseFlowPolicy {
    SERVER,
    PROTECTED_CLIENT,
}

internal object SipUdpResponseRouting {
    private const val DEFAULT_SIP_PORT = 5060

    private data class SentBy(
        val host: String,
        val port: Int,
    )

    fun route(
        responseBytes: ByteArray,
        sourceAddress: InetAddress,
        sourcePort: Int,
    ): SipUdpResponseRoute {
        val text = responseBytes.toString(Charsets.US_ASCII)
        val viaRange = topViaValueRange(text)
            ?: return sourceRoute(responseBytes, sourceAddress, sourcePort)
        val via = text.substring(viaRange)
        val sentBy = parseSentBy(via)
            ?: return sourceRoute(responseBytes, sourceAddress, sourcePort)
        val params = parseParameters(via)
        val rportPresent = params.containsKey("rport")
        val explicitRport = params["rport"]?.toIntOrNull()

        val destinationPort = when {
            explicitRport != null -> explicitRport
            rportPresent -> sourcePort
            else -> sentBy.port
        }
        val destinationHost = when {
            rportPresent -> null
            params["maddr"].isNullOrBlank().not() -> params["maddr"]
            params["received"].isNullOrBlank().not() -> params["received"]
            else -> sentBy.host
        }
        val destinationAddress = if (rportPresent) {
            sourceAddress
        } else {
            null
        }
        val normalizedBytes = normalizeVia(
            text = text,
            viaRange = viaRange,
            via = via,
            sourceAddress = sourceAddress,
            sourcePort = sourcePort,
            params = params,
            rportPresent = rportPresent,
            explicitRport = explicitRport,
        )

        return SipUdpResponseRoute(
            bytes = normalizedBytes,
            destinationAddress = destinationAddress,
            destinationHost = destinationHost,
            destinationPort = destinationPort,
            diagnostic = "viaSentBy=${sentBy.host}:${sentBy.port} " +
                "rportPresent=$rportPresent explicitRport=$explicitRport " +
                "maddr=${params["maddr"]} received=${params["received"]}",
        )
    }

    fun routeIfResponse(
        messageBytes: ByteArray,
        sourceAddress: InetAddress,
        sourcePort: Int,
    ): SipUdpResponseRoute? {
        if (!messageBytes.toString(Charsets.US_ASCII).startsWith("SIP/2.0 ")) {
            return null
        }
        return route(messageBytes, sourceAddress, sourcePort)
    }

    private fun sourceRoute(
        bytes: ByteArray,
        sourceAddress: InetAddress,
        sourcePort: Int,
    ) = SipUdpResponseRoute(
        bytes = bytes,
        destinationAddress = sourceAddress,
        destinationHost = null,
        destinationPort = sourcePort,
        diagnostic = "packet-source fallback: no parseable top Via",
    )

    private fun topViaValueRange(text: String): IntRange? {
        var offset = 0
        for (line in text.lineSequence()) {
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            val name = line.substring(0, separator.coerceAtLeast(0))
            if (separator > 0 &&
                (name.equals("via", true) || name.equals("v", true))
            ) {
                val valueStart = offset + separator + 1
                val first = text.indexOfFirstFrom(valueStart) { !it.isWhitespace() }
                if (first < 0) return null
                return first until (offset + line.length)
            }
            offset += line.length + 2
        }
        return null
    }

    private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun parseSentBy(via: String): SentBy? {
        val sentByText = via.substringAfter(' ', "")
            .substringBefore(';')
            .trim()
        if (sentByText.isEmpty()) return null

        if (sentByText.startsWith('[')) {
            val closingBracket = sentByText.indexOf(']')
            if (closingBracket <= 1) return null
            val host = sentByText.substring(1, closingBracket)
            val port = sentByText.substring(closingBracket + 1)
                .removePrefix(":")
                .toIntOrNull()
                ?: DEFAULT_SIP_PORT
            return SentBy(host, port)
        }

        val lastColon = sentByText.lastIndexOf(':')
        val hasSingleColon = lastColon >= 0 && sentByText.indexOf(':') == lastColon
        if (!hasSingleColon) return SentBy(sentByText, DEFAULT_SIP_PORT)
        return SentBy(
            host = sentByText.substring(0, lastColon),
            port = sentByText.substring(lastColon + 1).toIntOrNull()
                ?: DEFAULT_SIP_PORT,
        )
    }

    private fun parseParameters(via: String): Map<String, String?> =
        via.substringAfter(';', "")
            .split(';')
            .filter { it.isNotBlank() }
            .associate { raw ->
                val name = raw.substringBefore('=').trim().lowercase()
                val value = raw.substringAfter('=', "")
                    .trim()
                    .takeIf { raw.contains('=') }
                name to value
            }

    private fun normalizeVia(
        text: String,
        viaRange: IntRange,
        via: String,
        sourceAddress: InetAddress,
        sourcePort: Int,
        params: Map<String, String?>,
        rportPresent: Boolean,
        explicitRport: Int?,
    ): ByteArray {
        if (!rportPresent || explicitRport != null) return text.toByteArray(Charsets.US_ASCII)

        var normalized = via.replace(
            Regex("(?i)(;rport)(?=;|$)"),
            ";rport=$sourcePort",
        )
        val sourceHost = sourceAddress.hostAddress?.substringBefore('%')
        if (!params.containsKey("received") && !sourceHost.isNullOrBlank()) {
            normalized += ";received=$sourceHost"
        }
        return text.replaceRange(viaRange, normalized).toByteArray(Charsets.US_ASCII)
    }
}
