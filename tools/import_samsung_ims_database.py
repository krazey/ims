#!/usr/bin/env python3
"""Generate PhhIms' normalized carrier database from Samsung IMS JSON files.

The generated XML contains derived carrier facts used by PhhIms. It does not
embed the source JSON or Samsung-only implementation details.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


PROFILE_KEYS = (
    "name",
    "mnoname",
    "representative_plmn",
    "pdn",
    "emergency_support",
    "remote_uri_type",
    "ipver",
    "transport",
    "support_ipsec",
    "use_precondition",
    "wifi_precondition_enabled",
    "support_roaming",
    "auth_algo",
    "enc_algo",
    "subscribe_for_reg",
    "reg_expires",
    "session_expires",
    "min_se",
    "invite_timeout",
    "ringing_timer",
    "ringback_timer",
    "mss_size",
    "pcscf_pref",
    "sos_urn_required",
    "enable_gruu",
    "block_deregi_on_srvcc",
    "last_pani_header",
    "supported_geolocation_phase",
    "audio_codec",
    "enable_evs_codec",
)

GLOBAL_KEYS = (
    "all_csfb_error_code_list",
    "voice_csfb_error_code_list",
    "e911_csfb_error_code_list",
    "emergency_domain_setting",
    "no_sim_emergency_domain_setting",
    "enable_default_sms_fallback",
    "srvcc_version",
    "ss_domain_setting",
    "ss_cf_uri_type",
    "iwlan_pani_format",
)


def strip_json_comments(text: str) -> str:
    """Remove // and /* */ comments without touching strings."""
    output: list[str] = []
    index = 0
    in_string = False
    escaped = False
    while index < len(text):
        char = text[index]
        following = text[index + 1] if index + 1 < len(text) else ""
        if in_string:
            output.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            output.append(char)
            index += 1
            continue
        if char == "/" and following == "/":
            index += 2
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        if char == "/" and following == "*":
            index += 2
            while index + 1 < len(text) and text[index : index + 2] != "*/":
                index += 1
            index += 2
            continue
        output.append(char)
        index += 1
    return "".join(output)


def load_json(path: Path) -> dict:
    return json.loads(strip_json_comments(path.read_text(encoding="utf-8")))


def canonical_plmn(raw: str) -> str:
    if not raw.isdigit() or len(raw) not in (5, 6):
        return ""
    return raw[:3] + raw[3:].zfill(3)


def clean_mno_name(raw: str) -> tuple[str, bool]:
    blocked = raw.endswith("@BLOCKGC")
    return raw.removesuffix("@BLOCKGC"), blocked


def scalar(value: object) -> str:
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, (dict, list)):
        return ",".join(str(item) for item in value)
    return str(value)


def set_attributes(element: ET.Element, values: dict, keys: tuple[str, ...]) -> None:
    for key in keys:
        if key in values and values[key] not in (None, "", []):
            element.set(key, scalar(values[key]))


def source_digest(source: Path) -> str:
    digest = hashlib.sha256()
    for name in ("mnomap.json", "imsprofile.json", "imsswitch.json", "globalsettings.json"):
        digest.update(name.encode())
        digest.update((source / name).read_bytes())
    return f"sha256:{digest.hexdigest()}"


def build_database(source: Path, source_label: str) -> ET.Element:
    mnomap = load_json(source / "mnomap.json").get("mnomap", [])
    profile_document = load_json(source / "imsprofile.json")
    profiles = profile_document.get("profile", [])
    switches_document = load_json(source / "imsswitch.json")
    global_document = load_json(source / "globalsettings.json")

    root = ET.Element(
        "carrier-database",
        {
            "version": "1",
            "source": source_label,
            "source_digest": source_digest(source),
            "verification": "firmware_reference",
        },
    )

    mappings_element = ET.SubElement(root, "mappings", {"count": str(len(mnomap))})
    for mapping in mnomap:
        raw_mno = scalar(mapping.get("mnoname", ""))
        mno, blocked = clean_mno_name(raw_mno)
        attributes = {
            "plmn": scalar(mapping.get("mccmnc", "")),
            "mccmnc": canonical_plmn(scalar(mapping.get("mccmnc", ""))),
            "mno": mno,
        }
        for key in ("subset", "gid1", "gid2", "spname", "note"):
            value = scalar(mapping.get(key, ""))
            if value:
                attributes[key] = value
        if blocked:
            attributes["block_gc"] = "true"
        ET.SubElement(mappings_element, "mapping", attributes)

    default_profile = next(
        (profile for profile in profiles if profile.get("name") == "default"),
        {},
    )
    carrier_profiles = [profile for profile in profiles if profile.get("name") != "default"]
    profiles_element = ET.SubElement(
        root,
        "profiles",
        {"count": str(len(carrier_profiles))},
    )
    for profile in carrier_profiles:
        effective = dict(default_profile)
        effective.update(profile)
        element = ET.SubElement(profiles_element, "profile")
        set_attributes(element, effective, PROFILE_KEYS)
        services: set[str] = set()
        networks: set[str] = set()
        for network in effective.get("network", []):
            if not network.get("enabled", True):
                continue
            networks.update(part.strip() for part in network.get("type", "").split(",") if part.strip())
            services.update(str(item) for item in network.get("services", []))
        if networks:
            element.set("networks", ",".join(sorted(networks)))
        if services:
            element.set("services", ",".join(sorted(services)))

    switches = switches_document.get("imsswitch", [])
    switches_element = ET.SubElement(root, "switches", {"count": str(len(switches))})
    for switch in switches:
        attributes = {"mnoname": scalar(switch.get("mnoname", ""))}
        for key, value in switch.items():
            if key != "mnoname" and isinstance(value, (bool, int, str)):
                attributes[key] = scalar(value)
        ET.SubElement(switches_element, "switch", attributes)

    global_settings = global_document.get("globalsetting", [])
    globals_element = ET.SubElement(
        root,
        "global-settings",
        {"count": str(len(global_settings))},
    )
    for settings in global_settings:
        element = ET.SubElement(
            globals_element,
            "global",
            {"mnoname": scalar(settings.get("mnoname", ""))},
        )
        set_attributes(element, settings, GLOBAL_KEYS)

    return root


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "source",
        type=Path,
        help="directory containing Samsung res/raw IMS JSON files",
    )
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--source-label",
        default="Samsung S23 imsservice",
        help="human-readable firmware/source label stored in the generated database",
    )
    args = parser.parse_args()

    root = build_database(args.source, args.source_label)
    ET.indent(root, space="    ")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(
        args.output,
        encoding="utf-8",
        xml_declaration=True,
        short_empty_elements=True,
    )
    print(
        f"generated {args.output}: "
        f"{root.find('mappings').get('count')} mappings, "
        f"{root.find('profiles').get('count')} profiles"
    )


if __name__ == "__main__":
    main()
