# Graphite editor theme

The theme is imported last by `editor.sass`, `dialogs.sass`, and `splash.sass`.
Keep upstream component styles intact and put visual overrides in this directory.
Build with the existing `lein sass once` task; generated CSS is not a separate source of truth.

- `_palette.scss`: neutral surface/text tokens, plus independent UI accent/selection tokens.
- `_controls.scss`: shared JavaFX controls, including popup menus and dialog controls.
- `_editor.scss`: editor-specific chrome, headers, splitters, viewport toolbar and previews.
- `editor.colors`: Canvas/OpenGL neutral colors, used by scenes, rulers and animation views.
- `editor.code.view/default-editor-color-scheme`: the code canvas background and selection.
- `editor.console/console-color-scheme`: console background, text and selection.
- `GamePreview`: exposes a toolbar style class and delegates viewport background to CSS.

Do not remap semantic warning/error, resource-type, syntax, axis or recording colors to
the UI accent. Do not replace JavaFX skins, the dock layout schema, drag events, saved
divider positions or the tab close animation workaround. Dock headers must remain within
the existing 28px drag strip. Splitter styling must retain a usable hit target.

## After upstream merges

1. Rebuild all three CSS outputs and check JavaFX CSS diagnostics, not only Sass syntax.
2. Check standard controls in normal, hovered, focused, selected, disabled and overridden
   states. New controls inherit the palette, but new inline styles or custom drawing need
   explicit review. Do not claim automatic coverage of arbitrary future controls.
3. Check Assets, Outline, Properties, document tabs, Console, Game, resource preview,
   script editor, curves, animation timeline, welcome screen, preferences, context menus
   and save/discard dialogs. Preserve property override and error indications.
4. Exercise panel dragging, tab dragging, splitter resizing, keyboard focus, closing tabs
   and reopening the application with saved layout. Run `editor.docking-test`.
5. Check Windows and macOS, CJK labels, and 100%/150%/200% scaling before calling coverage
   complete. OS-native title bars and file pickers follow the operating system theme.
6. Deliver through `patch/README.md`, verifying against an exact baseline. A UI stylesheet
   change does not justify rebuilding the docking or project model.
