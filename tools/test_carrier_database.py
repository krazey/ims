#!/usr/bin/env python3
"""Fast integrity checks for the checked-in generated carrier database."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


DATABASE = Path(__file__).parents[1] / "app/src/main/res/xml/sip_carrier_database.xml"

RUNTIME_PROFILE_ATTRIBUTES = {
    "name", "mnoname", "representative_plmn", "pdn", "emergency_support",
    "remote_uri_type", "ipver", "transport", "support_ipsec",
    "use_precondition", "wifi_precondition_enabled", "support_roaming",
    "auth_algo", "enc_algo", "subscribe_for_reg", "enable_gruu",
    "reg_retry_base_time", "reg_retry_max_time",
    "reg_retry_pcscf_policy_on_403", "reg_expires", "session_expires",
    "min_se", "invite_timeout", "ringing_timer", "ringback_timer",
    "keep_alive_mode_mo", "keep_alive_mode_mt", "keep_alive_interval",
    "mss_size", "pcscf_pref", "sos_urn_required", "block_deregi_on_srvcc",
    "last_pani_header", "supported_geolocation_phase", "audio_codec",
    "enable_evs_codec", "networks", "services",
}
RUNTIME_MAPPING_ATTRIBUTES = {
    "plmn", "mccmnc", "mno", "subset", "gid1", "gid2", "spname",
    "block_gc", "note",
}
RUNTIME_SWITCH_ATTRIBUTES = {
    "mnoname", "enableIms", "enableServiceDatachannel", "enableServiceRcs",
    "enableServiceRcschat", "enableServiceSmsip", "enableServiceVilte",
    "enableServiceVolte", "enableServiceVowifi",
}
RUNTIME_GLOBAL_ATTRIBUTES = {
    "mnoname", "all_csfb_error_code_list", "voice_csfb_error_code_list",
    "e911_csfb_error_code_list", "emergency_domain_setting",
    "no_sim_emergency_domain_setting", "enable_default_sms_fallback",
    "srvcc_version", "ss_domain_setting", "ss_cf_uri_type",
    "iwlan_pani_format",
}


class CarrierDatabaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.root = ET.parse(DATABASE).getroot()
        cls.mappings = list(cls.root.find("mappings"))
        cls.profiles = list(cls.root.find("profiles"))
        cls.globals = list(cls.root.find("global-settings"))

    def mapping(self, canonical: str, mno: str):
        return next(
            item
            for item in self.mappings
            if item.get("mccmnc") == canonical and item.get("mno") == mno
        )

    def profile(self, mno: str, name: str):
        return next(
            item
            for item in self.profiles
            if item.get("mnoname") == mno and item.get("name") == name
        )

    def global_settings(self, mno: str):
        return next(item for item in self.globals if item.get("mnoname") == mno)

    def test_snapshot_is_complete(self):
        self.assertEqual(1441, len(self.mappings))
        self.assertEqual(1429, len(self.profiles))
        self.assertEqual(585, len(self.root.find("switches")))
        self.assertEqual(666, len(self.globals))

    def test_every_generated_profile_attribute_has_a_runtime_disposition(self):
        generated = set().union(*(profile.attrib.keys() for profile in self.profiles))
        self.assertEqual(RUNTIME_PROFILE_ATTRIBUTES, generated)

    def test_every_other_generated_attribute_has_a_runtime_disposition(self):
        sections = (
            ("mappings", RUNTIME_MAPPING_ATTRIBUTES),
            ("switches", RUNTIME_SWITCH_ATTRIBUTES),
            ("global-settings", RUNTIME_GLOBAL_ATTRIBUTES),
        )
        for section, expected in sections:
            elements = self.root.find(section)
            generated = set().union(*(element.attrib.keys() for element in elements))
            self.assertEqual(expected, generated, section)

    def test_two_digit_mncs_are_canonicalized_without_collision(self):
        self.assertEqual("40177", self.mapping("401077", "Tele2_KZ").get("plmn"))
        self.assertEqual("40107", self.mapping("401007", "Altel_KZ").get("plmn"))

    def test_known_carrier_profiles_survive_normalization(self):
        a1 = self.profile("Vodafone_HR", "Vodafone Croatia IMS")
        self.assertEqual("sip", a1.get("remote_uri_type"))
        self.assertIn("mmtel", a1.get("services").split(","))

        tele2 = self.profile("Tele2_KZ", "Tele2 Kazakhstan IMS")
        self.assertEqual("sip", tele2.get("remote_uri_type"))
        self.assertEqual("90", tele2.get("ringing_timer"))
        self.assertEqual("true", tele2.get("use_precondition"))
        self.assertEqual("udp-preferred", tele2.get("transport"))
        self.assertEqual(
            "380,403,500,503,1117",
            self.global_settings("Tele2_KZ").get("all_csfb_error_code_list"),
        )

        singtel = self.profile("Singtel_SG", "Singtel VoLTE")
        self.assertEqual("hmac-md5-96", singtel.get("auth_algo"))
        self.assertEqual("tcp", singtel.get("transport"))

        jio = self.profile("RJIL_IN", "RJIL VoLTE")
        self.assertEqual("ipv4", jio.get("ipver"))
        self.assertEqual("tcp", jio.get("transport"))
        self.assertEqual("false", jio.get("use_precondition"))

    def test_singtel_aliases_are_all_present(self):
        for canonical in ("525001", "525002", "525096"):
            self.mapping(canonical, "Singtel_SG")

    def test_chinese_voice_profiles_use_tel_uris(self):
        for mno, name in (
            ("CMCC_CN", "CMCC Mobile VoLTE"),
            ("CU_CN", "CU Mobile VoLTE"),
            ("CTC_CN", "CTC Mobile"),
        ):
            profile = self.profile(mno, name)
            self.assertEqual("tel", profile.get("remote_uri_type"))
            self.assertEqual("alerting", profile.get("keep_alive_mode_mo"))
            self.assertEqual("incoming", profile.get("keep_alive_mode_mt"))

    def test_verizon_keepalive_starts_while_outgoing(self):
        profile = self.profile("VZW_US", "VZW VoLTE")
        self.assertEqual("outgoing", profile.get("keep_alive_mode_mo"))
        self.assertEqual("incoming", profile.get("keep_alive_mode_mt"))
        self.assertEqual("2000", profile.get("keep_alive_interval"))

    def test_csfb_status_class_rules_are_preserved(self):
        tele2_se = self.global_settings("Tele2_SE")
        self.assertEqual(
            "3xx,5xx,6xx,1117",
            tele2_se.get("all_csfb_error_code_list"),
        )

        etisalat = self.global_settings("Etisalat_AE")
        self.assertEqual("403,5xx", etisalat.get("voice_csfb_error_code_list"))


if __name__ == "__main__":
    unittest.main()
