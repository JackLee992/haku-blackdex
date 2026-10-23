# Haku fork changes

## 0.2.0-dev.1 — Android13 container preview — 2026-10-23

- Build the full ARM64/ARM32 development host against API33 using official Maven repositories, pinned tooling and an ignored local development signer.
- Run genuine Application and Activity lifecycles, internal navigation/results and ordinary started services through the API33 container; adapt package-manager flags, provider/storage identity and ActivityTaskManager forwarding.
- Serialize AtomicFile readers with writers and verify commit bytes, fixing a real cancel/retry race that could lose lifecycle flags.
- Bind each process to an immutable capture session. Record launch/service/Activity evidence, explicit failure and exact-session cancellation instead of inferring success from old files.
- Add the Haku app-selection/progress/result/history/verify/share workflow, separate byte-preserved base/split APK copies from runtime DEX, and verify every ZIP entry before publication and sharing.
- Validate the owned fixture through the actual Redmi API33 ARM64 GUI, recover both source-deleted runtime payloads byte-exactly, and independently verify DEX checksums/parser acceptance and ZIP contents. ARM32 remains build-only; worker-only DEX and deep recovery remain unqualified.
- Add 11 JVM regressions for archive/readback/copy/cancellation/quota behavior, a reproducible lifecycle fixture and an independent export verifier. See [validation](haku/CONTAINER_VALIDATION.md) and [usage](haku/CONTAINER_GUIDE.md).

## Initial API33 source upgrade — 2026-10-22

Based on CodingGay/BlackDex `5580fa8f5d658afae4eb667f8c8d6632be5b9aaf`, with upstream history and Apache-2.0 license preserved.

- Add calibrated Android13 ARM DexFile cookie extraction, exact self-memory reads and fixed-width DEX size decoding.
- Preserve original standard DEX bytes, validate original SHA1/Adler32 and structure, publish verified SHA256-addressed artifacts atomically.
- Reject unsupported layouts, CDEX, incomplete reads/writes, empty enumeration and corrupted output without repair or false success.
- Add experimental AOSP13 ObjPtr LoadMethod hook candidate and explicit failure propagation; runtime hook qualification is pending.
- Disable Android13+ deep ArtMethod recovery and inherited signal-suppression/hideXposed paths for the new platform route.
- Add independent 86-assertion ASan/UBSan host suite and reproducible ARM64/ARM32 native build script. Add a separate real-ART fixture probe, with phone results tracked separately.

See [ANDROID13.md](ANDROID13.md) for exact evidence, full-container build limitations and unproven runtime behavior. Bundled upstream APKs are historical and do not contain this upgrade.

Physical API33 ARM64 probe passed two original-byte-exact 23,836-byte fixtures through real mCookie/layout/self-memory reads, no-replace publication and Java readback. Independent validation and SDK dexdump also passed; SELinux remained Enforcing with unchanged policy. The initial hard-link publication attempt was correctly rejected by app SELinux; atomic exclusive rename fixed publication without a policy change. Full container, VMCore/parser integration, LoadMethod hook, ARM32 runtime and deep recovery remain unqualified.
