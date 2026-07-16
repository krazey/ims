//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

data class SipRegisteredIdentity(
    val route: List<String>,
    val mySip: String,
    val myTel: String,
) {
    fun commonHeaders(): SipHeadersMap =
        mapOf(
            "route" to route,
            "from" to listOf("<$mySip>"),
            "to" to listOf("<$mySip>"),
        )
}

object SipRegisterSuccessParser {
    fun parse(response: SipResponse): SipRegisteredIdentity? {
        val lrParameterRegex = Regex("lr;[^>]*")
        val route =
            (response.headers.getOrDefault("service-route", emptyList()) +
                response.headers.getOrDefault("path", emptyList()))
                .toSet() // remove duplicates
                .toList()
                .map { lrParameterRegex.replace(it, "lr") }

        val associatedUris = response.headers["p-associated-uri"]
            ?.flatMap { it.split(",") }
            ?.mapNotNull(::extractAssociatedUri)
            .orEmpty()
        val mySip = associatedUris.firstOrNull {
            it.startsWith("sip:", ignoreCase = true)
        } ?: return null
        val myTel = associatedUris.firstOrNull {
            it.startsWith("tel:", ignoreCase = true)
        }?.substringAfter(":")
            ?: mySip.substringAfter(":").substringBefore("@").substringBefore(";")

        return SipRegisteredIdentity(
            route = route,
            mySip = mySip,
            myTel = myTel,
        )
    }

    private fun extractAssociatedUri(value: String): String? {
        val candidate = if ('<' in value && '>' in value) {
            value.substringAfter('<').substringBefore('>')
        } else {
            Regex("(?i)(?:sip|tel):[^\\s,>]+")
                .find(value)
                ?.value
                ?.substringBefore(';')
        }?.trim().orEmpty()

        return candidate.takeIf {
            it.startsWith("sip:", ignoreCase = true) ||
                it.startsWith("tel:", ignoreCase = true)
        }
    }
}
