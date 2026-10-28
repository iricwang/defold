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

import com.defold.editor.RemoteGuiMigration;
import com.dynamo.gamesys.proto.Gui.SceneDesc;
import com.google.protobuf.TextFormat;

public final class RemoteGuiMigrationTest {
    // Preserve resources, node/layout data and explicit defaults; repeated migration is a no-op.
    public static void main(String[] args) throws Exception {
        var scene = SceneDesc.newBuilder();
        TextFormat.merge("spine_scenes { name: \"actor\" spine_scene: \"/actor.spinescene\" } resources { name: \"existing\" path: \"/other.spinescene\" } nodes { id: \"box\" type: TYPE_BOX } layouts { name: \"Portrait\" nodes { id: \"box\" alpha: 0.0 } }", scene);
        var before = scene.buildPartial();
        if (!RemoteGuiMigration.migrate(scene)) throw new AssertionError("Legacy resources not migrated");
        if (scene.getSpineScenesCount()!=0 || scene.getResourcesCount()!=2) throw new AssertionError("Resources lost");
        if (!scene.getResources(0).getName().equals("actor") || !scene.getResources(0).getPath().equals("/actor.spinescene")) throw new AssertionError("Resource binding changed");
        if (!scene.getResources(1).equals(before.getResources(0)) || !scene.getNodesList().equals(before.getNodesList()) || !scene.getLayoutsList().equals(before.getLayoutsList())) throw new AssertionError("Unrelated data changed");
        var after=scene.buildPartial();
        if (RemoteGuiMigration.migrate(scene) || !scene.buildPartial().equals(after)) throw new AssertionError("Migration is not idempotent");
        System.out.println("PASS: legacy bindings, existing resources, nodes, sparse layouts and idempotence");
    }
}
