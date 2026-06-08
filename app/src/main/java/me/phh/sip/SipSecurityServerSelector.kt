//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

data class SipSecurityServerSelection(
    val type: String,
    val params: Map<String, String>,
)

object SipSecurityServerSelector {
    private val supportedAlgorithms = listOf("hmac-sha-1-96", "hmac-md5-96")
    private val supportedEncryptionAlgorithms = listOf("aes-cbc", "null")

    private fun normalizeParams(rawParams: Map<String, String?>): Map<String, String> =
        rawParams.mapNotNull { (key, value) ->
            val normalizedValue = value?.trim()?.lowercase()
            if (normalizedValue.isNullOrEmpty()) {
                null
            } else {
                key.trim().lowercase() to normalizedValue
            }
        }.toMap()

    fun select(securityServerHeaders: List<String>): SipSecurityServerSelection {
        val supported = securityServerHeaders
            .map { header ->
                val (rawType, rawParams) = header.getParams()
                rawType.trim().lowercase() to normalizeParams(rawParams)
            }
            .filter { (type, params) ->
                type == "ipsec-3gpp" &&
                    supportedEncryptionAlgorithms.contains(params["ealg"] ?: "null") &&
                    supportedAlgorithms.contains(params["alg"])
            }
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
