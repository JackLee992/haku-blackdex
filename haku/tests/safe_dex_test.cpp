// Controlled SafeDex contract tests; production implementation is linked as a separate object.
#include "SafeDex.h"
#include "ArtDexLayout.h"
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
namespace fs=std::filesystem;
static int checks=0;
static void require(bool condition,const std::string& message){if(!condition)throw std::runtime_error(message);++checks;}
static std::vector<uint8_t> read_file(const fs::path& path){std::ifstream input(path,std::ios::binary);if(!input)throw std::runtime_error("fixture read failed");return std::vector<uint8_t>((std::istreambuf_iterator<char>(input)),{});}
static void raw_write(const fs::path& path,const std::vector<uint8_t>& bytes){std::ofstream output(path,std::ios::binary);output.write(reinterpret_cast<const char*>(bytes.data()),bytes.size());if(!output)throw std::runtime_error("test write failed");}
static std::atomic<int> write_mode{0},read_mode{0},fsync_mode{0},write_calls{0},read_calls{0};
extern "C" ssize_t haku_test_write(int fd,const void* data,size_t count){int call=write_calls++;switch(write_mode.load()){case 1:count=std::min(count,size_t(7));break;case 2:if(call==0){errno=EINTR;return -1;}break;case 3:return 0;case 4:errno=ENOSPC;return -1;}return ::write(fd,data,count);}
extern "C" ssize_t haku_test_pread(int fd,void* data,size_t count,off_t offset){int call=read_calls++;switch(read_mode.load()){case 1:count=std::min(count,size_t(11));break;case 2:if(call==0){errno=EINTR;return -1;}break;case 3:return 0;}return ::pread(fd,data,count,offset);}
extern "C" int haku_test_fsync(int fd){if(fsync_mode.load()){errno=EIO;return -1;}return ::fsync(fd);}
#if defined(__APPLE__)
static std::atomic<int> rename_mode{0};
extern "C" int haku_test_renameatx_np(int olddir,const char* oldname,int newdir,const char* newname,unsigned int flags){if(rename_mode.load()){errno=EACCES;return -1;}return ::renameatx_np(olddir,oldname,newdir,newname,flags);}
#endif
static void reset_io(){write_mode=0;read_mode=0;fsync_mode=0;write_calls=0;read_calls=0;}
static size_t temporary_files(const fs::path& directory){size_t count=0;for(const auto& e:fs::directory_iterator(directory))if(e.path().extension()==".tmp")++count;return count;}
int main(int argc,char** argv){try{
 if(argc!=5)throw std::runtime_error("usage: safe_dex_test MAIN DISTINCT MAIN_SHA DISTINCT_SHA");
 auto original=read_file(argv[1]),distinct=read_file(argv[2]);std::string error;
 require(original.size()>8192,"genuine code DEX must exceed8KiB");require(original.size()==distinct.size(),"identity variants must have equal length");require(original!=distinct,"variants contain distinct bytes");
 require(haku::Sha256({})=="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","independent empty SHA256 vector");require(haku::Sha256({'a','b','c'})=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","independent abc SHA256 vector");
 require(haku::Sha256(original)==argv[3],"payload SHA256 matches Python hashlib");require(haku::Sha256(distinct)==argv[4],"equal-length SHA256 matches Python hashlib");require(haku::Sha256(original)!=haku::Sha256(distinct),"equal-length identity is content-derived");
 require(haku::ValidateDex(original,&error),"genuine D8 DEX accepted: "+error);require(haku::ValidateDex(distinct,&error),"valid changed literal DEX accepted: "+error);
 require(!haku::ValidateDex({},&error),"empty rejected");require(!haku::ValidateDex(std::vector<uint8_t>(4096),&error),"4KiB zero page rejected");require(!haku::ValidateDex(std::vector<uint8_t>(original.begin(),original.begin()+4096),&error),"actual genuine4096 prefix rejected");
 auto damaged=original;damaged.back()^=1;require(!haku::ValidateDex(damaged,&error),"checksum corruption rejected");damaged=original;damaged[0]='x';require(!haku::ValidateDex(damaged,&error),"unsupported magic rejected");require(damaged[0]=='x',"validator preserves original magic bytes");
 uintptr_t base=0x100000;int memory_calls=0;auto reader=[&](uintptr_t address,void* output,size_t count){++memory_calls;if(address<base||count>original.size()||address-base>original.size()-count)return false;memcpy(output,original.data()+address-base,count);return true;};
 haku::Snapshot snapshot;require(haku::ReadDex(base,original.size(),reader,&snapshot,&error),"bounded complete memory snapshot accepted: "+error);require(snapshot.bytes==original,"snapshot preserves all original bytes");require(snapshot.sha256==argv[3],"snapshot immutable identity");require(memory_calls==2,"snapshot observes header then complete allocation");
 std::fill(original.begin(),original.end(),0);require(snapshot.bytes==read_file(argv[1]),"snapshot survives target memory overwrite");original=read_file(argv[1]);
 auto reject=[&](uintptr_t start,size_t size,const haku::ReadMemory& r,const std::string& label){haku::Snapshot out=snapshot;require(!haku::ReadDex(start,size,r,&out,&error),label);require(out.bytes.empty()&&out.sha256.empty(),label+" leaves no successful result");};
 reject(0,original.size(),reader,"null address rejected");reject(base,111,reader,"short allocation rejected");reject(base,4096,reader,"prefix allocation mismatch rejected");reject(std::numeric_limits<uintptr_t>::max()-10,original.size(),reader,"address range overflow rejected");reject(base,haku::kMaxDexBytes+1,reader,"oversized allocation rejected");reject(base,original.size(),[](uintptr_t,void*,size_t){return false;},"unreadable header rejected");
 int n=0;reject(base,original.size(),[&](uintptr_t a,void* b,size_t len){if(++n==2)return false;return reader(a,b,len);},"incomplete full read rejected");
 n=0;reject(base,original.size(),[&](uintptr_t a,void* b,size_t len){bool ok=reader(a,b,len);if(++n==2&&ok)static_cast<uint8_t*>(b)[8]^=1;return ok;},"header mutation between reads rejected");
 uintptr_t objectBase=0x300000;uintptr_t objectFields[3]={0xDEADBEEF,base,original.size()};
 auto objectReader=[&](uintptr_t address,void* output,size_t count){if(address>=objectBase&&count<=sizeof(objectFields)&&address-objectBase<=sizeof(objectFields)-count){memcpy(output,reinterpret_cast<const uint8_t*>(objectFields)+address-objectBase,count);return true;}return reader(address,output,count);};
 haku::Snapshot layoutSnapshot;require(haku::ReadArtDexObject(objectBase,objectReader,&layoutSnapshot,&error),"synthetic candidate ART prefix reads begin/size slots");require(layoutSnapshot.bytes==original&&layoutSnapshot.sha256==argv[3],"ART prefix delegates to complete verified original snapshot");
 require(!haku::ReadArtDexObject(0,objectReader,&layoutSnapshot,&error),"null ART object rejected");require(!haku::ReadArtDexObject(std::numeric_limits<uintptr_t>::max()-8,objectReader,&layoutSnapshot,&error),"overflow ART object rejected");require(!haku::ReadArtDexObject(objectBase,[](uintptr_t,void*,size_t){return false;},&layoutSnapshot,&error),"unreadable ART object fields rejected");objectFields[2]=4096;require(!haku::ReadArtDexObject(objectBase,objectReader,&layoutSnapshot,&error),"ART object truncated declared allocation rejected");objectFields[2]=original.size();
 char pattern[]="/tmp/haku-safedex-XXXXXX";char* temporary=mkdtemp(pattern);if(!temporary)throw std::runtime_error("mkdtemp");fs::path directory=temporary;
 reset_io();require(haku::WriteArtifact(directory.string(),snapshot,&error),"full artifact publish: "+error);fs::path published=directory/(snapshot.sha256+".dex");require(read_file(published)==snapshot.bytes,"published artifact is full and byte-identical");struct stat st{};require(stat(published.c_str(),&st)==0&&(st.st_mode&0777)==0600,"published file private0600");require(temporary_files(directory)==0,"successful publish has no temporary files");require(haku::WriteArtifact(directory.string(),snapshot,&error),"idempotent identical artifact verifies");
 haku::Snapshot second{distinct,haku::Sha256(distinct)};require(haku::WriteArtifact(directory.string(),second,&error),"equal-length distinct artifact publishes independently");require(fs::exists(directory/(second.sha256+".dex")),"second identity retained separately");
 auto run_io=[&](int wm,int rm,int fm,bool success,const std::string& label){fs::path sub=directory/label;fs::create_directory(sub);reset_io();write_mode=wm;read_mode=rm;fsync_mode=fm;bool ok=haku::WriteArtifact(sub.string(),snapshot,&error);reset_io();require(ok==success,label+" result");if(success)require(read_file(sub/(snapshot.sha256+".dex"))==snapshot.bytes,label+" complete bytes");else require(!fs::exists(sub/(snapshot.sha256+".dex")),label+" no false successful artifact");require(temporary_files(sub)==0,label+" no orphan temporary file");};
 run_io(1,0,0,true,"short_writes");run_io(2,0,0,true,"interrupted_write");run_io(0,1,0,true,"short_readback");run_io(0,2,0,true,"interrupted_readback");run_io(3,0,0,false,"zero_write");run_io(4,0,0,false,"disk_full");run_io(0,3,0,false,"failed_readback");run_io(0,0,1,false,"failed_fsync");
 fs::path concurrent=directory/"concurrent";fs::create_directory(concurrent);bool result1=false,result2=false;std::string error1,error2;
 std::thread one([&]{result1=haku::WriteArtifact(concurrent.string(),snapshot,&error1);});std::thread two([&]{result2=haku::WriteArtifact(concurrent.string(),snapshot,&error2);});one.join();two.join();
 require(result1&&result2,"concurrent publishers either win exclusive rename or verify existing bytes");require(read_file(concurrent/(snapshot.sha256+".dex"))==snapshot.bytes,"concurrent same-name publication preserves original bytes");require(temporary_files(concurrent)==0,"concurrent publication cleans both temporary files");
#if defined(__APPLE__)
 fs::path denied=directory/"rename_denied";fs::create_directory(denied);rename_mode=1;bool denied_result=haku::WriteArtifact(denied.string(),snapshot,&error);rename_mode=0;
 require(!denied_result,"no-replace rename permission failure rejects publication");require(error.find("rename_noreplace:errno="+std::to_string(EACCES))==0,"rename failure retains exact stage and errno");require(!fs::exists(denied/(snapshot.sha256+".dex")),"denied publication has no fallback output");require(temporary_files(denied)==0,"denied publication cleans temporary file");
#endif
 auto invalid=snapshot;invalid.sha256=std::string(64,'0');require(!haku::WriteArtifact(directory.string(),invalid,&error),"declared identity mismatch rejected");
 fs::path conflict=directory/"conflict";fs::create_directory(conflict);auto corrupt=snapshot.bytes;corrupt.back()^=1;raw_write(conflict/(snapshot.sha256+".dex"),corrupt);require(!haku::WriteArtifact(conflict.string(),snapshot,&error),"existing equal-length corrupt file rejected");require(read_file(conflict/(snapshot.sha256+".dex"))==corrupt,"immutable conflicting artifact preserved");
 fs::path linked=directory/"linked";fs::create_directory(linked);fs::create_symlink(published,linked/(snapshot.sha256+".dex"));require(!haku::WriteArtifact(linked.string(),snapshot,&error),"existing symlink target rejected");fs::create_directory_symlink(directory,directory.parent_path()/(directory.filename().string()+"-link"));auto symlinkDir=directory.parent_path()/(directory.filename().string()+"-link");require(!haku::WriteArtifact(symlinkDir.string(),snapshot,&error),"symlink output directory rejected");fs::remove(symlinkDir);fs::remove_all(directory);
 std::cout<<"SafeDex independent contracts: "<<checks<<" assertions passed\n";return 0;
 }catch(const std::exception& error){std::cerr<<"FAIL: "<<error.what()<<" (after "<<checks<<" assertions)\n";return 1;}}
