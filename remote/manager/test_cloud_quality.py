"""Real H264 quality-switch regression without changing game render dimensions."""
import argparse, json, os, subprocess, tempfile, shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--manifest',required=True);p.add_argument('--javac',default='javac');a=p.parse_args()
manifest=Path(a.manifest).resolve();runtime=json.loads(manifest.read_text());base=manifest.parent
source=r'''
package com.defold.editor;
import java.nio.file.*;import java.util.*;import java.io.*;import java.net.*;import java.util.concurrent.TimeUnit;
public class CatalogTest {
 static int checks;
 static void check(boolean pass,String name){if(!pass)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static void encoder(RemoteManager.Worker worker,String lease,String quality,int expected)throws Exception {
  HttpURLConnection c=worker.connection("/encoder","POST");c.setRequestProperty("X-Remote-Lease",lease);c.setDoOutput(true);
  try{try(var out=c.getOutputStream()){out.write(RemoteManager.JSON.writeValueAsBytes(Map.of("resolution",quality)));}check(c.getResponseCode()==expected,"encoder accepts only supported quality: "+quality);}finally{c.disconnect();}
 }
 static int[] video(RemoteManager.Worker worker,String lease,String query)throws Exception {
  HttpURLConnection c=worker.connection("/video?"+query,"GET");c.setRequestProperty("X-Remote-Lease",lease);
  try{check(c.getResponseCode()==200,"real H264 stream opens");try(var in=new DataInputStream(c.getInputStream())){int[] size={in.readInt(),in.readInt()};int bytes=in.readInt();in.readInt();check(bytes>0&&bytes<2097152,"encoded frame produced");in.readNBytes(bytes);return size;}}finally{c.disconnect();}
 }
 public static void main(String[] args)throws Exception {
  try(RemoteManager m=new RemoteManager(Path.of(args[0]),Path.of(args[1]),0)){
   var added=(Map<?,?>)m.command(Map.of("method","add","params",Map.of("path",args[2])));String id=added.get("id").toString();m.command(Map.of("method","start","params",Map.of("id",id)));RemoteManager.Project p=m.project(id);
   long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
   while(System.nanoTime()<until){synchronized(p){if(p.worker!=null)break;if(p.state.equals("error"))throw new AssertionError(p.error);}Thread.sleep(100);}
   check(p.worker!=null,"fixture running");RemoteManager.Worker worker=p.worker;String session=worker.sessionId,lease=RemoteGateway.secret();p.controller="quality-test";p.controlToken=lease;p.controlUntil=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);worker.lease(lease);
   worker.call(Map.of("command","device","width",1080,"height",2400,"name","device.png"));worker.call(Map.of("command","status"));worker.lease(lease);Object revision=worker.status.get("device_revision");
   for(String quality:List.of("540p","720p","1080p","540p")){
    encoder(worker,lease,quality,200);int[] expected=RemoteRuntime.remoteQuality(quality).dimensions(1080,2400),actual=video(worker,lease,"resolution=540p&profile=remote");
    check(actual[0]==(expected[0]&~1)&&actual[1]==(expected[1]&~1),quality+" actual video "+actual[0]+"x"+actual[1]);
    worker.call(Map.of("command","status"));check(session.equals(worker.sessionId)&&revision.equals(worker.status.get("device_revision")),"switch preserves game session and device revision");
   }
   encoder(worker,lease,"original",400);int[] lan=video(worker,lease,"resolution=1080p");check(lan[0]==864&&lan[1]==1920,"LAN quality remains independent of WAN preset");
  }
  System.out.println("Quality regression passed: "+checks+" checks");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='defold-quality-test-') as d:
 work=Path(d);shutil.copytree(Path(__file__).resolve().parents[2]/'scripts/automation/fixture',work/'game');java=work/'CatalogTest.java';java.write_text(source)
 cp=str(base/runtime['classes'])+os.pathsep+str(base/runtime['jar'])
 subprocess.run([a.javac,'-cp',cp,'-d',d,str(java)],check=True)
 test_java = (base/runtime['java']).with_name('java.exe') if os.name == 'nt' else base/runtime['java']
 subprocess.run([str(test_java),'--enable-native-access=ALL-UNNAMED','-cp',d+os.pathsep+cp,'com.defold.editor.CatalogTest',str(manifest),str(work/'manager'),str(work/'game')],check=True,timeout=150)
