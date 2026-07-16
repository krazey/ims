#!/usr/bin/env python3
"""Fast integrity checks for the checked-in generated carrier database."""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


DATABASE = Path(__file__).parents[1] / "app/src/main/res/xml/sip_carrier_database.xml"


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
        self.assertEqual(1416, len(self.mappings))
        self.assertEqual(1370, len(self.profiles))
        self.assertEqual(560, len(self.root.find("switches")))
        self.assertEqual(637, len(self.globals))

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
        self.assertEqual(
            "380,403,500,503,1117",
            self.global_settings("Tele2_KZ").get("all_csfb_error_code_list"),
        )

        singtel = self.profile("Singtel_SG", "Singtel VoLTE")
        self.assertEqual("hmac-md5-96", singtel.get("auth_algo"))
        self.assertEqual("tcp", singtel.get("transport"))

    def test_singtel_aliases_are_all_present(self):
        for canonical in ("525001", "525002", "525096"):
            self.mapping(canonical, "Singtel_SG")

    def test_chinese_voice_profiles_use_tel_uris(self):
        for mno, name in (
            ("CMCC_CN", "CMCC Mobile VoLTE"),
            ("CU_CN", "CU Mobile VoLTE"),
            ("CTC_CN", "CTC Mobile"),
        ):
            self.assertEqual("tel", self.profile(mno, name).get("remote_uri_type"))


if __name__ == "__main__":
    unittest.main()
