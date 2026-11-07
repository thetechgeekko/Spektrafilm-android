/*
 * Spektrafilm for Android — sfraw::readAllFromFd host regression (#287). GPLv3.
 *
 * The RAW fd decode now reads its source with fstat + 1 MiB read(2) instead of
 * 64 KiB fread chunks. This pins what must not change: exact bytes, start at the
 * current file offset, the 64 MiB limit edge (equal accepted, one more refused,
 * also on a descriptor without a stat size), cancellation, and a single buffer
 * allocation when the size is known.
 */
#include "fd_read_all.h"
#include <sys/wait.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
int fails=0;
#define CHK(c,m) do{ if(!(c)){std::printf("FAIL %s\n",m);fails++;} else std::printf("ok %s\n",m);}while(0)
static std::string mk(size_t n){ std::string tmpl = std::string(std::getenv("TMPDIR") ? std::getenv("TMPDIR") : "/tmp") + "/fdrXXXXXX"; std::vector<char> pv(tmpl.begin(), tmpl.end()); pv.push_back(0); char* p = pv.data(); int fd=mkstemp(p); std::vector<uint8_t> b(n); for(size_t i=0;i<n;i++) b[i]=uint8_t(i*131+7); size_t w=0; while(w<n){ssize_t k=write(fd,b.data()+w,n-w); w+=k;} close(fd); return std::string(p);}
int main(){
  auto never=[]{return false;};
  for (size_t n : {size_t(0), size_t(1), size_t(1<<20), size_t((1<<20)+1), size_t(5*(1<<20)+12345)}) {
    auto p=mk(n); int fd=open(p.c_str(),O_RDONLY); std::vector<uint8_t> v;
    auto st=sfraw::readAllFromFd(fd, 64u<<20, v, never);
    bool same = v.size()==n; for(size_t i=0;same&&i<n;i++) same = v[i]==uint8_t(i*131+7);
    char m[64]; std::snprintf(m,64,"size %zu read exactly, one alloc=%d",n,(int)(v.capacity()<=n+1||n==0));
    CHK(st==sfraw::FdReadStatus::Ok && same, m);
    close(fd); unlink(p.c_str());
  }
  { auto p=mk(1000); int fd=open(p.c_str(),O_RDONLY); std::vector<uint8_t> v;
    CHK(sfraw::readAllFromFd(fd,1000,v,never)==sfraw::FdReadStatus::Ok && v.size()==1000,"exactly at limit accepted"); close(fd);
    fd=open(p.c_str(),O_RDONLY); CHK(sfraw::readAllFromFd(fd,999,v,never)==sfraw::FdReadStatus::TooLarge,"one over limit refused (stat)"); close(fd);
    fd=open(p.c_str(),O_RDONLY); lseek(fd,100,SEEK_SET); CHK(sfraw::readAllFromFd(fd,900,v,never)==sfraw::FdReadStatus::Ok && v.size()==900 && v[0]==uint8_t(100*131+7),"starts at current offset"); close(fd);
    unlink(p.c_str()); }
  { int pf[2]; if (pipe(pf) != 0) return 2; std::vector<uint8_t> src(3*(1<<20)+5,7); if(fork()==0){ close(pf[0]); size_t w=0; while(w<src.size()) w+=write(pf[1],src.data()+w,src.size()-w); _exit(0);} close(pf[1]);
    std::vector<uint8_t> v; CHK(sfraw::readAllFromFd(pf[0],64u<<20,v,never)==sfraw::FdReadStatus::Ok && v.size()==src.size(),"pipe (no stat size) reads to EOF"); close(pf[0]); wait(nullptr); }
  { int pf[2]; if (pipe(pf) != 0) return 2; if(fork()==0){ close(pf[0]); std::vector<uint8_t> b(2000,1); if (write(pf[1],b.data(),b.size()) < 0) _exit(1); _exit(0);} close(pf[1]);
    std::vector<uint8_t> v; CHK(sfraw::readAllFromFd(pf[0],1000,v,never)==sfraw::FdReadStatus::TooLarge,"pipe over limit refused"); close(pf[0]); wait(nullptr); }
  { auto p=mk(4<<20); int fd=open(p.c_str(),O_RDONLY); std::vector<uint8_t> v; int calls=0;
    CHK(sfraw::readAllFromFd(fd,64u<<20,v,[&]{return ++calls>2;})==sfraw::FdReadStatus::Cancelled,"cancellation honoured mid-read"); close(fd); unlink(p.c_str()); }
  { std::vector<uint8_t> v; CHK(sfraw::readAllFromFd(-1,10,v,never)==sfraw::FdReadStatus::IoError,"bad fd -> IoError"); }
  std::printf(fails?"FAIL\n":"PASS\n"); return fails;
}
