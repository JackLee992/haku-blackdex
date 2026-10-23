#!/usr/bin/env python3
"""Create controlled D8 DEX with real methods/constants, plus equal-length valid variant."""
import argparse,hashlib,json,os,pathlib,subprocess,zlib
ROOT=pathlib.Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk')));p.add_argument('--java-home',default=os.environ.get('JAVA_HOME','/opt/homebrew/Cellar/openjdk@17/17.0.20.1/libexec/openjdk.jdk/Contents/Home'));p.add_argument('--output',type=pathlib.Path,required=True);args=p.parse_args()
out=args.output;out.mkdir(parents=True,exist_ok=True);java=pathlib.Path(args.java_home);sdk=pathlib.Path(args.sdk);env={**os.environ,'JAVA_HOME':str(java),'PATH':str(java/'bin')+os.pathsep+os.environ['PATH']}
source=out/'src/fixture/Controlled.java';source.parent.mkdir(parents=True,exist_ok=True)
methods=['package fixture; public final class Controlled {','public static String marker(){return "haku_dynamic_dex_ok_v1";}']
for i in range(180):methods.append('public static String code%03d(){return "independent_fixture_%03d_copper_chestnut_actual_code_constant_contract";}'%(i,i))
methods.append('}');source.write_text('\n'.join(methods)+'\n');classes=out/'classes';classes.mkdir(exist_ok=True)
subprocess.run([str(java/'bin/javac'),'--release','8','-d',str(classes),str(source)],check=True,env=env)
jar=out/'payload.jar';subprocess.run([str(java/'bin/jar'),'cf',str(jar),'-C',str(classes),'.'],check=True,env=env)
subprocess.run([str(sdk/'build-tools/35.0.0/d8'),'--min-api','26','--lib',str(sdk/'platforms/android-33/android.jar'),'--output',str(out),str(jar)],check=True,env=env)
raw=(out/'classes.dex').read_bytes();assert len(raw)>8192
altered=bytearray(raw);old=b'haku_dynamic_dex_ok_v1';new=b'haku_changed_dex_ok_v1';assert len(new)==len(old) and altered.count(old)==1
start=altered.index(old);altered[start:start+len(old)]=new;altered[12:32]=hashlib.sha1(altered[32:]).digest();altered[8:12]=(zlib.adler32(altered[12:])&0xffffffff).to_bytes(4,'little');(out/'equal-length-distinct.dex').write_bytes(altered)
metadata={'schema':'haku.safe-dex.fixture.v1','generator':'D8 build-tools35.0.0 / javac17 --release8 / min-api26','payloadBytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'equalLengthDistinctSha256':hashlib.sha256(altered).hexdigest(),'sha256Empty':hashlib.sha256(b'').hexdigest(),'sha256Abc':hashlib.sha256(b'abc').hexdigest(),'methods':181,'derivation':'second valid DEX changes one equal-length literal and recomputes original-format SHA1/Adler32; neither fixture comes from an app dump'}
(out/'fixtures.json').write_text(json.dumps(metadata,indent=2)+'\n');print(json.dumps(metadata))
