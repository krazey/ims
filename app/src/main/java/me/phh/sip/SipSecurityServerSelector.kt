//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

data class SipSecurityServerSelection(
    val type: String,
    val params: Map<String, String>,
)

object SipSecurityServerSelector {
    private val supportedAlgorithms = listOf("hmac-sha-1-96", "hmac-md5-96")
    private val supportedEncryptionAlgorithms = listOf("aes-cbc", "null")

    private fun splitSecurityServerMechanisms(header: String): List<String> {
        val mechanisms = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var escaped = false

        header.forEach { c ->
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                c == '\\' && inQuotes -> {
                    current.append(c)
                    escaped = true
                }
                c == '"' -> {
                    current.append(c)
                    inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> {
                    val mechanism = current.toString().trim()
                    if (mechanism.isNotEmpty()) {
                        mechanisms.add(mechanism)
                    }
                    current.clear()
                }
                else -> current.append(c)
            }
        }

        val mechanism = current.toString().trim()
        if (mechanism.isNotEmpty()) {
            mechanisms.add(mechanism)
        }

        return mechanisms
    }

    private fun normalizeParams(rawParams: Map<String, String?>): Map<String, String> =
        rawParams.mapNotNull { (key, value) ->
            val normalizedValue = value?.trim()?.lowercase()
            if (normalizedValue.isNullOrEmpty()) {
                null
            } else {
                key.trim().lowercase() to normalizedValue
            }
        }.toMap()

    private fun hasValidSpi(value: String?): Boolean {
        val parsed = value?.toLongOrNull() ?: return false
        return parsed in 0..0xffffffffL
    }

    private fun hasValidPort(value: String?): Boolean {
        val parsed = value?.toIntOrNull() ?: return false
        return parsed in 1..65535
    }

    private fun isSupportedIpsecOffer(type: String, params: Map<String, String>): Boolean =
        type == "ipsec-3gpp" &&
            supportedAlgorithms.contains(params["alg"]) &&
            supportedEncryptionAlgorithms.contains(params["ealg"] ?: "null") &&
            hasValidSpi(params["spi-c"]) &&
            hasValidSpi(params["spi-s"]) &&
            hasValidPort(params["port-c"]) &&
            hasValidPort(params["port-s"])

    fun select(securityServerHeaders: List<String>): SipSecurityServerSelection {
        val supported = securityServerHeaders
            .flatMap { header -> splitSecurityServerMechanisms(header) }
            .map { mechanism ->
                val (rawType, rawParams) = mechanism.getParams()
                rawType.trim().lowercase() to normalizeParams(rawParams)
            }
            .filter { (type, params) -> isSupportedIpsecOffer(type, params) }
            .sortedByDescending { (_, params) ->
                params["q"]?.toFloatOrNull() ?: 0f
            }

        require(supported.isNotEmpty()) {
            "No supported Security-Server header in ${securityServerHeaders.joinToString(" | ")}"
        }

        val (type, params) = supported[0]
        return SipSecurityServerSelection(type = type, params = params)
    }
}
