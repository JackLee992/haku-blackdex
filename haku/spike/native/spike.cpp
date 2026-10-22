#include <jni.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <string>
#include <sys/uio.h>
#include <unistd.h>
#include <vector>
#include "SafeDex.h"
#include "ArtDexLayout.h"
namespace {
std::string quote(const std::string& text){std::string out="\"";for(unsigned char ch:text){if(ch=='"'||ch=='\\'){out+='\\';out+=char(ch);}else if(ch>=32&&ch<127)out+=char(ch);else out+='?';}return out+'"';}
jstring result(JNIEnv* env,const std::string& value){return env->NewStringUTF(value.c_str());}
jstring failure(JNIEnv* env,const std::string& error){if(env->ExceptionCheck())env->ExceptionClear();return result(env,"{\"passed\":false,\"error\":"+quote(error)+"}");}
thread_local int read_errno=0;
thread_local ssize_t read_returned=0;
thread_local size_t read_requested=0;
bool read_exact(uintptr_t address,void* destination,size_t size){
 read_errno=0;read_returned=0;read_requested=size;
 if(!address||size>haku::kMaxDexBytes||address>std::numeric_limits<uintptr_t>::max()-size)return false;
 size_t completed=0;while(completed<size){iovec local{static_cast<uint8_t*>(destination)+completed,size-completed};iovec remote{reinterpret_cast<void*>(address+completed),size-completed};ssize_t n=process_vm_readv(getpid(),&local,1,&remote,1,0);if(n<0&&errno==EINTR)continue;if(n<=0){read_errno=n<0?errno:0;read_returned=n;return false;}completed+=size_t(n);}return true;
}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_jacklee_haku_artspike_SpikeActivity_nativeCapture(JNIEnv* env,jclass,jobject dex,jbyteArray expected,jstring directory){
 try {
  if(sizeof(uintptr_t)!=8)return failure(env,"requires_arm64_pointer_width");
  jsize expected_size=env->GetArrayLength(expected);if(expected_size<=8192||size_t(expected_size)>haku::kMaxDexBytes)return failure(env,"invalid_owned_fixture_size");
  std::vector<uint8_t> bytes(static_cast<size_t>(expected_size));env->GetByteArrayRegion(expected,0,expected_size,reinterpret_cast<jbyte*>(bytes.data()));if(env->ExceptionCheck())return failure(env,"expected_bytes_unavailable");
  std::string error;if(!haku::ValidateDex(bytes,&error))return failure(env,"owned_fixture_invalid:"+error);
  jclass cls=env->GetObjectClass(dex);jfieldID field=env->GetFieldID(cls,"mCookie","Ljava/lang/Object;");if(!field||env->ExceptionCheck())return failure(env,"cookie_field_unavailable");
  jobject value=env->GetObjectField(dex,field);jclass longs=env->FindClass("[J");if(!value||!longs||!env->IsInstanceOf(value,longs))return failure(env,"cookie_is_not_long_array");jlongArray cookie=static_cast<jlongArray>(value);jsize length=env->GetArrayLength(cookie);if(length<2||length>64)return failure(env,"invalid_cookie_length");
  std::vector<jlong> entries(static_cast<size_t>(length));env->GetLongArrayRegion(cookie,0,length,entries.data());if(env->ExceptionCheck())return failure(env,"cookie_read_failed");
  const char* raw=env->GetStringUTFChars(directory,nullptr);if(!raw)return failure(env,"output_path_unavailable");std::string output(raw);env->ReleaseStringUTFChars(directory,raw);
  std::string expected_sha=haku::Sha256(bytes);bool matched=false,written=false;size_t valid=0;std::ostringstream observations;observations<<'[';
  for(jsize i=1;i<length;i++){
   if(i>1)observations<<',';haku::Snapshot snapshot;std::string detail;bool readable=haku::ReadArtDexObject(static_cast<uintptr_t>(entries[size_t(i)]),read_exact,&snapshot,&detail);int failure_errno=read_errno;ssize_t failure_returned=read_returned;size_t failure_requested=read_requested;bool equal=readable&&snapshot.bytes==bytes&&snapshot.sha256==expected_sha;bool published=false;
   if(equal){matched=true;published=haku::WriteArtifact(output,snapshot,&detail);written|=published;}if(readable)++valid;
   observations<<"{\"cookieIndex\":"<<i<<",\"layoutAndDexValid\":"<<(readable?"true":"false")<<",\"originalBytesEqual\":"<<(equal?"true":"false")<<",\"published\":"<<(published?"true":"false")<<",\"bytes\":"<<snapshot.bytes.size()<<",\"sha256\":"<<quote(snapshot.sha256)<<",\"readerErrno\":"<<failure_errno<<",\"readerReturned\":"<<failure_returned<<",\"readerRequested\":"<<failure_requested<<",\"error\":"<<quote(detail)<<'}';
  }
  observations<<']';std::ostringstream json;json<<"{\"passed\":"<<(matched&&written?"true":"false")<<",\"reader\":\"process_vm_readv exact own-process reads\",\"layout\":\"candidate API33 arm64 vptr/begin_/size_ calibrated against owned original\",\"cookieLength\":"<<length<<",\"skippedCookieIndex0\":true,\"validDexObjects\":"<<valid<<",\"expectedSha256\":"<<quote(expected_sha)<<",\"observations\":"<<observations.str()<<'}';return result(env,json.str());
 }catch(const std::exception& error){return failure(env,std::string("native_exception:")+error.what());}catch(...){return failure(env,"native_exception_unknown");}
}
