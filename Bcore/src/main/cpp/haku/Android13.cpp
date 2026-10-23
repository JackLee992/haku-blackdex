// Copyright 2026 Haku contributors. SPDX-License-Identifier: Apache-2.0
// Reference: AOSP android-13.0.0_r1, qualified against an owned calibration DEX.
#include "Android13.h"
#include "SafeDex.h"
#include "ArtDexLayout.h"
#include "VmCore.h"
#include "utils/Log.h"
#include "dex/dex_file_loader.h"
#include "dex/dex_file.h"
#include "Dobby/include/dobby.h"
#include <android/api-level.h>
#include <cerrno>
#include <atomic>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <unistd.h>

namespace haku {
namespace {
std::mutex state_mutex;
bool calibrated=false;
bool hook_installed=false;
std::atomic<unsigned> hook_failures{0};
std::string hook_directory;
thread_local bool in_hook=false;

bool ReadSelf(uintptr_t address,void* output,size_t length) {
    if(!address||address>std::numeric_limits<uintptr_t>::max()-length)return false;
    iovec local{output,length},remote{reinterpret_cast<void*>(address),length};
    ssize_t n;
    do {
        n=syscall(__NR_process_vm_readv,getpid(),&local,1,&remote,1,0);
    }while(n<0&&errno==EINTR);
    // Never fall back to unchecked memcpy or a device security change.
    return n>=0&&size_t(n)==length;
}
void Throw(JNIEnv* env,const std::string& message) {
    if(env->ExceptionCheck())return;
    jclass type=env->FindClass("java/lang/IllegalStateException");
    if(type){env->ThrowNew(type,message.c_str());env->DeleteLocalRef(type);}
}
bool QualifiedPlatform(std::string* error) {
#if defined(__aarch64__) || defined(__arm__)
    if(android_get_device_api_level()==33)return true;
#endif
    *error="Haku native engine currently requires Android API 33 on ARM; layout unqualified";
    return false;
}
bool ReadObject(uintptr_t object,Snapshot* out,std::string* error) {
    return ReadArtDexObject(object, ReadSelf, out, error);
}
bool Initialize(JNIEnv* env,std::string* error) {
    if(!QualifiedPlatform(error))return false;
    std::lock_guard<std::mutex> lock(state_mutex);
    if(calibrated)return true;
    jclass core=env->FindClass("top/niunaijun/blackbox/core/VMCore");
    if(!core){*error="VMCore unavailable for calibration";return false;}
    jmethodID get_bytes=env->GetStaticMethodID(core,"loadEmptyDexBytes","()[B");
    if(!get_bytes){env->DeleteLocalRef(core);*error="Calibration byte API unavailable";return false;}
    auto raw=static_cast<jbyteArray>(env->CallStaticObjectMethod(core,get_bytes));
    env->DeleteLocalRef(core);
    if(env->ExceptionCheck()||!raw){*error="Calibration bytes unavailable";return false;}
    jsize length=env->GetArrayLength(raw);
    if(length<112||size_t(length)>kMaxDexBytes){env->DeleteLocalRef(raw);*error="Calibration bytes invalid length";return false;}
    std::vector<uint8_t> expected(size_t(length),0);
    env->GetByteArrayRegion(raw,0,length,reinterpret_cast<jbyte*>(expected.data()));
    env->DeleteLocalRef(raw);
    if(env->ExceptionCheck()||!ValidateDex(expected,error))return false;
    jlongArray cookies=VmCore::loadEmptyDex(env);
    if(env->ExceptionCheck()||!cookies){*error="Calibration cookies unavailable";return false;}
    jsize count=env->GetArrayLength(cookies);
    if(count!=1){env->DeleteLocalRef(cookies);*error="Calibration requires one standard DexFile";return false;}
    jlong cookie=0;env->GetLongArrayRegion(cookies,0,1,&cookie);env->DeleteLocalRef(cookies);
    Snapshot snapshot;
    if(env->ExceptionCheck()||!cookie||!ReadObject(uintptr_t(cookie),&snapshot,error))return false;
    if(snapshot.bytes!=expected){*error="ART DexFile layout failed known-byte calibration";return false;}
    calibrated=true;return true;
}
bool VerifyAndWrite(const Snapshot& s,const std::string& directory,std::string* error) {
    const art_lkchan::DexFileLoader parser;
    std::vector<std::unique_ptr<const art_lkchan::DexFile>> parsed;
    if(!parser.OpenAll(s.bytes.data(),s.bytes.size(),"haku-original",true,true,error,&parsed)||parsed.size()!=1){
        if(error->empty())*error="DEX parser rejected snapshot";
        return false;
    }
    return WriteArtifact(directory,s,error);
}
// ObjPtr<T> contains one uintptr_t in AOSP 13. Forward the opaque value as a
// one-word aggregate, not a Handle pointer and never dereference/decode it.
struct ArtObjPtr { uintptr_t reference; };
static_assert(sizeof(ArtObjPtr)==sizeof(uintptr_t),"ObjPtr ABI size");
using LoadMethod13=void(*)(void*,const void*,const void*,ArtObjPtr,void*);
LoadMethod13 original_load_method=nullptr;
void OnLoadMethod13(void* linker,const void* dex,const void* method,ArtObjPtr klass,void* target) {
    original_load_method(linker,dex,method,klass,target);
    if(in_hook)return;
    in_hook=true;
    try {
        Snapshot snapshot;std::string error;
        if(!ReadObject(reinterpret_cast<uintptr_t>(dex),&snapshot,&error)||!VerifyAndWrite(snapshot,hook_directory,&error)) {
            ++hook_failures;
            ALOGE("Haku Android13 hook capture rejected: %s",error.c_str());
        }
    }catch(...){++hook_failures;ALOGE("Haku Android13 hook capture failed: allocation or native exception");}
    in_hook=false;
}
}

void CaptureCookie(JNIEnv* env,jlong cookie,jstring directory,jboolean deep) {
    try {
        std::string error;
        if(deep){Throw(env,"Deep ArtMethod recovery is unsupported on Android 13+");return;}
        if(!cookie||!directory){Throw(env,"Missing DexFile cookie or output directory");return;}
        if(!Initialize(env,&error)){Throw(env,error);return;}
        const char* path=env->GetStringUTFChars(directory,nullptr);
        if(!path)return;
        std::string dir(path);env->ReleaseStringUTFChars(directory,path);
        Snapshot snapshot;
        if(!ReadObject(uintptr_t(cookie),&snapshot,&error)||!VerifyAndWrite(snapshot,dir,&error))Throw(env,error);
        else if(hook_failures.load())Throw(env,"One or more requested ART hook captures failed; session is incomplete");
    }catch(...){Throw(env,"Haku native capture allocation/operation failure");}
}
void InstallAndroid13Hook(JNIEnv* env,jstring directory) {
    try {
        std::string error;
        if(!directory||!Initialize(env,&error)){Throw(env,error.empty()?"Missing hook directory":error);return;}
        const char* path=env->GetStringUTFChars(directory,nullptr);if(!path)return;
        std::string dir(path);env->ReleaseStringUTFChars(directory,path);
        std::lock_guard<std::mutex> lock(state_mutex);
        if(hook_installed){
            if(dir!=hook_directory)Throw(env,"Hook already bound to another session directory");
            return;
        }
        const char* symbol="_ZN3art11ClassLinker10LoadMethodERKNS_7DexFileERKNS_13ClassAccessor6MethodENS_6ObjPtrINS_6mirror5ClassEEEPNS_9ArtMethodE";
        void* address=DobbySymbolResolver("libart.so",symbol);
        if(!address){Throw(env,"Android 13 ObjPtr LoadMethod symbol unavailable; cookie engine remains usable");return;}
        hook_directory=dir;
        int status=DobbyHook(address,reinterpret_cast<void*>(OnLoadMethod13),reinterpret_cast<void**>(&original_load_method));
        if(status!=0||!original_load_method){hook_directory.clear();Throw(env,"Android 13 LoadMethod hook registration failed");return;}
        hook_installed=true;
    }catch(...){Throw(env,"Haku native hook setup failure");}
}
}
