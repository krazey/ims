// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

internal object SipMultipartBody {
    fun isContentType(contentType: String?, expectedType: String): Boolean =
        contentType
            ?.substringBefore(';')
            ?.trim()
            ?.equals(expectedType, ignoreCase = true) == true

    fun isMultipart(contentType: String?): Boolean =
        contentType
            ?.substringBefore(';')
            ?.trim()
            ?.startsWith("multipart/", ignoreCase = true) == true

    fun bodyContainsContentType(body: ByteArray, expectedPartContentType: String): Boolean {
        val bodyText = body.toString(Charsets.ISO_8859_1)
        return bodyText
            .lineSequence()
            .any { line ->
                line.substringBefore(':')
                    .trim()
                    .equals("content-type", ignoreCase = true) &&
                    isContentType(line.substringAfter(':').trim(), expectedPartContentType)
            }
    }

    fun extractPartBody(
        contentType: String?,
        body: ByteArray,
        expectedPartContentType: String,
    ): ByteArray? {
        val bodyText = body.toString(Charsets.ISO_8859_1)
        val boundaries = candidateBoundaries(contentType, bodyText)

        for (boundary in boundaries) {
            val extracted = extractPartBodyWithBoundary(
                bodyText = bodyText,
                boundary = boundary,
                expectedPartContentType = expectedPartContentType,
            )
            if (extracted != null) return extracted
        }

        return null
    }

    private fun extractPartBodyWithBoundary(
        bodyText: String,
        boundary: String,
        expectedPartContentType: String,
    ): ByteArray? {
        val delimiter = "--$boundary"

        for (rawPart in bodyText.split(delimiter).drop(1)) {
            val part = rawPart.trimStart('\r', '\n')
            if (part.startsWith("--")) break
            if (part.isBlank()) continue

            val (headerEnd, separatorLength) = headerBodySeparator(part) ?: continue
            val headerBlock = part.substring(0, headerEnd)
            val partBody = part.substring(headerEnd + separatorLength)
            val partContentType = headerBlock
                .lineSequence()
                .firstNotNullOfOrNull { line ->
                    val name = line.substringBefore(':').trim()
                    if (name.equals("content-type", ignoreCase = true)) {
                        line.substringAfter(':').trim()
                    } else {
                        null
                    }
                }
                ?: continue

            if (isContentType(partContentType, expectedPartContentType)) {
                return partBody
                    .trimEnd('\r', '\n')
                    .toByteArray(Charsets.ISO_8859_1)
            }
        }

        return null
    }

    private fun candidateBoundaries(contentType: String?, bodyText: String): List<String> =
        buildList {
            boundaryFromContentType(contentType)?.let { add(it) }

            bodyText
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("--") && !it.startsWith("---") }
                .map { it.removePrefix("--").removeSuffix("--").trim() }
                .filter { it.isNotBlank() }
                .forEach { add(it) }
        }.distinct()

    private fun boundaryFromContentType(contentType: String?): String? =
        contentType
            ?.split(';')
            ?.asSequence()
            ?.drop(1)
            ?.mapNotNull { parameter ->
                val name = parameter.substringBefore('=').trim()
                val value = parameter.substringAfter('=', "").trim().trim('"')
                if (name.equals("boundary", ignoreCase = true) && value.isNotBlank()) {
                    value
                } else {
                    null
                }
            }
            ?.firstOrNull()

    private fun headerBodySeparator(part: String): Pair<Int, Int>? {
        val crlf = part.indexOf("\r\n\r\n")
        if (crlf >= 0) return crlf to 4

        val lf = part.indexOf("\n\n")
        if (lf >= 0) return lf to 2

        return null
    }
}
