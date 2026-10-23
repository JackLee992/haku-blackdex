# Haku development host APK

Run from this checkout with JDK 11 and an Android SDK containing platform 33,
build-tools 30.0.3, NDK 23.1.7779620, and CMake 3.22.1:

```sh
bash haku/build-host.sh
```

The default variant is `BlackDex64Debug`. Set `HAKU_HOST_FLAVOR=BlackDex32` for
the ARM32 host, `HAKU_HOST_OUTPUT_ROOT` for an absolute artifact directory, or
`JAVA_HOME` / `ANDROID_SDK_ROOT` to select installed tools. When this network
requires an explicit HTTP CONNECT proxy, set `HAKU_PROXY_HOST` and
`HAKU_PROXY_PORT`; these apply only to the Gradle invocation and preserve HTTPS
certificate validation. For example:

```sh
HAKU_PROXY_HOST=127.0.0.1 HAKU_PROXY_PORT=7897 bash haku/build-host.sh
```

The script creates an ignored `.haku-build/signing/haku-development.jks` on
first use. Its `android` passwords identify development signing only. Preserve
that local file when installing subsequent development updates. No release
signing configuration is changed. Application IDs are
`com.jacklee.haku.blackdex64` and `com.jacklee.haku.blackdex32`; their launcher
labels include `Haku` and `Dev`.

The output directory contains the APK, Gradle log, APK/signing inspection, Java
version, and a JSON receipt with APK/native-library hashes and the source
revision/diff hash. Hashes describe that build; rebuilds are not asserted to be
byte-identical. Source edits must settle before producing the final acceptance
artifact. The script performs no device operations.

Gradle 6.7.1 (distribution SHA256 pinned), AGP 4.2.0, and Kotlin 1.5.0 remain
pinned to limit build-system migration. Dependencies resolve through official
Google Maven and Maven Central over HTTPS. Removed legacy UI and telemetry
libraries no longer require JCenter, Aliyun, or JitPack. All modules compile
against API33; the host retains targetSdk30 during container compatibility
work. Compiling against API33 does not imply Android13 runtime acceptance.

Native compilation supports ARM64 and ARM32, matching the current backend
scope. For native-only builds and sanitizer tests, see `haku/build-native.sh`
and `haku/tests/run_native_tests.py`.
