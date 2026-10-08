#!/usr/bin/env python3
"""Prepare branch patch metadata and publish verified macOS packages."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

import package

ROOT = Path(__file__).resolve().parents[1]
PLATFORMS = ("arm64-macos", "x86_64-macos")


def channel():
    data = json.loads((ROOT / "patch/channel.json").read_text())
    if not (data["schema"] == 1 and type(data["revision"]) is int
            and data["revision"] > 0
            and re.fullmatch(r"[a-z0-9-]+", data["channel"])
            and re.fullmatch(r"[\w.-]+/[\w.-]+", data["repository"])
            and isinstance(data["version"], str) and data["version"].strip()
            and isinstance(data["notes"], str)):
        raise ValueError("Invalid patch/channel.json")
    return data


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def stage():
    data = channel()
    data["feed"] = (f'https://github.com/{data["repository"]}/releases/'
                    f'download/patch-{data["channel"]}/manifest.json')
    write_json(ROOT / "editor/resources/patch/build.json", data)


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def publish(directory, commit):
    data = channel()
    repo = data["repository"]
    tag = f'patch-{data["channel"]}-{data["revision"]}'
    feed_tag = f'patch-{data["channel"]}'
    # List through the API: auth/network failures must never look like missing releases.
    releases = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    releases = [release for page in releases for release in page]
    existing = next((r for r in releases if r["tag_name"] == tag), None)
    if any(re.fullmatch(re.escape(feed_tag) + r"-(\d+)", r["tag_name"])
           and int(r["tag_name"].rsplit("-", 1)[1]) > data["revision"] for r in releases):
        raise ValueError("Refusing to publish an older patch revision")
    manifest_path = directory / "manifest.json"
    if existing and not existing["draft"]:
        # Also repairs an interrupted feed publication without replacing the package.
        print(f"{tag} already published; reusing its immutable manifest.")
        gh("release", "download", tag, "--repo", repo, "--pattern", "manifest.json",
           "--dir", str(directory), "--clobber")
        published = json.loads(manifest_path.read_text())
        if published.get("commit") != commit:
            raise ValueError("This revision belongs to another commit; run patch/manage.py bump --notes before publishing changes")
    else:
        data["commit"] = commit
        previous = max((r for r in releases
                        if not r["draft"] and re.fullmatch(re.escape(feed_tag) + r"-\d+", r["tag_name"])
                        and int(r["tag_name"].rsplit("-", 1)[1]) < data["revision"]),
                       key=lambda r: int(r["tag_name"].rsplit("-", 1)[1]), default=None)
        if not previous:
            raise ValueError("Patch-only publication requires an existing installation baseline")
        base_revision = int(previous["tag_name"].rsplit("-", 1)[1])
        base_url = f"https://github.com/{repo}/releases/download/{tag}"
        result = {**data, "installers": {}, "assets": {}, "states": {},
                  "previous": f"https://github.com/{repo}/releases/download/{previous['tag_name']}/manifest.json"}
        assets = []
        for platform in PLATFORMS:
            name = f"Defold-{platform}-patch-{base_revision}-to-{data['revision']}.zip"
            state_name = f"Defold-{platform}-state.json"
            state = json.loads((directory / state_name).read_text())
            if (state['platform'] != platform or any(state['metadata'].get(key) != data[key]
                    for key in ('revision', 'version', 'channel', 'repository', 'commit'))):
                raise ValueError('Patch state metadata does not match this release')
            with zipfile.ZipFile(directory / name) as archive:
                plan = json.loads(archive.read('patch.json'))
            if (plan['base_revision'] != base_revision or plan['revision'] != data['revision']
                    or plan['target'] != state['files']):
                raise ValueError('Patch package and state disagree')
            for filename, section in ((name, 'assets'), (state_name, 'states')):
                path = directory / filename
                with path.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                result[section][platform] = {'url': f'{base_url}/{filename}', 'sha256': digest,
                                             'size': path.stat().st_size}
                assets.append(str(path))
            result['assets'][platform]['base_revision'] = base_revision
        write_json(manifest_path, result)
        notes_path = directory / "notes.txt"
        notes_path.write_text(data["notes"])
        if not existing:
            gh("release", "create", tag, "--repo", repo, "--target", commit, "--draft",
               "--prerelease", "--title", data["version"], "--notes-file", str(notes_path))
        gh("release", "upload", tag, *assets, str(manifest_path), "--repo", repo, "--clobber")
        # Publish the immutable package release before pointing clients at its manifest.
        gh("release", "edit", tag, "--repo", repo, "--target", commit, "--draft=false")
    if not any(r["tag_name"] == feed_tag for r in releases):
        gh("release", "create", feed_tag, "--repo", repo, "--target", commit,
           "--prerelease", "--title", f'{data["channel"]} patch feed',
           "--notes", "Update manifest for the branch patch updater.")
    gh("release", "upload", feed_tag, str(manifest_path), "--repo", repo, "--clobber")


def prepare_base(directory, platform):
    """Fetch a small release inventory; bootstrap legacy releases from their ZIP once."""
    data = channel()
    repo = data['repository']
    prefix = f"patch-{data['channel']}-"
    pages = json.loads(gh('api', '--paginate', '--slurp', f'repos/{repo}/releases?per_page=100'))
    candidates = [release for page in pages for release in page if not release['draft']
                  and re.fullmatch(re.escape(prefix) + r'\d+', release['tag_name'])]
    latest = max(candidates, key=lambda release: int(release['tag_name'].rsplit('-', 1)[1]))
    directory.mkdir(parents=True, exist_ok=True)
    tag = latest['tag_name']
    gh('release', 'download', tag, '--repo', repo, '--pattern', 'manifest.json', '--dir', str(directory), '--clobber')
    released = json.loads((directory / 'manifest.json').read_text())
    if (released['repository'] != repo or released['channel'] != data['channel']
            or released['revision'] >= data['revision']):
        raise ValueError('Increment patch/channel.json before building a new patch')
    state_path = directory / f'Defold-{platform}-state.json'
    info = released.get('states', {}).get(platform)
    if info:
        gh('release', 'download', tag, '--repo', repo, '--pattern', state_path.name, '--dir', str(directory), '--clobber')
        if (state_path.stat().st_size != info['size']
                or hashlib.sha256(state_path.read_bytes()).hexdigest() != info['sha256']):
            raise ValueError('Downloaded baseline state checksum mismatch')
        state = json.loads(state_path.read_text())
    else:
        bundle = directory / f'Defold-{platform}.zip'
        gh('release', 'download', tag, '--repo', repo, '--pattern', bundle.name, '--dir', str(directory), '--clobber')
        asset = next(asset for asset in latest['assets'] if asset['name'] == bundle.name)
        with bundle.open('rb') as stream:
            digest = 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest()
        if bundle.stat().st_size != asset['size'] or (asset.get('digest') and digest != asset['digest']):
            raise ValueError('Downloaded baseline ZIP checksum mismatch')
        state = package.bootstrap(bundle, platform)
        state['metadata']['commit'] = released['commit']
        write_json(state_path, state)
        bundle.unlink()
    if (state['platform'] != platform or any(state['metadata'].get(key) != released[key]
            for key in ('revision', 'version', 'channel', 'repository', 'commit'))):
        raise ValueError('Baseline inventory does not match its release')
    print(state_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    base = sub.add_parser('prepare-base', help='Download the released file inventory for a patch-only build')
    base.add_argument('--directory', type=Path, required=True)
    base.add_argument('--platform', choices=PLATFORMS, required=True)
    sub.add_parser("stage", help="Embed the installed patch version before building the editor")
    bump = sub.add_parser("bump", help="Increment the patch revision for the next change")
    bump.add_argument("--notes", required=True)
    local = sub.add_parser("local", help="Prepare a local update feed from a patch and its state")
    local.add_argument("--patch", type=Path, required=True)
    local.add_argument("--state", type=Path, required=True)
    local.add_argument("--platform", choices=PLATFORMS, default="arm64-macos")
    local.add_argument("--port", type=int, default=8765)
    release = sub.add_parser("publish", help="Publish both CI packages, then update the feed")
    release.add_argument("--directory", type=Path, required=True)
    release.add_argument("--commit", required=True)
    args = parser.parse_args()
    if args.command == "prepare-base":
        prepare_base(args.directory, args.platform)
    elif args.command == "stage":
        stage()
    elif args.command == "bump":
        if not args.notes.strip():
            parser.error("--notes must describe the change")
        data = channel()
        data["revision"] += 1
        base_version = re.sub(r"-patch\.\d+$", "", data["version"])
        data["version"] = f'{base_version}-patch.{data["revision"]}'
        data["notes"] = args.notes.strip()
        write_json(ROOT / "patch/channel.json", data)
        stage()
        print(data["version"])
    elif args.command == "local":
        state = json.loads(args.state.read_text())
        data = state['metadata']
        with zipfile.ZipFile(args.patch) as archive:
            plan = json.loads(archive.read('patch.json'))
        if (state['platform'] != args.platform or plan['revision'] != data['revision']
                or plan['target'] != state['files']):
            raise ValueError('Local patch does not match its target state')
        directory = ROOT / "patch/local"
        directory.mkdir(exist_ok=True)
        base = f"http://127.0.0.1:{args.port}"
        write_json(directory / "build.json", {**state['base_metadata'], "feed": f"{base}/manifest.json"})
        output = directory / "preview-patch.zip"
        shutil.copyfile(args.patch, output)
        with output.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        result = {**data, 'installers': {}, 'assets': {args.platform: {
            'url': f'{base}/{output.name}', 'sha256': digest, 'size': output.stat().st_size,
            'base_revision': plan['base_revision']}}}
        write_json(directory / "manifest.json", result)
        print(f"Serve with: python3 -m http.server {args.port} --bind 127.0.0.1 --directory patch/local")
        print(f"Editor JVM option: -Ddefold.patch.directory={directory}")
    else:
        publish(args.directory, args.commit)


if __name__ == "__main__":
    main()
