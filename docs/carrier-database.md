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
Supported fields are activated as the base PhhIms policy: called-party URI
type, SIP control transport, Security-Client algorithms, reg-event
subscription, REGISTER GRUU support, session timers, call-signaling
keepalives, registration recovery, call setup timers and normal-call CSFB
response rules. CSFB rules retain Samsung's status classes and exclusions,
such as `5xx` or `^(?!407)4xx`. SIP 380/382 Alternative-Service responses
also preserve emergency registration actions and `urn:service:sos.*`
routing. Log-verified PhhIms behavior stays in the policy overlay and has
higher precedence.

Samsung-only media, UT and RCS settings remain available as reference data
until PhhIms has an equivalent typed control point. Importing a field never
adds reflection or carrier-named executable branches.

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
