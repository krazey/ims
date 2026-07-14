// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

/** Split a SIP list header without treating quoted/name-addr commas as separators. */
internal fun splitSipListHeader(value: String): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    var angleDepth = 0
    var commentDepth = 0

    fun finishValue() {
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(values::add)
        current.setLength(0)
    }

    value.forEach { character ->
        when {
            escaped -> escaped = false
            character == '\\' && (quoted || commentDepth > 0) -> escaped = true
            character == '"' && commentDepth == 0 -> quoted = !quoted
            !quoted && character == '<' && commentDepth == 0 -> angleDepth++
            !quoted && character == '>' && commentDepth == 0 && angleDepth > 0 -> angleDepth--
            !quoted && angleDepth == 0 && character == '(' -> commentDepth++
            !quoted && angleDepth == 0 && character == ')' && commentDepth > 0 -> commentDepth--
            character == ',' && !quoted && angleDepth == 0 && commentDepth == 0 -> {
                finishValue()
                return@forEach
            }
        }
        current.append(character)
    }
    finishValue()
    return values
}

internal fun recordRouteValues(headers: SipHeadersMap): List<String> =
    headers["record-route"].orEmpty().flatMap(::splitSipListHeader)

/** RFC 3261 section 12.1.2 route set for a dialog created by a UAC. */
internal fun outgoingDialogRouteSet(headers: SipHeadersMap): List<String> =
    recordRouteValues(headers).asReversed()
