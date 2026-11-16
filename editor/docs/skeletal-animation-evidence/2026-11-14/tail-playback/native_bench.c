// Copyright 2020-2026 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <spine/spine.h>
#include <spine/extension.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stddef.h>
#include <math.h>
static size_t live_bytes,allocations;
typedef union {max_align_t align;size_t size;} Header;
static void* tracked_malloc(size_t n){Header*h=malloc(sizeof(Header)+n);if(!h)abort();h->size=n;live_bytes+=n;allocations++;return h+1;}
static void tracked_free(void*p){if(p){Header*h=(Header*)p-1;live_bytes-=h->size;free(h);}}
static void* tracked_realloc(void*p,size_t n){if(!p)return tracked_malloc(n);Header*h=(Header*)p-1;size_t old=h->size;h=realloc(h,sizeof(Header)+n);if(!h)abort();h->size=n;live_bytes+=n;live_bytes-=old;allocations++;return h+1;}
void _spAtlasPage_createTexture(spAtlasPage*self,const char*path){(void)self;(void)path;}
void _spAtlasPage_disposeTexture(spAtlasPage*self){(void)self;}
char* _spUtil_readFile(const char*path,int*length){return _spReadFile(path,length);}
static double now_ms(){struct timespec t;clock_gettime(CLOCK_MONOTONIC_RAW,&t);return t.tv_sec*1000.0+t.tv_nsec/1e6;}
static int cmp(const void*a,const void*b){double x=*(const double*)a,y=*(const double*)b;return(x>y)-(x<y);}
static volatile double checksum;
static void pose(spSkeleton*s,spAnimationState*state){spAnimationState_update(state,1.f/60);spAnimationState_apply(state,s);spSkeleton_update(s,1.f/60);spSkeleton_updateWorldTransform(s,SP_PHYSICS_UPDATE);}
static void vertices(spSkeleton*s,float*buffer){for(int i=0;i<s->slotsCount;i++){spSlot*slot=s->drawOrder[i];spAttachment*a=slot->attachment;if(!a)continue;int n=0;if(a->type==SP_ATTACHMENT_REGION){n=8;spRegionAttachment_computeWorldVertices((spRegionAttachment*)a,slot,buffer,0,2);}else if(a->type==SP_ATTACHMENT_MESH){n=((spVertexAttachment*)a)->worldVerticesLength;spVertexAttachment_computeWorldVertices((spVertexAttachment*)a,slot,0,n,buffer,0,2);}if(n)checksum+=buffer[n-2];}}
int main(int argc,char**argv){if(argc<4)return 2;_spSetMalloc(tracked_malloc);_spSetRealloc(tracked_realloc);_spSetFree(tracked_free);spAtlas*atlas=spAtlas_createFromFile(argv[1],0);if(!atlas){fprintf(stderr,"atlas failed\n");return 2;}spSkeletonJson*reader=spSkeletonJson_create(atlas);spSkeletonData*data=spSkeletonJson_readSkeletonDataFile(reader,argv[2]);if(!data){fprintf(stderr,"%s\n",reader->error);return 2;}size_t shared=live_bytes;int count=argc>4?atoi(argv[4]):1;spSkeleton**sk=calloc(count,sizeof(*sk));spAnimationState**st=calloc(count,sizeof(*st));spAnimationStateData*sd=spAnimationStateData_create(data);int maxverts=65536;float*buffer=malloc(maxverts*sizeof(float));for(int i=0;i<count;i++){sk[i]=spSkeleton_create(data);spSkeleton_setSkin(sk[i],data->defaultSkin);spSkeleton_setToSetupPose(sk[i]);st[i]=spAnimationState_create(sd);if(!spAnimationState_setAnimationByName(st[i],0,argv[3],1)){fprintf(stderr,"animation missing\n");return 2;}}
for(int f=0;f<600;f++)for(int i=0;i<count;i++){pose(sk[i],st[i]);vertices(sk[i],buffer);}size_t mem=live_bytes,alloc=allocations;double samples[200],pmean=0,vmean=0;for(int b=0;b<200;b++){double a=now_ms();for(int f=0;f<30;f++)for(int i=0;i<count;i++)pose(sk[i],st[i]);double mid=now_ms();for(int f=0;f<30;f++)for(int i=0;i<count;i++)vertices(sk[i],buffer);double end=now_ms();pmean+=(mid-a)/30;vmean+=(end-mid)/30;samples[b]=(end-a)/30;}qsort(samples,200,sizeof(double),cmp);printf("{\"instances\":%d,\"bones\":%d,\"slots\":%d,\"pose_mean_ms\":%.9f,\"vertices_mean_ms\":%.9f,\"combined_p50_ms\":%.9f,\"combined_p95_ms\":%.9f,\"shared_bytes\":%zu,\"instances_bytes\":%zu,\"allocations_during_6000_frames\":%zu,\"checksum\":%.3f}\n",count,data->bonesCount,data->slotsCount,pmean/200,vmean/200,samples[100],samples[190],shared,mem-shared,allocations-alloc,checksum);for(int i=0;i<count;i++){spAnimationState_dispose(st[i]);spSkeleton_dispose(sk[i]);}free(sk);free(st);free(buffer);spAnimationStateData_dispose(sd);spSkeletonData_dispose(data);spSkeletonJson_dispose(reader);spAtlas_dispose(atlas);return 0;}
