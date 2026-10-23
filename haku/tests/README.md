# Independent SafeDex contract tests

Run from the repository root:

```sh
python3 haku/tests/run_native_tests.py
```

The runner generates controlled standard DEX files with Android build-tools 35.0.0 D8 and SDK 33. It uses JDK 17 at the installed Homebrew path by default; set `JAVA_HOME` or `ANDROID_HOME` for another installation. No application or device dump is used. The primary payload contains 181 real methods and more than 23 KiB of code, metadata and constants. An equal-length variant changes one literal and recalculates SHA-1/Adler32 using Python's standard library.

The production `SafeDex.cpp` is compiled as a separate object with ASan/UBSan and warnings treated as errors. Its POSIX `write`, `pread`, and `fsync` calls are replaced by test wrappers; on macOS the exclusive rename call is also wrapped to inject a publication permission failure. The wrappers exercise short I/O, EINTR, zero progress, ENOSPC, readback failure and fsync failure while retaining actual filesystem operations. This does not replace validation, hashing, snapshot logic or artifact publication with test implementations.

The harness checks genuine DEX acceptance, byte and magic preservation, SHA-256 reference vectors, a real 4096-byte truncation, corruption, memory-reader failure and races, synthetic candidate ART prefix bounds and delegation, snapshot independence from source mutation, equal-length identities, full byte-identical publication, private file mode, idempotent existing output, corrupted existing output and symlink denial. Fixture and compiled test files are temporary and removed after the run.

Verified on the current macOS host: **86 assertions passed** with AddressSanitizer and UndefinedBehaviorSanitizer enabled. LeakSanitizer is disabled on macOS because the installed runtime reports it unsupported. This is a host core-contract check; it does not establish Android ART hook, JNI, virtualization, APK build, or real-device compatibility.
