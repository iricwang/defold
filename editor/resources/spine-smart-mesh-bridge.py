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

"""Headless adapter for the user-installed spine-smart-mesh tool."""
import json
import os
import sys
# Do not identify a child macOS app as the hosting JavaFX application.
for name in list(os.environ):
    if name in {"CFProcessPath", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"} or name.startswith(("APP_ICON_", "APP_NAME_", "JAVA_STARTED_ON_FIRST_THREAD_")):
        os.environ.pop(name, None)
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import mesh
from PIL import Image
request = json.loads(Path(sys.argv[2]).read_text())
if request["operation"] == "rig-guide":
    import importlib.util
    spec = importlib.util.spec_from_file_location("defold_auto_rig", Path(__file__).with_name("spine-auto-rig.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    try:
        result = module.centerline(request["file"], request.get("alpha", 8))
    except ValueError as error:
        result = {"error": str(error)}
elif request["operation"] == "open":
    import app, re, zlib
    # The first native string stores the version, with the last ASCII byte's
    # high bit set. Use it only as a CLI version hint, never as project data.
    header = zlib.decompressobj(-15).decompress(Path(request["file"]).read_bytes(), 128)
    version = re.search(rb"4\.[23]\.[0-9]*[\xb0-\xb9]", header)
    if version:
        declared = bytes(b & 0x7f for b in version.group()).decode("ascii")
        mesh.require(declared in app.VERSIONS.values(), "请安装与工程匹配的 Spine 版本。")
        app.VERSION = declared
    app.WORK = Path(sys.argv[2]).parent / "work"
    app.WORK.mkdir()
    project = app.Project(request["file"])
    # Keep the selected native file as the origin for relative images/audio.
    project.data.setdefault("skeleton", {})["images"] = os.path.relpath(project.images, project.source.parent)
    audio = project.data["skeleton"].get("audio")
    if audio and Path(audio).is_absolute():
        project.data["skeleton"]["audio"] = os.path.relpath(audio, project.source.parent)
    result = {"source": project.data, "version": project.version}
elif request["operation"] == "save":
    import app, tempfile, shutil
    source = request["source"]
    # Spine 4.3 rejects a present, zero-length event timeline.
    for animation in source.get("animations", {}).values():
        if not animation.get("events"):
            animation.pop("events", None)
    version = source["skeleton"]["spine"]
    mesh.require(version in app.VERSIONS.values(), "保存需要匹配的 Spine 版本。")
    target = Path(request["file"])
    mesh.require(not target.exists(), "不会覆盖已有 Spine 工程。")
    image_folder = target.stem + "_images"
    audio_folder = target.stem + "_audio"
    image_target, audio_target = target.parent / image_folder, target.parent / audio_folder
    mesh.require(not image_target.exists() and not audio_target.exists(), "目标工程的资源目录已存在，请换一个文件名。")
    with tempfile.TemporaryDirectory(prefix="defold-spine-save-") as temp:
        folder = Path(temp)
        candidate, native = folder / "source.json", folder / "output.spine"
        images = folder / image_folder
        images.mkdir()
        for key, attachment in mesh.attachments(source):
            if attachment.get("type", "region") not in ("region", "mesh", "linkedmesh"):
                continue
            mesh.require(not attachment.get("sequence"), "原生保存暂不支持序列附件。")
            original = mesh.image_path(Path(request["root"]), key[2], attachment)
            relative = original.relative_to(Path(request["root"]).resolve())
            destination = images / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(original, destination)
        source["skeleton"]["images"] = "./" + image_folder + "/"
        audio_root = Path(request["root"]) / (source["skeleton"].get("audio") or "")
        for event in source.get("events", {}).values():
            if event.get("audio"):
                original = (audio_root / event["audio"]).resolve()
                mesh.require(original.is_relative_to(audio_root.resolve()) and original.is_file(), "找不到事件音频。")
                destination = folder / audio_folder / event["audio"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(original, destination)
        source["skeleton"]["audio"] = "./" + audio_folder + "/"
        candidate.write_text(json.dumps(source, ensure_ascii=False))
        app.run_spine(["-i", candidate, "-o", native, "-r", target.stem], version)
        verification = folder / "verification"
        verification.mkdir()
        app.run_spine(["-i", native, "-o", verification, "-e", "json"], version)
        exports = list(verification.glob("*.json"))
        mesh.require(len(exports) == 1, "原生工程回读数量不匹配。")
        actual = json.loads(exports[0].read_text())
        def normalize(value):
            if isinstance(value, list):
                items = [normalize(item) for item in value]
                # Spine canonicalizes constant linear spans to stepped spans.
                for frame, following in zip(items, items[1:]):
                    if isinstance(frame, dict) and isinstance(following, dict) and frame.get("curve") == "stepped":
                        values = lambda f: {k: v for k, v in f.items() if k not in {"time", "curve"}}
                        if app.close_enough(values(frame), values(following)):
                            frame.pop("curve")
                return items
            if isinstance(value, dict):
                defaults = {"scaleX": 1, "scaleY": 1, "inherit": "normal", "color": "ffffffff", "type": "region", "blend": "normal"}
                return {key: normalize(item) for key, item in value.items()
                        if key not in defaults or item != defaults[key]}
            return value
        source, actual = normalize(source), normalize(actual)
        for section in ("bones", "slots", "animations", "events", "ik", "transform", "path", "physics"):
            mesh.require(app.close_enough(source.get(section) or {}, actual.get(section) or {}), "原生回读不一致：" + section)
        expected_attachments, actual_attachments = dict(mesh.attachments(source)), dict(mesh.attachments(actual))
        mesh.require(expected_attachments.keys() == actual_attachments.keys(), "原生回读附件清单不一致。")
        for key, expected in expected_attachments.items():
            got = actual_attachments[key]
            if expected.get("type") == "mesh":
                mesh.require(app.face_signature(expected["triangles"]) == app.face_signature(got["triangles"]), "原生三角面回读不一致。")
                def edge_signature(value):
                    edges = value.get("edges", [])
                    return {tuple(sorted(edges[i:i + 2])) for i in range(0, len(edges), 2)}
                mesh.require(edge_signature(expected) == edge_signature(got), "原生约束边回读不一致。")
                expected, got = dict(expected), dict(got)
                for value in (expected, got):
                    value.pop("triangles", None)
                    value.pop("edges", None)  # Editor edge ordering is not semantic.
            mesh.require(app.close_enough(expected, got), "原生附件回读不一致：" + str(key))
        target.parent.mkdir(parents=True, exist_ok=True)
        mesh.require(actual["skeleton"].get("images") == source["skeleton"]["images"], "Spine 未保留图片目录，未输出工程。")
        # Publish resources first; roll back our new folders if publishing fails.
        published = []
        native_created = False
        try:
            for name in (image_folder, audio_folder):
                if (folder / name).exists():
                    destination = target.parent / name
                    destination.mkdir()
                    published.append(destination)
                    shutil.copytree(folder / name, destination, dirs_exist_ok=True)
            with target.open("xb") as output:
                native_created = True
                output.write(native.read_bytes())
        except Exception:
            if native_created:
                target.unlink(missing_ok=True)
            for directory in published:
                shutil.rmtree(directory)
            raise
    result = {"file": str(target), "verified": True}
else:
    data, key, root = request["source"], request["key"], request["root"]
    attachment = mesh.get_attachment(data, key)
    with Image.open(mesh.image_path(root, key[2], attachment)) as image:
        size = image.size
    if request.get("trace"):
        import importlib.util, numpy as np
        spec = importlib.util.spec_from_file_location("defold_mesh_trace", Path(__file__).with_name("spine-mesh-trace.py"))
        tracing = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tracing)
        try:
            traced = tracing.trace(mesh.image_path(root, key[2], attachment), request.get("config", {}))
        except (ValueError, AttributeError) as error:
            Path(sys.argv[3]).write_text(json.dumps({"error": str(error)}, ensure_ascii=False))
            sys.exit(0)
        slot = next(slot for slot in data["slots"] if slot["name"] == key[1])
        matrices = mesh.bone_matrices(data)
        local_matrix = np.linalg.inv(matrices[slot["bone"]]) @ mesh.pixel_matrix(data, key, size)
        points = np.asarray(traced["points"])
        local = (local_matrix @ np.c_[points, np.ones(len(points))].T).T[:, :2]
        generated = {name: value for name, value in attachment.items() if name in ("name", "path", "color", "width", "height")}
        generated.update(type="mesh", uvs=traced["uvs"], triangles=traced["triangles"],
                         vertices=local.flatten().tolist(), hull=len(points), edges=traced["edges"])
        result = {"attachment": generated, "report": traced["report"]}
    else:
        config = mesh.defaults(data, key, size)
        config.update(request.get("config", {}))
        result = mesh.generate(data, key, root, config)
Path(sys.argv[3]).write_text(json.dumps(result, ensure_ascii=False, allow_nan=False))
