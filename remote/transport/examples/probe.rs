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

use livekit::{prelude::*,webrtc::{video_stream::native::NativeVideoStream,audio_stream::native::NativeAudioStream}};
use futures_util::StreamExt;
use serde_json::{json,Value};
use std::{sync::{Arc,atomic::{AtomicU64,Ordering}},time::Duration};
use tokio::io::{AsyncBufReadExt,BufReader};
#[tokio::main]
async fn main()->Result<(),Box<dyn std::error::Error>>{
 let mut lines=BufReader::new(tokio::io::stdin()).lines();
 let c:Value=serde_json::from_str(&lines.next_line().await?.unwrap())?;
 let (room,mut events)=Room::connect(c["url"].as_str().unwrap(),c["token"].as_str().unwrap(),RoomOptions::default()).await?;
 let frames=Arc::new(AtomicU64::new(0));let samples=Arc::new(AtomicU64::new(0));let energy=Arc::new(AtomicU64::new(0));
 let vf=frames.clone();let af=samples.clone();let en=energy.clone();
 tokio::spawn(async move{while let Some(e)=events.recv().await{if let RoomEvent::Disconnected{..}=&e{println!("{}",json!({"event":"disconnected"}));}if let RoomEvent::TrackSubscribed{track,..}=e{match track{
  RemoteTrack::Video(t)=>{let n=vf.clone();tokio::spawn(async move{let mut s=NativeVideoStream::new(t.rtc_track());let mut first=true;while let Some(f)=s.next().await{n.fetch_add(1,Ordering::Relaxed);if first{first=false;println!("{}",json!({"event":"video","width":f.buffer.width(),"height":f.buffer.height()}));}}});},
  RemoteTrack::Audio(t)=>{let n=af.clone();let energy=en.clone();tokio::spawn(async move{let mut s=NativeAudioStream::new(t.rtc_track(),48000,2);while let Some(f)=s.next().await{n.fetch_add(f.data.len() as u64,Ordering::Relaxed);energy.fetch_add(f.data.iter().map(|v|(*v as i64)*(*v as i64)).sum::<i64>() as u64,Ordering::Relaxed);}});}
 }}}});
 println!("{}",json!({"event":"connected"}));
 while let Some(line)=lines.next_line().await?{let command:Value=serde_json::from_str(&line)?;
  if command["action"]=="stats"{let n=samples.load(Ordering::Relaxed);println!("{}",json!({"event":"stats","frames":frames.load(Ordering::Relaxed),"samples":n,"rms":(energy.load(Ordering::Relaxed) as f64/n.max(1) as f64).sqrt()}));continue;}
  if command["action"]=="close"{break;}
  let result=room.local_participant().perform_rpc(PerformRpcData{destination_identity:c["hostIdentity"].as_str().unwrap().into(),method:"defold.control".into(),payload:command.to_string(),response_timeout:Duration::from_secs(15),..Default::default()}).await;
  match result{Ok(v)=>println!("{}",json!({"event":"reply","result":serde_json::from_str::<Value>(&v)?})),Err(e)=>println!("{}",json!({"event":"rpc-error","message":e.to_string()}))};
 }
 let _=room.close().await;Ok(())
}
