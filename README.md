# PhhIms - VoLTE/VoWiFi for LineageOS on Samsung devices

Open-source SIP/IMS stack for LineageOS, based on [phhusson/ims](https://github.com/phhusson/ims) and the Samsung-focused fork history from [amikhasenko/ims](https://github.com/amikhasenko/ims).

This fork is used as a privileged userspace `ImsService` / MmTel provider for Samsung devices where the vendor IMS stack is missing, unusable, or not practical to port to current LineageOS releases.

The current tested focus is Samsung LineageOS 23.x / Android 16 bring-up across both Exynos and Qualcomm Snapdragon devices:

- Samsung Galaxy S9 / S9+ / Note9 / Exynos9810 (`starlte`, `star2lte`, `crownlte`)
- Samsung Galaxy S20 / S20+ / S20 Ultra / Exynos9830 (`x1s`, `y2s`, `z3s` family; current testing mainly `x1s`)
- Samsung Galaxy S23 Ultra / Qualcomm Snapdragon 8 Gen 2 / SM8550 (`dm1q`)
- O2 Germany / Telefónica Germany family as the main known-good carrier test environment

This is not a universal drop-in IMS replacement. It still depends on carrier provisioning, Samsung RIL behavior, correct Android telephony overlays, CarrierConfig, sepolicy, audio HAL behavior, and ROM-side integration.

## What this app does

Android expects a privileged app implementing `android.telephony.ims.ImsService` and registering itself as the MmTel provider. This app provides that service with a pure-userspace SIP/IMS stack.

At a high level it:

- requests and tracks the IMS bearer network
- reads P-CSCF information from `LinkProperties` or falls back to 3GPP DNS discovery
- performs SIP AKA registration with Security-Server / IPsec negotiation
- reports IMS registration state and capabilities back to Android telephony
- handles VoLTE and VoWiFi voice calls with SIP and RTP
- negotiates AMR-NB / AMR-WB audio, RTP telephone-event DTMF, SIP session timers, IMS preconditions, UPDATE, and re-INVITE media changes
- handles call waiting with dialog routing by Call-ID, SIP hold/resume, active/held swaps, and automatic resume after the waiting leg ends
- optionally applies bundled WebRTC AEC3 echo cancellation to the downlink render reference and uplink microphone PCM before AMR encoding
- handles SMS over IMS with carrier-specific fallback behaviour where required
- bridges incoming and outgoing SIP call state into Android `ImsCallSession` callbacks
- avoids tearing down active calls during LTE/IWLAN tech-only handover events

## Current status

Status is based on the current Samsung LineageOS 23.x test branches. Expect
carrier and device differences. This is still a bring-up/testing IMS stack, not
a generic certified carrier IMS implementation.

| Area | Status |
| --- | --- |
| IMS registration | Working in current Exynos9810, Exynos9830, and Snapdragon dm1q tests, including AKA/IPsec registration, P-CSCF fallback, temporary P-CSCF failure recovery, REGISTER retry/reconnect handling, 494 fallback handling, transient SIP reconnect handling, invalid-subscription debounce, and stale IMS bearer recovery. |
| VoLTE outgoing calls | Working in tested configs, including Android call UI progress, SIP setup, PRACK/session-timer handling, RTP, AMR-NB/AMR-WB negotiation, DTMF, BYE handling, carrier-policy-backed retry hooks, short-code handling, emergency-number guardrails, and two-way audio when ROM-side audio fixes are present. |
| VoLTE incoming calls | Working in current tests for accept, local end, remote end, reject, duplicate INVITE, CANCEL, and precondition call paths. Re-test after every dialog/call-state change. |
| Call waiting / hold | Working in current tests for a second incoming INVITE, SIP hold/resume, active/held swap, and automatic resume after the waiting leg ends. Conference merge is not implemented and is rejected cleanly. |
| VoWiFi | Working in current testing on Exynos and Snapdragon targets. IWLAN/LTE route changes still depend heavily on QNS, CarrierConfig, WFC mode, service state, and Samsung RIL behaviour. Cellular/mobile-preferred WFC must still allow calls over IWLAN when the device is currently registered through VoWiFi. |
| Active VoLTE ↔ VoWiFi switching | Current code defers unsafe tech-only IMS reconnects while a call is active/pending or while media threads are still running. This avoids killing RTP and leaving a fake silent call. Phone-info may still show the original registration tech until the call ends. |
| SMS over IMS | Basic send/receive path tested. IMS MESSAGE success now waits for RP-layer result where needed. SMS fallback status codes, fallback cooldown, RP result timeout, and carrier-specific SMSC quirks are policy-driven. |
| Audio | SIP/RTP media is handled in userspace, but working microphone/earpiece routing still depends on ROM-side Samsung audio HAL fixes and mixer routing. Optional bundled WebRTC AEC3 is available for speaker echo cancellation and is disabled by default. AMR-WB runs at 16 kHz; AMR-NB is internally resampled from 8 kHz to 16 kHz and back. |
| USSD/MMI | IMS UT/USSD is not the current goal. Potential USSD/MMI requests are routed over CS when IMS UT/USSD is unavailable. |
| Video calling / RCS / UT | Not a goal for now. Voice and basic SMS are the focus. |

`SipHandler.kt` has been split into smaller topic-specific helpers for
registration, SIP message/header building, SDP generation/parsing,
incoming/outgoing INVITE handling, UPDATE/re-INVITE handling, RTP/audio runtime,
SMS handling, and dialog termination. `SipHandler` still owns mutable call/dialog
state, SIP write execution where required, media thread orchestration, callbacks,
and high-level call-flow decisions.

A common non-IMS fallback mode is: Android has no usable IMS registration when a
call starts, so telephony uses circuit-switched fallback and the modem may drop
to GSM/EDGE during the call. In that case, inspect IMS network acquisition,
P-CSCF discovery, SIP REGISTER, 401 challenge handling, and reconnect retry
behaviour before assuming the SIP call path failed.


## Recent carrier-policy and recovery changes

Carrier-specific SIP behavior is collected in `SipCarrierPolicy` /
`SipCarrierSettings` instead of being scattered across REGISTER, outgoing INVITE,
SMS, and dial-string call paths. Typed defaults remain in code; operator
exceptions are loaded from `res/xml/sip_carrier_policies.xml` using MCC/MNC and
optional Android carrier-ID selectors. See `docs/carrier-policies.md` for the
schema and public reference sources.

The current policy model covers:

- REGISTER extra headers and optional reg-event `SUBSCRIBE` behavior
- carrier service-code CSFB and plain `tel:` short-code exceptions
- local short-code `phone-context` generation
- emergency-like dial-string guardrails for the normal MMTel path
- outgoing PANI policy
- carrier-specific public-number normalization before outgoing TEL URI generation
- SingTel-style compact outgoing INVITE/SMS/security behavior
- P-CSCF registration recovery and transient SIP reconnect handling
- SMS fallback status codes, fallback cooldown, and RP result wait timeout
- outgoing INVITE retry/recovery hooks such as 422 retry, illegal-SDP retry,
  and SIP auth/security reconnect handling

Registration recovery now temporarily skips a failed advertised P-CSCF when
alternate P-CSCF addresses are available, keeps framework IMS registration stable
during short controlled SIP transport reconnects, and debounces transient
`subId=-1` updates during radio/subscription churn before retiring the active
`SipHandler`.

## Recent call-waiting and media changes

Call waiting keeps active, held, and pending dialogs targetable by Call-ID.
Accepting a waiting INVITE first holds the active call with a `sendonly`
re-INVITE. Android can then accept the waiting call, swap active and held calls,
or resume the held call. When the waiting leg ends, the previously held call is
automatically resumed. Conference merge is not implemented and is rejected
without disturbing the existing calls.

The media path avoids blocking RTP receive when decoder input is temporarily
unavailable, primes and re-buffers downlink PCM around startup and underruns,
and reports incoming ringing promptly. These changes reduce startup repeats,
stutter, and long receive stalls without moving media logic back into
`SipHandler`.

Optional WebRTC AEC3 owns one processor per media generation. The exact PCM
written to `AudioTrack`, including concealment and silence, is used as the
far-end render reference. Microphone PCM is accumulated into complete 10 ms
frames and processed before uplink gain and AMR encoding. AMR-NB audio is
resampled from 8 kHz to 16 kHz for AEC3 and back to 8 kHz afterward.


## Important Samsung-specific background

Samsung devices usually do not expose a clean generic AOSP IMS stack. A working ROM needs cooperation between several layers:

1. Android telephony must bind this package as the MmTel IMS provider.
2. Carrier config and framework overlays must expose VoLTE/VoWiFi capability.
3. The RIL must expose a usable IMS APN/network and P-CSCF information, or DNS fallback must work.
4. Samsung audio HAL routing must allow userspace RTP audio instead of forcing modem/baseband call paths.
5. IMS registration must survive network loss, IWLAN/LTE transitions, REGISTER failures, and Android/QNS route changes.
6. Incoming SIP dialog state must be bridged correctly into Android call sessions, otherwise the UI may show an incoming call while the remote side keeps ringing.

The code has been iterated around these problem areas:

- delayed SIP handler startup until a valid service state/RPLMN exists
- correct AKA challenge realm handling for SIP registration
- Security-Server / IPsec parsing and fallback handling for stricter carriers
- reconnect/backoff after IMS bearer loss or failed REGISTER attempts
- avoiding IMS access switches while a call is active, pending, or media is still running
- outgoing provisional response handling for ringback/progress
- outgoing PRACK/session-timer handling and conservative SDP retry paths
- AMR-NB / AMR-WB codec negotiation and RTP media restart after dialog SDP changes
- separate incoming/outgoing call session state
- incoming `INVITE` parsing robustness
- duplicate incoming `INVITE`, late `CANCEL`, PRACK, UPDATE, and final ACK handling
- incoming accept path: build dialog state before notifying Android, then send `200 OK` and handle ACK correctly
- incoming reject path: signal busy/reject to the remote side instead of only closing Android UI state
- call cleanup after BYE/CANCEL/network failure
- SMS-over-IMS plumbing and RP-layer result handling


## Known carrier test/status notes

Carrier status below is based on community and test-device logs for this fork.
It is not a carrier certification list and should not be read as a guarantee
that every SIM/device combination on the same network will be provisioned for
IMS.

### Known-good / actively tested paths

| Carrier / environment | PLMN / numeric | Current notes |
| --- | --- | --- |
| O2 Germany / Telefónica Germany family | `26203` / normalized `262003` | Main known-good environment. Used for Exynos9810, Exynos9830, and dm1q testing across IMS registration, VoLTE, VoWiFi, SMS-over-IMS, WFC/IWLAN transitions, P-CSCF fallback/recovery, and reconnect handling. O2/Fonic-family paths have also been validated on S20/x1s bring-up builds. |
| Congstar / Telekom Germany | Telekom Germany MVNO | S20/x1s tests showed IMS registration, landline/mobile calls, VoWiFi mobile calls, and normal day-to-day behavior working smoothly. Congstar-alone testing was marked good. |
| Fonic / O2 Germany MVNO | O2 Germany MVNO | O2/Fonic-family tests are treated as working. Earlier SMS/debug logs were useful for dual-SIM/subscription and RP-layer fallback handling. |
| Swiss / border-carrier test set | Swiss and German networks | CH/DE border-location tests included Congstar, fraenk, O2, Migros, Salt, and related Swiss-side network behavior. Treat this set as working from the tester perspective, while still rechecking roaming/WFC/border-cell behavior when touching subscription, QNS, or WFC policy. |
| Talkmobile / Vodafone UK | Vodafone UK MVNO | Tested through VoLTE/VoWiFi call logs. Required media renegotiation fixes for early `183 Session Progress` AMR-NB followed by final `200 OK` AMR-WB SDP. |
| A1 Croatia | `21910` / normalized `219010` | Tested carrier-policy exception. Needs stock-like REGISTER access-network headers and does not require the optional reg-event `SUBSCRIBE` before IMS voice can be considered ready. |
| Vodafone Turkey | `28602` / normalized `286002` | Tested carrier-policy exception. Short service code `542` is kept as plain `tel:542`, and outgoing INVITE needs access-tech PANI. |
| SingTel Singapore | `52501` / normalized `525001` | Carrier-policy exception for stock-like compact outgoing INVITE/SMS/security behavior, including SingTel SMSC handling. Some test SIMs may be data-only, so do not treat every 52501 SIM as a full voice IMS validation. |

### Carrier-specific policy exceptions currently modeled

| PLMN / numeric | Carrier / use | Policy behavior |
| --- | --- | --- |
| `219010` | A1 Croatia | REGISTER extra access-network headers; skip reg-event `SUBSCRIBE` requirement. |
| `232005` | 3 Austria | Service code `333` is forced to CSFB instead of building a normal IMS INVITE. |
| `286002` | Vodafone Turkey | Plain `tel:` short-code handling for configured local service codes; outgoing access-tech PANI. |
| `401077` | Tele2 Kazakhstan | Normalize Kazakhstan mobile/public number forms to `+7` E.164 before outgoing TEL URI generation. |
| `450006` | LG U+ Korea | UDP control socket and non-session AKA policy. |
| `525001` | SingTel Singapore | Stock-like compact outgoing INVITE/SMS/security shape and SingTel SMSC handling. |
| `208010` | Test carrier profile | UDP control socket test profile. |

### Partial / reference carrier logs

These carriers have useful logs or stock-reference behavior in the development
history, but should not be listed as fully known-good unless current PhhIms logs
show registration, outgoing call, incoming call, SMS, and reconnect behavior on
the target device:

| Carrier / environment | PLMN / numeric | Current notes |
| --- | --- | --- |
| Telekom / T-Mobile Poland | `26002` | Stock Samsung profile shows IMS registered with `mmtel` and `smsip`; PhhIms logs were used for outgoing INVITE-shape debugging. |
| Tele2 Kazakhstan | `40107` / normalized `401077` | Active carrier-policy/interoperability target. Public-number normalization is modeled, but outgoing HD calls and SMS should remain bring-up status until the full path is revalidated. |
| Jio India | carrier-specific PLMN varies | Active interoperability target, especially incoming VoLTE behavior. Keep this as test/bring-up unless current logs prove the full path. |
| Airtel India | carrier-specific PLMN varies | Test/bring-up target; keep carrier behavior policy-driven. |
| Jazz Pakistan | carrier-specific PLMN varies | Test/bring-up target; keep carrier behavior policy-driven. |

When adding a new carrier entry, prefer adding policy data to `SipCarrierPolicy`
instead of adding new MCC/MNC checks in `SipHandler`, `SipSmsHandler`, or
outgoing INVITE builders.


## Source layout notes

The SIP stack is intentionally split into topic-specific helper files. Small
helpers are acceptable when they keep SIP registration, dialog state, RTP/audio,
SMS, and Android callback behaviour easier to review separately.

Important groups:

- `SipRegister*`, `SipChallenge`, `SipSecurity*`, and `SipIpsec*` handle REGISTER, AKA, Security-Server, and IPsec setup.
- `SipOutgoingInvite*`, `SipIncomingInvite*`, `SipInDialogInvite`, `SipUpdate*`, and `SipRemoteDialogTermination` handle call signalling.
- `SipAudio*`, `SipUplink*`, `SipDownlink*`, `SipEcho*`, `SipAmrRtpPayload`, and `SipRtp*` handle userspace media, optional AEC3, and RTP helpers.
- `SipCallWaiting*` helpers keep pending/active/held dialogs separated and build hold/resume SDP for call waiting.
- `app/jni/webrtc_aec_jni.cpp` wraps the pinned `webrtc-aec3` source tree for mono PCM16 render and capture processing.
- `ImsNetwork*`, `ImsReconnectController`, `ImsTransportGuard`, and `WfcSubscriptionSettingMonitor` handle IMS bearer and VoWiFi/VoLTE access changes.
- `SipCarrierSettings`, `SipSmsFallbackPolicy`, and `SipOutgoingInviteRetryPolicy` hold carrier-policy-backed behavior for carrier exceptions, SMS fallback, and outgoing call retry/recovery decisions.
- `SipSmsHandler`, `Sms`, and `SmscAddress` handle SMS-over-IMS.

Keep behavioural fixes, carrier policy changes, audio/media changes, and pure
refactors in separate commits when possible. That makes regressions much easier
to bisect.

## Repository integration

### Local manifest

```xml
<project path="packages/apps/PhhIms" remote="github" name="krazey/ims" revision="main" />
```

### `lineage.dependencies`

```json
{
  "repository": "krazey/ims",
  "target_path": "packages/apps/PhhIms",
  "branch": "main"
}
```

After syncing, initialize all native submodules recursively. `repo sync` does
not do this automatically. This pulls `rnnoise`, the pinned `webrtc-aec3`
source tree, and AEC3's nested Abseil dependency:

```sh
cd packages/apps/PhhIms
git submodule update --init --recursive
```

For a standalone checkout, clone the repository with its submodules:

```sh
git clone --recurse-submodules https://github.com/krazey/ims.git
```

## Building in-tree

Use the Soong/LineageOS build. This is the intended build path.

`Android.bp` builds `PhhIms` as a privileged platform-signed app using `platform_apis: true`, so it can access the internal telephony/IMS APIs required by `MmTelFeature`, `ImsConfigImplBase`, `Rlog`, and friends.

The JVM regression tests also require a full private-platform API stub matching
the target build. Point Gradle at the generated framework stub/classes JAR from
that AOSP or LineageOS build; a public or `current/system` SDK JAR is not enough:

```sh
./gradlew -PplatformApiJar=/path/to/platform-apis.jar testDebugUnitTest
```

No Gradle build or public SDK modification is needed for production ROM builds.

For a standalone Gradle build, `build.sh` no longer uses bundled signing
material. Set `PHHIMS_SIGNING_CERT` and `PHHIMS_SIGNING_KEY` to explicit
SignApk-compatible files if a platform-signed output is required. Without both
variables, the built APK is copied to `app.apk` without platform signing. Never
commit ROM platform private keys to this repository.

The AEC wrapper is self-contained and does not depend on Android's
`external/webrtc`. The Soong path compiles the pinned AEC3 sources and links the
platform `cpufeatures` static library. The Gradle/CMake path compiles the Android
NDK `cpufeatures` source directly. Both paths require the recursive submodule
checkout described above.

Add the package from your device or common tree:

```makefile
PRODUCT_PACKAGES += \
    PhhIms
```

`PhhImsOverlay` is pulled in by the app module's `required` entry.

## Device tree integration

The exact paths differ per tree, but current Samsung Exynos and Qualcomm Snapdragon integration usually needs the following pieces.

### Packages

```makefile
# IMS over Wi-Fi data service and network qualification service.
# These are also useful for VoLTE-only bring-up because the telephony
# framework still expects the WLAN data/network service hooks to exist.
PRODUCT_PACKAGES += \
    Iwlan \
    QualifiedNetworksService \
    PhhIms
```

### Debug availability overrides

For bring-up, these properties are useful when carrier config defaults would otherwise hide IMS capability:

```makefile
PRODUCT_PROPERTY_OVERRIDES += \
    persist.dbg.volte_avail_ovr=1 \
    persist.dbg.wfc_avail_ovr=1 \
    persist.dbg.allow_ims_off=1
```

Do not treat these as a replacement for correct carrier config. They are bring-up helpers.

### Framework overlay

Example: `overlay/frameworks/base/core/res/res/values/config.xml`

```xml
<resources>
    <bool name="config_carrier_volte_available">true</bool>
    <bool name="config_device_volte_available">true</bool>
    <bool name="config_device_vt_available">true</bool>
    <string name="config_wlan_data_service_package">com.google.android.iwlan</string>
    <string name="config_wlan_network_service_package">com.google.android.iwlan</string>
    <string name="config_qualified_networks_service_package">com.android.telephony.qns</string>
</resources>
```

### Telephony overlay

Example: `overlay/packages/services/Telephony/res/values/config.xml`

```xml
<resources>
    <string name="config_ims_mmtel_package" translatable="false">me.phh.ims</string>
</resources>
```

### Privapp permissions

Example target path:

```makefile
PRODUCT_COPY_FILES += \
    $(COMMON_PATH)/privapp-permissions-me.phh.ims.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-me.phh.ims.xml
```

Example file:

```xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="me.phh.ims">
        <permission name="android.permission.READ_PRIVILEGED_PHONE_STATE"/>
        <permission name="android.permission.MODIFY_PHONE_STATE"/>
    </privapp-permissions>
</permissions>
```

### CarrierConfig overlay

A real device tree should carry a CarrierConfig overlay for the tested carrier/device combination. The debug properties above may make the UI expose toggles, but stable behavior should come from proper CarrierConfig values.

Useful areas to check:

- `carrier_volte_available_bool`
- `editable_enhanced_4g_lte_bool`
- `carrier_wfc_ims_available_bool`
- `carrier_wfc_supports_wifi_only_bool`
- `carrier_default_wfc_ims_enabled_bool`
- `carrier_default_wfc_ims_mode_int`
- `editable_wfc_mode_bool`
- IMS SMS availability
- default WFC mode and roaming behavior
- IWLAN handover policy / integrity algorithm quirks where required by the carrier/device

### Vendor IMS properties / sepolicy

Some Samsung RIL components set or read `vendor.ril.ims.*` properties. If your device tree needs this, add a vendor property type and allow the Samsung radio service to set it.

Example:

```te
# sepolicy/vendor/property.te
vendor_internal_prop(vendor_ims_prop)
```

```text
# sepolicy/vendor/property_contexts
vendor.ril.ims.                u:object_r:vendor_ims_prop:s0
```

```te
# sepolicy/vendor/sehradiomanager.te
allow sehradiomanager vendor_ims_prop:property_service set;
```

## Exynos9810 integration notes

Current Exynos9810 testing covers the Galaxy S9 / S9+ / Note9 family (`starlte`, `star2lte`, `crownlte`) on LineageOS 23.x.

Known ROM-side pieces used in current testing:

- Add `PhhIms`, `Iwlan`, and `QualifiedNetworksService` to the device/common product packages.
- Bind `me.phh.ims` as the MmTel provider through the Telephony overlay.
- Expose VoLTE/VoWiFi capability through framework overlays and carrier config.
- Carry a CarrierConfig overlay for the tested carrier. For Telefónica Germany family testing this includes deterministic WFC availability and IWLAN behavior.
- Use ROM-side audio HAL and mixer fixes where needed. The old `EXYNOS9810_CALLVOL_FIX` Soong flag is deprecated and is no longer used by current bring-up trees.
- Keep Samsung RIL / IMS property sepolicy in sync if your tree exposes `vendor.ril.ims.*` properties.

Validation checklist used for Exynos9810:

- IMS registers on LTE.
- Outgoing VoLTE call connects, has ringback/progress, two-way audio, DTMF, and clean BYE handling.
- Incoming VoLTE accept, local end, remote end, reject, duplicate INVITE, and late CANCEL paths work.
- AMR-NB fallback and AMR-WB negotiation are checked where the carrier/device exposes HD voice.
- SMS send and receive work after IMS reconnects.
- USSD/MMI routes over CS when IMS UT/USSD is unavailable.
- VoWiFi registers and calls work when WFC is enabled.
- VoLTE ↔ VoWiFi transitions do not leave stale/frozen IMS registration.
- Active-call LTE/IWLAN tech-only switches do not kill RTP/media.

## Exynos9830 integration notes

Current Exynos9830 testing covers the Galaxy S20 5G / Exynos9830 family, mainly `x1s`, on LineageOS 23.x.

Known ROM-side pieces used in current testing:

- Add `PhhIms`, `Iwlan`, and `QualifiedNetworksService` to the product packages.
- Bind `me.phh.ims` as the MmTel provider.
- Expose VoLTE/VoWiFi capability through framework overlays and carrier config.
- Include the WLAN data service, WLAN network service, and QNS framework overlay strings.
- Use carrier config for WFC availability/defaults and IWLAN handover behavior.
- Be aware that QNS may select IWLAN while idle when LTE quality is weak even when the user-facing WFC mode looks like “mobile preferred”. This is expected Android/QNS policy behavior, not a SipHandler-triggered switch.

Validation checklist used for Exynos9830:

- IMS registers on LTE.
- IMS can register on IWLAN / VoWiFi when WFC is enabled.
- Outgoing and incoming calls work on VoLTE and VoWiFi.
- AMR-NB fallback, AMR-WB negotiation, DTMF, and dialog SDP media changes are checked where possible.
- LTE ↔ IWLAN route changes during active calls keep audio alive.
- Phone-info may keep showing the original IMS registration tech during a deferred active-call switch; the important runtime check is that RTP and DTMF continue.
- If IMS is unavailable when a call starts, Android may use CS fallback and show GSM/GPRS/2G radio tech for that call.

## Qualcomm Snapdragon / dm1q integration notes

Current Snapdragon testing covers the Galaxy S23 Ultra (`dm1q`, SM8550 / Qualcomm Snapdragon 8 Gen 2 class) on LineageOS 23.x.

Known ROM-side pieces used in current testing:

- Add `PhhIms`, `Iwlan`, and `QualifiedNetworksService` to the product packages.
- Bind `me.phh.ims` as the MmTel provider.
- Expose VoLTE/VoWiFi capability through framework overlays and carrier config.
- Keep Qualcomm RIL, QNS, IWLAN, and CarrierConfig behavior aligned with the device tree's IMS data-service setup.
- Use carrier config for WFC availability/defaults and IWLAN handover behavior.
- Be aware that mobile/cellular-preferred WFC is not cellular-only. If QNS/the modem currently has a valid IWLAN IMS registration, outgoing calls should be allowed over VoWiFi instead of being blocked while waiting for a cellular IMS path.
- Snapdragon audio routing is still ROM/HAL dependent. SIP/RTP may be correct even when microphone gain, speaker/earpiece routing, or call-volume behavior needs device-side audio fixes.

Validation checklist used for dm1q:

- IMS registers on LTE and IWLAN.
- Outgoing and incoming calls work on VoLTE and VoWiFi.
- Mobile-preferred WFC allows calls when the active IMS registration is currently IWLAN.
- Airplane-mode and radio-service churn do not permanently lose IMS registration.
- Temporary invalid subscription updates do not immediately retire the active SIP handler.
- P-CSCF recovery does not regress normal registration and can select an alternate advertised P-CSCF after a failed registration/connect attempt.
- AMR-NB fallback, AMR-WB negotiation, DTMF, and dialog SDP media changes are checked where possible.
- Audio routing, microphone gain, and speaker/earpiece behavior are validated separately from SIP/RTP success.


## Samsung audio notes

Audio is not solved only inside this app. Samsung HALs often special-case cellular calls and may route capture/playback through modem/baseband paths instead of normal userspace audio paths.

### Device-side audio integration

Current bring-up trees handle call-volume and mixer routing in the ROM/audio HAL integration directly. The old `EXYNOS9810_CALLVOL_FIX` Soong flag is deprecated and is no longer used.

This is not part of this app directly, but without matching ROM-side audio fixes, the SIP/IMS stack may register and place calls while audio behavior still looks broken. Validate microphone gain, speaker/earpiece routing, and call-volume behavior per device family.

### Telecom audio mode

Some Samsung HALs treat `MODE_IN_CALL` as a modem-call path. For a userspace IMS stack, `MODE_IN_COMMUNICATION` may be required so `AudioRecord` stays on the real microphone ADC path instead of a baseband uplink PCM.

If calls connect but the microphone is silent, check the HAL routing first before assuming SIP/RTP is broken.

### Optional WebRTC AEC3

AEC3 is intended to remove loudspeaker/earpiece playback leaking back into the
microphone. It does not replace correct Samsung audio routing, mixer setup, or
microphone gain. The feature is disabled while the configured delay is zero,
so device families can be validated independently before enabling it.

The native wrapper consumes mono PCM16 in 10 ms frames. AMR-WB uses its native
16 kHz PCM rate. AMR-NB is resampled to 16 kHz before AEC3 processing and back
to 8 kHz before AMR-NB encoding.

## Runtime media and recovery properties

| Property | Default | Purpose |
| --- | --- | --- |
| `persist.phh.ims.webrtc_aec_delay_ms` | `0` | Enable AEC3 with this fixed render-to-capture delay in milliseconds. Positive values are clamped to `1..500` and read when microphone capture starts; zero disables AEC. |
| `persist.phh.ims.webrtc_aec_strength_pct` | `175` | Echo suppression strength relative to the bundled AEC3 default. Values are clamped to `100..300` and read when microphone capture starts. Values above `100` also keep residual suppression active when the adaptive filter has not converged. |
| `persist.sys.phhims.uplink_gain_q8` | unset / `0` | Runtime uplink gain override applied after AEC. Values are clamped to `128..768`; `256` is unity gain. |
| `ro.phhims.uplink_gain_q8` | `256` | Read-only build default used when the persistent uplink gain is unset. |
| `persist.ims.pcscf_fallback` | unset | Last-resort P-CSCF host or address after RIL-provided addresses and 3GPP DNS discovery fail. |

Example AEC test setup:

```sh
adb shell setprop persist.phh.ims.webrtc_aec_delay_ms 100
adb shell setprop persist.phh.ims.webrtc_aec_strength_pct 175
```

Start a new call after changing these properties. To return to the default
media path:

```sh
adb shell setprop persist.phh.ims.webrtc_aec_delay_ms 0
```

## Useful debug commands

```sh
adb shell dumpsys ims
adb shell dumpsys telephony.registry
adb shell dumpsys carrier_config
adb shell dumpsys connectivity
adb shell dumpsys package me.phh.ims
```

For focused IMS logs:

```sh
adb logcat -c
adb logcat -v threadtime \
  'PHH SipHandler:D' 'PHH SipConnection:D' 'PHH MmTelFeature:D' \
  'ImsPhone:D' 'ImsPhoneCallTracker:D' 'DNC-0:D' 'TNP:D' \
  'Qns:*' 'QualifiedNetworksService:*' 'Iwlan:*' \
  '*:S'
```

For broad logs:

```sh
adb logcat -b all -v threadtime | grep -iE \
  'PHH|PhhIms|SipHandler|MmTel|Ims|Iwlan|Qns|P-CSCF|REGISTER|401|INVITE|PRACK|ACK|BYE|CANCEL|RTP|AEC|echo|SMS'
```

Useful UI check:

```text
*#*#4636#*#*
```

Check whether Android says IMS is registered and whether voice/SMS over IMS are available.

## Debugging common failure modes

### LTE call drops to 2G / EDGE

Usually means Android did not have an active IMS registration when the call started, so it used circuit-switched fallback.

Check:

- did `getVolteNetwork()` receive a valid IMS network?
- did `LinkProperties` contain P-CSCF addresses?
- did DNS fallback produce a P-CSCF?
- did the initial SIP REGISTER receive the expected `401 Unauthorized` challenge?
- did the second REGISTER use the correct realm from `WWW-Authenticate`?
- did reconnect retry trigger after failures?

### IMS registration freezes after VoWiFi/VoLTE switching

Usually means the app or framework still believes an old IMS access/network is valid.


### IMS deregisters during brief `subId=-1` / PLMN `000000` churn

During airplane-mode, radio, or SIM-service churn, telephony can briefly expose
an invalid subscription even though the SIM and modem recover shortly after. The
app should debounce this before retiring the active SIP handler.

Useful logs:

```text
delaying SipHandler retirement for transient invalid subscription
subscription recovered during invalid-subId grace
subscription removed oldSubId=... after invalid-subId grace
```

Immediate SIP handler retirement is still expected for real feature removal or a
real subscription removal that does not recover during the grace window.

Check:

- IWLAN/LTE registration tech reported to `ImsRegistrationImplBase`
- whether a call is active or pending while access changes
- stale `NetworkCallback` state
- reconnect/re-request of the IMS bearer after access becomes unsuitable
- whether the switch was only a tech-only LTE/IWLAN change with unchanged network/local address/P-CSCF


### Mobile-preferred WFC rejects calls while registered on IWLAN

Mobile/cellular-preferred WFC is not cellular-only. If Android/QNS currently
selected IWLAN and the app is registered over VoWiFi, outgoing calls should be
allowed over that IWLAN registration. Do not reject the call just because the
user-facing WFC mode says mobile preferred.

Check logs for:

```text
Rejecting outgoing call while waiting for required IMS access
preferred=cellular tech=IWLAN
```

That indicates the access-convergence guard is too strict for mobile-preferred
WFC and should allow IWLAN fallback when the current IMS registration is valid.

### Active call goes silent after LTE ↔ IWLAN switch

Check for a tech-only IMS access switch during the call:

```text
networkChanged=false
localChanged=false
pcscfChanged=false
techChanged=true
```

Current code should log and defer that reconnect while the call is active, pending, or media threads are still running:

```text
Deferring tech-only IMS reconnect while SIP call is active or pending
```

If media dies immediately after the switch, check for accidental cleanup from:

```text
Stopping call runtime state: IMS reconnect
Encode thread exiting: callStopped=true
Decode thread cleanup complete: callStopped=true
```

### Phone-info shows stale VoLTE/VoWiFi during active-call switching

This can be expected with the current deferral behavior. The app intentionally avoids re-registering mid-call for tech-only LTE/IWLAN changes, because reconnecting can kill RTP/media and leave a fake silent call. The important checks are whether RTP continues, DTMF works, and the call ends cleanly.

### Incoming call UI appears, but remote keeps ringing

Usually means Android was notified before SIP dialog state was fully usable, or accept handling did not send/complete the expected SIP response path.

Check:

- `INVITE` parsing
- `Call-ID`, tags, CSeq, Contact, Record-Route/Route handling
- whether `currentCall` exists before `notifyIncomingCall()`
- whether accept sends `200 OK`
- whether ACK is received and matched
- PRACK/100rel handling; do not wait for a PRACK that was never negotiated

### Reject/decline does not reach the caller

Check that reject sends a SIP reject response such as busy/reject while the call is still an incoming dialog. Closing only the Android call session is not enough; the remote network must receive a SIP response.

### Caller shown as unknown in call history

The incoming call profile must carry usable caller identity extras from the SIP `From`/P-Asserted-Identity information:

- `ImsCallProfile.EXTRA_OI`
- `ImsCallProfile.EXTRA_CNA`
- `ImsCallProfile.EXTRA_DISPLAY_TEXT`
- presentation flags such as `EXTRA_OIR` / `EXTRA_CNAP`

### Outgoing call has no ringback/progress

Check provisional SIP responses:

- `180 Ringing`
- `183 Session Progress`

Android should be notified with call-session progress before final answer, otherwise the remote side may ring while the local UI/audio state feels wrong.

### Audio is one-way or silent

Separate SIP success from audio routing.

If SIP says the call is established but audio is broken, check:

- Android audio mode used by Telecom
- Samsung HAL route/mixer state
- receiver/earpiece gain mixer controls
- RTP socket lifecycle
- AMR/AMR-WB codec negotiation
- whether cleanup from a previous call left stale media threads or sockets
- whether optional AEC3 is enabled and the session started for the current media generation
- whether disabling AEC3 changes the symptom, which separates echo processing from HAL routing

## P-CSCF fallback

If the RIL does not report P-CSCF addresses via `LinkProperties`, the app attempts standard 3GPP DNS discovery:

If multiple P-CSCF addresses are advertised, registration recovery can
temporarily skip a P-CSCF that failed connect/register and try another advertised
address before falling back to a full IMS network retry. This is intentionally
short-lived and only active when alternates are available.


```text
ims.mnc<MNC>.mcc<MCC>.3gppnetwork.org
```

A last-resort manual override is available:

```sh
adb shell setprop persist.ims.pcscf_fallback <ip>
```

## Enabling VoLTE toggle state

On some builds it helps to force the enhanced 4G setting once:

```sh
adb shell settings put global enhanced_4g_mode_enabled 1
```

On fresh installs this usually defaults to enabled when overlays/carrier config expose VoLTE correctly.

## Building with Gradle

The public SDK stubs do not expose all internal IMS APIs used here. For development-only Gradle builds, you need a full `android.jar` from an AOSP/LineageOS build in `app/libs/android.jar`, and you may need to remove duplicate public IMS stubs from the platform SDK jar.

For ROM integration, use the in-tree Soong build instead.

## Notes

- The app has no launcher icon and does not appear in the app drawer.
- The app must be privileged and platform-signed.
- Carrier provisioning still matters. A carrier that does not provision IMS for the SIM/device combination may never register.
- Keep registration, VoLTE, VoWiFi, SMS, and audio changes in separate commits while rebasing; it makes regressions much easier to isolate.
- For Samsung bring-up, always test: registration, outgoing call, incoming accept, incoming local end, incoming remote end, incoming reject, call waiting accept/hold/swap/resume, SMS send/receive, DTMF, VoWiFi-only, VoLTE-only, VoLTE→VoWiFi, VoWiFi→VoLTE, active-call route switching, and reconnect after IMS bearer loss.
- When AEC3 is enabled, test both AMR-WB and AMR-NB calls on earpiece and speakerphone, including double-talk, and verify that disabling AEC3 restores the unchanged baseline path.

## License

GPL-2.0, following the upstream project.
