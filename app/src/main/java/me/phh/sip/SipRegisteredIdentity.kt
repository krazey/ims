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
    fun parse(response: SipResponse): SipRegisteredIdentity {
        val lrParameterRegex = Regex("lr;[^>]*")
        val route =
            (response.headers.getOrDefault("service-route", emptyList()) +
                response.headers.getOrDefault("path", emptyList()))
                .toSet() // remove duplicates
                .toList()
                .map { lrParameterRegex.replace(it, "lr") }

        val associatedUri =
            response.headers["p-associated-uri"]!!
                .flatMap { it.split(",") }
                .map { it.trimStart('<').trimEnd('>').split(':') }
        val preSip = associatedUri.first { it[0] == "sip" }[1]
        val mySip = "sip:$preSip"
        val myTel = associatedUri.firstOrNull { it[0] == "tel" }?.get(1) ?: preSip.split("@")[0]

        return SipRegisteredIdentity(
            route = route,
            mySip = mySip,
            myTel = myTel,
        )
    }
}
