# AI Canvas editor integration

Open a GUI resource and choose **进入 AI 模式**. The editor provides Action, Result and Assets cards, card-scoped conversations, attachments, streaming replies, and project-owned records under `ai-workspace/` through the local CodexCamp Companion service.

Design actions request candidate images. Adopting a candidate enables the Assets splitting editor, semantic component names and module directories, editable GUI/atlas production, resource validation and native editor preview. Migrated archive previews are accepted after checking candidate identity and archive content hashes.

Canvas controls include middle-button panning, zoom, double-click card focus, a following details pane, right-click deletion, and up to 100 session-local undo/redo steps. A card drag is one operation. Camera and selection changes do not consume undo history. Deleted cards retain their archived files and conversations. Text inputs retain native text undo.

The editor uses native theme colors and distinct card borders. The macOS paste fallback handles the JDK clone-path `Operation not permitted` failure without retrying unrelated permission errors. No runtime engine code is changed by this integration.

## Validation and delivery

On macOS, the combined archive, production, canvas, conversation and filesystem-copy tests pass: 36 tests, 184 assertions. Live editor verification covered card details/focus, image reuse, middle-button panning, deletion and keyboard undo/redo, and opening an adopted candidate in the splitting window.

The current local application remains patch.108. Later Canvas fixes have been hotloaded for validation; restarting requires an incremental package containing those changes. This source branch does not publish a release. The independent Remote Manager working-tree edits are not part of this change.

Codex desktop sidebar project membership remains an integration limitation. Backend project binding must not be presented as proof that a conversation is visible under the desktop sidebar project. See the Companion feature branch integration notes before installing its experimental project-registration changes.
