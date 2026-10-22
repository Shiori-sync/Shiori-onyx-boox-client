# Fresh Palma 2 Pro enrollment and firmware-update baseline

The owner requested installation and connection on a new, previously unsigned-in
Palma 2 Pro, followed by a user-initiated firmware update to test connection
persistence. **The firmware update has not been started.**

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
`clusterHost.onyxMaterialHostBaseUrl`. PowerSync correctly stopped before native
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

PowerSync's normal Return to previous server flow then restored the exact original
cluster-map hash, selected server, account/library state, and sync flags. The custom
marker was removed. Recovery did not require the server or manual native writes.

## Current checkpoint and next observation

**4.1.x compatibility is confirmed on this tested 4.1.1 build with local
PowerSync 0.3.4/code 15.** This is observed device evidence, not a claim that every
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
- The disposable read-only diagnostic package was removed. PowerSync remains
  connected. No firmware update has been started by the agent.

The device is ready for the owner to initiate the firmware update. PowerSync 0.3.4
and this firmware build are the comparison baseline. Do not upgrade PowerSync at
the same time, since that would confound the connection-persistence experiment.

### First firmware download attempt

The owner attempted the full update. At 00:18 local time the native updater
reported **Download failed**. Logs show `SocketTimeoutException` connecting to
`firmware.boox.com:80`, with repeated 3-second connection timeouts across its
resolved addresses. The firmware/build and boot ID are unchanged: no reboot or
completed installation occurred. PowerSync was not opened during diagnosis.
[Read-only comparison](download-failure.json) confirms account identity, selected
cluster/endpoints, sync flags, marker, All Files Access, and installed APK bytes
are unchanged from the pre-update baseline.

A contemporaneous laptop TCP probe reached ports 80 and 443, but resolved a
different address set from the tablet's failed attempts. This supports a download
connectivity/CDN-path problem; it does not establish a specific DNS, Wi-Fi, or
server root cause. The full-update persistence experiment remains pending.

After the update, first capture firmware, package state, native account/selection,
flags, marker and cluster hashes **before opening PowerSync or attempting repair**.
Then open PowerSync, record its status, and verify actual native sync. This separates
firmware persistence from app-assisted recovery. Leave firmware initiation to the
owner.

Private device snapshots, APKs, diagnostic output, decompiled evidence and UI XML
are in ignored `artifacts/palma-firmware-20261021/`. The dated path is retained from
the initial interrupted attempt. No credentials or raw account/provider rows are
included in this document. Version 0.3.4 is a local build, not a published release.
