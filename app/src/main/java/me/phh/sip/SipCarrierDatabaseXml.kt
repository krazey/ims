// SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.telephony.Rlog
import me.phh.ims.R
import org.xmlpull.v1.XmlPullParser

internal object SipCarrierDatabaseXml {
    private const val TAG = "PHH CarrierDatabase"

    fun find(
        context: Context,
        query: SipCarrierDatabaseQuery,
    ): SipCarrierDatabaseRecord? {
        if (query.mccMnc.isEmpty()) return null
        val parser = context.resources.getXml(R.xml.sip_carrier_database)
        val mappings = mutableListOf<SipCarrierDatabaseMapping>()
        val profiles = mutableListOf<SipCarrierDatabaseProfile>()
        var selected: SipCarrierDatabaseMapping? = null
        var switches: Map<String, Boolean> = emptyMap()
        var switchesAreExact = false
        var csfbStatusCodes: Set<Int> = emptySet()
        var voiceCsfbStatusCodes: Set<Int> = emptySet()
        var emergencyDomain: String? = null
        var globalSettingsAreExact = false
        var source = "Samsung IMS database"
        var verification = "firmware_reference"

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "carrier-database" -> {
                            source = parser.attribute("source") ?: source
                            verification = parser.attribute("verification") ?: verification
                        }

                        "mapping" -> {
                            val canonical = parser.attribute("mccmnc").orEmpty()
                            if (canonical == query.mccMnc) {
                                mappings += SipCarrierDatabaseMapping(
                                    sourcePlmn = parser.attribute("plmn").orEmpty(),
                                    canonicalMccMnc = canonical,
                                    mnoName = parser.attribute("mno").orEmpty(),
                                    subset = parser.attribute("subset").orEmpty(),
                                    gid1 = parser.attribute("gid1").orEmpty(),
                                    gid2 = parser.attribute("gid2").orEmpty(),
                                    spn = parser.attribute("spname").orEmpty(),
                                    blockGc = parser.attribute("block_gc").toBoolean(),
                                )
                            }
                        }

                        "profiles" -> selected = SipCarrierDatabaseSelector.select(mappings, query)

                        "profile" -> {
                            val mapping = selected
                            val mnoName = parser.attribute("mnoname").orEmpty()
                            if (mapping != null && mnoMatches(mapping.mnoName, mnoName)) {
                                profiles += readProfile(parser)
                            }
                        }

                        "switch" -> {
                            val mapping = selected
                            val candidate = parser.attribute("mnoname").orEmpty()
                            val exact = mapping?.mnoName.equals(candidate, ignoreCase = true)
                            if (mapping != null && mnoMatches(mapping.mnoName, candidate) &&
                                (exact || !switchesAreExact)
                            ) {
                                switches = readBooleanAttributes(parser)
                                switchesAreExact = exact
                            }
                        }

                        "global" -> {
                            val mapping = selected
                            val candidate = parser.attribute("mnoname").orEmpty()
                            val exact = mapping?.mnoName.equals(candidate, ignoreCase = true)
                            if (mapping != null && mnoMatches(mapping.mnoName, candidate) &&
                                (exact || !globalSettingsAreExact)
                            ) {
                                csfbStatusCodes = parser.intSet("all_csfb_error_code_list")
                                voiceCsfbStatusCodes = parser.intSet("voice_csfb_error_code_list")
                                emergencyDomain = parser.attribute("emergency_domain_setting")
                                globalSettingsAreExact = exact
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            Rlog.e(TAG, "Could not read carrier database for ${query.mccMnc}", t)
            return null
        } finally {
            parser.close()
        }

        val mapping = selected ?: SipCarrierDatabaseSelector.select(mappings, query) ?: return null
        return SipCarrierDatabaseRecord(
            mapping = mapping,
            profiles = profiles.sortedByDescending {
                it.mnoName.equals(mapping.mnoName, ignoreCase = true)
            },
            serviceSwitches = switches,
            csfbStatusCodes = csfbStatusCodes,
            voiceCsfbStatusCodes = voiceCsfbStatusCodes,
            emergencyDomain = emergencyDomain,
            source = source,
            verification = verification,
        ).also {
            Rlog.i(
                TAG,
                "Matched ${query.mccMnc} to ${mapping.mnoName}; " +
                    "profiles=${profiles.size} services=${it.voiceProfile?.services.orEmpty()}",
            )
        }
    }

    private fun readProfile(parser: XmlPullParser): SipCarrierDatabaseProfile =
        SipCarrierDatabaseProfile(
            name = parser.attribute("name").orEmpty(),
            mnoName = parser.attribute("mnoname").orEmpty(),
            representativePlmn = parser.attribute("representative_plmn").orEmpty(),
            pdn = parser.attribute("pdn").orEmpty(),
            emergency = parser.attribute("emergency_support").toBoolean(),
            remoteUriType = parser.attribute("remote_uri_type").orEmpty(),
            ipVersion = parser.attribute("ipver").orEmpty(),
            transport = parser.attribute("transport").orEmpty(),
            supportIpsec = parser.attribute("support_ipsec").toBoolean(),
            usePrecondition = parser.attribute("use_precondition").toBoolean(),
            wifiPrecondition = parser.attribute("wifi_precondition_enabled").toBoolean(),
            supportRoaming = parser.attribute("support_roaming").toBoolean(),
            authAlgorithms = parser.csv("auth_algo"),
            encryptionAlgorithms = parser.csv("enc_algo"),
            subscribeForReg = parser.attribute("subscribe_for_reg")?.toBooleanStrictOrNull()
                ?: true,
            services = parser.csv("services").toSet(),
            networks = parser.csv("networks").toSet(),
            minSeSeconds = parser.attribute("min_se")?.toIntOrNull(),
            sessionExpiresSeconds = parser.attribute("session_expires")?.toIntOrNull(),
            ringingTimerSeconds = parser.attribute("ringing_timer")?.toIntOrNull(),
            ringbackTimerSeconds = parser.attribute("ringback_timer")?.toIntOrNull(),
            mssSize = parser.attribute("mss_size")?.toIntOrNull(),
        )

    private fun readBooleanAttributes(parser: XmlPullParser): Map<String, Boolean> {
        val values = mutableMapOf<String, Boolean>()
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index)
            if (name != "mnoname") {
                parser.getAttributeValue(index).toBooleanStrictOrNull()?.let { values[name] = it }
            }
        }
        return values
    }

    private fun mnoMatches(mapped: String, candidate: String): Boolean {
        if (mapped.equals(candidate, ignoreCase = true)) return true
        return mapped.substringBefore(':').equals(candidate, ignoreCase = true)
    }

    private fun XmlPullParser.attribute(name: String): String? = getAttributeValue(null, name)

    private fun XmlPullParser.csv(name: String): List<String> =
        attribute(name).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

    private fun XmlPullParser.intSet(name: String): Set<Int> =
        csv(name).mapNotNull(String::toIntOrNull).toSet()
}
