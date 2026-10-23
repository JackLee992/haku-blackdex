# Owned container acceptance fixture

This source builds a small, owned application for testing Haku's real Android13
container. It is not a fixture made from another application's code or data.

```sh
python3 haku/container-fixture/build.py
```

The default output is `dist/haku-container-fixture.apk` plus its build receipt.
The builder uses Android SDK platform33/build-tools35 and JDK17; pass `--sdk`
and `--java-home` for other installed tool locations. All generated files and
the development signing key remain in ignored `.build/` and `dist/` directories.

The custom Application decodes two distinct equal-length DEX payloads from owned
XOR assets, executes their different marker methods through retained loaders,
registers their elements in the app loader and deletes their clear source files.
The fixture then runs a real Activity, a second Activity with result delivery,
and a started Service in a distinct `:worker` process. Each process writes its
own lifecycle/payload record under `files/fixture-records/<pid>.json`. The
container must run these callbacks; directory creation alone cannot satisfy the
gate. A standalone run provides the baseline before testing the container.

For a deterministic cancel-during-startup test, build a separate slow variant:

```sh
python3 haku/container-fixture/build.py --startup-delay-ms 10000 \
  --output-name haku-container-fixture-slow
```

Its owned Application records startup, then pauses for a bounded interval. Wait
for that fresh startup record, return to Haku and use its actual Cancel button.
Check that the exact report becomes failed with `Capture cancelled by user`,
that the UI says `Capture cancelled`, and that the process stops before Activity
creation. Reinstall the default zero-delay fixture before ordinary acceptance;
the delay also applies to its worker and deliberately exceeds the normal
capture window. Preserve the cancelled report and check that a retry creates a
new session without rewriting the old one.

After capturing the default fixture through Haku's GUI, collect only the matching
published `report.json`, `export.zip`, fresh fixture records and capture start
elapsed time from the authorized device. Collection can use the debug app's
`run-as` or an already authorized root diagnostic path. Device access is not
part of the verifier and device identifiers are never needed in source control.

```sh
python3 haku/container-fixture/verify.py \
  --fixture dist/haku-container-fixture.apk \
  --report /path/to/export/report.json \
  --zip /path/to/export/export.zip \
  --records-dir /path/to/fresh-fixture-records \
  --min-elapsed-ms START_ELAPSED_MS \
  --dexdump "$ANDROID_HOME/build-tools/35.0.0/dexdump" \
  --output /path/to/acceptance.json
```

This independent verifier requires a verified receipt, exact original APK and
DEX bytes, original header sizes/SHA1/Adler32, SDK parser acceptance when supplied,
ZIP CRC/inventory/size/SHA256, fresh virtual-process lifecycle records and actual
navigation/result/service execution. It rejects failed receipts and changed
archives. Runtime payloads must have executed and their sources must have been
deleted before capture. A successful fixture does not establish capture from
independent child loaders, worker-only code, later loads, every method, or an
installable rebuilt APK.
