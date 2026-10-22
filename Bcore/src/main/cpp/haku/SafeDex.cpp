// Copyright 2026 Haku contributors. SPDX-License-Identifier: Apache-2.0
// Original implementation; DEX checks follow the public AOSP format contract.
#include "SafeDex.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <set>
#include <stdio.h>
#if defined(__linux__)
#include <sys/syscall.h>
#endif
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

namespace haku {
namespace {
bool Fail(std::string* error, const char* reason) {
    if (error) *error = reason;
    return false;
}
uint32_t Rol(uint32_t v, unsigned n) { return (v << n) | (v >> (32 - n)); }
uint32_t Ror(uint32_t v, unsigned n) { return (v >> n) | (v << (32 - n)); }
uint32_t Big32(const uint8_t* p) {
    return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3];
}
void PutBig32(uint8_t* p, uint32_t v) {
    for (int i = 3; i >= 0; --i) { p[i] = uint8_t(v); v >>= 8; }
}
// Iterate padded SHA blocks without allocating a second DEX-sized buffer.
template<class F> void Blocks(const uint8_t* p, size_t size, F compress) {
    size_t whole = size / 64;
    for (size_t i = 0; i < whole; ++i) compress(p + i * 64);
    uint8_t tail[128] = {};
    size_t rem = size % 64;
    if (rem) memcpy(tail, p + whole * 64, rem);
    tail[rem] = 0x80;
    size_t padded = rem < 56 ? 64 : 128;
    uint64_t bits = uint64_t(size) * 8;
    for (unsigned i = 0; i < 8; ++i) { tail[padded - 1 - i] = uint8_t(bits); bits >>= 8; }
    compress(tail);
    if (padded == 128) compress(tail + 64);
}
std::array<uint8_t, 20> Sha1(const uint8_t* p, size_t size) {
    uint32_t h[] = {0x67452301u,0xefcdab89u,0x98badcfeu,0x10325476u,0xc3d2e1f0u};
    Blocks(p, size, [&](const uint8_t* b) {
        uint32_t w[80];
        for (unsigned i = 0; i < 16; ++i) w[i] = Big32(b + i * 4);
        for (unsigned i = 16; i < 80; ++i) w[i] = Rol(w[i-3]^w[i-8]^w[i-14]^w[i-16], 1);
        uint32_t a=h[0], c=h[2], d=h[3], e=h[4], bb=h[1];
        for (unsigned i = 0; i < 80; ++i) {
            uint32_t f, k;
            if (i < 20) { f=(bb&c)|(~bb&d); k=0x5a827999u; }
            else if (i < 40) { f=bb^c^d; k=0x6ed9eba1u; }
            else if (i < 60) { f=(bb&c)|(bb&d)|(c&d); k=0x8f1bbcdcu; }
            else { f=bb^c^d; k=0xca62c1d6u; }
            uint32_t t=Rol(a,5)+f+e+k+w[i]; e=d; d=c; c=Rol(bb,30); bb=a; a=t;
        }
        h[0]+=a;h[1]+=bb;h[2]+=c;h[3]+=d;h[4]+=e;
    });
    std::array<uint8_t,20> out{};
    for (unsigned i=0;i<5;++i) PutBig32(out.data()+i*4,h[i]);
    return out;
}
bool Span(uint64_t off, uint64_t count, uint64_t width, size_t size) {
    return count == 0 ? off == 0 : off >= 112 && off <= size && count <= (size-off)/width;
}
bool ReadFd(int fd, std::vector<uint8_t>* bytes) {
    size_t done=0;
    while (done < bytes->size()) {
        ssize_t n=pread(fd, bytes->data()+done, bytes->size()-done, off_t(done));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { if (n == 0) errno = EIO; return false; }
        done += size_t(n);
    }
    return true;
}
bool CheckFd(int fd, const Snapshot& s) {
    struct stat st{};
    if (fstat(fd,&st) != 0) return false;
    if (!S_ISREG(st.st_mode) || st.st_size != off_t(s.bytes.size())) { errno = EINVAL; return false; }
    std::vector<uint8_t> readback(s.bytes.size());
    if (!ReadFd(fd,&readback)) return false;
    if (readback != s.bytes) { errno = EBADMSG; return false; }
    return true;
}
bool FailIo(std::string* error, const char* stage, int saved_errno) {
    if (error) *error = std::string(stage) + ":errno=" + std::to_string(saved_errno) + ":" + strerror(saved_errno);
    return false;
}
int RenameNoReplace(int directory, const char* source, const char* destination) {
#if defined(__APPLE__)
    return renameatx_np(directory, source, directory, destination, RENAME_EXCL);
#elif defined(__linux__) && defined(__NR_renameat2)
    // Android app SELinux can prohibit hard links to app_data_file. Atomic
    // no-replace rename needs no link permission and never clobbers a conflict.
    return int(syscall(__NR_renameat2, directory, source, directory, destination, 1u /* RENAME_NOREPLACE */));
#else
    errno = ENOSYS;
    return -1;
#endif
}

}

uint32_t ReadU32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}
std::string Sha256(const std::vector<uint8_t>& bytes) {
    static const uint32_t k[] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    uint32_t h[]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    Blocks(bytes.data(),bytes.size(),[&](const uint8_t* b) {
        uint32_t w[64];
        for(unsigned i=0;i<16;++i)w[i]=Big32(b+4*i);
        for(unsigned i=16;i<64;++i) {
            uint32_t x=w[i-15],y=w[i-2];
            w[i]=w[i-16]+(Ror(x,7)^Ror(x,18)^(x>>3))+w[i-7]+(Ror(y,17)^Ror(y,19)^(y>>10));
        }
        uint32_t a=h[0],bb=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],hh=h[7];
        for(unsigned i=0;i<64;++i) {
            uint32_t t1=hh+(Ror(e,6)^Ror(e,11)^Ror(e,25))+((e&f)^(~e&g))+k[i]+w[i];
            uint32_t t2=(Ror(a,2)^Ror(a,13)^Ror(a,22))+((a&bb)^(a&c)^(bb&c));
            hh=g;g=f;f=e;e=d+t1;d=c;c=bb;bb=a;a=t1+t2;
        }
        h[0]+=a;h[1]+=bb;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=hh;
    });
    const char hex[]="0123456789abcdef";
    std::string out;out.reserve(64);
    for(uint32_t word:h)for(int shift=28;shift>=0;shift-=4)out.push_back(hex[(word>>shift)&15]);
    return out;
}

bool ValidateDex(const std::vector<uint8_t>& b, std::string* error) {
    if(b.size()<112 || b.size()>kMaxDexBytes)return Fail(error,"invalid_length");
    if(memcmp(b.data(),"dex\n",4) || b[7])return Fail(error,"unsupported_magic_preserved");
    std::string version(reinterpret_cast<const char*>(b.data()+4),3);
    if(version!="035"&&version!="037"&&version!="038"&&version!="039"&&version!="040")return Fail(error,"unsupported_dex_version");
    auto u=[&](size_t off){return ReadU32(b.data()+off);};
    if(u(32)!=b.size()||u(36)!=112||u(40)!=0x12345678)return Fail(error,"header_length_or_endian");
    if(u(44)||u(48))return Fail(error,"unsupported_link_data");
    const uint32_t widths[]={4,4,12,8,8,32};
    for(unsigned i=0;i<6;++i)
        if(!Span(u(60+i*8),u(56+i*8),widths[i],b.size()))return Fail(error,"id_section_bounds");
    uint64_t data=u(108),map=u(52);
    if(data<112||data>b.size()||data%4||u(104)!=b.size()-data||!u(104)||map<data||map>b.size()-4||map%4)
        return Fail(error,"data_or_map_bounds");
    uint64_t n=u(size_t(map));
    if(n<2||n>(b.size()-map-4)/12)return Fail(error,"map_bounds");
    std::set<uint16_t> types; uint64_t prior=0,prior_end=0; bool first=true,header=false,map_entry=false;
    for(uint64_t i=0;i<n;++i) {
        size_t p=size_t(map+4+i*12);uint16_t type=uint16_t(b[p])|uint16_t(b[p+1])<<8;
        uint64_t count=u(p+4),off=u(p+8);
        if(b[p+2]||b[p+3]||!count||!types.insert(type).second||(!first&&off<=prior)||off>=b.size()||off<prior_end)
            return Fail(error,"map_order_overlap_or_duplicate");
        uint64_t width=1;unsigned alignment=1;
        if(type<=8||(type>=0x1000&&type<=0x1003)||type==0x2001||type==0x2006)alignment=4;
        if(off%alignment)return Fail(error,"map_alignment");
        if(type==0){width=112;header=count==1&&off==0;}
        else if(type>=1&&type<=6){
            width=widths[type-1];
            if(count!=u(56+(type-1)*8)||off!=u(60+(type-1)*8))return Fail(error,"map_header_mismatch");
        }else if(type==7||type==8)width=type==7?4:8;
        else if(type==0x1000){width=4+n*12;map_entry=count==1&&off==map;}
        else {
            if(off<data||!((type>=0x1001&&type<=0x1003)||(type>=0x2000&&type<=0x2006)))return Fail(error,"unsupported_map_type");
            if(type>=0x1001&&type<=0x1003)width=4;
            else if(type==0x2000)width=4;
            else if(type==0x2001||type==0x2006)width=16;
            else if(type==0x2002)width=2;
            else if(type==0x2003)width=3;
        }
        if(count>(b.size()-off)/width)return Fail(error,"map_minimum_bounds");
        prior_end=off+count*width;prior=off;first=false;
    }
    if(!header||!map_entry)return Fail(error,"map_missing_required_entry");
    for(unsigned i=0;i<6;++i)if(u(56+i*8)&&!types.count(uint16_t(i+1)))return Fail(error,"map_missing_id_section");
    if(!u(96))return Fail(error,"no_class_definitions");
    auto signature=Sha1(b.data()+32,b.size()-32);
    if(memcmp(signature.data(),b.data()+12,20))return Fail(error,"original_sha1_mismatch");
    uLong adler=adler32(0L,Z_NULL,0);
    adler=adler32(adler,b.data()+12,uInt(b.size()-12));
    if(uint32_t(adler)!=u(8))return Fail(error,"original_adler32_mismatch");
    if(error)error->clear();return true;
}

bool ReadDex(uintptr_t begin,size_t allocation_size,const ReadMemory& reader,Snapshot* out,std::string* error) {
    if(!out)return Fail(error,"missing_output");
    *out=Snapshot{};
    if(!begin||allocation_size<112||allocation_size>kMaxDexBytes||begin>std::numeric_limits<uintptr_t>::max()-allocation_size)
        return Fail(error,"invalid_memory_range");
    uint8_t header[112];
    if(!reader(begin,header,sizeof(header)))return Fail(error,"unreadable_header");
    if(memcmp(header,"dex\n",4)||header[7])return Fail(error,"unsupported_magic_preserved");
    uint32_t file_size=ReadU32(header+32);
    if(file_size!=allocation_size)return Fail(error,"allocation_header_length_mismatch");
    std::vector<uint8_t> bytes(file_size);
    if(!reader(begin,bytes.data(),bytes.size()))return Fail(error,"incomplete_memory_read");
    if(memcmp(header,bytes.data(),sizeof(header)))return Fail(error,"header_changed_during_read");
    if(!ValidateDex(bytes,error))return false;
    out->sha256=Sha256(bytes);out->bytes=std::move(bytes);return true;
}

bool WriteArtifact(const std::string& directory,const Snapshot& s,std::string* error) {
    if(!ValidateDex(s.bytes,error)||s.sha256!=Sha256(s.bytes))return Fail(error,"invalid_snapshot");
    int dir=open(directory.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(dir<0)return FailIo(error,"open_output_directory",errno);
    static std::atomic<unsigned long> sequence{0};
    std::string name=s.sha256+".dex";
    std::string temp=".haku-"+std::to_string(getpid())+"-"+std::to_string(sequence.fetch_add(1))+".tmp";
    int fd=openat(dir,temp.c_str(),O_CREAT|O_EXCL|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
    if(fd<0){int e=errno;close(dir);return FailIo(error,"create_temp",e);}
    const char* stage=nullptr;int failure_errno=0;size_t done=0;
    while(done<s.bytes.size()) {
        ssize_t n=write(fd,s.bytes.data()+done,s.bytes.size()-done);
        if(n<0&&errno==EINTR)continue;
        if(n<=0){stage="write_temp";failure_errno=n<0?errno:EIO;break;}done+=size_t(n);
    }
    if(!stage&&fsync(fd)!=0){stage="fsync_temp";failure_errno=errno;}
    if(!stage&&!CheckFd(fd,s)){stage="verify_temp";failure_errno=errno;}
    if(close(fd)!=0&&!stage){stage="close_temp";failure_errno=errno;}
    if(!stage&&RenameNoReplace(dir,temp.c_str(),name.c_str())!=0) {
        if(errno==EEXIST){
            int existing=openat(dir,name.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
            if(existing<0){stage="open_existing";failure_errno=errno;}
            else {
                if(!CheckFd(existing,s)){stage="verify_existing";failure_errno=errno;}
                if(close(existing)!=0&&!stage){stage="close_existing";failure_errno=errno;}
            }
        }else{stage="rename_noreplace";failure_errno=errno;}
    }
    // A successful rename has already removed the temporary name. EEXIST
    // leaves it behind; remove only our temp, never a conflicting final file.
    if(unlinkat(dir,temp.c_str(),0)!=0&&errno!=ENOENT&&!stage){stage="cleanup_temp";failure_errno=errno;}
    if(!stage&&fsync(dir)!=0){stage="fsync_directory";failure_errno=errno;}
    if(close(dir)!=0&&!stage){stage="close_directory";failure_errno=errno;}
    if(stage)return FailIo(error,stage,failure_errno);
    if(error)error->clear();
    return true;
}
}
