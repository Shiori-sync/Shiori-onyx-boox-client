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

Fresh enrollment is pending: SSH to the management host became unreachable when a
new one-use code was needed. The owner was asked for a code or restored management
access. The app remains at Connect to Alexandria with its original configuration
restored. **Do not treat this as a connected pre-update baseline yet.**

Before the owner starts the firmware update, record a successful active connection,
matching account/configuration, both sync flags, marker and endpoint hashes,
PowerSync version/signature/permission/package state, and actual native receipt of
existing synthetic library records. Counts are observation checkpoints, not proof
of complete resource synchronization.

After the update, first capture firmware, package state, native account/selection,
flags, marker and cluster hashes **before opening PowerSync or attempting repair**.
Then open PowerSync, record its status, and verify actual native sync. This separates
firmware persistence from app-assisted recovery. Leave firmware initiation to the
owner.

Private device snapshots, APKs, diagnostic output, decompiled evidence and UI XML
are in ignored `artifacts/palma-firmware-20261021/`. The dated path is retained from
the initial interrupted attempt. No credentials or raw account/provider rows are
included in this document. Version 0.3.4 is a local build, not a published release.
