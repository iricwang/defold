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

import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, HTTPServer
import sys
import threading


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({'sdk': sys.argv[2], 'platform': 'x86_64-win32'}).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        if self.path == '/shutdown':
            threading.Thread(target=self.server.shutdown).start()


with HTTPServer(('127.0.0.1', int(sys.argv[1])), Handler) as server:
    with Path('starts.txt').open('a', encoding='utf-8', newline='\n') as stream:
        stream.write('started\n')
    server.serve_forever()
