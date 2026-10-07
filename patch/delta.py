"""File-level incremental patches between two macOS editor ZIP bundles."""
import hashlib
import json
from pathlib import PurePosixPath
import stat
import zipfile


def inventory(archive):
    result = {}
    for entry in archive.infolist():
        if entry.is_dir():
            continue
        name = entry.filename
        path = PurePosixPath(name)
        if (not name.startswith("Defold.app/Contents/") or ".." in path.parts
                or "\\" in name):
            raise ValueError(f"Unsafe bundle entry: {name}")
        relative = str(path.relative_to("Defold.app"))
        if relative in result:
            raise ValueError(f"Duplicate bundle entry: {name}")
        mode = entry.external_attr >> 16
        kind = "link" if stat.S_ISLNK(mode) else "file"
        with archive.open(entry) as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        result[relative] = {"path": relative, "sha256": digest,
                            "mode": stat.S_IMODE(mode), "kind": kind}
    if "Contents/Resources/config" not in result or "Contents/MacOS/Defold" not in result:
        raise ValueError("Not a Defold macOS bundle")
    return result


def create(base_zip, target_zip, output, base_revision, revision):
    if revision <= base_revision:
        raise ValueError("Patch revision must increase")
    with zipfile.ZipFile(base_zip) as base, zipfile.ZipFile(target_zip) as target:
        before, after = inventory(base), inventory(target)
        runtime = lambda files: {k: v for k, v in files.items() if k.startswith("Contents/Resources/packages/jdk-")}
        if runtime(before) != runtime(after):
            raise ValueError("JDK changed; use the full installer for this revision")
        plan = {"schema": 1, "base_revision": base_revision, "revision": revision,
                "base": list(before.values()), "target": list(after.values())}
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as patch:
            patch.writestr("patch.json", json.dumps(plan))
            for name, item in after.items():
                if before.get(name) != item:
                    with target.open("Defold.app/" + name) as src, patch.open("files/" + name, "w") as dst:
                        while block := src.read(1024 * 1024):
                            dst.write(block)
    return plan


def metadata(bundle):
    with zipfile.ZipFile(bundle) as archive:
        jars = [entry for entry in archive.namelist()
                if entry.startswith("Defold.app/Contents/Resources/packages/defold-") and entry.endswith(".jar")]
        if len(jars) != 1:
            raise ValueError("Expected one editor JAR in the bundle")
        with archive.open(jars[0]) as stream, zipfile.ZipFile(stream) as jar:
            return json.loads(jar.read("patch/build.json"))
