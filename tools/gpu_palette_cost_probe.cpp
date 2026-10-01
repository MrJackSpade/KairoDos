// SPDX-License-Identifier: GPL-2.0-or-later
// Offscreen feasibility probe only: no emulator, window or app changes.
#include "kairo/gpu_frame_queue.h"
#include <cstdio>
#include <vector>
#include <ctime>
#include <cstdlib>
static uint64_t ns(clockid_t id=CLOCK_MONOTONIC) {
    timespec t{}; clock_gettime(id,&t); return uint64_t(t.tv_sec)*1000000000+t.tv_nsec;
}
static GLuint shader(GLenum type,const char* source) {
    GLuint s=glCreateShader(type); glShaderSource(s,1,&source,nullptr); glCompileShader(s);
    GLint ok=0; glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[4096]{}; glGetShaderInfoLog(s,sizeof(log),nullptr,log); fprintf(stderr,"%s\n",log); std::exit(2);}
    return s;
}
int main() {
    kairo::GpuFrameQueue queue;
    if(!queue.create(3,0)){fprintf(stderr,"EGL creation failed\n");return 2;}
    printf("{\"renderer\":\"%s\",\"runs\":[\n",glGetString(GL_RENDERER));
    GLuint vs=shader(GL_VERTEX_SHADER,R"(#version 300 es
    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);})");
    GLuint fs=shader(GL_FRAGMENT_SHADER,R"(#version 300 es
    precision highp float; precision highp usampler2D;
    uniform usampler2D indices; uniform sampler2D palette; uniform int rowPalette;
    out vec4 color;
    void main(){ivec2 p=ivec2(gl_FragCoord.xy);uint i=texelFetch(indices,p,0).r;
    color=texelFetch(palette,ivec2(int(i),rowPalette==0?0:p.y),0);})");
    GLuint program=glCreateProgram(); glAttachShader(program,vs); glAttachShader(program,fs); glLinkProgram(program);
    GLint linked=0; glGetProgramiv(program,GL_LINK_STATUS,&linked); if(!linked)return 3;
    GLuint tex[2]{}; glGenTextures(2,tex);
    int run=0;
    for(int width : {320,640}) for(bool perRow : {false,true}) {
        const int height=width==320?200:480, rows=perRow?height:1;
        std::vector<uint8_t> indices(width*height),palette(256*rows*4),pixels(width*height*4);
        for(int y=0;y<rows;++y)for(int i=0;i<256;++i){auto p=(y*256+i)*4;palette[p]=i;palette[p+1]=y;palette[p+2]=i^y;palette[p+3]=255;}
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,tex[0]);
        glTexImage2D(GL_TEXTURE_2D,0,GL_R8UI,width,height,0,GL_RED_INTEGER,GL_UNSIGNED_BYTE,nullptr);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,tex[1]);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,256,rows,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glUseProgram(program);glUniform1i(glGetUniformLocation(program,"indices"),0);glUniform1i(glGetUniformLocation(program,"palette"),1);
        glUniform1i(glGetUniformLocation(program,"rowPalette"),perRow);
        glViewport(0,0,width,height);glDisable(GL_BLEND);glDisable(GL_DITHER);glDisable(GL_DEPTH_TEST);glPixelStorei(GL_UNPACK_ALIGNMENT,1);
        uint64_t upload=0,snapshot=0,submit=0,cpu=0,complete=0;
        for(int frame=0;frame<140;++frame) {
            for(size_t i=0;i<indices.size();++i)indices[i]=uint8_t(i+frame);
            glFinish();
            const auto start=ns(),cpuStart=ns(CLOCK_THREAD_CPUTIME_ID);
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,tex[0]);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,width,height,GL_RED_INTEGER,GL_UNSIGNED_BYTE,indices.data());
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,tex[1]);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,rows,GL_RGBA,GL_UNSIGNED_BYTE,palette.data());
            const auto uploaded=ns();
            glBindFramebuffer(GL_FRAMEBUFFER,queue.framebuffer());glDrawArrays(GL_TRIANGLES,0,3);
            const auto drawn=ns();queue.publish(width,height,false);const auto submitted=ns();
            const auto cpuEnd=ns(CLOCK_THREAD_CPUTIME_ID);glFinish();const auto completed=ns();
            if(glGetError()!=GL_NO_ERROR){fprintf(stderr,"GL error\n");return 4;}
            if(frame>=20){upload+=uploaded-start;snapshot+=submitted-drawn;submit+=submitted-start;cpu+=cpuEnd-cpuStart;complete+=completed-start;}
        }
        glBindFramebuffer(GL_FRAMEBUFFER,queue.framebuffer());glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());
        for(int y=0;y<height;++y)for(int x=0;x<width;++x){const auto i=indices[y*width+x];const auto row=perRow?y:0;const auto p=(y*width+x)*4;
            if(pixels[p]!=i||pixels[p+1]!=uint8_t(row)||pixels[p+2]!=uint8_t(i^row)||pixels[p+3]!=255){fprintf(stderr,"pixel mismatch %d %d\n",x,y);return 5;}}
        printf("%s{\"width\":%d,\"height\":%d,\"paletteRows\":%d,\"iterations\":120,\"uploadBytesPerIteration\":%zu,\"uploadWallNs\":%llu,\"snapshotWallNs\":%llu,\"submitWallNs\":%llu,\"submitCpuNs\":%llu,\"completionWallNs\":%llu,\"verifiedPixels\":%d}\n",
            run++?",":"",width,height,rows,indices.size()+palette.size(),(unsigned long long)upload,(unsigned long long)snapshot,(unsigned long long)submit,(unsigned long long)cpu,(unsigned long long)complete,width*height);
    }
    uint64_t clockWall=0,clockCpu=0;
    for(int i=0;i<120;++i){
        const auto start=ns(),c0=ns(CLOCK_THREAD_CPUTIME_ID);
        const auto uploaded=ns(),drawn=ns(),submitted=ns(),c1=ns(CLOCK_THREAD_CPUTIME_ID),completed=ns();
        clockWall+=completed-start;clockCpu+=c1-c0;
        if(uploaded>drawn || drawn>submitted)return 6;
    }
    printf("],\"clockOnly\":{\"iterations\":120,\"wallNs\":%llu,\"cpuNs\":%llu}}\n",(unsigned long long)clockWall,(unsigned long long)clockCpu);
    glDeleteTextures(2,tex);glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs);queue.destroy();
}
