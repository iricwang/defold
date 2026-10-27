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

-- Explicit QA integration: registered commands only. No eval, no source loading, no automatic business success.
local M = {}
local enabled = sys.get_config_string("project.automation_enabled", "0") == "1"
local inbox = sys.get_config_string("project.automation_inbox", "")
local handlers, seen = {}, {}
local elapsed, pending, expires = 0, nil, 0
local last_payload, seen_count, exhausted = nil, 0, false

local function emit(kind, operation_id, event, payload)
    print("DEFOLD_AUTOMATION " .. json.encode({kind=kind, operation_id=operation_id,
        event=event, payload=payload or {}}))
end

function M.register(name, handler)
    assert(type(name) == "string" and type(handler) == "function")
    handlers[name] = handler
end

function M.ready()
    if enabled then emit("ready", "startup", "ready", {}) end
end

-- Call from an actual application success callback, not from generic on_input dispatch.
function M.ack(event, payload)
    if enabled and pending and elapsed <= expires then
        emit("event", pending, event, payload)
        pending = nil
    end
end

function M.update(dt)
    if not enabled or inbox == "" or exhausted then return end
    elapsed = elapsed + dt
    if pending and elapsed > expires then pending = nil end
    local file = io.open(inbox, "rb")
    if not file then return end
    local text = file:read(32769)
    file:close()
    if not text or #text > 32768 or text == last_payload then return end
    last_payload = text
    local ok, command = pcall(json.decode, text)
    if not ok or type(command) ~= "table" or type(command.operation_id) ~= "string" then return end
    if seen[command.operation_id] then return end
    if seen_count >= 4096 then
        emit("error", command.operation_id, command.name, {message="session_operation_limit"})
        exhausted = true
        return
    end
    seen_count = seen_count + 1
    seen[command.operation_id] = true
    if command.name == "__arm_input" then
        pending = command.arguments.operation_id
        expires = elapsed + math.min(30, command.arguments.timeout_seconds or 5)
        emit("result", command.operation_id, "armed", {})
    elseif handlers[command.name] then
        local success, value = pcall(handlers[command.name], command.arguments or {})
        emit(success and "result" or "error", command.operation_id, command.name,
             success and (value or {}) or {message=tostring(value)})
    else
        emit("error", command.operation_id, command.name, {message="unregistered_command"})
    end
end

return M
