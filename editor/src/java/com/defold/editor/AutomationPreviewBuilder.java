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

import com.dynamo.gameobject.proto.GameObject.CollectionDesc;
import com.dynamo.gameobject.proto.GameObject.ComponentDesc;
import com.dynamo.gameobject.proto.GameObject.InstanceDesc;
import com.dynamo.gameobject.proto.GameObject.PrototypeDesc;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/** Writes only a preview wrapper using the installed editor's official runtime protobuf schema. */
public final class AutomationPreviewBuilder {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected compiled root, resource path, new wrapper directory, specification");
        Path root = Path.of(args[0]).toRealPath();
        String resource = args[1];
        if (!resource.startsWith("/") || resource.contains("\\") || resource.contains(":")
                || resource.contains("..") || resource.startsWith("//")) {
            throw new IllegalArgumentException("Invalid resource path");
        }
        Path compiled = root.resolve(resource.substring(1) + "c").toRealPath();
        if (!compiled.startsWith(root)) throw new IllegalArgumentException("Resource outside compiled root");
        Map<?, ?> spec = new ObjectMapper().readValue(Path.of(args[3]).toFile(), Map.class);
        List<?> support = (List<?>)spec.get("support_objects");
        if (resource.endsWith(".collection") && support.isEmpty()) {
            CollectionDesc.parseFrom(Files.readAllBytes(compiled));
            System.out.println(resource + "c");
            return;
        }
        if (!resource.endsWith(".gui") && !resource.endsWith(".go") && !resource.endsWith(".collection")) {
            throw new IllegalArgumentException("Expected GUI, GO or Collection");
        }
        if (!args[2].matches("__automation_preview_[a-f0-9]+")) {
            throw new IllegalArgumentException("Invalid wrapper directory");
        }
        Path directory = Files.createDirectory(root.resolve(args[2]));
        String prototype = resource + "c";
        if (resource.endsWith(".gui")) {
            PrototypeDesc go = PrototypeDesc.newBuilder().addComponents(ComponentDesc.newBuilder()
                    .setId("gui").setComponent(prototype)).build();
            Files.write(directory.resolve("preview.goc"), go.toByteArray(), StandardOpenOption.CREATE_NEW);
            prototype = "/" + args[2] + "/preview.goc";
        } else if (resource.endsWith(".go")) {
            PrototypeDesc.parseFrom(Files.readAllBytes(compiled));
        }
        // Omit static component counts, as Bob does for dynamic factories. The engine then uses project limits,
        // including extension components, factory-created instances and model bones.
        CollectionDesc.Builder collection = resource.endsWith(".collection")
                ? CollectionDesc.parseFrom(Files.readAllBytes(compiled)).toBuilder().clearComponentTypes()
                : CollectionDesc.newBuilder().setName("automation_preview").addInstances(InstanceDesc.newBuilder()
                    .setId("/" + spec.get("instance_id")).setPrototype(prototype));
        for (Object item : support) {
            Map<?, ?> object = (Map<?, ?>)item;
            String id = "/" + object.get("id");
            if (collection.getInstancesList().stream().anyMatch(instance -> instance.getId().equals(id)))
                throw new IllegalArgumentException("Duplicate preview instance: " + id);
            collection.addInstances(InstanceDesc.newBuilder().setId(id).setPrototype(object.get("resource_path") + "c"));
        }
        Files.write(directory.resolve("main.collectionc"), collection.build().toByteArray(), StandardOpenOption.CREATE_NEW);
        System.out.println("/" + args[2] + "/main.collectionc");
    }
}
