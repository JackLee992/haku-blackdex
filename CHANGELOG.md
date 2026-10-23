# Haku fork changes

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
