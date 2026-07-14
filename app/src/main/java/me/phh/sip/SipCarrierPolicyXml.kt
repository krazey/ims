// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.telephony.Rlog
import me.phh.ims.R
import org.xmlpull.v1.XmlPullParser

internal object SipCarrierPolicyXml {
    private const val TAG = "PHH CarrierPolicyXml"

    private data class SelectedOverlay(
        val specificity: Int,
        val order: Int,
        val name: String,
        val overlay: SipCarrierPolicyOverlay,
    )

    fun apply(
        context: Context,
        base: SipCarrierPolicy,
        carrierId: Int,
    ): SipCarrierPolicy {
        val selected = mutableListOf<SelectedOverlay>()
        val parser = context.resources.getXml(R.xml.sip_carrier_policies)
        var order = 0
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "carrier") {
                    val mccMnc = parser.attribute("mccmnc")?.trim().orEmpty()
                    val selectedCarrierId = parser.attribute("carrierId")?.toIntOrNull()
                    val matchAny = parser.attribute("match").equals("any", ignoreCase = true)
                    val name = parser.attribute("name").orEmpty().ifBlank {
                        "mccmnc=$mccMnc carrierId=$selectedCarrierId"
                    }
                    val overlay = readCarrierOverlay(parser)
                    val mccMatches = mccMnc.isNotEmpty() && mccMnc == base.mccMnc
                    val carrierIdMatches = selectedCarrierId != null &&
                        carrierId >= 0 && selectedCarrierId == carrierId
                    val selectorMatches = if (matchAny) {
                        mccMatches || carrierIdMatches
                    } else {
                        (mccMnc.isEmpty() || mccMatches) &&
                            (selectedCarrierId == null || carrierIdMatches)
                    }
                    if (selectorMatches) {
                        selected += SelectedOverlay(
                            specificity = if (matchAny) {
                                1
                            } else {
                                (if (mccMnc.isNotEmpty()) 1 else 0) +
                                    (if (selectedCarrierId != null) 1 else 0)
                            },
                            order = order,
                            name = name,
                            overlay = overlay,
                        )
                    }
                    order++
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            Rlog.e(
                TAG,
                "Could not load carrier policy XML for mccmnc=${base.mccMnc} carrierId=$carrierId",
                t,
            )
            return base
        } finally {
            parser.close()
        }

        var resolved = base
        selected.sortedWith(compareBy<SelectedOverlay> { it.specificity }.thenBy { it.order })
            .forEach { match ->
                Rlog.i(
                    TAG,
                    "Applying carrier policy '${match.name}' to mccmnc=${base.mccMnc} " +
                        "carrierId=$carrierId specificity=${match.specificity}",
                )
                resolved = match.overlay.applyTo(resolved)
            }
        return resolved
    }

    private fun readCarrierOverlay(parser: XmlPullParser): SipCarrierPolicyOverlay {
        val booleans = mutableMapOf<String, Boolean>()
        val longs = mutableMapOf<String, Long>()
        val strings = mutableMapOf<String, String>()
        val arrays = mutableMapOf<String, MutableList<String>>()
        val headers = mutableMapOf<String, MutableList<String>>()
        var currentArray: String? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "boolean" -> {
                        val name = parser.attribute("name") ?: continue
                        parser.attribute("value")?.toBooleanStrictOrNull()?.let {
                            booleans[name] = it
                        }
                    }

                    "long" -> {
                        val name = parser.attribute("name") ?: continue
                        parser.attribute("value")?.toLongOrNull()?.let { longs[name] = it }
                    }

                    "string" -> {
                        val name = parser.attribute("name") ?: continue
                        parser.attribute("value")?.let { strings[name] = it }
                    }

                    "string-array" -> {
                        currentArray = parser.attribute("name")
                        currentArray?.let { arrays.getOrPut(it) { mutableListOf() } }
                    }

                    "item" -> {
                        val arrayName = currentArray ?: continue
                        parser.attribute("value")?.let {
                            arrays.getOrPut(arrayName) { mutableListOf() } += it
                        }
                    }

                    "register-header" -> {
                        val name = parser.attribute("name") ?: continue
                        val value = parser.attribute("value") ?: continue
                        headers.getOrPut(name) { mutableListOf() } += value
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "string-array" -> currentArray = null
                    "carrier" -> return SipCarrierPolicyOverlay(
                        booleans = booleans,
                        longs = longs,
                        strings = strings,
                        stringArrays = arrays,
                        registerHeaders = headers,
                    )
                }

                XmlPullParser.END_DOCUMENT -> return SipCarrierPolicyOverlay()
            }
        }
    }

    private fun XmlPullParser.attribute(name: String): String? =
        getAttributeValue(null, name)
}
