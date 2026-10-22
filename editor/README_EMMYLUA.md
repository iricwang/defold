# Lua code intelligence

The built-in editor uses EmmyLua (`emmylua_ls` 0.25.1) by default. Preferences →
Code → Lua language service selects **EmmyLua** or the bundled **LuaLS** fallback.
The existing language-service checkbox disables either backend. Changing the
backend and closing Preferences restarts the project’s language services.

Supported resources are `.lua`, `.script`, `.gui_script`, `.render_script` and
`.editor_script`. Game scripts use Lua 5.1; editor scripts run in a separate Lua 5.2
analysis process. Defold API annotations and annotations from project dependencies
remain available. No source code is sent to an online service.

Features include annotation-aware completion (`@class`, `@field`, `@type`,
`@param`, `@return`, `@alias`, `@generic`), hover documentation, definition and
reference navigation, rename, diagnostics, document/selection formatting,
automatic function parameter help, semantic colors and unobtrusive type/parameter
hints at the end of source lines. Parameter help, semantic colors and hints have
separate switches in Code preferences. Escape dismisses parameter help.

Lifecycle parameter types are supplied to EmmyLua through virtual protocol
annotations. They do not modify project files or editor buffers. Explicit user
annotations take precedence. The adapter maps diagnostic locations, semantic
tokens, navigation and edits back to the original document; generated annotations
are removed from formatting edits. LuaLS retains its existing lifecycle plugin.

The server’s `.emmyrc.json`, `.emmyrc.lua` and `.luarc.json` project configuration
files are supported by EmmyLua itself. Project dependency changes restart analysis
to refresh generated annotations. If a server stops unexpectedly, change the
backend or disable/re-enable language services in Preferences to restart it.

## Regression tests

After `lein init` and `lein javac`, from `editor/`:

```sh
lein test editor.code.insight-test editor.lsp.defold-lua-test integration.lsp-test editor.code.script-test
lein classpath > ../tmp/emmy-test-classpath.txt
python3 scripts/test_emmylua.py \
  --server resources/_unpack/arm64-macos/bin/lsp/emmylua/emmylua_ls \
  --bridge-classpath ../tmp/emmy-test-classpath.txt
```

Use the corresponding platform folder and `emmylua_ls.exe` on Windows. The Python
suite starts the pinned executable through the production protocol adapter and
uses disposable local projects, explicit analysis-completion notifications and
request replies. It covers all five extensions, annotation inference, API
completion, callback types, signature help, semantic tokens, hints, diagnostics,
formatting, rename/references, Unicode edit positions, project isolation and
restart. The macOS and Windows patch workflows run these checks before publishing.

The backend archive is pinned by SHA-256 for each platform. Its MIT license is
shipped in `_unpack/shared/emmylua/LICENSE.txt`.

## Local verification — patch.78 (2026-10-22)

Apple Silicon macOS: 42 editor/LSP tests (682 assertions) and 19 real-backend
checks passed. The live editor was exercised using `cljfx.plorer`, and the exact
patch.74 baseline was upgraded to patch.78 through a local HTTP feed. All 168
installed files matched the target inventory. Defold restarted and reopened the
Arrow Away project with both EmmyLua processes running. Only
`/Applications/Defold.app` remains; the verified old backup was moved to Trash.

Typing keeps the previous hints while analysis is pending, then replaces them
with the completed result. The same live typing check recorded 20 missing-hint
frames before the fix and zero missing frames across 22 frames in the compiled
patch.78 build. Unchanged lines follow inserted/deleted rows; disabling hints
still clears them immediately. The backend can return no signature at certain
cursor positions; the parameter popup then closes when that response arrives.

| Feature | Evidence |
| --- | --- |
| Type annotations, API and lifecycle inference, completion, resolve, hover, definitions, references, rename, diagnostics, formatting, UTF-16 edits, all script extensions, project isolation and restart | [19 real-backend tests](doc/emmylua-validation/backend-tests.txt) |
| Protocol capabilities, position mappings, lexical/semantic colors and pending hint preservation | [42 editor tests](doc/emmylua-validation/editor-tests.txt) |
| Stable typing and line-end hints | [Frame trace](doc/emmylua-validation/typing-regression.txt), [screenshot](doc/emmylua-validation/code-hints.png) |
| Function parameter help | [Screenshot](doc/emmylua-validation/signature-help.png) |
| Lifecycle action completion | [Screenshot](doc/emmylua-validation/lifecycle-completion.png) |
| Native backend and feature settings | [Screenshot](doc/emmylua-validation/preferences.png) |
| Definition navigation and rename | [Definition](doc/emmylua-validation/goto-definition.png), [rename](doc/emmylua-validation/rename.png) |
| Installed application and project services | [Runtime state](doc/emmylua-validation/installed-runtime.txt), [screenshot](doc/emmylua-validation/installed-project.png) |

[Machine-readable results](doc/emmylua-validation/validation.json) include scope
and limitations. Windows and Intel macOS were not run locally; their CI checks
are configured. The local verification did not publish a release; the patch workflow publishes
after an authorized push to the delivery branch.
