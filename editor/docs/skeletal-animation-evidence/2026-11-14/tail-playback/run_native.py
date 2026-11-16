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

import subprocess,json,hashlib,time
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description='Run the archived tail runtime benchmark matrix against a compiled native_bench.')
p.add_argument('directory', type=Path, help='Directory containing native_bench, benchmark.atlas and the archived spinejson fixtures')
w=p.parse_args().directory.resolve();jobs=[]
for label,file,animation in [('current','current.spinejson','tail_path_demo'),('animated','animated.spinejson','benchmark_sway')]:
 for count in [1,16,64,128,256]:jobs.append((label,w/file,animation,count))
for file in sorted([*w.glob('influences-*.spinejson'),*w.glob('bones*.spinejson')]):jobs.append((file.stem,file,'benchmark_sway',64))
results=[]
for label,file,animation,count in jobs:
 for trial in range(3):
  r=subprocess.run([str(w/'native_bench'),str(w/'benchmark.atlas'),str(file),animation,str(count)],capture_output=True,text=True,check=True)
  item={'variant':label,'file':file.name,'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'animation':animation,'trial':trial,**json.loads(r.stdout)};results.append(item)
 print(label,count,'ms',round(results[-1]['combined_p50_ms'],4),flush=True)
 (w/'native-results.json').write_text(json.dumps(results,indent=2))
