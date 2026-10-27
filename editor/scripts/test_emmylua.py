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

"""Real-process regression for the pinned EmmyLua backend. No project is uploaded.

Usage: python scripts/test_emmylua.py --server resources/_unpack/arm64-macos/bin/lsp/emmylua/emmylua_ls
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest

SERVER = None
BRIDGE = None
LIBRARY = Path(__file__).resolve().parents[1] / 'resources' / 'lua-annotations'


class Client:
    def __init__(self, root, editor=False):
        self.root = root
        started = time.monotonic()
        self.stderr = (root/'language-server.log').open('w+')
        self.process = subprocess.Popen(BRIDGE + [SERVER] if BRIDGE else [SERVER], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr)
        self.lock = threading.Lock()
        self.condition = threading.Condition()
        self.responses = {}
        self.notifications = []
        self.serial = 0
        self.failure = None
        self.configuration = {'runtime': {'version': 'Lua5.1', 'extensions': ['.lua', '.script', '.gui_script', '.render_script']},
                              'workspace': {'library': [str(LIBRARY)]},
                              'diagnostics': {'globals': ['init', 'final', 'update', 'on_input', 'on_message']}}
        if editor:
            self.configuration['runtime'] = {'version': 'Lua5.2', 'extensions': ['.editor_script']}
            self.configuration['workspace']['ignoreGlobs'] = ['**/*.lua', '**/*.script', '**/*.gui_script', '**/*.render_script']
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()
        result = self.request('initialize', {'processId': os.getpid(), 'rootUri': root.as_uri(),
            'workspaceFolders': [{'uri': root.as_uri(), 'name': 'regression'}],
            'capabilities': {'workspace': {'configuration': True}, 'window': {'workDoneProgress': True},
                'textDocument': {'completion': {'completionItem': {'snippetSupport': True, 'labelDetailsSupport': True,
                    'documentationFormat': ['markdown', 'plaintext'],
                    'resolveSupport': {'properties': ['detail', 'documentation', 'labelDetails', 'additionalTextEdits']}}},
                    'signatureHelp': {'signatureInformation': {'parameterInformation': {'labelOffsetSupport': True}}},
                    'semanticTokens': {'requests': {'full': True}, 'tokenTypes': ['class','parameter','variable','property','function','method','keyword','string','number','comment'], 'tokenModifiers': [], 'formats': ['relative']},
                    'inlayHint': {}, 'rename': {'prepareSupport': True}}}})
        self.capabilities = result['capabilities']
        self.notify('initialized', {})
        self.wait(lambda: any(n.get('method') == '$/progress' and n.get('params', {}).get('value', {}).get('kind') == 'end' for n in self.notifications))
        print(f'Backend ready ({"editor" if editor else "game"}, bridge={bool(BRIDGE)}): {time.monotonic()-started:.3f}s', file=sys.stderr)

    def send(self, value):
        raw = json.dumps(value).encode()
        with self.lock:
            self.process.stdin.write(f'Content-Length: {len(raw)}\r\n\r\n'.encode() + raw)
            self.process.stdin.flush()

    def read(self):
        try:
            while True:
                headers = {}
                while True:
                    line = self.process.stdout.readline()
                    if not line:
                        raise EOFError('Language server stopped')
                    if line == b'\r\n':
                        break
                    k, v = line.decode().split(':', 1)
                    headers[k.lower()] = v.strip()
                value = json.loads(self.process.stdout.read(int(headers['content-length'])))
                if 'method' in value and 'id' in value:
                    if value['method'] == 'workspace/configuration':
                        result = [self.configuration if item.get('section') == 'emmylua' else None for item in value['params']['items']]
                    else:
                        result = None
                    self.send({'jsonrpc': '2.0', 'id': value['id'], 'result': result})
                else:
                    with self.condition:
                        if 'id' in value:
                            self.responses[value['id']] = value
                        else:
                            self.notifications.append(value)
                        self.condition.notify_all()
        except Exception as error:
            with self.condition:
                self.failure = error
                self.condition.notify_all()

    def wait(self, predicate, timeout=30):
        with self.condition:
            ok = self.condition.wait_for(lambda: predicate() or self.failure, timeout)
            if not predicate():
                self.stderr.flush()
                self.stderr.seek(0)
                print(self.stderr.read()[-6000:], file=sys.stderr)
                raise RuntimeError(self.failure or f'Timed out after {timeout}s')
            return ok

    def request(self, method, params):
        self.serial += 1
        serial = self.serial
        self.send({'jsonrpc':'2.0','id':serial,'method':method,'params':params})
        self.wait(lambda: serial in self.responses)
        value = self.responses.pop(serial)
        if 'error' in value:
            raise RuntimeError((method, value['error']))
        return value.get('result')

    def notify(self, method, params):
        self.send({'jsonrpc':'2.0','method':method,'params':params})

    def open(self, name, text):
        uri = (self.root / name).as_uri()
        self.notify('textDocument/didOpen', {'textDocument':{'uri':uri,'languageId':'lua','version':1,'text':text}})
        self.wait(lambda: any(n.get('method') == 'textDocument/publishDiagnostics' and n['params']['uri'] == uri for n in self.notifications))
        return {'textDocument':{'uri':uri}}

    def close(self):
        try:
            self.request('shutdown', None)
            self.notify('exit', None)
            self.process.wait(timeout=10)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait()
            self.process.stdin.close()
            self.process.stdout.close()
            self.stderr.close()


class EmmyLuaRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='defold-emmylua-')
        cls.root = Path(cls.temp.name)
        (cls.root/'regression_model.lua').write_text('---@class Player\n---@field hp number\nlocal M = {}\n---@param name string\n---@param count number\n---@return number\nfunction M.score(name, count) return count end\nreturn M\n')
        cls.client = Client(cls.root)

    @classmethod
    def tearDownClass(cls):
        cls.client.close()
        cls.temp.cleanup()

    def request_at(self, method, name, text, line, character):
        document = self.client.open(name, text)
        return self.client.request(method, {**document, 'position':{'line':line,'character':character}})

    # Verifies every supported game script extension receives module-aware completion.
    def test_completion_extensions(self):
        for ext in ['lua', 'script', 'gui_script', 'render_script']:
            with self.subTest(ext=ext):
                result = self.request_at('textDocument/completion', 'completion.'+ext, 'local model = require "regression_model"\nmodel.', 1, 6)
                items = result.get('items', []) if isinstance(result, dict) else result
                self.assertIn('score', [i['label'] for i in items])

    # Verifies class annotations flow into member completion.
    def test_class_fields(self):
        result = self.request_at('textDocument/completion', 'types.lua', '---@type Player\nlocal player\nplayer.', 2, 7)
        items = result.get('items', []) if isinstance(result, dict) else result
        self.assertIn('hp', [i['label'] for i in items])

    # Verifies Defold API annotations are included in workspace analysis.
    def test_defold_api(self):
        result = self.request_at('textDocument/completion', 'api.gui_script', 'gui.', 0, 4)
        items = result.get('items', []) if isinstance(result, dict) else result
        self.assertIn('get_node', [i['label'] for i in items])

    # Verifies the lifecycle annotation used by the editor adapter resolves engine types.
    def test_lifecycle_types(self):
        prefix = '' if BRIDGE else '---@param action on_input.action\n'
        result = self.request_at('textDocument/completion', 'lifecycle.gui_script', prefix + 'function on_input(self, action_id, action)\n    action.\nend', 1 if BRIDGE else 2, 11)
        items = result.get('items', []) if isinstance(result, dict) else result or []
        self.assertIn('x', [i['label'] for i in items])

    # Verifies lifecycle typing also survives wrapped function parameter lists.
    def test_multiline_lifecycle(self):
        if not BRIDGE:
            self.skipTest('requires production lifecycle adapter')
        text = 'function on_input(\n    self,\n    action_id,\n    action)\n    action.\nend'
        result = self.request_at('textDocument/completion', 'wrapped.gui_script', text, 4, 11)
        items = result.get('items', []) if isinstance(result, dict) else result or []
        self.assertIn('x', [i['label'] for i in items])

    # Verifies aliases, inheritance and generics participate in completion and inference.
    def test_annotation_inference(self):
        cases = [
            ('inherit', '---@class Base\n---@field inherited number\n---@class Child: Base\n---@type Child\nlocal child\nchild.', 'inherited'),
            ('alias', '---@alias Character Player\n---@type Character\nlocal character\ncharacter.', 'hp'),
            ('generic', '---@generic T\n---@param value T\n---@return T\nlocal function identity(value) return value end\n---@type Player\nlocal player\nlocal result = identity(player)\nresult.', 'hp')]
        for name, text, member in cases:
            with self.subTest(name=name):
                lines = text.split('\n')
                result = self.request_at('textDocument/completion', name+'.lua', text, len(lines)-1, len(lines[-1]))
                items = result.get('items', []) if isinstance(result, dict) else result or []
                self.assertIn(member, [item['label'] for item in items])

    # Verifies annotated type mismatches produce actual diagnostics, not just syntax errors.
    def test_type_diagnostics(self):
        doc = self.client.open('type_error.lua', '---@type number\nlocal number_value = "wrong"\nprint(number_value)\n')
        diagnostics = [d for n in self.client.notifications if n.get('method') == 'textDocument/publishDiagnostics'
                       and n['params']['uri'] == doc['textDocument']['uri'] for d in n['params']['diagnostics']]
        self.assertTrue(any('type' in str(d.get('code', '')) for d in diagnostics), diagnostics)

    # Verifies completion resolution retains documentation for module functions.
    def test_completion_resolution(self):
        result = self.request_at('textDocument/completion', 'resolve.lua', 'local m = require "regression_model"\nm.', 1, 2)
        items = result.get('items', []) if isinstance(result, dict) else result or []
        item = next(item for item in items if item['label'] == 'score')
        resolved = self.client.request('completionItem/resolve', item)
        self.assertIn('count', json.dumps(resolved))

    # Verifies IDEA-style signature labels and Chinese parameter/return docs survive the production adapter.
    def test_completion_annotation_documentation(self):
        text = ('---@class DocumentedContext\nlocal Context = {}\n'
                '---创建窗口并返回实例。\n---@param url string 窗口资源路径\n'
                '---@param count? integer 创建数量\n---@return DocumentedContext window 创建的窗口\n'
                'function Context:newWidget(url, count) return self end\nContext:')
        result = self.request_at('textDocument/completion', 'documented.lua', text, 7, 8)
        items = result.get('items', []) if isinstance(result, dict) else result or []
        item = next(i for i in items if i['label'] == 'newWidget')
        self.assertEqual('(url, count) -> DocumentedContext', item['labelDetails']['detail'])
        resolved = self.client.request('completionItem/resolve', item)
        self.assertEqual(item['labelDetails'], resolved['labelDetails'])
        documentation = resolved['documentation']['value']
        for expected in ['DocumentedContext:newWidget', '创建窗口并返回实例', '窗口资源路径', '创建数量', '创建的窗口', 'integer?']:
            self.assertIn(expected, documentation)

    # Verifies navigation into another module and hover type documentation.
    def test_definition_hover(self):
        text = 'local model = require "regression_model"\nlocal value = model.score("test", 2)'
        doc = self.client.open('navigate.lua', text)
        params = {**doc, 'position':{'line':1,'character':21}}
        definitions = self.client.request('textDocument/definition', params)
        self.assertIn('regression_model.lua', json.dumps(definitions))
        self.assertIn('count', json.dumps(self.client.request('textDocument/hover', params)))

    # Verifies parameter help identifies the second argument.
    def test_signature(self):
        response = self.request_at('textDocument/signatureHelp', 'signature.lua', 'local model = require "regression_model"\nmodel.score("test", )', 1, 19)
        self.assertTrue(response['signatures'])
        self.assertIn('count', json.dumps(response))
        self.assertEqual(1, response.get('activeParameter', response['signatures'][0].get('activeParameter')))

    # Verifies semantic token offsets and visible-range type hints are available.
    def test_semantics_and_hints(self):
        doc = self.client.open('hints.lua', 'local count = 42\nprint(count)\n')
        tokens = self.client.request('textDocument/semanticTokens/full', doc)
        self.assertGreater(len(tokens['data']), 0)
        self.assertEqual(0, len(tokens['data']) % 5)
        hints = self.client.request('textDocument/inlayHint', {**doc, 'range':{'start':{'line':0,'character':0},'end':{'line':2,'character':0}}})
        self.assertTrue(hints)

    # Verifies scoped renames and references preserve unrelated identifiers.
    def test_rename_references(self):
        doc = self.client.open('rename.lua', 'local value = 1\nprint(value)\n')
        params = {**doc, 'position':{'line':0,'character':8}}
        self.assertIsNotNone(self.client.request('textDocument/prepareRename', params))
        edit = self.client.request('textDocument/rename', {**params, 'newName':'renamed'})
        self.assertEqual(2, len(edit['changes'][doc['textDocument']['uri']]))
        refs = self.client.request('textDocument/references', {**params,'context':{'includeDeclaration':True}})
        self.assertEqual(2, len(refs))

    # Verifies document and selection formatting produce actual edits.
    def test_formatting(self):
        doc = self.client.open('format.lua', 'local x=1\nif x then\nprint(x)\nend\n')
        options = {'tabSize':4,'insertSpaces':True}
        self.assertTrue(self.client.request('textDocument/formatting', {**doc,'options':options}))
        self.assertTrue(self.client.request('textDocument/rangeFormatting', {**doc,'options':options,'range':{'start':{'line':0,'character':0},'end':{'line':4,'character':0}}}))

    # Verifies diagnostics appear and are cleared by a full document update.
    def test_diagnostics_edit_close(self):
        doc = self.client.open('diagnostic.script', 'local x = )\n')
        uri = doc['textDocument']['uri']
        def diagnostics():
            return [n['params'] for n in self.client.notifications if n.get('method')=='textDocument/publishDiagnostics' and n['params']['uri']==uri]
        self.client.wait(lambda: any(d['diagnostics'] for d in diagnostics()))
        with self.client.condition:
            self.client.notifications.clear()
        self.client.notify('textDocument/didChange', {**doc,'textDocument':{'uri':uri,'version':2},'contentChanges':[{'text':'local x = 1\nprint(x)\n'}]})
        self.client.wait(lambda: any(not d['diagnostics'] for d in diagnostics()))
        self.client.notify('textDocument/didClose', doc)

    # Verifies virtual lifecycle lines never leak into edits, diagnostics or semantic offsets.
    def test_lifecycle_edit_positions(self):
        if not BRIDGE:
            self.skipTest('requires production lifecycle adapter')
        text = 'function update(self, dt)\n    local elapsed=dt\n    print(elapsed)\nend\n'
        doc = self.client.open('mapped.script', text)
        params = {**doc, 'position': {'line': 1, 'character': 12}}
        edit = self.client.request('textDocument/rename', {**params, 'newName': 'duration'})
        edits = edit['changes'][doc['textDocument']['uri']]
        self.assertEqual([1, 2], sorted(e['range']['start']['line'] for e in edits))
        formatted = self.client.request('textDocument/formatting', {**doc, 'options': {'tabSize': 4, 'insertSpaces': True}})
        self.assertTrue(formatted)
        self.assertNotIn('__DEFOLD_LIFECYCLE__', json.dumps(formatted))
        self.assertNotIn('---@param', json.dumps(formatted))
        self.assertEqual(0, formatted[0]['range']['start']['line'])
        tokens = self.client.request('textDocument/semanticTokens/full', doc)['data']
        row = col = 0
        lines = text.split('\n')
        for dr, dc, length, _, _ in zip(*(iter(tokens),) * 5):
            row += dr
            col = col + dc if dr == 0 else dc
            self.assertLess(row, len(lines))
            self.assertLessEqual(col + length, len(lines[row]))

    # Verifies incremental UTF-16 edits and diagnostics retain original source coordinates.
    def test_incremental_unicode_diagnostics(self):
        if not BRIDGE:
            self.skipTest('requires production lifecycle adapter')
        doc = self.client.open('unicode.script', 'function update(self, dt)\n    print("中文😀", dt)\nend\n')
        uri = doc['textDocument']['uri']
        before = len(self.client.notifications)
        self.client.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 2},
            'contentChanges': [{'range': {'start': {'line': 1, 'character': 18}, 'end': {'line': 1, 'character': 20}}, 'text': ')'}]})
        def errors():
            return [d for n in self.client.notifications[before:] if n.get('method') == 'textDocument/publishDiagnostics'
                    and n['params']['uri'] == uri for d in n['params']['diagnostics'] if d.get('severity') == 1]
        self.client.wait(lambda: bool(errors()))
        self.assertTrue(all(d['range']['start']['line'] <= 2 for d in errors()))
        before = len(self.client.notifications)
        self.client.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 3},
            'contentChanges': [{'text': 'function update(self, dt)\n    print(dt)\nend\n'}]})
        self.client.wait(lambda: any(n.get('method') == 'textDocument/publishDiagnostics' and n['params']['uri'] == uri
                                    and not any(d.get('severity') == 1 for d in n['params']['diagnostics'])
                                    for n in self.client.notifications[before:]))

    # Verifies the separate Lua 5.2 editor script server supplies editor API completions.
    def test_editor_script(self):
        with tempfile.TemporaryDirectory(prefix='defold-emmy-editor-') as directory:
            client = Client(Path(directory), editor=True)
            try:
                doc = client.open('tools.editor_script', 'editor.')
                result = client.request('textDocument/completion', {**doc, 'position': {'line': 0, 'character': 7}})
                items = result.get('items', []) if isinstance(result, dict) else result or []
                self.assertIn('get', [i['label'] for i in items])
            finally:
                client.close()

    # Verifies a workspace can be reopened after shutdown and preserves module analysis.
    def test_same_project_restart(self):
        with tempfile.TemporaryDirectory(prefix='defold-emmy-restart-') as directory:
            root = Path(directory)
            (root/'persist.lua').write_text('return {answer = 42}\n')
            for _ in range(2):
                client = Client(root)
                try:
                    doc = client.open('main.lua', 'local p = require "persist"\np.')
                    result = client.request('textDocument/completion', {**doc, 'position': {'line': 1, 'character': 2}})
                    items = result.get('items', []) if isinstance(result, dict) else result or []
                    self.assertIn('answer', [i['label'] for i in items])
                finally:
                    client.close()

    # Verifies a second project starts and shuts down without leaking the first project's types.
    def test_project_isolation_restart(self):
        with tempfile.TemporaryDirectory(prefix='defold-emmy-second-') as directory:
            second = Client(Path(directory))
            try:
                doc = second.open('isolated.lua', '---@type Player\nlocal player\nplayer.')
                result = second.request('textDocument/completion', {**doc,'position':{'line':2,'character':7}})
                self.assertNotIn('hp', [i['label'] for i in (result.get('items',[]) if isinstance(result,dict) else result or [])])
            finally:
                second.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--server', required=True)
    parser.add_argument('--bridge-classpath')
    parser.add_argument('--java', default='java')
    args, remaining = parser.parse_known_args()
    SERVER = str(Path(args.server).resolve())
    if args.bridge_classpath:
        BRIDGE = [args.java, '--enable-native-access=ALL-UNNAMED', '-cp', Path(args.bridge_classpath).read_text().strip(),
                  'clojure.main', '-e', '(do (javafx.application.Platform/startup (fn [] (javafx.application.Platform/setImplicitExit false))) (require (quote editor.lsp.emmy-bridge)) (apply (resolve (quote editor.lsp.emmy-bridge/-main)) *command-line-args*))', '--']
    unittest.main(argv=['test_emmylua'] + remaining, verbosity=2)
