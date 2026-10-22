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
"""Explicit whole-file reload through an already enabled local socket REPL."""
import json
import re
import socket
import time
import uuid


def exchange(port, code, timeout):
    token = 'DEFOLD_RELOAD_' + uuid.uuid4().hex
    success, failure = token + '_OK', token + '_ERROR'
    expression = ('(do (try (do ' + code + ') (println "\\n' + success + '")'
                  ' (catch Throwable e (prn (Throwable->map e))'
                  ' (println "\\n' + failure + '"))) (flush))\n')
    deadline = time.monotonic() + timeout
    with socket.create_connection(('127.0.0.1', port), timeout=timeout) as connection:
        connection.sendall(expression.encode('utf-8'))
        output = bytearray()
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError('Reload timed out; outcome unknown. Inspect the editor before retrying.')
            connection.settimeout(remaining)
            chunk = connection.recv(65536)
            if not chunk:
                raise RuntimeError('REPL disconnected without completion; reload may be partial.')
            output.extend(chunk)
            if len(output) > 8 * 1024 * 1024:
                raise RuntimeError('REPL output exceeded 8 MiB; inspect editor logs.')
            decoded = output.decode('utf-8', errors='replace')
            if failure + '\n' in decoded or failure + '\r\n' in decoded:
                raise RuntimeError('Reload failed; sources may be partially loaded.\n' + decoded)
            if success + '\n' in decoded or success + '\r\n' in decoded:
                print(decoded.replace(success, 'RELOAD_OK'), end='')
                return


def reload_sources(root, args):
    if not (1 <= args.port <= 65535 and args.pid > 0 and args.timeout > 0):
        raise ValueError('A valid port, positive PID and positive timeout are required')
    source_root = (root / 'editor/src/clj').resolve()
    forms = [f'(assert (= {args.pid} (.pid (java.lang.ProcessHandle/current))) "Editor PID mismatch")',
             '(assert (find-ns (quote editor.ui)) "Not an initialized Defold editor")']
    # Attached socket REPLs can have a parentless loader. Load source resources
    # through the same per-request loader, without changing installed files.
    resources = json.dumps((root / 'editor/resources').resolve().as_uri() + '/')
    forms.append('(doseq [loader [(clojure.lang.RT/baseLoader) '
                 '(.getContextClassLoader (Thread/currentThread))]] '
                 '(when (instance? clojure.lang.DynamicClassLoader loader) '
                 '(.addURL ^clojure.lang.DynamicClassLoader loader '
                 '(java.net.URL. ' + resources + '))))')
    for namespace in args.namespace:
        if not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_-]*(?:\.[a-zA-Z][a-zA-Z0-9_-]*)+', namespace):
            raise ValueError('Invalid source namespace: ' + namespace)
        path = (source_root / (namespace.replace('.', '/').replace('-', '_') + '.clj')).resolve()
        if not path.is_relative_to(source_root) or not path.is_file():
            raise ValueError('Source namespace not found: ' + namespace)
        # Require first so existing defonce records retain their installed class identity.
        forms.extend([f'(require (quote {namespace}))',
                      '(load-file ' + json.dumps(path.as_posix(), ensure_ascii=False) + ')'])
    if args.after:
        after = args.after.resolve()
        if not after.is_file():
            raise ValueError('After-reload file not found: ' + str(after))
        forms.append('(load-file ' + json.dumps(after.as_posix(), ensure_ascii=False) + ')')
    started = time.monotonic()
    try:
        exchange(args.port, '\n'.join(forms), args.timeout)
    except TimeoutError as error:
        raise RuntimeError('Reload timed out; outcome unknown. Inspect the editor before retrying.') from error
    print(f'Loaded {len(args.namespace)} namespace(s) in {time.monotonic() - started:.2f}s. '
          'Session-only; installed files unchanged. Validate the feature before delivery.')
