# China-market BOOX Tab 8 — Android 11

The owner requested the same PowerSync/Alexandria connection on a China-market
Tab 8. Observed firmware is `2026-06-03_18-47_4.2-rel_0603_095d17272b`, from
`ro.vendor.build.onyxid`; `ro.build.display.id` instead reports the generic
`D60_SMT_V02_2022_0309`. Android 11/API 30, ksync 32125, Notes 45446.
[Aggregate evidence](tab8.json).

Initially the selected native server was `zh`, with no signed-in account, both
sync flags off, six Reader catalog entries and one note. PowerSync was absent.
Private baseline snapshots were taken before enrollment. The actual native
Binder transactions and parcel layouts match the product implementation; two
read/service checks also passed as the ordinary product UID.

## Compatibility changes

Local PowerSync **0.3.5/code 16** lowers the product minimum from API 33 to API 30.
Release lint identified one incompatible call: `InputStream.readNBytes`, used to
bound an HTTP error body. It is replaced with a bounded read compatible with
Android 11, with short-read, empty-read, limit and I/O-error coverage. There is
no model or firmware allowlist. This Tab 8 supports the full nine-field native
endpoint schema, independently of the older Palma schema fix.

Signed installation used the existing certificate. BOOX initially disabled the
new package; it was explicitly enabled. After the final in-place update, BOOX
re-disabled it. In native Freeze Settings, the PowerSync-specific Freeze checkbox
was turned off. Final readback shows **Freeze new apps** off and **Auto Freeze:**
**Do not freeze**. The agent targeted only the PowerSync row; the global setting
changed during this interval without an agent toggle of that control. All Files Access is granted. The final
APK also contains the [Palma post-update status comparison fix](README.md).
The combined build passes 65 unit tests, lint (0 errors / 12 existing warnings),
and release/test assembly. These are local builds; GitHub still publishes 0.3.3.

## Enrollment and preservation

The first native sign-in stopped at the notebook-preservation check. The note's
status and owner fields changed during enrollment; the owner confirmed deleting
that note while setup was running. Both sync flags remained off. The safety check
was not bypassed or weakened.

PowerSync's normal **Return to previous server** restored the exact original
cluster map and `zh` selection, removed the enrolled account, and retained all
six book records. The deleted note's ID, type, status, owner and sync flag remained
unchanged from its post-deletion state. A fresh enrollment then completed normally:
active phase, account/configuration matching, both sync flags enabled, unchanged
notebook fingerprint before synchronization, and all six baseline book UUIDs
present. No Resume action was required on this second attempt.

Native sync subsequently reached 1,126 note/folder records, and the owner observed
incoming content. A specific-ID check confirms the original note remains deleted
(`status=0`) with the same projected fields. Later whole-library fingerprint
changes are expected as native sync receives records; they are not an enrollment
preservation failure.

The native Library **Sync Cloud Books** flow and its reading-data confirmation
then received 138 additional book records: **144 total**, all six original UUIDs
preserved, including both known synthetic EPUB/PDF fixtures from Alexandria.

This qualifies enrollment and observed native sync on this model/build. Complete
resource downloads, every reading workflow, and arbitrary Android 11 firmware
remain separate claims. Private captures/APKs/provider projections stay under
ignored `artifacts/tab8-20261022/`; no credentials or raw notebook contents are
published here.
