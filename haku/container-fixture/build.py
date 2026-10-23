#!/usr/bin/env python3
"""Build an owned lifecycle/runtime-DEX fixture, without a Gradle dependency."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def default_java_home():
    if os.environ.get('JAVA_HOME'):
        return os.environ['JAVA_HOME']
    mac_java = pathlib.Path('/usr/libexec/java_home')
    if mac_java.exists():
        found = subprocess.run([str(mac_java), '-v', '17'], capture_output=True, text=True)
        if found.returncode == 0:
            return found.stdout.strip()
    javac = shutil.which('javac')
    return str(pathlib.Path(javac).resolve().parents[1]) if javac else ''


p = argparse.ArgumentParser()
p.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', str(pathlib.Path.home() / 'Library/Android/sdk')))
p.add_argument('--java-home', default=default_java_home())
p.add_argument('--startup-delay-ms', type=int, default=0,
               help='Owned Application delay for deterministic cancel/retry QA (0..15000)')
p.add_argument('--output-name', default='haku-container-fixture',
               help='Artifact basename in dist/')
a = p.parse_args()
if not 0 <= a.startup_delay_ms <= 15000 or not a.output_name.replace('-', '').isalnum():
    p.error('Invalid startup delay or output basename')
if not a.java_home or not (pathlib.Path(a.java_home) / 'bin/javac').is_file():
    p.error('Select JDK17 with --java-home or JAVA_HOME')
sdk, java = pathlib.Path(a.sdk), pathlib.Path(a.java_home)
bt = sdk / 'build-tools/35.0.0'
android = sdk / 'platforms/android-33/android.jar'
build, dist = ROOT / '.build/container-fixture', ROOT / 'dist'
build.mkdir(parents=True, exist_ok=True)
dist.mkdir(exist_ok=True)
env = {**os.environ, 'JAVA_HOME': str(java), 'PATH': str(java / 'bin') + os.pathsep + os.environ['PATH']}


def run(*args):
    subprocess.run([str(x) for x in args], check=True, env=env)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


fixtures = build / 'payloads'
run('python3', ROOT / 'haku/tests/generate_fixtures.py', '--output', fixtures, '--sdk', sdk, '--java-home', java)
assets = build / 'assets'
assets.mkdir(exist_ok=True)
(assets / 'startup-delay-ms.txt').write_text(str(a.startup_delay_ms))
for filename, name in [('classes.dex', 'main.xor'), ('equal-length-distinct.dex', 'distinct.xor')]:
    (assets / name).write_bytes(bytes(b ^ 0xa7 for b in (fixtures / filename).read_bytes()))
sources = sorted((HERE / 'src').rglob('*.java'))
inputs = [HERE / 'build.py', HERE / 'AndroidManifest.xml', *sources, ROOT / 'haku/tests/generate_fixtures.py']
before = {str(f.relative_to(ROOT)): sha(f) for f in inputs}
classes = build / 'classes'
classes.mkdir(exist_ok=True)
run(java / 'bin/javac', '--release', '8', '-encoding', 'UTF-8', '-classpath', android, '-d', classes, *sources)
jar = build / 'classes.jar'
run(java / 'bin/jar', 'cf', jar, '-C', classes, '.')
dex = build / 'dex'
dex.mkdir(exist_ok=True)
run(bt / 'd8', '--min-api', '26', '--lib', android, '--output', dex, jar)
package = build / 'resources.ap_'
run(bt / 'aapt2', 'link', '-o', package, '--manifest', HERE / 'AndroidManifest.xml', '-I', android,
    '--min-sdk-version', '26', '--target-sdk-version', '28', '--version-code', '1',
    '--version-name', '1.0', '-A', assets)
unsigned = build / 'unsigned.apk'
with zipfile.ZipFile(package) as src, zipfile.ZipFile(unsigned, 'w') as out:
    for name in sorted(src.namelist()):
        info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(info, src.read(name))
    info = zipfile.ZipInfo('classes.dex', (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    out.writestr(info, (dex / 'classes.dex').read_bytes())
aligned = build / 'aligned.apk'
run(bt / 'zipalign', '-f', '-p', '4', unsigned, aligned)
key = build / 'development.keystore'
if not key.exists():
    run(java / 'bin/keytool', '-genkeypair', '-keystore', key, '-alias', 'haku-container-fixture',
        '-storepass', 'android', '-keypass', 'android', '-dname', 'CN=Haku Owned Container Fixture',
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650')
apk = dist / (a.output_name + '.apk')
run(bt / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', apk, aligned)
run(bt / 'apksigner', 'verify', '--verbose', apk)
assert before == {str(f.relative_to(ROOT)): sha(f) for f in inputs}, 'Fixture changed while building'
metadata = {'schema': 'haku.container.fixture.build.v1', 'package': 'com.jacklee.haku.containerfixture',
            'apk': str(apk.relative_to(ROOT)), 'apkSha256': sha(apk), 'apkBytes': apk.stat().st_size,
            'mainDexSha256': sha(dex / 'classes.dex'), 'sourceSha256': before,
            'payloads': json.loads((fixtures / 'fixtures.json').read_text()),
            'runtimeVerified': False, 'records': 'files/fixture-records/<pid>.json',
            'startupDelayMs': a.startup_delay_ms,
            'scope': 'Owned XOR assets decoded, executed, registered in app loader, source files deleted; genuine Application/Activity and optional worker lifecycle.'}
(dist / (a.output_name + '-build.json')).write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(metadata, indent=2))
