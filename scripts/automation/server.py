"""Defold background MCP v1. Launch with an explicit allowlist of projects."""
import argparse
import asyncio
import base64
import io
import json
from pathlib import Path
import sys
import site

ROOT = Path(__file__).resolve().parents[2]
# Local development dependencies are isolated from the system Python installation.
sys.path.insert(0, str(ROOT / 'tmp/automation-python'))
site.addsitedir(str(ROOT / 'tmp/automation-python'))

import anyio
from mcp.server.lowlevel import Server
from mcp.server.lowlevel.helper_types import ReadResourceContents
from mcp.server.stdio import stdio_server
from mcp import types
from PIL import Image

from core import AutomationError, Service
from build import digest
import scenarios


def obj(properties, required=()):
    return {'type': 'object', 'properties': properties, 'required': list(required), 'additionalProperties': False}


STRING = {'type': 'string', 'minLength': 1, 'maxLength': 256}
INTEGER = {'type': 'integer', 'minimum': 0}
DEVICE = obj({**{key: {'type': 'integer', 'minimum': 32, 'maximum': 4096} for key in ('width', 'height')},
              **{key: {'type': 'integer', 'minimum': 0, 'maximum': 4095} for key in ('top', 'right', 'bottom', 'left')}},
             ('width', 'height'))
PROJECT_CONFIG = {'type': 'object', 'maxProperties': 16, 'additionalProperties': False,
                  'patternProperties': {r'^project\.[a-zA-Z0-9_]+$': {'type': 'string', 'maxLength': 256,
                                                                  'pattern': r'^[^\u0000\r\n]*$'}}}
POINT = {key: {'type': 'integer', 'minimum': 0, 'maximum': 4095} for key in ('x', 'y')}
REGION = obj({**POINT, **{key: {'type': 'integer', 'minimum': 1, 'maximum': 4096} for key in ('width', 'height')}},
             ('x', 'y', 'width', 'height'))
BUTTON = {'type': 'integer', 'enum': [1, 2, 4]}
ACTION = {'oneOf': [
    obj({'type': {'const': 'click'}, **POINT, 'button': BUTTON}, ('type', 'x', 'y')),
    obj({'type': {'const': 'drag'}, **POINT, 'to_x': POINT['x'], 'to_y': POINT['y'],
         'steps': {'type': 'integer', 'minimum': 1, 'maximum': 120}, 'button': BUTTON},
        ('type', 'x', 'y', 'to_x', 'to_y')),
    obj({'type': {'const': 'move'}, **POINT}, ('type', 'x', 'y')),
    obj({'type': {'const': 'key'}, 'key': {'type': 'integer', 'minimum': 32, 'maximum': 155}}, ('type', 'key')),
    obj({'type': {'const': 'text'}, 'text': {'type': 'string', 'maxLength': 4096}}, ('type', 'text')),
    obj({'type': {'const': 'wheel'}, **POINT, 'delta': {'type': 'integer', 'minimum': -100, 'maximum': 100}},
        ('type', 'x', 'y', 'delta'))]}
SCENARIO = scenarios.schema(obj, STRING, DEVICE, ACTION, PROJECT_CONFIG, REGION)
SUPPORT_OBJECTS = {'type': 'array', 'maxItems': 32, 'items': obj({
    'id': {'type': 'string', 'pattern': '^[A-Za-z0-9_-]{1,64}$'}, 'resource_path': STRING}, ('id', 'resource_path'))}
SELECTOR = {**obj({key: STRING for key in ('id','element_id','type','text')}), 'minProperties':1}
LAYOUT_RULE = obj({'kind': {'enum':['inside_viewport','inside_safe_area','no_overlap']},
                   'selector': SELECTOR, 'other': SELECTOR}, ('kind','selector'))
LAYOUT_RULE['allOf']=[{'if':{'properties':{'kind':{'const':'no_overlap'}}},'then':{'required':['other']}}]


def tools():
    session = {'session_id': STRING}
    operation = {'operation_id': STRING}
    definitions = [
        ('editor_snapshot', 'Read one GUI including unsaved graph content, node properties and undo guard. Does not save/open/select tabs.',
         obj({'project_id':STRING,'path':STRING},('project_id','path')),True),
        ('editor_gui_edit', 'Apply bounded existing-node base-layout property edits as one native undo transaction. Requires snapshot session/revision/history. Leaves file unsaved.',
         obj({'project_id':STRING,'path':STRING,'editorSessionId':STRING,'expectedRevision':STRING,'expectedHistory':STRING,**operation,
              'changes':{'type':'array','minItems':1,'maxItems':64,'items':obj({'id':STRING,'property':STRING,'value':{}},('id','property','value'))}},
             ('project_id','path','editorSessionId','expectedRevision','expectedHistory','operation_id','changes')),False),
        ('editor_history', 'Undo/redo only the unchanged top automation transaction. Intervening user edits reject the operation; never saves.',
         obj({'project_id':STRING,'path':STRING,'editorSessionId':STRING,'expectedRevision':STRING,'expectedHistory':STRING,**operation,
              'transactionId':STRING,'action':{'enum':['undo','redo']}},
             ('project_id','path','editorSessionId','expectedRevision','expectedHistory','operation_id','transactionId','action')),False),
        ('editor_preview_build', 'Freeze explicit unsaved GUI snapshots into an isolated local source build. Other dependencies remain disk based. Native-extension source builds unsupported; use compiled preview for those.',
         obj({'project_id':STRING,'resource_path':STRING,'include_gui_paths':{'type':'array','items':STRING,'maxItems':31},**operation},
             ('project_id','resource_path','operation_id')),False),
        ('project_discover', 'List explicitly registered projects and actual v1 capabilities.', obj({}), True),
        ('build_run', 'Build a frozen disk copy locally; returns a job. Never saves editor files or uploads native sources.',
         obj({'project_id': STRING, **operation}, ('project_id', 'operation_id')), False),
        ('resource_search', 'Search saved project GUI/GO/Collection paths. Excludes build output, links and dependency-library files; does not read unsaved editor changes.',
         obj({'project_id': STRING, 'query': {'type': 'string', 'maxLength': 256},
              'types': {'type': 'array', 'items': {'enum': ['gui', 'go', 'collection']}, 'maxItems': 3, 'uniqueItems': True},
              'offset': INTEGER, 'limit': {'type': 'integer', 'minimum': 1, 'maximum': 100}}, ('project_id',)), True),
        ('preview_build', 'Build a GUI/GO/Collection as an isolated runtime entry in a disk snapshot. Scripts execute, original bootstrap services are absent, GO stays at origin. Returns a build job usable by session_start/scenario_run.',
         obj({'project_id': STRING, 'resource_path': STRING, **operation}, ('project_id', 'resource_path', 'operation_id')), False),
        ('build_import', 'Freeze existing build/default plus the matching project engine/PDB. Does not rebuild changed sources. Supports native-extension projects without uploading source.',
         obj({'project_id': STRING, **operation}, ('project_id', 'operation_id')), False),
        ('preview_import', 'Preview a compiled GUI/GO/Collection using shared frozen resources and its native engine, without recompiling or copying resources. Explicit support_objects may supply audio/managers/cameras. Original bootstrap services otherwise absent. Returns a job.',
         obj({'build_id': STRING, 'resource_path': STRING, 'support_objects': SUPPORT_OBJECTS,
              'instance_id': {'type': 'string', 'pattern': '^[A-Za-z0-9_-]{1,64}$'}, **operation}, ('build_id', 'resource_path', 'operation_id')), False),
        ('job_status', 'Read background build/start completion or failure.', obj({'job_id': STRING}, ('job_id',)), True),
        ('job_cancel', 'Cancel an owned job. Completed work is not undone.', obj({'job_id': STRING}, ('job_id',)), False),
        ('operation_status', 'Read an accepted operation without replaying its side effects.', obj(operation, ('operation_id',)), True),
        ('session_start', 'Start an owned hidden GPU runtime from a completed build. Returns a job; no desktop input.',
         obj({'build_id': STRING, 'device': DEVICE, 'project_config': PROJECT_CONFIG, 'app_channel':{'type':'boolean'}, **operation},
             ('build_id', 'device', 'operation_id')), False),
        ('session_status', 'Inspect the owned process, device revision and bridge frame sequence.', obj(session, ('session_id',)), True),
        ('session_stop', 'Release inputs and stop only this server-owned game.', obj(session, ('session_id',)), False),
        ('device_list', 'Search existing simulator presets. Rotations are quarter turns; safeArea is bottom-left x/y/w/h.',
         obj({'query': {'type': 'string', 'maxLength': 256}, 'offset': INTEGER,
              'limit': {'type': 'integer', 'minimum': 1, 'maximum': 50}}), True),
        ('device_resolve', 'Resolve a preset and rotation to runtime dimensions and safe insets; cutout masks are metadata only.',
         obj({'name': STRING, 'rotation': {'type': 'integer', 'enum': [0, 1, 2, 3]}}, ('name', 'rotation')), True),
        ('device_apply', 'Apply dimensions and safe insets; old-device observations become invalid. Returns a new capture.',
         obj({**session, **operation, 'device': DEVICE, 'expected_revision': INTEGER},
             ('session_id', 'operation_id', 'device', 'expected_revision')), False),
        ('input_acquire', 'Acquire control after release/expiry. Owned startup already returns the first lease.', obj(session, ('session_id',)), False),
        ('input_renew', 'Renew an owned input lease for 60 seconds.', obj({**session, 'lease_id': STRING}, ('session_id', 'lease_id')), False),
        ('input_release', 'Release control. All v1 gestures already release keys/buttons before returning.', obj({**session, 'lease_id': STRING}, ('session_id', 'lease_id')), False),
        ('input_perform', 'Perform a bounded gesture in original screenshot pixels, top-left origin. Dispatch is not a business callback ACK.',
         obj({**session, **operation, 'lease_id': STRING, 'action': ACTION, 'observation_id': STRING},
             ('session_id', 'operation_id', 'lease_id', 'action', 'observation_id')), False),
        ('input_status', 'Read last completed input receipt for this session.', obj(session, ('session_id',)), True),
        ('capture', 'Capture a fresh original GPU frame with bridge metadata. Includes an inline thumbnail and original image resource.', obj(session, ('session_id',)), True),
        ('element_query', 'Query exact selectors in the immutable scene captured at this image render boundary. Verified default GUI rectangles only; unsupported text/custom projection/clipping is unknown.',
         obj({'artifact_id':STRING,'selector':SELECTOR,'offset':INTEGER,'limit':{'type':'integer','minimum':1,'maximum':100}},('artifact_id',)), True),
        ('element_click', 'Resolve one GUI element again in a fresh frame and click its visible convex rectangle. Rejects ambiguous, stale, hidden and unsupported geometry. Does not prove business success or absence of occlusion.',
         obj({**session,**operation,'lease_id':STRING,'observation_id':STRING,'selector':SELECTOR},('session_id','operation_id','lease_id','observation_id','selector')),False),
        ('application_command', 'Call a command explicitly registered by the game QA SDK and wait for the exact operation ID result. Requires app_channel at startup; no eval or automatic source injection.',
         obj({**session,**operation,'lease_id':STRING,'name':{'type':'string','pattern':'^[A-Za-z][A-Za-z0-9_.-]{0,63}$'},'arguments':{'type':'object'},
              'timeout_ms':{'type':'integer','minimum':1,'maximum':30000}},('session_id','operation_id','lease_id','name')),False),
        ('element_click_verified', 'Arm an operation ID in the integrated game QA SDK, resolve/click an element and wait for the matching application event. Timeout does not undo a click and must not be blindly retried.',
         obj({**session,**operation,'lease_id':STRING,'observation_id':STRING,'selector':SELECTOR,'event':STRING,
              'timeout_ms':{'type':'integer','minimum':1,'maximum':30000}},('session_id','operation_id','lease_id','observation_id','selector','event')),False),
        ('layout_check', 'Check GUI rectangle containment/safe area or explicit pair overlap in one captured frame. Returns unknown when geometry is unsupported; text/custom shaders are not pixel coverage checks.',
         obj({'artifact_id':STRING,'rules':{'type':'array','minItems':1,'maxItems':64,'items':LAYOUT_RULE}},('artifact_id','rules')),True),
        ('device_overlay', 'Create a derived inspection image with catalog cutout rectangles and safe-area outline. Requires matching preset dimensions/insets; original PNG stays unchanged.',
         obj({'artifact_id':STRING,'name':STRING,'rotation':{'type':'integer','enum':[0,1,2,3]}},('artifact_id','name','rotation')),True),
        ('capture_region', 'Crop an immutable original runtime artifact. Returns source observation and pixel offset; map coordinates back before input. Does not capture a new frame.',
         obj({'artifact_id': STRING, 'region': REGION}, ('artifact_id', 'region')), True),
        ('image_compare', 'Compare two original frames with identical devices. Returns RGB pixel metrics and a difference image; never infers business success. Ignores alpha; no resizing.',
         obj({'before_id': STRING, 'after_id': STRING, 'region': REGION,
              'channel_threshold': {'type': 'integer', 'minimum': 0, 'maximum': 255},
              'max_changed_ratio': {'type': 'number', 'minimum': 0, 'maximum': 1},
              'ignore': {'type': 'array', 'items': REGION, 'maxItems': 16}}, ('before_id', 'after_id')), True),
        ('scene_query', 'Search live engine nodes by id/type/text substring. Sample paths are not persistent IDs. Independent of screenshots; no exact screen geometry.',
         obj({**session, 'selector': obj({key: STRING for key in ('id', 'type', 'text')}),
              'limit': {'type': 'integer', 'minimum': 1, 'maximum': 500}}, ('session_id',)), True),
        ('logs_read', 'Read bounded runtime logs with a cursor and explicit overflow.', obj({**session, 'after': INTEGER}, ('session_id',)), True),
        ('wait', 'Wait for a log substring AFTER the supplied cursor, then capture. Prefer application event markers over frame sleeps.',
         obj({**session, 'contains': STRING, 'after': INTEGER,
              'timeout_ms': {'type': 'integer', 'minimum': 1, 'maximum': 30000}},
             ('session_id', 'contains', 'after')), True),
        ('report_export', 'Write an offline HTML/JSON screenshot report for this session.', obj(session, ('session_id',)), False),
        ('artifact_get', 'Read a previously captured artifact, even after stopping its game.', obj({'artifact_id': STRING}, ('artifact_id',)), True),
        ('scenario_run', 'Run a bounded declarative scenario across up to 8 devices using one frozen build. Returns a job; result.state is passed/failed/cancelled. Every case owns and closes its game; retains failure images/logs. No arbitrary code.',
         obj({'build_id': STRING, 'scenario': SCENARIO, **operation}, ('build_id', 'scenario', 'operation_id')), False),
        ('scenario_get', 'Read a compact run summary or a bounded page of device steps, including failed assertion evidence. Use the run_id from job progress or result.',
         obj({'run_id': STRING, 'case_index': INTEGER, 'offset': INTEGER,
              'limit': {'type': 'integer', 'minimum': 1, 'maximum': 8}}, ('run_id',)), True),
    ]
    return [types.Tool(name='defold_' + name, description=description, inputSchema=schema,
                       outputSchema={'type': 'object'},
                       annotations=types.ToolAnnotations(readOnlyHint=readonly, destructiveHint=False,
                                                         openWorldHint=False))
            for name, description, schema, readonly in definitions]


def devices():
    return json.loads((ROOT / 'editor/resources/game-preview/devices.json').read_text(encoding='utf-8'))


def dispatch(service, name, arguments):
    args = dict(arguments)
    name = name.removeprefix('defold_')
    if name == 'project_discover':
        return service.discover()
    if name == 'editor_snapshot':
        return service.editor_call(args['project_id'],'snapshot',{'path':args['path']})
    if name in ('editor_gui_edit','editor_history'):
        project_id=args.pop('project_id')
        key=args.pop('operation_id')
        endpoint='edit' if name=='editor_gui_edit' else 'history'
        return service.operation(key,{'tool':name,'project_id':project_id,**args},
            lambda:service.editor_call(project_id,endpoint,{**args,'operationId':key}))
    if name == 'editor_preview_build':
        return service.submit(args['operation_id'],{'tool':name,**args},
            lambda job:service.editor_preview(args['project_id'],args['resource_path'],job,args.get('include_gui_paths')))
    if name == 'resource_search':
        return service.resources(**args)
    if name == 'element_query': return service.elements(**args)
    if name == 'layout_check': return service.layout_check(**args)
    if name == 'element_click':
        key=args.pop('operation_id')
        return service.operation(key, {'tool':name,**args}, lambda:service.element_click(**args))
    if name in ('application_command','element_click_verified'):
        function=getattr(service,name)
        return service.operation(args['operation_id'],{'tool':name,**args},lambda:function(**args))
    if name == 'device_overlay':
        preset=dispatch(service,'device_resolve',{'name':args['name'],'rotation':args['rotation']})
        return service.cutout_overlay(args['artifact_id'],preset['device'],preset['cutouts'],args['name'])
    if name == 'preview_build':
        return service.submit(args['operation_id'], {'tool': name, **args},
                              lambda job: service.build(args['project_id'], job, args['resource_path']))
    if name == 'preview_import':
        return service.submit(args['operation_id'], {'tool': name, **args},
                              lambda job: service.preview_import(args['build_id'], args['resource_path'], job,
                                  args.get('support_objects'), args.get('instance_id', 'preview')))
    if name in ('build_run', 'build_import'):
        build_function = service.build if name == 'build_run' else service.import_build
        return service.submit(args['operation_id'], {'tool': name, **args},
                              lambda job: build_function(args['project_id'], job))
    if name == 'session_start':
        return service.submit(args['operation_id'], {'tool': name, **args},
                              lambda job: service.start(args['build_id'], args['device'], job, args.get('project_config'), args.get('app_channel',False)))
    if name == 'scenario_run':
        scenarios.validate(args['scenario'], SCENARIO)
        return service.submit(args['operation_id'], {'tool': name, **args},
                              lambda job: scenarios.run(service, args['build_id'], args['scenario'], job))
    if name == 'scenario_get':
        return scenarios.inspect(service, **args)
    if name in ('job_status', 'job_cancel'):
        return service.job_status(args['job_id'], name == 'job_cancel')
    if name == 'operation_status':
        result = service.operations.get(args['operation_id'])
        if not result:
            raise AutomationError('OPERATION_NOT_FOUND', args['operation_id'])
        return dict(result)
    if name == 'session_status':
        return service.session(args['session_id'])['worker'].call('status')
    if name == 'session_stop':
        return service.stop(args['session_id'])
    if name in ('input_acquire', 'input_renew', 'input_release'):
        return service.lease(**args, release=name == 'input_release')
    if name in ('input_perform', 'device_apply'):
        operation_id = args.pop('operation_id')
        function = service.input if name == 'input_perform' else service.device
        return service.operation(operation_id, {'tool': name, **args}, lambda: function(**args))
    if name == 'input_status':
        return service.session(args['session_id']).get('last_input', {'state': 'none'})
    if name == 'capture':
        return service.capture(**args)
    if name == 'capture_region':
        return service.crop(**args)
    if name == 'image_compare':
        return service.compare(**args)
    if name == 'scene_query':
        return service.scene(**args)
    if name == 'logs_read':
        return service.logs(**args)
    if name == 'wait':
        return service.wait_log(**args)
    if name == 'report_export':
        return service.report(**args)
    if name == 'artifact_get':
        result = service.artifacts.get(args['artifact_id'])
        if not result:
            raise AutomationError('ARTIFACT_NOT_FOUND', args['artifact_id'])
        return result
    if name == 'device_list':
        catalog = devices()
        matches = [item for item in catalog['devices'] if args.get('query', '').lower() in item['name'].lower()]
        offset, limit = args.get('offset', 0), args.get('limit', 10)
        return {'source': catalog['source'], 'total': len(matches), 'devices': matches[offset:offset + limit],
                'next_offset': offset + limit if offset + limit < len(matches) else None}
    if name == 'device_resolve':
        match = next((item for item in devices()['devices'] if item['name'] == args['name']), None)
        if match is None:
            raise AutomationError('DEVICE_NOT_FOUND', args['name'])
        orientation = next((item for item in match['orientations'] if item['rotation'] == args['rotation']), None)
        if orientation is None:
            raise AutomationError('UNSUPPORTED_CAPABILITY', 'Preset does not define this orientation')
        width, height = match['width'], match['height']
        if args['rotation'] % 2:
            width, height = height, width
        x, y, w, h = orientation['safeArea']
        return {'device': {'width': width, 'height': height, 'top': round(height - y - h),
                           'right': round(width - x - w), 'bottom': round(y), 'left': round(x)},
                'name': match['name'], 'rotation': args['rotation'], 'cutouts': orientation['cutouts'],
                'cutout_rendering': 'not_applied_to_raw_game_image'}
    raise AutomationError('UNKNOWN_TOOL', name)


def artifact_content(result):
    # Full scene samples stay in immutable JSON on disk; MCP queries are bounded and paginated.
    def compact(value):
        if isinstance(value,dict): return {k: ({**{a:b for a,b in v.items() if a!='nodes'},'node_count':len(v.get('nodes',[]))}
                                              if k=='scene_snapshot' else compact(v)) for k,v in value.items()}
        if isinstance(value,list): return [compact(v) for v in value]
        return value
    result = compact(result)
    artifact = result if 'image_path' in result else result.get('after')
    content = [types.TextContent(type='text', text=json.dumps(result, ensure_ascii=False))]
    if artifact and 'image_path' in artifact:
        with Image.open(artifact['image_path']) as original:
            preview = original.copy()
            preview.thumbnail((960, 960))
            output = io.BytesIO()
            preview.save(output, format='PNG')
        content.extend([
            types.ImageContent(type='image', mimeType='image/png', data=base64.b64encode(output.getvalue()).decode()),
            types.ResourceLink(type='resource_link', uri=artifact['uri'], name='Original runtime frame', mimeType='image/png')])
        result = {**result, 'inline_preview': {'width': preview.width, 'height': preview.height,
                  'to_frame_scale': [artifact['width'] / preview.width, artifact['height'] / preview.height],
                  'to_frame_offset': artifact.get('to_frame_offset', [0, 0]),
                  'source_observation_id': artifact['observation_id']}}
    return types.CallToolResult(content=content, structuredContent=result)


def make_server(service):
    server = Server('defold-background', version='0.1.0')

    @server.list_tools()
    async def list_tools():
        return tools()

    @server.call_tool()
    async def call_tool(name, arguments):
        try:
            result = await anyio.to_thread.run_sync(lambda: dispatch(service, name, arguments))
            return await anyio.to_thread.run_sync(lambda: artifact_content(result))
        except Exception as error:
            payload = {'error': {'code': getattr(error, 'code', 'INTERNAL_ERROR'), 'message': str(error)}}
            return types.CallToolResult(content=[types.TextContent(type='text', text=json.dumps(payload))],
                                        structuredContent=payload, isError=True)

    @server.read_resource()
    async def read_resource(uri):
        uri = str(uri)
        for item in service.artifacts.values():
            if uri == item['uri']:
                return [ReadResourceContents(Path(item['image_path']).read_bytes(), 'image/png')]
        raise ValueError('Unknown artifact URI')

    return server


async def serve(service):
    server = make_server(service)
    try:
        async with stdio_server() as (read_stream, write_stream):
            await server.run(read_stream, write_stream, server.create_initialization_options())
    finally:
        await anyio.to_thread.run_sync(service.close)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'tmp/automation-runtime/manifest.json')
    parser.add_argument('--project', type=Path, action='append', required=True)
    parser.add_argument('--artifacts', type=Path, default=ROOT / 'tmp/automation-artifacts')
    parser.add_argument('--engine', type=Path, help='Matching locally compiled custom engine (adjacent PDB on Windows)')
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    from build import host_platform
    if manifest.get('platform', 'x86_64-win32') != host_platform():
        raise RuntimeError('Automation manifest belongs to another platform; build locally on this host')
    for path, expected in {manifest['jar']: manifest['inputs'][manifest['jar']], **manifest['outputs']}.items():
        if not Path(path).is_file() or digest(path) != expected:
            raise RuntimeError('Automation build does not match installed files; rebuild the local automation sidecar: ' + path)
    service = Service(manifest, args.project, args.artifacts, args.engine)
    anyio.run(serve, service)


if __name__ == '__main__':
    main()
