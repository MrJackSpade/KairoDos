// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdio>
#include <chrono>
#include <dlfcn.h>
#include "staging_bridge.h"
static auto started=std::chrono::steady_clock::now();
static uint64_t translated=0,executed=0;
static int poll(){return std::chrono::steady_clock::now()-started<std::chrono::seconds(120);}
static void cpu(int,int,uint64_t t,uint64_t e){translated=t;executed=e;}
int main(int argc,char**argv){
 if(argc!=5)return 2;
 auto lib=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!lib){puts(dlerror());return 3;}
 auto run=reinterpret_cast<decltype(&kairo_staging_run)>(dlsym(lib,"kairo_staging_run"));if(!run)return 4;
 KairoStagingCallbacks cb{};cb.version=2;cb.poll=poll;cb.cpu_telemetry=cpu;
 const int result=run(argv[2],argv[3],argv[4],&cb);
 printf("CORE_RESULT=%d translated=%llu executed=%llu\n",result,(unsigned long long)translated,(unsigned long long)executed);return result;
}
