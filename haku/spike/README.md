# Owned ART API33 arm64 calibration spike

Build without Gradle:

```sh
python3 haku/spike/build.py
```

The APK is `dist/haku-art-api33-spike.apk`, package `com.jacklee.haku.artspike`, activity `.SpikeActivity`. It requires a physical API33 64-bit process and packages only an arm64 native library. The fixture targets SDK28 for this controlled legacy DexFile-cookie probe; it does not establish access behavior for arbitrary target SDKs.

The application decodes two controlled, genuine 23,836-byte DEX assets. Their lengths match, but one literal and its valid SHA-1/Adler32 values differ. Each DEX is loaded into a separate class loader, its expected marker is invoked, and its `DexFile` remains strongly retained.

JNI reads the real `mCookie` object as `long[]`, skips index0, and sends each remaining candidate through production `ArtDexLayout.h` and `SafeDex.cpp`. Every native memory read uses an exact `process_vm_readv` loop within the spike's own process. A result is accepted only when all bytes and SHA-256 match the original controlled fixture. Production `WriteArtifact` then publishes immutable SHA-named bytes with atomic no-replace rename, and Java independently rereads the file and checks byte/hash identity.

A failed native result is retained before Java readback, with per-candidate errors and memory-reader errno. Missing output cannot erase the original native diagnosis.

Runtime output:

- `files/spike-result.json`: per-case marker, original identity, cookie count, skipped index0, native layout/read/write results and Java readback result.
- `files/captured/<sha256>.dex`: independently matched original bytes.

Build inputs, native source hashes, fixture identities, toolchain and signing output identity are in `dist/haku-art-api33-spike-build.json`. Generated fixtures, development key and compiler output live under `.build/art-spike`.

A passing physical-device run qualifies only this device's retained, app-owned cookie/layout/read/write contract. It does not qualify the BlackBox container, the `ClassLinker::LoadMethod` hook, native-only loading coverage, other ART versions, other vendor builds or third-party application recovery. The builder leaves `runtimeVerified=false`; device evidence must be recorded separately by the operator.

## Recorded physical acceptance — 2026-10-22

The initial probe reached valid byte-exact ART snapshots but publication failed: app SELinux denied hard-link creation. The first Java reporter obscured this with a missing-artifact exception; reporting now preserves native failures. Publication was changed to atomic no-replace rename without any security-policy change.

The corrected probe APK `71f4d6392e3aaa370a1bbd9b4c35af31bf97b3f0605feed1d3758b4c1a7da1ca` passed both 23,836-byte cases on the supplied Xiaomi API33 ARM64 phone. Installed APK identity, executed marker, real cookie slot1, exact self-memory read, SHA256-addressed publication and Java byte/hash readback passed. Root independently compared original assets and ran validation plus SDK dexdump on both outputs. SELinux remained Enforcing with unchanged policy. See [../../ANDROID13.md](../../ANDROID13.md) for fixture identities and remaining integration gates. No device identifiers or runtime artifacts are stored in the repository.

This acceptance does not exercise Android13.cpp's bundled parser, VMCore calibration integration, LoadMethod hooks, ARM32 runtime, deep recovery, BlackBox virtualization or Haku app integration. Build metadata deliberately continues to say `runtimeVerified=false` because each new build requires separate runtime evidence.
