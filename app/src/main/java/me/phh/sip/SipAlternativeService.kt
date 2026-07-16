// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal enum class SipAlternativeServiceAction {
    INITIAL_REGISTRATION,
    EMERGENCY_REGISTRATION,
    EMERGENCY,
}

internal data class SipAlternativeService(
    val action: SipAlternativeServiceAction,
    val reason: String,
    val serviceUrn: String,
) {
    val requiresImsRetry: Boolean
        get() = action == SipAlternativeServiceAction.INITIAL_REGISTRATION ||
            action == SipAlternativeServiceAction.EMERGENCY_REGISTRATION
}

internal object SipAlternativeServiceParser {
    private val serviceUrnPattern = Regex(
        "urn:service:sos(?:\\.[a-z0-9-]+)*",
        RegexOption.IGNORE_CASE,
    )

    fun parse(response: SipResponse): SipAlternativeService? {
        if (response.statusCode !in setOf(380, 382)) return null
        val body = response.body.toString(Charsets.UTF_8)
        val type = tagValue(body, "type") ?: return null
        if (!type.equals("emergency", ignoreCase = true)) return null

        val action = when (tagValue(body, "action").normalizedToken()) {
            "initial-registration" -> SipAlternativeServiceAction.INITIAL_REGISTRATION
            "emergency-registration" -> SipAlternativeServiceAction.EMERGENCY_REGISTRATION
            "emergency", "" -> SipAlternativeServiceAction.EMERGENCY
            else -> return null
        }
        val serviceUrn = sequenceOf(
            tagValue(body, "service-urn"),
            tagValue(body, "serviceUrn"),
            response.headers["contact"]?.joinToString(","),
            body,
        ).filterNotNull()
            .mapNotNull { serviceUrnPattern.find(it)?.value }
            .firstOrNull()
            ?.lowercase()
            ?: "urn:service:sos"

        return SipAlternativeService(
            action = action,
            reason = decodeXmlText(tagValue(body, "reason").orEmpty()),
            serviceUrn = serviceUrn,
        )
    }

    private fun tagValue(xml: String, localName: String): String? {
        val escapedName = Regex.escape(localName)
        val pattern = Regex(
            "<(?:(?:[a-zA-Z0-9_-]+):)?$escapedName(?:\\s[^>]*)?>" +
                "(.*?)</(?:(?:[a-zA-Z0-9_-]+):)?$escapedName\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.find(xml)?.groupValues?.get(1)?.trim()
    }

    private fun String?.normalizedToken(): String = this.orEmpty()
        .trim()
        .lowercase()
        .replace('_', '-')
        .replace(' ', '-')

    private fun decodeXmlText(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
