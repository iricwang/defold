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

## Code navigation

Mouse Back/Forward buttons over a script and **Ctrl+Alt+Left/Right** on Windows
and Linux share the same navigation history. macOS uses **Command+Option+Left/Right**.
The code context menu also exposes Navigate Back and Navigate Forward.

Opening scripts, switching script tabs, and jumping to definitions or references
record the position being left. Navigation restores the file, selection and
scroll position, reopens closed tabs, and skips files that no longer exist.
Jumping somewhere new after Back clears the forward history. History is kept
per project window for the current session, with up to 100 entries per direction.

## IDEA navigation (patch.80)

The default keyboard preset is now **IntelliJ IDEA**. Select **File → Preferences
→ General → Keyboard preset** to switch back to **Defold**. Existing user-defined
shortcut changes remain applied on top of either preset; the Keymap tab shows
conflicts and resets a command to the currently selected preset.

| Windows / Linux | Action |
| --- | --- |
| Ctrl + hover | Underline a resolvable symbol; existing hover documentation remains available |
| Ctrl + left click / Ctrl+B / F12 | Go to definition; show references when already on its declaration |
| Alt+F7 / Ctrl+Alt+F7 / Shift+F12 | Show reference locations |
| Shift+F6 / F2 | Rename |
| Ctrl+F12 | File symbols |
| Ctrl+F9 | Build |
| Ctrl+Shift+F9 | Clean build |
| Ctrl+Shift+N | Open file |
| Ctrl+E | Recent files |
| Ctrl+Alt+Left / Right | Navigate back / forward |

On macOS this preset uses Command in place of Ctrl for the above Ctrl shortcuts;
Alt is Option. This is a navigation subset, not a complete IDEA keymap. Ctrl+P
and Ctrl+Q (Command on macOS) are reserved in this preset and no longer open a
file or quit the application; manual parameter/documentation commands are not
part of this batch. Defold's original preset retains its original assignments.

Definition candidates and references use a native themed popup, sorted by file
and line, with a code preview for each location. The count includes declarations
returned by the language server. Filter by path or code, press Down to browse,
Enter to jump, or Esc to cancel. Empty results show a non-modal notification.
Only confirmed jumps enter navigation history. Moving the caret, changing tabs,
or editing the source invalidates pending navigation responses. Ctrl clicks
resolve the symbol under the mouse, independently of the previous caret.
Alt+click multi-cursors, ordinary selections and middle-button box selection
remain unchanged.

## Completion documentation

Completion opens a documentation pane automatically. Up/Down changes the selected
item and its documentation; Ctrl+Space toggles the pane while the list is open,
and Esc closes both. The native editor theme and normal completion insertion are
preserved.

The list displays EmmyLua's parameter names after the function name and its return
type on the right. The adjacent pane shows the server's full signature, declaring
type, description, parameter and return comments, including Chinese text and
Markdown. Optional parameters, overloads and generic types are displayed when
the backend supplies them. Missing annotations are not invented. Documentation
is resolved only for the selected candidate and reused within the completion list.

The presentation follows the [IntelliJ EmmyLua completion renderer](https://github.com/EmmyLua/IntelliJ-EmmyLua/blob/master/src/main/java/com/tang/intellij/lua/editor/completion/LookupElement.kt)
and [documentation provider](https://github.com/EmmyLua/IntelliJ-EmmyLua/blob/master/src/main/java/com/tang/intellij/lua/documentation/LuaDocumentationProvider.kt).

## Regression tests

After `lein init` and `lein javac`, from `editor/`:

```sh
lein test editor.code.insight-test editor.lsp.defold-lua-test integration.lsp-test editor.code.script-test
lein test editor.code.navigation-test editor.code.symbol-navigation-test editor.keymap-test
lein test editor.code.completion-presentation-test editor.code-completion-test
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


## Lua parameter intentions (Alt+Enter)

Place the caret on a parameter name in a valid Lua function declaration and press
**Alt+Enter** (Option+Return on macOS), or choose **Lua parameter actions…** from
the script context menu. The shortcut is available in both Defold and IntelliJ
IDEA presets; explicit custom mappings still take precedence. Use Up/Down and
Enter to choose an action, or click it; Escape cancels. The popup uses the native
context-menu theme and sizes to its contents, with no list scrollbar or extra
focus border. Modified Enter is consumed so the opening Alt+Enter cannot also
confirm an action.

- **Create field for parameter** is available on colon methods such as
  `function M:show(bInMenu)`. The native dialog asks for a field name and inserts
  `self.field = bInMenu` at the beginning of the method, using its indentation.
  Invalid identifiers, reserved words and already assigned/declared fields are
  rejected. Optionally add `---@field public field type` to an explicit class
  declaration on the same top-level table in this file. Cross-file class aliases
  are not resolved; the checkbox is disabled when ownership cannot be established.
- **Create parameter annotation** adds `---@param bInMenu table` to the function's
  existing documentation and selects `table` for immediate replacement. It also
  supports local/global functions and standalone assigned anonymous functions.
  Existing annotations, including optional parameters, are not duplicated.

Each action is one undoable edit, including a field annotation and assignment
created together. Pending actions are discarded if the document or selection
changes. Parsing runs only when invoked. This first version requires syntactically
valid Lua; it does not offer intentions inside strings, comments or parameter
usages. It uses EmmyLua IDEA's editable `table` fallback, not inferred certainty;
field annotations preserve an existing parameter type and description when present.

Behavior references: [CreateFieldFromParameterIntention](https://github.com/EmmyLua/IntelliJ-EmmyLua/blob/master/src/main/java/com/tang/intellij/lua/codeInsight/intention/CreateFieldFromParameterIntention.kt)
and [CreateParameterAnnotationIntention](https://github.com/EmmyLua/IntelliJ-EmmyLua/blob/master/src/main/java/com/tang/intellij/lua/codeInsight/intention/CreateParameterAnnotationIntention.kt).
The plugin also has function/return/type documentation, method creation, visibility,
boolean inversion and constant-expression intentions; these are not included in
this first implementation.

Tests: `lein test editor.code.lua-intentions-test`.


## Lua Darcula Contrast highlighting

Lua resources (`.lua`, `.script`, `.gui_script`, `.render_script`, and
`.editor_script`) use the supplied `Darcula_Contrast.icls` colors, resolved against
IDEA 2025.3's bundled Darcula scheme and EmmyLua's default mappings. The user's
`TEXT` background override is `#2B2D30`, with `#A9B7C6` foreground. Existing font
family and size preferences remain in effect; other languages keep their theme.

Keywords, strings, numbers, operators, punctuation, parameters, `self`, globals,
locals, fields, instance/static methods, standard library APIs and EmmyLua docs
have distinct mappings. Documentation tags use bold italic green with an
underline; global names/static methods are italic. Captured outer locals and
parameters use bold italic text and EmmyLua's green underline. Method/local
classification comes from lexical scope, so same-named locals and parameters do
not get mistaken for globals. Library identification and annotation types use
the installed language server's semantic information.

Lexical binding analysis runs on a worker after the existing debounce and is
cached for the current document text. Painting and minimap rendering reuse the
result. Results for older document versions are discarded. Incomplete Lua falls
back to lexical coloring plus available language-server tokens. The existing
semantic-highlighting preference controls the semantic layer. This is a bundled
Lua preset, not a general `.icls` importer.

Tests: `lein test editor.code.lua-highlight-test editor.code.insight-test`.


## Lua region 折叠

使用 IDEA 风格的独占行标记分组代码：

```lua
--region 初始化
function init(self)
    -- ...
end
--endregion
```

支持 `--region`、`-- region`、`---region` 以及对应的 `endregion`，支持嵌套。
点击行号右侧的箭头收起或展开；收起后仍显示区域名称和隐藏行数，点击行尾的省略标记也可展开。
折叠只改变当前编辑标签的显示，不修改文件内容；行号、断点与 LSP 位置仍使用原始源文件坐标。
上下方向键和翻页按可见行移动；查找、定义跳转或调试定位到隐藏行时自动展开对应区域。
复制跨越折叠区域的选区会包含完整代码。字符串、长注释和未配对标记不会被折叠。
折叠状态保存在当前打开的标签中；关闭标签后不保留。编辑造成区域边界改变时会自动展开，以免隐藏错误范围。
