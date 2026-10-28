// Copyright 2020-2026 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.defold.editor;

import com.dynamo.gamesys.proto.Gui.SceneDesc;
import com.dynamo.gamesys.proto.Gui.SceneDesc.ResourceDesc;
import com.google.protobuf.TextFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Editor-equivalent resource migration, exclusively in the manager's build copy. */
public final class RemoteGuiMigration {
    public static boolean migrate(SceneDesc.Builder scene) {
        if (scene.getSpineScenesCount() == 0) return false;
        var resources = new ArrayList<ResourceDesc>();
        for (var legacy : scene.getSpineScenesList()) {
            resources.add(ResourceDesc.newBuilder().setName(legacy.getName())
                    .setPath(legacy.getSpineScene()).build());
        }
        resources.addAll(scene.getResourcesList());
        scene.clearSpineScenes().clearResources().addAllResources(resources);
        return true;
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]).toRealPath();
        try (var paths = Files.walk(project)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".gui") && Files.isRegularFile(p)).toList()) {
                Path relative = project.relativize(file);
                if (relative.getName(0).toString().equals("build") || relative.getName(0).toString().startsWith(".")) continue;
                if (!file.toRealPath().startsWith(project)) throw new IllegalArgumentException("GUI symlink outside build copy: " + relative);
                String source = Files.readString(file);
                if (!source.contains("spine_scenes")) continue;
                var scene = SceneDesc.newBuilder();
                TextFormat.merge(source, scene);
                if (migrate(scene)) {
                    Files.writeString(file, TextFormat.printer().printToString(scene));
                    System.out.println("Migrated legacy GUI resources in build copy: " + relative);
                }
            }
        }
    }
}
