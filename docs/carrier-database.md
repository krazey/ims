# Carrier database

PhhIms ships a normalized carrier reference database generated from Samsung's
IMS configuration. It complements, but does not replace, the verified runtime
overrides in `res/xml/sip_carrier_policies.xml`.

The generated database contains:

- every MCC/MNC, IMSI-subset, GID1/GID2 and SPN mapping;
- every non-default IMS, emergency and RCS profile;
- effective network/service support and interoperable SIP settings;
- IMS service switches; and
- carrier-specific CSFB and emergency-domain settings.

Records imported from firmware have `verification="firmware_reference"`.
The voice/SMS runtime adapter covers address family, explicit SIP transport,
IPsec, cellular and IWLAN preconditions, roaming, network/service eligibility,
service switches, called-party URI type, Security-Client algorithms,
reg-event subscription, REGISTER expiry and GRUU, session and call timers,
call-signalling keepalives, codec filtering, registration recovery, SMS
fallback and normal-call CSFB rules. CSFB rules retain Samsung's status
classes and exclusions, such as `5xx` or `^(?!407)4xx`.

Samsung's `udp-preferred` is deliberately distinct from explicit `udp`; the
former does not force every carrier onto UDP. Non-positive timer values remain
stock sentinels and retain the safe PhhIms default. Manual, log-verified policy
overlays are applied after imported firmware data and remain authoritative.

Fields that require unsupported subsystems—EVS, PIDF geolocation, modem SRVCC,
emergency IMS, UT, RCS, ISIM/OMADM P-CSCF discovery and TLS—are parsed and
reported rather than silently discarded or falsely advertised. See
`carrier-database-runtime.md` for the field-by-field runtime matrix.

## Regenerating

Extract these files from `imsservice.apk/res/raw/` into one directory:

```text
mnomap.json
imsprofile.json
imsswitch.json
globalsettings.json
```

Then run:

```bash
python3 tools/import_samsung_ims_database.py \
    /path/to/imsservice/res/raw \
    app/src/main/res/xml/sip_carrier_database.xml \
    --source-label "Samsung S26 <firmware build> imsservice"
```

The source firmware JSON is intentionally not stored in this repository. The
generated file contains only normalized facts used for carrier matching and
future typed policy translation. A deterministic SHA-256 digest of the four
source files is embedded in the generated root element for provenance.

## Matching

PLMNs are represented canonically as three MCC digits plus three MNC digits.
For example, Samsung's `40177` becomes `401077`, while `40107` becomes
`401007`; Tele2 Kazakhstan and Altel Kazakhstan therefore remain distinct.

Qualified matches outrank generic PLMN matches. A qualifier is only eligible
when the corresponding IMSI/GID/SPN value is available. This prevents an MVNO
entry from accidentally replacing its host carrier merely because both share
the same PLMN.
