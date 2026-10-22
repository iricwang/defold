-- Copyright 2020-2026 The Defold Foundation
-- Copyright 2014-2020 King
-- Copyright 2009-2014 Ragnar Svensson, Christian Murray
-- Licensed under the Defold License version 1.0 (the "License"); you may not use
-- this file except in compliance with the License.
--
-- You may obtain a copy of the License, together with FAQs at
-- https://www.defold.com/license
--
-- Unless required by applicable law or agreed to in writing, software distributed
-- under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
-- CONDITIONS OF ANY KIND, either express or implied. See the License for the
-- specific language governing permissions and limitations under the License.
--
-- Merge these functions into your GO script.
-- Export Lua first and change the module path to your exported file.
-- Business callbacks run in the game, never in the editor preview.
local panthera = require("panthera.panthera")
local animation_data = require("main_panthera")

-- Keep Euler channels independent across seeks and rotations beyond 90 degrees.
-- The linear timeline sampler also keeps playback and scrubbing on the same path.
local function create_go_animation(data, collection_name, objects)
    local state = panthera.create_go(data, collection_name, objects)
    local original = state.adapter
    local adapter = {}
    for key, value in pairs(original) do adapter[key] = value end
    local rotations = {}
    local axes = {rotation_x = "x", rotation_y = "y", rotation_z = "z"}
    adapter.set_node_property = function(node, property, value)
        local axis = axes[property]
        if not axis then return original.set_node_property(node, property, value) end
        local euler = rotations[node]
        if not euler then euler = go.get(node, "euler"); rotations[node] = euler end
        euler[axis] = value
        original.stop_tween(node, property)
        local x, y, z = math.rad(euler.x) / 2, math.rad(euler.y) / 2, math.rad(euler.z) / 2
        local cx, cy, cz = math.cos(x), math.cos(y), math.cos(z)
        local sx, sy, sz = math.sin(x), math.sin(y), math.sin(z)
        go.set_rotation(vmath.quat(sy*sz*cx + sx*cy*cz,
                                  sy*cz*cx + sz*sx*cy,
                                  -sy*sx*cz + sz*cy*cx,
                                  -sy*sz*sx + cy*cz*cx), node)
        return true
    end
    state.adapter = adapter
    return state
end

local event_handlers = {
}

function init(self)
    self.animation = create_go_animation(animation_data)
    panthera.play(self.animation, "default", {
        easing = function(t) return t end,
        callback_event = function(event_id, node, data, value)
            local handler = event_handlers[event_id]
            if handler then handler(self, node, data, value) end
        end,
    })
end

function final(self)
    if self.animation then panthera.stop(self.animation) end
end
