#!/usr/bin/env bash
set -euo pipefail

# Complete development host build; no device commands or release signing changes.
project_root=$(cd "$(dirname "$0")/.." && pwd)
flavor=${HAKU_HOST_FLAVOR:-BlackDex64}
case "$flavor" in BlackDex64|BlackDex32) ;; *) echo "Unsupported flavor: $flavor" >&2; exit 2 ;; esac
if [[ -z ${JAVA_HOME:-} && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 11)
fi
: "${JAVA_HOME:?Set JAVA_HOME to JDK 11}"
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
export ANDROID_HOME=$ANDROID_SDK_ROOT
output_root=${HAKU_HOST_OUTPUT_ROOT:-"$project_root/.haku-build/host/$flavor"}
mkdir -p "$output_root" "$project_root/.haku-build/signing"
key_file="$project_root/.haku-build/signing/haku-development.jks"
if [[ ! -f "$key_file" ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$key_file" -storepass android \
    -keypass android -alias haku-development -keyalg RSA -keysize 2048 -validity 3650 \
    -dname 'CN=Haku Development, O=Haku, C=CN'
  chmod 600 "$key_file"
fi
gradle_args=(":app:assemble${flavor}Debug" --console=plain --stacktrace --max-workers=4)
if [[ -n ${HAKU_PROXY_HOST:-} ]]; then
  : "${HAKU_PROXY_PORT:?Set HAKU_PROXY_PORT with HAKU_PROXY_HOST}"
  gradle_args+=("-Dhttp.proxyHost=$HAKU_PROXY_HOST" "-Dhttp.proxyPort=$HAKU_PROXY_PORT"
    "-Dhttps.proxyHost=$HAKU_PROXY_HOST" "-Dhttps.proxyPort=$HAKU_PROXY_PORT")
fi
cd "$project_root"
source_manifest() {
  python3 - "$project_root" "$1" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root, output = map(pathlib.Path, sys.argv[1:])
names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root)
hashes = {}
for name in sorted(set(names.decode().split('\0')) - {''}):
    path = root / name
    if path.is_file():
        hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
output.write_text(json.dumps(hashes, sort_keys=True, indent=2) + '\n')
PY
}
source_manifest "$output_root/source-manifest-before.json"
./gradlew "${gradle_args[@]}" "$@" 2>&1 | tee "$output_root/build.log"
source_manifest "$output_root/source-manifest-after.json"
if ! cmp -s "$output_root/source-manifest-before.json" "$output_root/source-manifest-after.json"; then
  echo 'Sources changed during compilation; rerun after edits settle to produce a valid receipt.' >&2
  exit 1
fi
apk="$project_root/app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk"
flavor_lower=$(printf '%s' "$flavor" | tr '[:upper:]' '[:lower:]')
artifact="$output_root/haku-$flavor_lower-dev.apk"
cp "$apk" "$artifact"
build_tools="$ANDROID_SDK_ROOT/build-tools/30.0.3"
"$build_tools/apksigner" verify --verbose --print-certs "$artifact" > "$output_root/signature.txt"
"$build_tools/aapt" dump badging "$artifact" > "$output_root/badging.txt"
"$JAVA_HOME/bin/java" -version 2> "$output_root/java-version.txt"
python3 - "$artifact" "$output_root" "$project_root" <<'PY'
import hashlib, json, pathlib, subprocess, sys, zipfile
apk, output, project = map(pathlib.Path, sys.argv[1:])
sha256 = lambda data: hashlib.sha256(data).hexdigest()
with zipfile.ZipFile(apk) as archive:
    libraries = {name: sha256(archive.read(name)) for name in archive.namelist()
                 if name.startswith('lib/') and name.endswith('.so')}
metadata = {
    'apk': str(apk.resolve()), 'apk_sha256': sha256(apk.read_bytes()),
    'apk_size': apk.stat().st_size, 'native_libraries': libraries,
    'git_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip(),
    'tracked_diff_sha256': sha256(subprocess.check_output(['git', 'diff', 'HEAD'], cwd=project)),
    'source_manifest_sha256': sha256((output / 'source-manifest-before.json').read_bytes()),
    'gradle': '6.7.1', 'agp': '4.2.0', 'kotlin': '1.5.0', 'compile_sdk': 33,
    'target_sdk': 30, 'build_tools': '30.0.3', 'ndk': '23.1.7779620', 'cmake': '3.22.1',
    'scope': 'Development host build only; no runtime or device acceptance implied.'
}
(output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(metadata, indent=2))
PY
