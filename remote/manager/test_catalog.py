"""Regression for the shared LAN/WAN catalog and bounded RPC pagination."""
import argparse, json, os, subprocess, tempfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--manifest',required=True);p.add_argument('--javac',default='javac');a=p.parse_args()
manifest=Path(a.manifest).resolve();runtime=json.loads(manifest.read_text());base=manifest.parent
source=r'''
package com.defold.editor;
import java.nio.file.*;
import java.util.*;
public class CatalogTest {
 static int checks;
 static void check(boolean pass,String name){if(!pass)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 static List<Map<String,Object>> read(RemoteCloud cloud)throws Exception {
  List<Map<String,Object>> all=new ArrayList<>();Set<String> seen=new HashSet<>();String cursor="";
  do {Map<String,Object> page=cloud.catalog(Map.of("cursor",cursor));
   check(RemoteManager.JSON.writeValueAsBytes(page).length<14000,"page stays below RPC limit");
   all.addAll((List<Map<String,Object>>)page.get("games"));cursor=(String)page.get("nextCursor");
   if(!cursor.isEmpty()&&!seen.add(cursor))throw new AssertionError("cursor repeated");
  }while(!cursor.isEmpty());return all;
 }
 public static void main(String[] args)throws Exception {
  try(RemoteManager m=new RemoteManager(Path.of(args[0]),Path.of(args[1]),0)){
   m.settings.put("prepare",false);
   for(int i=0;i<73;i++){String id=String.format("game-%03d",i);m.projects.put(id,new RemoteManager.Project(id,Path.of(args[1]).resolve(id),"游戏"+i+"界".repeat(300)));}
   m.projects.get("game-007").shared=false;
   List<Map<String,Object>> rows=read(m.cloud);
   check(rows.size()==72,"all games beyond old 50-item cap returned");
   check(new HashSet<>(rows.stream().map(p->p.get("id")).toList()).equals(new HashSet<>(m.sharedProjects().stream().map(p->p.id).toList())),"LAN and WAN share identical project IDs");
   check(rows.stream().noneMatch(p->p.containsKey("root")||p.containsKey("build")),"private paths excluded");
   m.projects.put("game-999",new RemoteManager.Project("game-999",Path.of(args[1]).resolve("new"),"新增游戏"));
   m.projects.remove("game-001");m.projects.get("game-002").shared=false;
   rows=read(m.cloud);
   check(rows.size()==71&&rows.stream().anyMatch(p->p.get("id").equals("game-999")),"new game appears on next read");
   check(rows.stream().noneMatch(p->Set.of("game-001","game-002","game-007").contains(p.get("id"))),"removed and unshared games disappear");
   m.projects.clear();check(read(m.cloud).isEmpty(),"empty catalog terminates");
  }
  System.out.println("Catalog regression passed: "+checks+" checks");
 }
}'''
with tempfile.TemporaryDirectory(prefix='defold-catalog-test-') as d:
 work=Path(d);java=work/'CatalogTest.java';java.write_text(source)
 cp=str(base/runtime['classes'])+os.pathsep+str(base/runtime['jar'])
 subprocess.run([a.javac,'-cp',cp,'-d',d,str(java)],check=True)
 subprocess.run([str(base/runtime['java']),'--enable-native-access=ALL-UNNAMED','-cp',d+os.pathsep+cp,'com.defold.editor.CatalogTest',str(manifest),str(work/'manager')],check=True,timeout=90)
