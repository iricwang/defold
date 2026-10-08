"""Build editor-only patches from a released file inventory, without bundling an app."""
import hashlib
import json
import re
import zipfile

import delta


def bootstrap(bundle, platform):
    metadata = delta.metadata(bundle)
    with zipfile.ZipFile(bundle) as archive:
        files = delta.inventory(archive)
        runtimes = {name.split('/')[3] for name in files
                    if name.startswith('Contents/Resources/packages/jdk-')}
        if len(runtimes) != 1:
            raise ValueError('Expected one bundled JDK')
        runtime = runtimes.pop()
        release = archive.read(f'Defold.app/Contents/Resources/packages/{runtime}/release').decode()
        modules = re.search(r'^MODULES="([^"]+)"', release, re.MULTILINE)
        if not modules:
            raise ValueError('JDK module inventory missing')
    return {'schema': 1, 'platform': platform, 'metadata': metadata,
            'runtime': {'version': runtime[4:], 'modules': modules.group(1).split()},
            'files': list(files.values())}


def create(base, payload, metadata, platform, java_version, jlink_options, output):
    if (base['schema'] != 1 or base['platform'] != platform
            or metadata['channel'] != base['metadata']['channel']
            or metadata['repository'] != base['metadata']['repository']
            or metadata['revision'] <= base['metadata']['revision']):
        raise ValueError('Patch requires a matching released baseline and a newer revision')
    modules = set(re.findall(r'^--add-modules=([^\s#]+)', jlink_options, re.MULTILINE))
    if java_version != base['runtime']['version'] or not modules.issubset(base['runtime']['modules']):
        raise ValueError('JDK changed; patch-only builds cannot replace the runtime')
    before = {item['path']: item for item in base['files']}
    if any('_CodeSignature' in path for path in before):
        raise ValueError('Signed application baselines require a signed full installation')
    after = {path: item for path, item in before.items()
             if not (path.startswith('Contents/Resources/packages/defold-') and path.endswith('.jar'))}
    replacements = {}
    for source in sorted(payload.rglob('*')):
        if source.is_file():
            name = source.relative_to(payload).as_posix()
            if not name.startswith('Contents/') or '/jdk-' in name or source.is_symlink():
                raise ValueError(f'Unsafe patch payload: {name}')
            with source.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            mode = 0o755 if name == 'Contents/MacOS/Defold' else 0o644
            after[name] = {'path': name, 'sha256': digest, 'mode': mode, 'kind': 'file'}
            replacements[name] = source
    jars = [name for name in replacements if name.startswith('Contents/Resources/packages/defold-') and name.endswith('.jar')]
    if len(jars) != 1 or 'Contents/Resources/config' not in replacements or 'Contents/MacOS/Defold' not in replacements:
        raise ValueError('Incomplete editor patch payload')
    with zipfile.ZipFile(replacements[jars[0]]) as jar:
        embedded = json.loads(jar.read('patch/build.json'))
        if any(embedded[key] != metadata[key] for key in ('revision', 'channel', 'repository', 'version')):
            raise ValueError('Editor JAR metadata does not match the patch')
    plan = {'schema': 1, 'base_revision': base['metadata']['revision'], 'revision': metadata['revision'],
            'base': list(before.values()), 'target': list(after.values())}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('patch.json', json.dumps(plan))
        for name, source in replacements.items():
            if before.get(name) != after[name]:
                archive.write(source, 'files/' + name)
    return {**base, 'metadata': metadata, 'base_metadata': base['metadata'], 'files': list(after.values())}
