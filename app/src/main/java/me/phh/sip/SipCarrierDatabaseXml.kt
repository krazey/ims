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
        var csfbStatusRules: Set<String> = emptySet()
        var voiceCsfbStatusRules: Set<String> = emptySet()
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
                                csfbStatusRules = parser.csv(
                                    "all_csfb_error_code_list",
                                ).toSet()
                                voiceCsfbStatusRules = parser.csv(
                                    "voice_csfb_error_code_list",
                                ).toSet()
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
            csfbStatusRules = csfbStatusRules,
            voiceCsfbStatusRules = voiceCsfbStatusRules,
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
            enableGruu = parser.attribute("enable_gruu")?.toBooleanStrictOrNull() ?: true,
            registrationRetryBaseSeconds = parser.attribute("reg_retry_base_time")?.toIntOrNull(),
            registrationRetryMaxSeconds = parser.attribute("reg_retry_max_time")?.toIntOrNull(),
            registrationPcscfPolicyOn403 = parser.attribute(
                "reg_retry_pcscf_policy_on_403",
            ),
            services = parser.csv("services").toSet(),
            networks = parser.csv("networks").toSet(),
            minSeSeconds = parser.attribute("min_se")?.toIntOrNull(),
            sessionExpiresSeconds = parser.attribute("session_expires")?.toIntOrNull(),
            inviteTimeoutSeconds = parser.attribute("invite_timeout")?.toIntOrNull(),
            ringingTimerSeconds = parser.attribute("ringing_timer")?.toIntOrNull(),
            ringbackTimerSeconds = parser.attribute("ringback_timer")?.toIntOrNull(),
            keepAliveModeMo = parser.attribute("keep_alive_mode_mo") ?: "none",
            keepAliveModeMt = parser.attribute("keep_alive_mode_mt") ?: "none",
            keepAliveIntervalMs = parser.attribute("keep_alive_interval")?.toLongOrNull(),
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

}
