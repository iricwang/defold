# AI Canvas editor integration

Open a GUI resource and choose **进入 AI 模式**. The editor provides Action, Result and Assets cards, card-scoped conversations, attachments, streaming replies, and project-owned records under `ai-workspace/` through the local CodexCamp Companion service.

Design actions request candidate images. Adopting a candidate enables the Assets splitting editor, semantic component names and module directories, editable GUI/atlas production, resource validation and native editor preview. Migrated archive previews are accepted after checking candidate identity and archive content hashes.

Canvas controls include middle-button panning, zoom, double-click card focus, a following details pane, right-click deletion, and up to 100 session-local undo/redo steps. A card drag is one operation. Camera and selection changes do not consume undo history. Deleted cards retain their archived files and conversations. Text inputs retain native text undo.

The editor uses native theme colors and distinct card borders. The macOS paste fallback handles the JDK clone-path `Operation not permitted` failure without retrying unrelated permission errors. No runtime engine code is changed by this integration.

## AI project directory

The left pane lists the current game's `ai-workspace/` using native tree controls. `library/` contains ten creative categories: concept/gameplay, world/style, characters/monsters, scenes/levels, systems, interface, motion/effects, audio/music, balancing/configuration, and testing/delivery. Each category has a starter README; reopening never overwrites authored files. Existing conversations, canvases, attachments, candidates and delivery archives retain their original locations.

Expand folders to browse, double-click a file or press Enter to open it, and use Refresh to read external changes while retaining selection/expansion. Supported resources open in Defold; other document formats use the system file handler. Select a category folder to import files up to 64 MiB each; duplicate names receive numbered suffixes. Category documents and imports use the existing CodexCamp archive synchronization. Category selection does not change canvas/card ownership.

## Standalone text Canvas

Right-click a creative category to create a text-proposal `.aicanvas` document. The AI command also opens project planning without a GUI selection. Action requests text proposals; completed replies become selectable Result candidates. Adopt a candidate into Assets and explicitly export a source-linked Markdown version without overwriting previous exports. Card details show the full proposal and export path.

Canvas/Card IDs remain stable across file moves. Semantic document revisions exclude camera and card-position edits; presentation and sessions retain the existing archive storage. Unknown document versions open read-only, and external document edits are not silently overwritten. This is the first linear text-template implementation, not the complete typed-port, memory-system or production-adapter roadmap.

The connection tooltip distinguishes native project binding from Codex desktop sidebar membership. A pending sidebar projection does not imply a failed model connection, and a working task does not imply confirmed sidebar grouping.

## Validation and delivery

On macOS, the combined archive, production, canvas, conversation and filesystem-copy tests pass: 36 tests, 184 assertions. Live editor verification covered card details/focus, image reuse, middle-button panning, deletion and keyboard undo/redo, and opening an adopted candidate in the splitting window.

The local application is patch.109. The AI project directory addition is currently hotloaded for validation; restarting with that addition requires the next incremental package. Directory regression tests pass: 3 tests, 12 assertions. Live verification covers the ten categories, selection/expansion retention, native document opening, and ten category documents archived in CodexCamp. This source branch does not publish a release. The independent Remote Manager working-tree edits are not part of this change.

Codex desktop sidebar project membership remains an integration limitation. Backend project binding must not be presented as proof that a conversation is visible under the desktop sidebar project. See the Companion feature branch integration notes before installing its experimental project-registration changes.

Standalone Canvas and task-flow verification used a hotloaded editor and an isolated Companion service: real text generation, Result adoption, Markdown export, event-stream reconnection and tab reopening retained the same task with one completed turn. The combined Canvas/document/workbench/browser suite passed 29 tests and 159 assertions before the sidebar-status addition; the later workbench/document suite passed 14 tests and 67 assertions. These changes have not been packaged into the installed editor. No runtime engine changes are included.
