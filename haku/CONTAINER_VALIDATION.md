# Android13 container validation — 2026-10-23

This record separates the full container test from the earlier native-only
probe. Qualification is an owned fixture on a physical Redmi/chopin running
Android13 API33 with an ARM64 Haku host. It establishes this tested workflow,
not arbitrary application or Android14+ compatibility. The lab phone was rooted: authorized Shell root was used for installation and
collection, while the container engine ran as its ordinary app process without
requesting root. An unrooted stock phone was not separately qualified. Device
identifiers, keys, private paths and raw phone logs stay outside the repository.

## Acceptance boundary

The fixture's genuine Application decodes, executes and registers two distinct
23,836-byte DEX payloads, then deletes both clear source files. Haku must run
its first Activity, navigate to a second Activity and receive its result, and
start a Service in a separate virtual worker process. It must recover the exact
original fixture `classes.dex` and both runtime payloads, copy the original APK
separately, and publish a complete hash-verified ZIP through the actual UI.
Container bootstrap archives must not count as target DEX or establish success.

The independent [fixture verifier](container-fixture/verify.py) checks original
APK/DEX byte equality, exact runtime membership, original DEX file sizes and
SHA1/Adler32, Android SDK `dexdump`, ZIP CRC/inventory/size/SHA256, fresh virtual
process records, Activity/result delivery, and started-service evidence.

## Results

The final development candidate and its physical result are recorded in
[the machine-readable summary](validation/android13-arm64-20261023.json).
The release includes APKs, build receipts and SHA256SUMS; use those identities
when reproducing this record.

| Check | Result / evidence |
| --- | --- |
| Complete host build | ARM64 and ARM32 development APKs build against API33. ARM32 is build-only. |
| Archive/copy regressions | 11 JVM tests pass: readback, mutation, missing/extra entries, size mismatch, traversal/symlink denial, cancellation, quota/overflow. |
| Pending AtomicFile write | The same real-Android test APK fails both report/pointer cases on the old candidate and passes both on the fixed candidate; pending `.new` files survive and committed values are observed. |
| Native read/write baseline | Existing 86-assertion ASan/UBSan suite passed; the bounded SafeDex/layout implementation is unchanged by container integration. |
| Genuine main lifecycle | Application, first Activity create/resume, delayed callback, second Activity create/resume/finish, result delivery and first Activity resume. |
| Separate worker | Real Application and Service.onCreate/onStartCommand in a distinct virtual process. Worker-only DEX capture remains excluded. |
| Runtime recovery | Exact base DEX plus both executed/deleted-source payloads; separate content-hash filenames prevent equal-size collisions. |
| Package/export | Original base APK preserved separately; ZIP entry set, CRC, lengths, hashes and published ZIP digest checked independently. |
| GUI export | Actual application selection/start/result, Verify ZIP again, history reopen and system share chooser. No recipient was selected. |
| Read grant boundary | Ordinary ungranted Shell access to the non-exported provider is denied. |
| Cancellation/retry | Actual Cancel button during the owned fixture's bounded Application startup; exact report becomes failed/cancelled before Activity creation. A new attempt gets a new session and preserves the cancelled report. |
| Security / device ownership | No SELinux or root-manager policy changes; previous power setting restored after testing; unrelated intranet relay remains running. |

## Bugs reproduced and addressed

- The old path created an Application but did not execute its real onCreate,
  and did not replace the target launch transaction. Real callbacks/navigation
  now run rather than reporting directory creation as success.
- Android13 changed package-manager flag widths and ActivityTaskManager argument
  layouts. The adapted route preserves original navigation/result arguments.
- Provider attribution and storage calls sent the virtual package with the real
  host UID. Android rejected the identity. Boundary calls now use the real host
  identity while target Context identity stays virtual. Private native
  calibration no longer initializes external storage.
- Native calibration failure was previously only an initializer exception.
  Checkpoints and full chained causes now identify the failing boundary.
- Existing directories could imply success. Every attempt now has an immutable
  session ID, serialized terminal report, matching process records and exact
  cancellation/cleanup. A late old process cannot follow a newer active pointer. Cancel/retry testing then reproduced a second race: an unlocked Android AtomicFile reader deleted a writer’s pending `.new` file, silently losing a lifecycle flag. Report and active-pointer readers now take the same class/file locks as writers, and every commit is byte-checked after publication. A controlled real-Android pending-write regression covers this boundary.
- Whole-MiB rounding and package-only results hid extraction failures. Exact
  byte totals and distinct original/runtime/report origins now describe what was
  recovered. Failed or pending required lifecycle evidence is never green.
- Two injected container support archives initially inflated target capture
  totals. API33 now excludes only their exact canonical Haku paths, retains
  deleted-source target DexFile owners through synchronous reads, and fails if
  no target cookies remain.

An intentionally slow worker produced a partial result with an explicit
pending-service error; it was not promoted to verified. A failed receipt and a
changed ZIP were independently rejected. The first successful integration
candidate (v10, internal build label) captured five DEX/259,256 bytes, including
197,948 bytes of container support libraries. That historical figure is superseded by
the final target-only result; it is not the final target-code size.

## Reproduce

Build instructions are in [HOST_BUILD.md](HOST_BUILD.md); device/fixture
procedure and verifier arguments are in [container-fixture/README.md](container-fixture/README.md).
The user workflow is in [CONTAINER_GUIDE.md](CONTAINER_GUIDE.md).
Use the exact fixture APK matching the validation summary, select it through
the Haku GUI, and collect only that session's export and fresh records. A build,
screenshot, nonempty directory or copied APK alone does not satisfy acceptance.

## Remaining limits

Capture is a standard-DEX snapshot of the main application class loader about
2000ms after first Activity creation. It does not establish worker-only DEX,
independent child loaders, later loads, unloaded code, all method bodies,
CDEX/deep ArtMethod recovery, bound/foreground/restarted services, rebuilt or
installable APKs, ARM32 runtime or other OEM/OS layouts. The optional LoadMethod
hook remains experimental and off by default. Individual applications can fail
because further container contracts are unimplemented; the report retains that
failure rather than promising universal unpacking.
