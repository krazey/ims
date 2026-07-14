# Carrier policy data

PhhIms keeps safe protocol defaults in `SipCarrierPolicy` and loads operator
exceptions from `app/src/main/res/xml/sip_carrier_policies.xml`. Entries can be
selected by normalized MCC/MNC, Android canonical carrier ID, or both. More
specific matches are applied after general ones.

The XML accepts CarrierConfig-style `boolean`, `long`, `string`, and
`string-array` values plus repeatable `register-header` elements. The mapping
to typed policy fields is centralized in `SipCarrierPolicyOverlay`; unknown
keys are ignored so a newer policy file can still be read by an older build.

## Public reference data

Useful upstream inputs are:

- Android's canonical carrier-ID database:
  `platform/packages/providers/TelephonyProvider/assets/latest_carrier_id/carrier_list.textpb`
  ([source](https://android.googlesource.com/platform/packages/providers/TelephonyProvider/+/refs/heads/main/assets/latest_carrier_id/carrier_list.textpb)).
- AOSP CarrierConfig operator assets
  ([source](https://android.googlesource.com/platform/packages/apps/CarrierConfig/+/refs/heads/main/assets/)).
- The Android 17 AOSP ImsStack defaults and implementation, especially
  `java/assets/carrier_config/carrier_config.xml`
  ([source](https://android.googlesource.com/platform/packages/modules/ImsStack/+/refs/heads/android17-release/)).
- Android's carrier-identification documentation
  ([source](https://source.android.com/docs/core/connect/carrierid)).

These sources identify carriers and expose public Android policy. They are not
a complete database of real-network SIP quirks. Every exception added here
should therefore include a trace, device comparison, carrier document, or
issue reference in the commit message.

## Stock implementation comparisons

Samsung's `com.sec.imsservice`, Qualcomm vendor IMS packages, and Pixel vendor
IMS components can be inspected from firmware owned for testing with tools
such as JADX and apktool. Treat them as behavioral references: compare carrier
configuration, generated SIP messages, timers, and state transitions, then
write an independent implementation. Do not copy or redistribute proprietary
source, decompiled code, signing material, or extracted proprietary carrier
databases.

For repeatable comparisons, capture the same scenario on the stock stack and
PhhIms, redact subscriber identifiers and credentials, and record only the
minimal policy delta in the XML.
