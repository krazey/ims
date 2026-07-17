# Samsung carrier database runtime coverage

The generated database is firmware reference data. PhhIms resolves one voice
profile, applies a manual XML overlay last, and logs the resulting policy once
without subscriber identities or authentication material.

Mapping selection uses PLMN plus the available IMSI subset, GID1, GID2, and
service-provider name qualifiers. GID access is best-effort because the exact
TelephonyManager API differs between Android platform releases.

## Active profile fields

| Samsung field | PhhIms behavior |
| --- | --- |
| `remote_uri_type` | Selects `tel:` or `sip:...;user=phone` call targets. |
| `ipver` | Prefers matching local and P-CSCF address families. If the IMS bearer exposes no matching pair, PhhIms continues with the bearer-provided family and logs the mismatch. |
| `transport` | Honors explicit `tcp` and `udp`. `udp-preferred` remains TCP unless a tested manual overlay selects UDP; it is not equivalent to force-UDP. `tls` is retained and reported, but the MMTEL stack has no TLS control transport. |
| `support_ipsec` | Controls Security-Client, sec-agree headers, Security-Server processing, and transport transform installation. |
| `use_precondition` | Enables IR.92 QoS attributes for cellular outgoing offers. |
| `wifi_precondition_enabled` | Enables IR.92 QoS attributes for IWLAN outgoing offers. |
| `support_roaming` | Disables voice and SMS service tags and operations while roaming when false. |
| `auth_algo`, `enc_algo` | Filters the supported 3GPP IPsec algorithms advertised by PhhIms. |
| `subscribe_for_reg` | Controls reg-event SUBSCRIBE. |
| `enable_gruu` | Controls the REGISTER `gruu` option tag. |
| `reg_retry_*` | Controls REGISTER retry bounds and P-CSCF selection after 403. |
| `reg_expires` | Controls REGISTER and registration Contact expiry. |
| `session_expires`, `min_se` | Controls SIP session timers. |
| call setup timers | Controls INVITE, ringing, and ringback timeouts. |
| keepalive fields | Controls call-signalling keepalive start and interval policy. |
| `mss_size` | Validated and exposed in diagnostics. Java sockets do not provide a portable TCP MSS control, so PhhIms does not pretend that send-buffer size is equivalent. |
| `pcscf_pref` | Validated and reported. PCO/DNS are supported; ISIM, OMADM, and Samsung autoconfiguration sources are unavailable to this userspace stack. |
| `audio_codec` | Restricts AMR-WB advertisement. AMR-NB remains the interoperability fallback. |
| `enable_evs_codec` | Reported. EVS is not advertised because PhhIms has no EVS encoder/decoder. |
| `networks`, `services` | Gate voice and SMS service tags and operations per LTE/IWLAN access. |
| `sos_urn_required`, emergency profile/global fields | Parsed and reported for the existing emergency-CSFB guard. PhhIms does not implement emergency IMS calling. |
| `block_deregi_on_srvcc` | Parsed and reported. PhhIms has no modem SRVCC event API. |
| `last_pani_header` | Parsed and reported. It is not emitted without a trustworthy previous-cell identity. |
| geolocation phase | Parsed and reported. PIDF/geolocation signalling is not implemented. |

## Active switch and global fields

`enableIms`, `enableServiceVolte`, `enableServiceVowifi`, and
`enableServiceSmsip` gate the implemented services. ViLTE, RCS, RCS chat, and
data-channel switches are retained in the effective-policy log but those
services are outside PhhIms's voice/SMS scope.

Voice and general CSFB status rules are active. The default SMS-fallback flag
controls whether SIP failures may fall back to the framework SMS path.
Emergency, supplementary-service, SRVCC, and IWLAN-PANI globals are parsed and
reported; unsupported subsystems are not advertised as implemented.

## Test diagnostics

Carrier logs include:

- selected database mapping, profile, source, and effective policy;
- requested and actual IP family, control transport, IPsec, and service access;
- selected Security-Server ports, SPIs, algorithms, and protected socket ports;
- outgoing INVITE header names and non-sensitive SDP structure;
- `Reason`, `Warning`, `Retry-After`, and related final-response diagnostics;
- UDP response packet source, top-Via routing decision, and final destination.

These diagnostics are intended to distinguish a database mismatch from an IMS
bearer, SIP request-shape, or protected response-path failure.
