"""Regression for physical pixel to normalized worker input coordinates."""
import argparse, json, os, subprocess, tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--manifest',required=True);p.add_argument('--javac',default='javac');a=p.parse_args()
manifest=Path(a.manifest).resolve();runtime=json.loads(manifest.read_text());base=manifest.parent
source=r'''
package com.defold.editor;
public class CatalogTest {
 static int checks;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("pointer packet check "+checks);}
 public static void main(String[] args){
  for(int[] size:new int[][]{{1080,2400},{2400,1080},{720,1280},{393,852},{2,2},{65536,2}}){
   int w=size[0],h=size[1];
   for(int x=0;x<w;x++){byte[] p=RemoteCloud.pointerPacket(x,h/2,w,h,true);int nx=(p[0]&255)|((p[1]&255)<<8);int ny=(p[2]&255)|((p[3]&255)<<8);check((long)nx*(w-1)/65535==x);check((long)ny*(h-1)/65535==h/2);check(p[4]==1);}
   for(int y=0;y<h;y++){byte[] p=RemoteCloud.pointerPacket(w/2,y,w,h,false);int ny=(p[2]&255)|((p[3]&255)<<8);check((long)ny*(h-1)/65535==y);check(p[4]==0);}
  }
  for(int[] bad:new int[][]{{-1,0,1080,2400},{1080,0,1080,2400},{0,2400,1080,2400},{0,0,1,2}}){boolean rejected=false;try{RemoteCloud.pointerPacket(bad[0],bad[1],bad[2],bad[3],true);}catch(IllegalArgumentException e){rejected=true;}check(rejected);}
  System.out.println("Pointer protocol regression passed: "+checks+" checks");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='defold-pointer-test-') as d:
 work=Path(d);java=work/'CatalogTest.java';java.write_text(source)
 cp=str(base/runtime['classes'])+os.pathsep+str(base/runtime['jar'])
 subprocess.run([a.javac,'-cp',cp,'-d',d,str(java)],check=True)
 subprocess.run([str(base/runtime['java']),'--enable-native-access=ALL-UNNAMED','-cp',d+os.pathsep+cp,'com.defold.editor.CatalogTest',str(manifest),str(work/'manager')],check=True,timeout=90)
