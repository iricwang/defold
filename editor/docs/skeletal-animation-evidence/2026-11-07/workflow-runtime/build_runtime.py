# Copyright 2020-2026 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

from pathlib import Path
import subprocess,re,os,json,hashlib
out=Path(__file__).parent;repo=Path('/Users/applemima1111/Documents/Dev/Defold')
java='/opt/homebrew/opt/openjdk@25/bin/java'
cp=Path('/tmp/defold-dev-update/pick-classpath.txt').read_text().strip()
opts=re.findall(r'"((?:--add-opens=|-Ddefold.extension.)[^"]+)"',(repo/'editor/project.clj').read_text())
commands=[
 ('export',[java,'--enable-native-access=ALL-UNNAMED','-Xmx768m',*opts,'-cp',cp,'clojure.main',str(out/'export.clj')],repo/'editor'),
 ('bob-build',[java,'--enable-native-access=ALL-UNNAMED','-Xmx768m','-jar',str(repo/'tmp/dynamo_home/share/java/bob.jar'),'--platform','arm64-macos','clean','build'],out/'project')]
result={}
for name,command,cwd in commands:
 with (out/(name+'.log')).open('w') as log:
  process=subprocess.run(command,cwd=cwd,env={**os.environ,'DM_BOB_ROOTFOLDER':str(out/'bob-tools')},stdout=log,stderr=subprocess.STDOUT)
 result[name]=process.returncode
 print(name,process.returncode)
 if process.returncode:print((out/(name+'.log')).read_text());raise SystemExit(process.returncode)
files={'bob':repo/'tmp/dynamo_home/share/java/bob.jar','engine':repo/'engine/engine/build/arm64-macos/dmengine_spine_local','plugin':out/'project/defold-spine/plugins/share/pluginSpineExt.jar'}
result['sha256']={key:hashlib.file_digest(path.open('rb'),'sha256').hexdigest() if hasattr(hashlib,'file_digest') else hashlib.sha256(path.read_bytes()).hexdigest() for key,path in files.items()}
(out/'build-results.json').write_text(json.dumps(result,indent=2))
