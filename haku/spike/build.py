#!/usr/bin/env python3
"""Build a standalone controlled ART API33 arm64 spike; no Gradle/device/root dependency."""
import argparse,hashlib,json,os,pathlib,platform,shutil,subprocess,zipfile
HERE=pathlib.Path(__file__).resolve().parent;ROOT=HERE.parents[1]
p=argparse.ArgumentParser();p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk')));p.add_argument('--java-home',default='/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home');p.add_argument('--ndk',default=os.environ.get('ANDROID_NDK_ROOT',str(pathlib.Path.home()/'Library/Android/sdk/ndk/29.0.14206865')));a=p.parse_args()
sdk=pathlib.Path(a.sdk);java=pathlib.Path(a.java_home);ndk=pathlib.Path(a.ndk);bt=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-33/android.jar';build=ROOT/'.build/art-spike';dist=ROOT/'dist';build.mkdir(parents=True,exist_ok=True);dist.mkdir(exist_ok=True);env={**os.environ,'JAVA_HOME':str(java),'PATH':str(java/'bin')+os.pathsep+os.environ['PATH']}
def run(*args):subprocess.run([str(v) for v in args],check=True,env=env)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
core=ROOT/'Bcore/src/main/cpp/haku';inputs=[core/'SafeDex.cpp',core/'SafeDex.h',core/'ArtDexLayout.h',HERE/'native/spike.cpp',*sorted((HERE/'src').rglob('*.java')),HERE/'AndroidManifest.xml',HERE/'build.py'];before={str(f.relative_to(ROOT)):sha(f) for f in inputs}
fixtures=build/'fixtures';run('python3',ROOT/'haku/tests/generate_fixtures.py','--output',fixtures,'--sdk',sdk,'--java-home',java);fixture_meta=json.loads((fixtures/'fixtures.json').read_text());assets=build/'assets';assets.mkdir(exist_ok=True)
for source,name in [('classes.dex','main.xor'),('equal-length-distinct.dex','distinct.xor')]: (assets/name).write_bytes(bytes(v^0xA7 for v in (fixtures/source).read_bytes()))
host={'Darwin':'darwin-x86_64','Linux':'linux-x86_64'}[platform.system()];compiler=ndk/'toolchains/llvm/prebuilt'/host/'bin/aarch64-linux-android26-clang++';native=build/'libhaku_art_spike.so'
flags=['-std=c++17','-O2','-g','-shared','-fPIC','-static-libstdc++','-Wall','-Wextra','-Werror','-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-Wl,-z,relro,-z,now,-z,noexecstack','-Wl,--build-id=sha1','-I',str(core)]
run(compiler,*flags,HERE/'native/spike.cpp',core/'SafeDex.cpp','-lz','-o',native)
classes=build/'classes';classes.mkdir(exist_ok=True);run(java/'bin/javac','--release','8','-encoding','UTF-8','-classpath',android,'-d',classes,*sorted((HERE/'src').rglob('*.java')));jar=build/'classes.jar';run(java/'bin/jar','cf',jar,'-C',classes,'.');dex=build/'dex';dex.mkdir(exist_ok=True);run(bt/'d8','--min-api','26','--lib',android,'--output',dex,jar)
resources=build/'resources.zip';run(bt/'aapt2','compile','--dir',HERE/'res','-o',resources);package=build/'resources.ap_';run(bt/'aapt2','link','-o',package,'--manifest',HERE/'AndroidManifest.xml','-I',android,'--min-sdk-version','26','--target-sdk-version','28','-A',assets,resources)
unsigned=build/'unsigned.apk'
with zipfile.ZipFile(package) as original,zipfile.ZipFile(unsigned,'w') as output:
 for name in sorted(original.namelist()):
  entry=zipfile.ZipInfo(name,(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;output.writestr(entry,original.read(name))
 for name,file in [('classes.dex',dex/'classes.dex'),('lib/arm64-v8a/libhaku_art_spike.so',native)]:
  entry=zipfile.ZipInfo(name,(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_STORED if name.startswith('lib/') else zipfile.ZIP_DEFLATED;output.writestr(entry,file.read_bytes())
aligned=build/'aligned.apk';run(bt/'zipalign','-f','-p','4',unsigned,aligned);key=build/'development.keystore'
if not key.exists():run(java/'bin/keytool','-genkeypair','-keystore',key,'-alias','haku-art-spike','-storepass','android','-keypass','android','-dname','CN=Haku Controlled ART Spike','-keyalg','RSA','-keysize','2048','-validity','3650')
apk=dist/'haku-art-api33-spike.apk';run(bt/'apksigner','sign','--ks',key,'--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned);run(bt/'apksigner','verify','--verbose',apk)
after={str(f.relative_to(ROOT)):sha(f) for f in inputs}
if before!=after:raise SystemExit('Production sources changed during spike build; rebuild required')
meta={'schema':'haku.art.spike.build.v1','package':'com.jacklee.haku.artspike','apk':str(apk.relative_to(ROOT)),'apkSha256':sha(apk),'apkBytes':apk.stat().st_size,'nativeSha256':sha(native),'sourceSha256':before,'fixture':fixture_meta,'runtimeVerified':False,'deviceContract':'API33 arm64 only; retained app-owned DexFile.mCookie long[], skipindex0, process_vm_readv exact reads, production ArtDexLayout+SafeDex byte/hash calibration and full write/readback','report':'files/spike-result.json','captured':'files/captured/<sha256>.dex','scope':'Does not qualify BlackBox container, class-load hook, arbitrary vendor ART versions or third-party package recovery','compiler':subprocess.check_output([str(compiler),'--version'],text=True).splitlines()[0],'flags':flags}
(dist/'haku-art-api33-spike-build.json').write_text(json.dumps(meta,indent=2)+'\n');print(json.dumps(meta,indent=2))
