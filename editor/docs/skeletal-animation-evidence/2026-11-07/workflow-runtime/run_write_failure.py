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
import subprocess,re,resource,signal
repo=Path('/Users/applemima1111/Documents/Dev/Defold')
root=Path(__file__).parent
(root/'write-failure').mkdir(exist_ok=True)
cp=Path('/tmp/defold-dev-update/pick-classpath.txt').read_text().strip()
opts=re.findall(r'"((?:--add-opens=|-Ddefold.extension.)[^"]+)"',(repo/'editor/project.clj').read_text())
def limited():
    resource.setrlimit(resource.RLIMIT_FSIZE,(16384,16384))
    signal.signal(signal.SIGXFSZ,signal.SIG_IGN)
with (root/'write-failure.log').open('w') as log:
    process=subprocess.run(['/opt/homebrew/opt/openjdk@25/bin/java','--enable-native-access=ALL-UNNAMED','-Xmx768m',*opts,'-cp',cp,'clojure.main',str(root/'write-failure.clj')],cwd=repo/'editor',stdout=log,stderr=subprocess.STDOUT,preexec_fn=limited)
print('exit',process.returncode)
print((root/'write-failure.log').read_text())
raise SystemExit(process.returncode)
