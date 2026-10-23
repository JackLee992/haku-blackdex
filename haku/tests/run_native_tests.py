#!/usr/bin/env python3
"""Build real DEX fixtures and run separate-object SafeDex tests under ASan/UBSan."""
import json,os,pathlib,subprocess,tempfile,sys
HERE=pathlib.Path(__file__).resolve().parent
ROOT=HERE.parents[1]
with tempfile.TemporaryDirectory(prefix='haku-safe-dex-tests-') as temporary:
 out=pathlib.Path(temporary);subprocess.run(['python3',str(HERE/'generate_fixtures.py'),'--output',str(out)],check=True)
 meta=json.loads((out/'fixtures.json').read_text());core=ROOT/'Bcore/src/main/cpp/haku'
 flags=['-std=c++17','-g','-O1','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-fno-omit-frame-pointer','-pthread','-I',str(core)]
 publish_injection=['-Drenameatx_np=haku_test_renameatx_np'] if sys.platform=='darwin' else []
 subprocess.run(['clang++',*flags,*publish_injection,'-Dwrite=haku_test_write','-Dpread=haku_test_pread','-Dfsync=haku_test_fsync','-c',str(core/'SafeDex.cpp'),'-o',str(out/'SafeDex.o')],check=True)
 subprocess.run(['clang++',*flags,str(HERE/'safe_dex_test.cpp'),str(out/'SafeDex.o'),'-lz','-o',str(out/'safe_dex_test')],check=True)
 subprocess.run([str(out/'safe_dex_test'),str(out/'classes.dex'),str(out/'equal-length-distinct.dex'),meta['sha256'],meta['equalLengthDistinctSha256']],check=True,env={**os.environ,'ASAN_OPTIONS':'detect_leaks=0' if sys.platform=='darwin' else 'detect_leaks=1','UBSAN_OPTIONS':'halt_on_error=1:print_stacktrace=1'})
