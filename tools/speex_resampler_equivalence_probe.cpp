// SPDX-License-Identifier: GPL-2.0-or-later
#include <speex/speex_resampler.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <algorithm>
#include <cstdint>
// Build twice, with and without -DKAIRO_RESAMPLER_INT16. Pass two distinct
// shared libraries (reference, candidate); both must export the Speex API.
#ifdef KAIRO_RESAMPLER_INT16
using Sample = int16_t;
#define PROCESS speex_resampler_process_interleaved_int
#define PROCESS_NAME "speex_resampler_process_interleaved_int"
#define SAMPLE_NAME "int16"
#else
using Sample = float;
#define PROCESS speex_resampler_process_interleaved_float
#define PROCESS_NAME "speex_resampler_process_interleaved_float"
#define SAMPLE_NAME "float"
#endif
struct Api {
 void* lib;
 decltype(&speex_resampler_init) init;
 decltype(&speex_resampler_destroy) destroy;
 decltype(&PROCESS) process;
 decltype(&speex_resampler_reset_mem) reset;
 decltype(&speex_resampler_set_rate) rate;
 decltype(&speex_resampler_skip_zeros) skip;
 Api(const char* path) {
  lib=dlopen(path,RTLD_NOW|RTLD_LOCAL);if(!lib){puts(dlerror());exit(2);}
#define GET(field,name) field=reinterpret_cast<decltype(field)>(dlsym(lib,name));if(!field)exit(3)
  GET(init,"speex_resampler_init");GET(destroy,"speex_resampler_destroy");
  GET(process,PROCESS_NAME);GET(reset,"speex_resampler_reset_mem");
  GET(rate,"speex_resampler_set_rate");GET(skip,"speex_resampler_skip_zeros");
#undef GET
 }
};
int main(int argc,char**argv){
 if(argc!=3)return 1;Api a(argv[1]),b(argv[2]);if(a.lib==b.lib||a.init==b.init)return 10;uint64_t samples=0;unsigned cases=0;
 const unsigned rates[][2]={{8000,48000},{11025,48000},{22050,48000},{32000,44100},{44100,48000},{48000,44100},{49716,48000},{96000,48000},{48000,48000},{48000,8000}};
 uint32_t rng=0xa4510239;
 for(unsigned q=0;q<=10;++q)for(unsigned channels:{1u,2u})for(auto &r:rates){
  int ea=0,eb=0;auto sa=a.init(channels,r[0],r[1],q,&ea);auto sb=b.init(channels,r[0],r[1],q,&eb);
  if(!sa||!sb||ea!=eb)return 4;
  std::vector<Sample> input(257*channels),oa(4096*channels),ob(4096*channels);
  for(unsigned batch=0;batch<30;++batch){
   if(batch==10){if(a.reset(sa)!=b.reset(sb)||a.skip(sa)!=b.skip(sb))return 5;}
   if(batch==20){if(a.rate(sa,r[1],r[0])!=b.rate(sb,r[1],r[0]))return 6;}
   for(auto &v:input){rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;v=(int32_t(rng)>>8)/256.0f;}
   if(batch%7==0)std::fill(input.begin(),input.end(),0.0f);
   if(batch%7==1){std::fill(input.begin(),input.end(),0.0f);input[0]=32767.0f;}
   unsigned available=1+batch*37%257,used=0;
   do {
    spx_uint32_t ia=available-used,ib=ia,na=1+batch*137%4096,nb=na;
    auto ra=a.process(sa,input.data()+used*channels,&ia,oa.data(),&na);
    auto rb=b.process(sb,input.data()+used*channels,&ib,ob.data(),&nb);
    if(ra!=rb||ia!=ib||na!=nb){printf("LENGTH FAIL q=%u rate=%u/%u\n",q,r[0],r[1]);return 7;}
    if(memcmp(oa.data(),ob.data(),na*channels*sizeof(Sample))){
     for(unsigned i=0;i<na*channels;++i)if(memcmp(&oa[i],&ob[i],sizeof(Sample))){printf("SAMPLE FAIL q=%u channels=%u rate=%u/%u batch=%u i=%u a=%.9g b=%.9g\n",q,channels,r[0],r[1],batch,i,double(oa[i]),double(ob[i]));break;}
     return 8;
    }
    samples+=na*channels;used+=ia;if(!ia&&!na)return 9;
   }while(used<available);
  }
  a.destroy(sa);b.destroy(sb);++cases;
 }
 printf("%u resampler cases, %llu bit-identical " SAMPLE_NAME " samples; qualities 0-10, mono/stereo, partial buffers, reset, skip and rate changes\n",cases,(unsigned long long)samples);
}
