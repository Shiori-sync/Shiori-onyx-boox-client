# Palma firmware update and China-market Tab 8 validation

The owner requested installation and connection on a new, previously unsigned-in
Palma 2 Pro, followed by a user-initiated firmware update to test connection
persistence. **The full update from 4.1.1 to 4.2 completed and native sync survived
without re-enrollment or connection repair.** The original baseline and first
failed download are retained below. The China-market Android 11 Tab 8 also joined
successfully; see [Tab 8 results](tab8.md).

## Initial state

The device reports model `Palma2_Pro_C`, Android 15/API 35, firmware
`2025-12-13_09-57_4.1.1-rel_1213_9755ef7ea`, incremental 77, security patch
2025-10-01, ksync 32044 and Notes 44417. Its build fingerprint still contains an
Android 13 identifier; the release/API properties above are recorded separately.
Native account, notebook, and Reader catalog counts were all zero; both sync
flags were false. The initial library was empty, not an existing stale-device join.

The published 0.3.3/code 14 APK matched its release SHA-256 and original signing
certificate. Installation succeeded. The package was observed disabled after
installation and was explicitly enabled; the cause of that state is unproven.
Two ordinary-product-UID native read/service checks passed.

## Compatibility failure and correction

Enrollment redeemed successfully, but the native cluster refresh dropped
`clusterHost.onyxMaterialHostBaseUrl`. Shiori correctly stopped before native
sign-in or enabling sync. The saved baseline remained intact, with no account,
notebooks, or Reader rows created. A read-only diagnostic compared the full saved
and refreshed custom entry: that field was the only difference.

The pulled native ksync APK's decompiled `ClusterHost` has eight endpoint
properties and no material/template-host property. Both stock cluster definitions
likewise contain the eight older properties. This is a native schema difference,
not an endpoint redirection.

Local **0.3.4/code 15** adds a narrowly scoped compatibility rule:

- Permit omission of the material-host field only when every saved pre-transition
  cluster proves the older eight-field schema. Unknown/incomplete baselines and
  mixed/new schemas still require the field.
- Reject changed present material hosts, missing/changed account and sync hosts,
  extra host fields, and any other full-entry differences during restoration.
- Preserve the full desired endpoint in the marker for firmware that supports it.
- Avoid repeatedly offering an endpoint update that this firmware cannot retain.

The new APK uses the original signing certificate and was installed in place,
without uninstalling the app or replacing its saved state. **58 unit tests** passed;
release lint has zero errors and the same 12 warnings. Signed release/test assembly
passed. [Build identity](local-build.json). The read-only diagnostic now reports
configuration difference paths without exposing their values.

Shiori's normal Return to previous server flow then restored the exact original
cluster-map hash, selected server, account/library state, and sync flags. The custom
marker was removed. Recovery did not require the server or manual native writes.

## Pre-update checkpoint

**4.1.x compatibility is confirmed on this tested 4.1.1 build with local
Shiori 0.3.4/code 15.** This is observed device evidence, not a claim that every
4.1.x firmware/model combination has been tested or a firmware allowlist.

After management connectivity returned, fresh enrollment and native sign-in
succeeded. The native sign-in callback did not complete before the timeout, even
though the account was already signed in. The existing **Resume connection**
flow completed without another enrollment or sign-in. This continuation step is
part of the result, not an uninterrupted first-time-connect pass.

The final [pre-update checkpoint](pre-update.json) confirms:

- Active phase, matching native account identity and installed configuration,
  All Files Access granted, and both notebook/reading sync flags enabled.
- Installed APK bytes match the original-certificate-signed local 0.3.4 build.
- One authorized server enrollment for this fresh device and the native marker
  present; selected endpoints and account/marker hashes saved privately.
- The stock firmware-update host remains `https://en-data.boox.com`.
- After the native Library's **Sync Cloud Books** action, 138 Reader catalog rows
  arrived, including both existing synthetic folder-check books and their two
  native shelf memberships. The owner also observed sync arriving.
- The notebook catalog was still empty at the captured checkpoint; neither full
  notebook synchronization nor complete source-file downloads are claimed.
- The disposable read-only diagnostic package was removed. Shiori remains
  connected. No firmware update has been started by the agent.

Shiori 0.3.4 and this firmware build were held fixed for the firmware experiment.

### First firmware download attempt

The owner attempted the full update. At 00:18 local time the native updater
reported **Download failed**. Logs show `SocketTimeoutException` connecting to
`firmware.boox.com:80`, with repeated 3-second connection timeouts across its
resolved addresses. The firmware/build and boot ID are unchanged: no reboot or
completed installation occurred. Shiori was not opened during diagnosis.
[Read-only comparison](download-failure.json) confirms account identity, selected
cluster/endpoints, sync flags, marker, All Files Access, and installed APK bytes
are unchanged from the pre-update baseline.

A contemporaneous laptop TCP probe reached ports 80 and 443, but resolved a
different address set from the tablet's failed attempts. This supports a download
connectivity/CDN-path problem; it does not establish a specific DNS, Wi-Fi, or
server root cause. This attempt did not exercise firmware persistence.

## Successful full update

The owner's retry installed `2026-08-10_11-54_4.2-rel_0810_74616a570`, incremental
230, Android 15/API 35, security patch 2026-04-01. A changed boot ID confirms a
reboot. Native ksync is now 32173, Notes 45960 and Reader 38999.
[Post-update comparison](post-update.json) was captured **before opening Shiori
or repairing anything**:

- Same account identity, selected Alexandria cluster, both enabled sync flags,
  native marker, All Files Access, and byte-identical Shiori 0.3.4 APK.
- Native sync was already operating: 138 Reader rows and 1,125 note/folder rows;
  the bounded native log window reported successful transfer tasks. These are
  observations, not proof of complete resource downloads or distinct task counts.
- The sole selected-entry difference is the newer firmware adding
  `clusterHost.onyxMaterialHostBaseUrl`, matching the saved enrollment value.
- Shiori itself was disabled (`enabled=3`, previously `1`). The owner identified
  BOOX's Freeze policy. Native sync continued while the companion was disabled.
  The app was enabled separately; later Settings inspection showed **Freeze new
  apps off**, **Auto Freeze: Do not freeze**, and Shiori unfrozen. No global
  Freeze setting was changed by the agent.

Opening 0.3.4 exposed a status defect: it compared the older saved eight-field
entry literally with the upgraded nine-field entry, reporting attention despite
the intact connection. Local **0.3.5/code 16** accepts this single addition only
when the pre-transition schema proves the old omission and the new value exactly
matches the enrolled template host. All other configuration comparisons remain
strict. Status, restoration guards and endpoint-maintenance guards use the same
comparison; it does not rewrite native state or the saved baseline.

After the experiment was captured, a certificate-matched in-place 0.3.5 installation
reported active phase and matching account/configuration. Cluster/system hashes,
selected endpoints, account, flags, marker and permission stayed unchanged across
that app update. No re-enrollment, Resume, or endpoint repair was needed on Palma.
The restore and maintenance guards were unit-tested; live restore was deliberately
not exercised on the working connection.

The combined Android 11 and firmware-status changes pass **65 unit tests**, lint
with **0 errors / 12 existing warnings**, and signed release/test assembly.
Both devices have local 0.3.5; the published release remains 0.3.3.

Private device snapshots, APKs, diagnostic output, decompiled evidence and UI XML
are in ignored `artifacts/palma-firmware-20261021/`. The dated path is retained from
the initial interrupted attempt. No credentials or raw account/provider rows are
included in this document. Versions 0.3.4 and 0.3.5 are local builds, not published releases.
