#!/usr/bin/env python3
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

"""Structured local nREPL adapter. Stdlib only; never starts/closes an editor."""
import argparse
import json
from pathlib import Path
import socket
import sys
import time
import uuid

LIMIT = 16 * 1024 * 1024

def encode(value):
    if isinstance(value, dict):
        return b'd' + b''.join(encode(k) + encode(v) for k, v in sorted(value.items())) + b'e'
    data = str(value).encode('utf-8')
    return str(len(data)).encode() + b':' + data

class Decoder:
    def __init__(self, stream):
        self.stream = stream
        self.count = 0
    def take(self, size):
        self.count += size
        if self.count > LIMIT:
            raise ValueError('Response exceeds 16 MiB; read a smaller section')
        out = bytearray()
        while len(out) < size:
            piece = self.stream.read(size - len(out))
            if not piece:
                raise EOFError('Editor disconnected; inspect before retrying a mutation')
            out.extend(piece)
        return bytes(out)
    def read(self, tag=None, depth=0):
        if depth > 50:
            raise ValueError('Invalid response nesting')
        tag = tag or self.take(1)
        if tag in (b'd', b'l'):
            items = []
            while (first := self.take(1)) != b'e':
                items.append(self.read(first, depth + 1))
            if tag == b'd':
                if len(items) % 2:
                    raise ValueError('Invalid dictionary response')
                return dict(zip(items[::2], items[1::2]))
            return items
        if tag == b'i':
            digits = bytearray()
            while (digit := self.take(1)) != b'e':
                digits.extend(digit)
            return int(digits)
        digits = bytearray(tag)
        while (digit := self.take(1)) != b':':
            digits.extend(digit)
            if len(digits) > 12:
                raise ValueError('Invalid response size')
        size = int(digits)
        if size < 0 or size > LIMIT:
            raise ValueError('Invalid response size')
        return self.take(size).decode('utf-8')

def call(port, request, timeout=30):
    path = Path(__file__).with_name('defold_spine_agent.clj').resolve()
    payload = json.dumps(request, ensure_ascii=False, allow_nan=False)
    # JSON string literals here are valid Clojure strings; no shell interpolation.
    code = ('(load-file ' + json.dumps(str(path), ensure_ascii=False) + ')\n'
            '(println "DEFOLD_SPINE_RESULT" (defold-spine-agent/handle-json '
            + json.dumps(payload, ensure_ascii=False) + '))')
    output = ''
    errors = []
    deadline = time.monotonic() + timeout
    with socket.create_connection(('127.0.0.1', port), timeout=timeout) as sock:
        sock.sendall(encode({'op': 'eval', 'id': str(uuid.uuid4()), 'code': code}))
        with sock.makefile('rb') as stream:
            decoder = Decoder(stream)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('No completion received; inspect before retrying a mutation')
                sock.settimeout(remaining)
                response = decoder.read()
                output += response.get('out', '')
                if 'eval-error' in response.get('status', []):
                    errors.append(response.get('ex', 'Adapter failed to load'))
                if 'done' in response.get('status', []):
                    break
    if errors:
        raise RuntimeError('; '.join(errors))
    marker = 'DEFOLD_SPINE_RESULT '
    for line in output.splitlines():
        if line.startswith(marker):
            return json.loads(line[len(marker):])
    raise RuntimeError('Editor returned no structured result; inspect before retrying a mutation')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True, help='Confirmed local Defold nREPL port, not editor HTTP port')
    parser.add_argument('--request', help='JSON request file; stdin if omitted')
    parser.add_argument('--timeout', type=float, default=30)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 < args.timeout <= 180:
        parser.error('Port must be 1–65535; timeout must be 0–180 seconds')
    try:
        text = Path(args.request).read_text(encoding='utf-8') if args.request else sys.stdin.read(LIMIT + 1)
        if len(text.encode('utf-8')) > LIMIT:
            raise ValueError('Request exceeds 16 MiB')
        request = json.loads(text, parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Nonfinite JSON number')))
        result = call(args.port, request, args.timeout)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result.get('ok') else 1
    except (OSError, ValueError, EOFError, RuntimeError) as exc:
        print(json.dumps({'ok': False, 'error': {'code': 'CONNECTION_ERROR', 'message': str(exc)}}, ensure_ascii=False))
        return 1

if __name__ == '__main__':
    sys.exit(main())
