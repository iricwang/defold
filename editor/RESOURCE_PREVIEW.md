# Resource previews and panel layout

The Resource Preview panel defaults to the right side, below the inspector.
Select a file in Assets to preview it. Pin freezes the selected resource;
Refresh re-renders the current resource, including unsaved scene changes.
Open opens the usual resource editor.

Built-in providers:

- PNG, JPEG, GIF and BMP: fitted image preview.
- WAV, MP3, M4A and AIFF: play/pause, stop, duration and seek controls, subject
  to the JavaFX platform decoder. Playback stops when selection changes or
  the project closes. OGG and Opus currently show an unsupported-encoding
  message; they remain usable by the game engine.
- Resources with a registered scene preview renderer, including GUI,
  collections, game objects and atlases: a framed snapshot using the existing
  editor renderer. Refresh updates the snapshot; Open provides full editing.

## Adding providers

Register through `editor.resource-preview/register-provider!` with a stable ID
and a descriptor containing:

- `:accept?`: a predicate receiving the registered resource-type map.
- `:create!`: a function receiving context and resource, returning
  `{:node javafx.scene.Node :dispose! cleanup-fn}`.

Context contains workspace, project, app-view, localization and open-resource.
Registration with the same ID replaces that provider. Later registrations
have priority. Creation and disposal run on the JavaFX thread. Providers
must not change resource data or add undo steps, and must release media,
listeners, temporary files and render contexts in `:dispose!`. Use resource
streams rather than assuming every resource is a local file (libraries can
be ZIP-backed). Keep creation bounded; expensive custom providers should
start cancellable background work and publish results on the FX thread.

## Layout

Drag the Assets, Inspector, Tools or Resource Preview header onto the left,
right or bottom zone. The highlighted zone receives the panel at the end;
repeat within a zone to reorder. The header menu provides the same actions.
Existing splitters resize panels and editor tabs retain their split views.
Panel placement is saved in `window.panel-layout`; divider sizes are saved
with the existing window preferences. View > Reset Panel Layout restores
panel placement. View > Show Resource Preview reveals its current zone.

The first version docks these four groups inside the main window. Inspector
keeps outline/properties together and Assets keeps changed files together;
separate floating OS windows are not implemented.
