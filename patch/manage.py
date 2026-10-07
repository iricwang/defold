#!/usr/bin/env python3
"""Prepare branch patch metadata and publish verified macOS packages."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

import delta

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


def manifest(directory, data, base_url, platforms):
    assets = {}
    for platform in platforms:
        name = f'Defold-{platform}-patch-{data["revision"]}.dmg'
        target = directory / name
        source = directory / f"Defold-{platform}.dmg"
        shutil.copyfile(source, target)
        with target.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        assets[platform] = {"url": f"{base_url}/{name}", "sha256": digest,
                            "size": target.stat().st_size}
    return {**data, "installers": assets, "assets": {}}


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
        result = manifest(directory, data, f"https://github.com/{repo}/releases/download/{tag}", PLATFORMS)
        previous = max((r for r in releases
                        if not r["draft"] and re.fullmatch(re.escape(feed_tag) + r"-\d+", r["tag_name"])
                        and int(r["tag_name"].rsplit("-", 1)[1]) < data["revision"]),
                       key=lambda r: int(r["tag_name"].rsplit("-", 1)[1]), default=None)
        if previous:
            base_revision = int(previous["tag_name"].rsplit("-", 1)[1])
            base_dir = directory / "base"
            base_dir.mkdir(exist_ok=True)
            gh("release", "download", previous["tag_name"], "--repo", repo,
               "--pattern", "Defold-*-macos.zip", "--dir", str(base_dir), "--clobber")
            for platform in PLATFORMS:
                name = f"Defold-{platform}-patch-{base_revision}-to-{data['revision']}.zip"
                output = directory / name
                try:
                    delta.create(base_dir / f"Defold-{platform}.zip", directory / f"Defold-{platform}.zip",
                                 output, base_revision, data["revision"])
                except ValueError as error:
                    # A runtime upgrade needs a complete installer.
                    if str(error) != "JDK changed; use the full installer for this revision":
                        raise
                    print(error)
                    continue
                with output.open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                result["assets"][platform] = {"url": f"https://github.com/{repo}/releases/download/{tag}/{name}",
                                              "sha256": digest, "size": output.stat().st_size,
                                              "base_revision": base_revision}
        write_json(manifest_path, result)
        notes_path = directory / "notes.txt"
        notes_path.write_text(data["notes"])
        if not existing:
            gh("release", "create", tag, "--repo", repo, "--target", commit, "--draft",
               "--prerelease", "--title", data["version"], "--notes-file", str(notes_path))
        assets = [str(directory / f'Defold-{p}-patch-{data["revision"]}.dmg') for p in PLATFORMS]
        assets += [str(directory / f"Defold-{p}.zip") for p in PLATFORMS]
        assets += [str(p) for p in directory.glob("Defold-*-patch-*-to-*.zip")]
        gh("release", "upload", tag, *assets, str(manifest_path), "--repo", repo, "--clobber")
        # Publish the immutable package release before pointing clients at its manifest.
        gh("release", "edit", tag, "--repo", repo, "--target", commit, "--draft=false")
    if not any(r["tag_name"] == feed_tag for r in releases):
        gh("release", "create", feed_tag, "--repo", repo, "--target", commit,
           "--prerelease", "--title", f'{data["channel"]} patch feed',
           "--notes", "Update manifest for the branch patch updater.")
    gh("release", "upload", feed_tag, str(manifest_path), "--repo", repo, "--clobber")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("stage", help="Embed the installed patch version before building the editor")
    bump = sub.add_parser("bump", help="Increment the patch revision for the next change")
    bump.add_argument("--notes", required=True)
    local = sub.add_parser("local", help="Prepare a local update preview from a built DMG")
    local.add_argument("--dmg", type=Path, required=True)
    local.add_argument("--platform", choices=PLATFORMS, default="arm64-macos")
    local.add_argument("--port", type=int, default=8765)
    local.add_argument("--base-zip", type=Path, required=True)
    local.add_argument("--target-zip", type=Path, required=True)
    release = sub.add_parser("publish", help="Publish both CI packages, then update the feed")
    release.add_argument("--directory", type=Path, required=True)
    release.add_argument("--commit", required=True)
    args = parser.parse_args()
    if args.command == "stage":
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
        installed = delta.metadata(args.base_zip)
        data = delta.metadata(args.target_zip)
        if (installed["channel"] != data["channel"] or installed["repository"] != data["repository"]
                or data["revision"] <= installed["revision"]):
            raise ValueError("Use two builds from the same channel with increasing revisions")
        directory = ROOT / "patch/local"
        directory.mkdir(exist_ok=True)
        base = f"http://127.0.0.1:{args.port}"
        write_json(directory / "build.json", {**installed, "feed": f"{base}/manifest.json"})
        shutil.copyfile(args.dmg, directory / f"Defold-{args.platform}.dmg")
        result = manifest(directory, data, base, [args.platform])
        output = directory / "preview-patch.zip"
        delta.create(args.base_zip, args.target_zip, output, installed["revision"], data["revision"])
        with output.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        result["assets"][args.platform] = {"url": f"{base}/{output.name}", "size": output.stat().st_size,
                                             "sha256": digest, "base_revision": installed["revision"]}
        write_json(directory / "manifest.json", result)
        print(f"Serve with: python3 -m http.server {args.port} --bind 127.0.0.1 --directory patch/local")
        print(f"Editor JVM option: -Ddefold.patch.directory={directory}")
    else:
        publish(args.directory, args.commit)


if __name__ == "__main__":
    main()
