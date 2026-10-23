# Haku Android 13 basic extraction upgrade

This fork preserves CodingGay/BlackDex history at `5580fa8f5d658afae4eb667f8c8d6632be5b9aaf` and its Apache-2.0 license. The new Haku sources are independently written under Apache-2.0. No code was copied from AGPL-licensed newBlackDex. The upstream README and bundled APKs describe the historical upstream product, not verified compatibility of this branch.

## Implemented boundary

The new native route is restricted to **API 33, ARM64 or ARM32, standard DEX**. Other API levels above 33 fail explicitly; the pre-33 legacy engine remains upstream behavior. Android 13 deep ArtMethod recovery is disabled in settings and rejected by Java/native entry points. This is an initial extraction backend upgrade, not a claim that the complete BlackBox virtual container runs Android 13 applications.

`DexFileCompat.getCookies` skips slot zero on API33+: ART stores an OatFile pointer there, followed by DexFile pointers. Java retains the target loader during synchronous cookie reads. A retained owned `empty.jar` DexFile calibrates the AOSP object prefix against its original `classes.dex` bytes before target reads. The candidate prefix is vptr, begin_, size_: ARM64 offsets 8/16; ARM32 offsets 4/8. Unknown/mismatching layouts fail, without scanning adjacent pointer slots.

`haku/ArtDexLayout.h` and `SafeDex.cpp` read through an exact-count `process_vm_readv` self-reader. DEX header `file_size` is decoded as little-endian **uint32**, independently of pointer width. Allocation and declared sizes must agree, with a 64 MiB per-DEX cap. A short read, changed header, bad map/sections, missing classes, original SHA-1/Adler32 mismatch, unsupported version or CDEX fails. No checksum, magic, length or method-body repair is performed. The native integration additionally invokes the bundled DEX parser with structure/checksum verification enabled. These checks do not constitute ART bytecode semantic verification or prove that every original method body was present.

Outputs are immutable `<sha256>.dex` files containing original bytes. Temporary files are written with a full-write loop, fsynced, read back and byte-compared before atomic no-replace rename publication (`renameat2(RENAME_NOREPLACE)` on Android/Linux; `renameatx_np(RENAME_EXCL)` on macOS). Unsupported no-replace rename fails; there is no overwriting rename fallback. Filesystem failures identify their exact operation stage and errno. A same-name existing file must match; incomplete files, collisions and symlinks fail. Equal-length different DEX files cannot overwrite one another. The API33 default output is the app-private `files/haku-dex` directory. The output filesystem must support these operations; unsupported storage fails instead of weakening publication guarantees.

Java skips the historical `DexUtils.fixDex` rewrite on API33+. Missing cookies, reflection failures, JNI errors and observed hook failures propagate to a failed dump result even if older files already exist. Success means the enumerated cookie captures completed; it is not proof that all dynamically loaded code or all processes were covered.

## Optional ART hook

`Android13.cpp` resolves only this AOSP 13 candidate:

```
_ZN3art11ClassLinker10LoadMethodERKNS_7DexFileERKNS_13ClassAccessor6MethodENS_6ObjPtrINS_6mirror5ClassEEEPNS_9ArtMethodE
```

The callback forwards `this`, `DexFile const&`, `ClassAccessor::Method const&`, a one-word by-value `ObjPtr<Class>`, and `ArtMethod*`; it calls the original first. AOSP's templated conversion constructor does not replace the implicit trivial same-type copy constructor. Missing symbol or failed hook registration is an explicit setup failure. The host VMCore is used instead of the old injected `vm.jar` copy on API33+.

This hook is experimental and has **not** been qualified on the Xiaomi. Captures currently run synchronously on the loading thread without Java reentry; repeated hashing/parsing/writing can increase load latency substantially. Its accumulated failure counter is checked by subsequent cookie capture, but late hook events after the reporting boundary are not a complete session protocol. The hook has a separate API33 preference, disabled by default without inheriting the old enabled preference. Keep it disabled for the first cookie-backend acceptance. A production event queue, bounded throughput, teardown/session reporting, OEM symbol/ABI qualification and stress tests remain necessary before enabling it broadly.

The API33+ route does not invoke upstream DexDump kill/killpg suppression. Container initialization also skips inherited `ProcessHook` signal suppression and the hideXposed entry on API33+. No system security policy changes are made by the new backend.

## Verification recorded on 2026-10-22

- `python3 haku/tests/run_native_tests.py`: **86 assertions passed**, ASan and UBSan enabled, warnings treated as errors. Real D8-generated 23,836-byte/181-method DEX, equal-size distinct variant, original hash/magic preservation, true 4 KiB truncation, invalid memory/race/overflow, short/EINTR/zero/ENOSPC writes, readback/fsync failures, conflicting output and symlink denial, concurrent same-name publishers and injected no-replace-rename permission failure. macOS LeakSanitizer is unsupported and explicitly disabled. Tests do not use device/app dumps.
- Full `libblackdex.so` and its inherited Dobby shared dependency cross-compiled successfully for **arm64-v8a and armeabi-v7a**, NDK 23.1.7779620, CMake 3.22.1, min API21. Runtime qualification remains exact API33. Reproduce with `bash haku/build-native.sh`; override `HAKU_NDK_ROOT`, `HAKU_CMAKE_ROOT`, `HAKU_NATIVE_BUILD_ROOT` as needed.
- The upstream Gradle 6.7.1 wrapper starts with JDK11. A full Bcore Java build was attempted offline and failed during dependency configuration because AGP4.2.0/Kotlin1.5.0 are not cached. Therefore the complete Java/Kotlin container APK has **not been built or tested** in this pass. Bcore also pins build-tools28.0.2, absent locally; app pins30.0.3. No wholesale SDK/Gradle/container migration is hidden in this patch.
- Physical Xiaomi API33 ARM64 diagnostic run: both controlled 23,836-byte DEX files executed their distinct marker methods; real ART slot1/layout/self-memory reads matched all original bytes and SHA256 values. Initial publication failed because app SELinux denied hard links to app_data_file. No security setting was changed. The corrected exclusive-rename probe then **passed** both cases on the same API33 ARM64 phone: installed APK identity matched, native publication and Java readback matched all original bytes, and independent host validation plus SDK dexdump accepted both artifacts. SELinux stayed Enforcing and the policy hash stayed unchanged.
- `haku/spike/` provides a separately built, controlled API33 ART probe using the same production SafeDex/layout core. Read its README/metadata for APK hash, source hashes, exact scope and phone collection procedure. Runtime/device results must be recorded from the actual phone separately; an APK build is not runtime acceptance.

## Real-device acceptance and remaining work

The completed first gate uses only our controlled fixture: load and retain two equal-size different standard DEX files, execute their distinct marker methods, inspect genuine ART mCookie slot1+, calibrate layout, dump through the production read/validate/write core, and independently compare full output bytes and hashes with packaged originals. Collect result JSON, build metadata, DEX artifacts, process exit and crash evidence. A nonzero assertion failure, unreadable layout or absent artifact fails the gate. Root owns all device operations.

Verified probe APK SHA256: `71f4d6392e3aaa370a1bbd9b4c35af31bf97b3f0605feed1d3758b4c1a7da1ca`. The two 23,836-byte original fixture identities were `4e950159e2e1df230470d7c191687affba85e84aa7c9e430559d2ea98de7e867` and `58c5847a33cec6eabc2c75dd2ecbfb0faa7ec38ed6fc1efeb6cfe765f839cd40`. No device identifiers, captured app contents, signing keys or probe binaries are committed. The runtime probe compiles the production layout/SafeDex core; it does not call Android13.cpp's bundled parser or VMCore calibration integration.

Even a passing standalone probe leaves full-container launch and binder/service compatibility, VMCore calibration integration, old hidden API access and JNI hook initialization, optional LoadMethod hook execution, 32-bit runtime, secondary processes, unload/lifetime races, memory pressure and OEM ART differences unqualified. Upstream ArtM scanning and its obsolete `dex_code_item_offset` assumption have not been ported; no new API33 deep-recovery path uses them. Do not advertise Android14+ or arbitrary protected-app recovery from these tests.

## Primary source contracts

- [AOSP13 cookie conversion and OatFile slot](https://android.googlesource.com/platform/art/+/refs/tags/android-13.0.0_r1/runtime/native/dalvik_system_DexFile.cc)
- [AOSP13 DexFile layout](https://android.googlesource.com/platform/art/+/refs/tags/android-13.0.0_r1/libdexfile/dex/dex_file.h)
- [AOSP13 LoadMethod declaration](https://android.googlesource.com/platform/art/+/refs/tags/android-13.0.0_r1/runtime/class_linker.h)
- [AOSP13 ObjPtr representation/copy constructors](https://android.googlesource.com/platform/art/+/refs/tags/android-13.0.0_r1/runtime/obj_ptr.h)
- [AOSP13 ArtMethod fields](https://android.googlesource.com/platform/art/+/refs/tags/android-13.0.0_r1/runtime/art_method.h)
- [Preserved upstream source](https://github.com/CodingGay/BlackDex/tree/5580fa8f5d658afae4eb667f8c8d6632be5b9aaf)
