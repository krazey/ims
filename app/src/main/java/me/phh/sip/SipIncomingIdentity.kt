// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal data class SipIncomingIdentity(
    val identityHeader: String?,
    val presentationRestricted: Boolean,
)

internal object SipIncomingIdentityResolver {
    private val restrictingPrivacyTokens = setOf("id", "user", "header", "full")

    fun resolve(headers: SipHeadersMap): SipIncomingIdentity {
        val from = headers["from"]?.firstOrNull()
        val privacyTokens = headers["privacy"].orEmpty()
            .flatMap { it.split(',', ';') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val explicitlyRestricted =
            "none" !in privacyTokens && privacyTokens.any { it in restrictingPrivacyTokens }
        val anonymousFrom = from?.let(::looksAnonymous).orDefault(false)
        val restricted = explicitlyRestricted || anonymousFrom
        val assertedIdentity = headers["p-asserted-identity"]?.firstOrNull()

        return SipIncomingIdentity(
            identityHeader = if (restricted) null else assertedIdentity ?: from,
            presentationRestricted = restricted,
        )
    }

    private fun looksAnonymous(header: String): Boolean {
        val normalized = header.lowercase().filterNot { it.isWhitespace() }
        return normalized.contains("anonymous") ||
            normalized.contains("sip:anonymous@anonymous.invalid")
    }

    private fun Boolean?.orDefault(default: Boolean): Boolean = this ?: default
}
