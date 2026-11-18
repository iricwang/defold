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

use std::{borrow::Cow, collections::HashMap, sync::{Arc, atomic::{AtomicU64,Ordering}}, time::{Duration,Instant}};
use futures_util::TryStreamExt;
use livekit::{prelude::*, options::{TrackPublishOptions, VideoCodec, VideoEncoderBackend, AudioEncoding},
    webrtc::{video_source::{native::NativeVideoSource,RtcVideoSource,VideoResolution},
    video_frame::{EncodedVideoFrame,EncodedVideoCodec,EncodedFrameType},
    audio_source::{native::NativeAudioSource,RtcAudioSource,AudioSourceOptions},audio_frame::AudioFrame}};
use serde_json::{json,Value};
use tokio::{io::{AsyncBufReadExt,AsyncReadExt,AsyncWriteExt,BufReader},sync::{mpsc,Mutex,oneshot},task::JoinHandle};
use tokio_util::io::StreamReader;

fn field<'a>(v:&'a Value,k:&str)->&'a str { v[k].as_str().unwrap_or("") }
fn io_error(e:impl std::fmt::Display)->std::io::Error {std::io::Error::other(e.to_string())}
// The private parent pipe carries commands only. Encoded media is read from loopback.
fn emit(out:&mpsc::Sender<Value>,v:Value){let _=out.try_send(v);}
fn keyframe(bytes:&[u8])->bool {bytes.windows(5).any(|w|w[..4]==[0,0,0,1]&&w[4]&31==5)||bytes.windows(4).any(|w|w[..3]==[0,0,1]&&w[3]&31==5)}
fn local_url(value:&str)->Result<reqwest::Url,std::io::Error>{let url=reqwest::Url::parse(value).map_err(io_error)?;
 if url.scheme()!="http"||url.host_str()!=Some("127.0.0.1")||url.username()!=""||url.password().is_some(){return Err(io_error("Media source must be private loopback"));}Ok(url)}
async fn source(client:&reqwest::Client,v:&Value,path:&str)->Result<impl tokio::io::AsyncRead+Unpin,std::io::Error>{
 let base=local_url(field(v,"endpoint"))?;let url=base.join(path).map_err(io_error)?;
 let r=client.get(url).bearer_auth(field(v,"token")).header("X-Remote-Lease",field(v,"lease")).send().await.map_err(io_error)?.error_for_status().map_err(io_error)?;
 Ok(StreamReader::new(r.bytes_stream().map_err(io_error)))
}
async fn video(room:Arc<Room>,v:Value,out:mpsc::Sender<Value>)->Result<(),std::io::Error>{
 let client=reqwest::Client::builder().redirect(reqwest::redirect::Policy::none()).connect_timeout(Duration::from_secs(3)).build().map_err(io_error)?;
 let mut input=source(&client,&v,"/video?resolution=540p&profile=remote").await?;
 let width=input.read_u32().await?;let height=input.read_u32().await?;
 if width<2||height<2||width>1920||height>1920{return Err(io_error("Invalid video dimensions"));}
 let resolution=VideoResolution{width,height};let source=NativeVideoSource::new_encoded(resolution.clone());
 let track=LocalVideoTrack::create_video_track("game",RtcVideoSource::Native(source.clone()));
 let publication=room.local_participant().publish_track(LocalTrack::Video(track),TrackPublishOptions{video_codec:VideoCodec::H264,video_encoder:VideoEncoderBackend::PreEncoded,simulcast:false,source:TrackSource::Screenshare,..Default::default()}).await.map_err(io_error)?;
 let start=Instant::now();let mut buffer=vec![];let mut previous_rate=(0,0);
 let result=async {loop{
  let size=tokio::time::timeout(Duration::from_secs(8),input.read_u32()).await.map_err(io_error)?? as usize;
  let _sequence=input.read_u32().await?;if size>2*1024*1024{return Err(io_error("Oversized video frame"));}
  buffer.resize(size,0);input.read_exact(&mut buffer).await?;
  if source.take_keyframe_request(){emit(&out,json!({"event":"encoder","keyframe":true}));}
  if let Some(rate)=source.take_rate_control_request(){let bitrate=rate.target_bitrate_bps.clamp(250000,1800000);let fps=rate.framerate_fps.clamp(10.0,30.0) as u32;if previous_rate!=(bitrate,fps){previous_rate=(bitrate,fps);emit(&out,json!({"event":"encoder","bitrate":bitrate,"fps":fps}));}}
  source.capture_encoded_frame(&EncodedVideoFrame{codec:EncodedVideoCodec::H264,payload:&buffer,timestamp_us:start.elapsed().as_micros() as i64,frame_type:if keyframe(&buffer){EncodedFrameType::Key}else{EncodedFrameType::Delta},resolution:resolution.clone(),frame_metadata:None});
 }}.await;
 let _=room.local_participant().unpublish_track(&publication.sid()).await;result
}
async fn audio(room:Arc<Room>,v:Value)->Result<(),std::io::Error>{
 let client=reqwest::Client::builder().redirect(reqwest::redirect::Policy::none()).build().map_err(io_error)?;let mut input=source(&client,&v,"/audio").await?;
 let magic=input.read_u32().await?;let rate=input.read_u32().await?;let channels=input.read_u32().await?;let bits=input.read_u32().await?;
 if magic!=0x44464131||!(8000..=192000).contains(&rate)||channels!=2||bits!=16{return Err(io_error("Invalid PCM header"));}
 let source=NativeAudioSource::new(AudioSourceOptions{echo_cancellation:false,noise_suppression:false,auto_gain_control:false},rate,2,40);
 let track=LocalAudioTrack::create_audio_track("game-audio",RtcAudioSource::Native(source.clone()));
 let publication=room.local_participant().publish_track(LocalTrack::Audio(track),TrackPublishOptions{audio_encoding:Some(AudioEncoding{max_bitrate:64000}),dtx:true,red:false,source:TrackSource::ScreenshareAudio,..Default::default()}).await.map_err(io_error)?;
 let result=async {loop{let size=tokio::time::timeout(Duration::from_secs(5),input.read_u32()).await.map_err(io_error)?? as usize;let _sequence=input.read_u32().await?;
  if size>3840||size%4!=0{return Err(io_error("Invalid PCM packet"));}if size==0{continue;}
  let mut data=vec![0;size];input.read_exact(&mut data).await?;let samples=data.chunks_exact(2).map(|b|i16::from_le_bytes([b[0],b[1]])).collect();
  source.capture_frame(&AudioFrame{data:Cow::Owned(samples),sample_rate:rate,num_channels:2,samples_per_channel:(size/4) as u32}).await.map_err(io_error)?;
 }}.await;
 let _=room.local_participant().unpublish_track(&publication.sid()).await;result
}
#[tokio::main]
async fn main()->Result<(),Box<dyn std::error::Error>>{
 let (out,mut output)=mpsc::channel::<Value>(256);
 let writer=tokio::spawn(async move{let mut stdout=tokio::io::stdout();while let Some(v)=output.recv().await{if stdout.write_all(format!("{v}\n").as_bytes()).await.is_err(){break;}let _=stdout.flush().await;}});
 let pending:Arc<Mutex<HashMap<u64,oneshot::Sender<Value>>>>=Arc::new(Mutex::new(HashMap::new()));
 let sequence=Arc::new(AtomicU64::new(1));let mut room:Option<Arc<Room>>=None;let mut events:Option<JoinHandle<()>>=None;let mut media=Vec::<JoinHandle<()>>::new();
 let mut lines=BufReader::new(tokio::io::stdin()).lines();
 while let Some(line)=lines.next_line().await?{
  if line.len()>65536{break;}let Ok(v)=serde_json::from_str::<Value>(&line) else{continue;};
  match field(&v,"command"){
   "join"=>{
    for t in media.drain(..){t.abort();}if let Some(t)=events.take(){t.abort();}if let Some(old)=room.take(){let _=old.close().await;}
    match Room::connect(field(&v,"url"),field(&v,"token"),RoomOptions::default()).await{
     Ok((connected,mut updates))=>{let connected=Arc::new(connected);let tx=out.clone();let calls=pending.clone();let seq=sequence.clone();
      connected.local_participant().register_rpc_method("defold.control".into(),move|call|{let tx=tx.clone();let calls=calls.clone();let seq=seq.clone();Box::pin(async move{
       let id=seq.fetch_add(1,Ordering::Relaxed);let (send,recv)=oneshot::channel();{let mut map=calls.lock().await;if map.len()>=32{return Err(RpcError::new(1501,"Host busy".into(),None));}map.insert(id,send);}
       emit(&tx,json!({"event":"rpc","id":id,"caller":call.caller_identity.to_string(),"payload":call.payload}));
       let result=tokio::time::timeout(Duration::from_secs(12),recv).await;calls.lock().await.remove(&id);
       match result{Ok(Ok(value))=>Ok(value.to_string()),_=>Err(RpcError::new(1500,"Host did not respond".into(),None))}
      })});
      let tx=out.clone();events=Some(tokio::spawn(async move{while let Some(e)=updates.recv().await{match e{
       RoomEvent::DataReceived{payload,participant,topic,..}=>emit(&tx,json!({"event":"data","caller":participant.map(|p|p.identity().to_string()).unwrap_or_default(),"topic":topic,"payload":String::from_utf8_lossy(&payload)})),
       RoomEvent::Disconnected{..}=>{emit(&tx,json!({"event":"disconnected"}));break;},
       _=>{}
      }}}));room=Some(connected);emit(&out,json!({"event":"connected"}));},
     Err(_)=>emit(&out,json!({"event":"error","message":"LiveKit connection failed"}))
    }
   },
   "reply"=>{if let Some(send)=pending.lock().await.remove(&v["id"].as_u64().unwrap_or(0)){let _=send.send(v["result"].clone());}},
   "media"=>{for t in media.drain(..){t.abort();let _=t.await;}if let Some(room)=room.clone(){for p in room.local_participant().track_publications().values(){let _=room.local_participant().unpublish_track(&p.sid()).await;}let value=v.clone();let tx=out.clone();let r=room.clone();media.push(tokio::spawn(async move{if let Err(e)=video(r,value,tx.clone()).await{emit(&tx,json!({"event":"media-ended","kind":"video","message":e.to_string()}));}}));if v["audio"]==true{let tx=out.clone();media.push(tokio::spawn(async move{if let Err(e)=audio(room,v).await{emit(&tx,json!({"event":"media-ended","kind":"audio","message":e.to_string()}));}}));}}},
   "stop-media"=>{for t in media.drain(..){t.abort();}if let Some(r)=&room{for p in r.local_participant().track_publications().values(){let _=r.local_participant().unpublish_track(&p.sid()).await;}}},
   "close"=>break,
   _=>{}
  }
 }
 for t in media{t.abort();}if let Some(t)=events{t.abort();}if let Some(r)=room{let _=r.close().await;}drop(out);writer.abort();Ok(())
}

#[cfg(test)]
mod tests{use super::*;
 #[test] fn only_idr_is_a_keyframe(){assert!(keyframe(&[0,0,0,1,0x65]));assert!(keyframe(&[0,0,1,0x65]));assert!(!keyframe(&[0,0,0,1,0x61]));}
 #[test] fn source_stays_on_loopback(){assert!(local_url("http://127.0.0.1:1234").is_ok());assert!(local_url("https://example.com").is_err());assert!(local_url("http://user@127.0.0.1:1234").is_err());}
}
