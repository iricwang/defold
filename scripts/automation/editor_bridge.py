"""Bounded authenticated loopback adapter; no arbitrary editor eval or automatic save."""
import json
import urllib.request
import urllib.error
from pathlib import Path

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Editor redirects are not permitted')

def call(project, endpoint, body):
    if endpoint not in ('snapshot', 'edit', 'history'):
        raise ValueError('Unsupported editor operation')
    internal = Path(project) / '.internal'
    discovery_path = internal / 'photocraft/editor-adapter.json'
    token_path = internal / 'editor.token'
    if not discovery_path.is_file() or not token_path.is_file():
        raise ValueError('No live editor adapter for this project; open it in an editor with automation routes')
    discovery = json.loads(discovery_path.read_text(encoding='utf-8'))
    port = discovery.get('port')
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError('Invalid local editor discovery')
    token = token_path.read_text(encoding='utf-8').strip()
    request = urllib.request.Request(f'http://127.0.0.1:{port}/automation/v1/{endpoint}',
        data=json.dumps(body,ensure_ascii=False).encode('utf-8'),
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method='POST')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    try:
        response=opener.open(request,timeout=30)
    except urllib.error.HTTPError as error:
        response=error
    with response:
        raw=response.read(4*1024*1024+1)
        if len(raw)>4*1024*1024: raise ValueError('Editor response exceeds 4 MiB')
        result=json.loads(raw)
        if result.get('error'):
            raise ValueError(str(result['error'].get('code','EDITOR_ERROR'))+': '+str(result['error'].get('message','')))
        if response.status != 200: raise ValueError('Editor request rejected')
    if result.get('editorSessionId') != discovery.get('editorSessionId'):
        raise ValueError('Editor session changed; inspect a new snapshot')
    return result
