# Defold / PhotoCraft many-to-many binding validation

Validated on macOS Apple silicon, 2026-10-17. Defold implementation: `cdcf5837e8`, native selector fix: `15eac21a6d` (patch.30). PhotoCraft binding implementation: `25f55b4f`; release validation fixes: `4bdaff0a` and `744d4f340e` (patch.5). CI/migration repair: `0a0e80f8df` (patch.31).

## Behavior

Each running editor retains a collection of independent PhotoCraft sessions. Each PhotoCraft retains independent Defold project sessions. Adding, renewing, selecting or disconnecting one connection does not release another connection. Both applications show their actual listening port; a second default-port instance falls back to a free port, while an explicitly configured port remains strict.

Defold's **File → Open GUI in PhotoCraft** asks for the destination when multiple connections exist. **PhotoCraft Binding Status** lists all connections and this editor's listener. **Disconnect PhotoCraft** removes one selected connection. PhotoCraft's **Defold Sync → Defold editor connection** offers a project/address selector and independent disconnect.

An incoming GUI-open request retains its authenticated source project. Synchronization resolves the open document's project rather than the currently selected connection. Existing project-source revisions, leases and publication conflict checks still apply: multiple connections do not silently merge independent drafts.

Connections remain runtime sessions. A running PhotoCraft automatically reconnects known editor addresses after an editor restart. If both applications exit, non-default endpoints must be added again. LAN connections were not manually exercised in this run.

## Actual application regression

Two native Defold editors opened distinct projects A/B on ports 18750/18751. Each project contains a different `main.gui`, deliberately sharing the same resource path. Two native PhotoCraft instances listened on 19155/19156, with authenticated control endpoints restricted to the QA fixtures. Existing applications on the default ports were left running independently.

Defold UI interactions used `cljfx.plorer`. Test-only key mappings dispatched the production open/status/disconnect commands through actual key events, avoiding reliance on native macOS menu accessibility. PhotoCraft connections, confirmation, disconnect and synchronization were operated through the native UI. Read-only control/HTTP inspection verified completion and document identity; credentials were not recorded.

| Check | Observed result |
| --- | --- |
| Four simultaneous edges | A→Craft1, A→Craft2, B→Craft1 and B→Craft2 all online |
| Add B while A has unsaved GUI work | Both sessions and the unsaved A document survived |
| Native Defold target selector | Both Craft addresses rendered; selected destination received B's GUI; cancel performed no open |
| A opens while B is selected | Craft2 opened A's `main.gui`, preserving selected connection B |
| A syncs while B is selected | Native Sync published `box-a.position.x = 420` into A's editor graph and disk; B's `main.gui` stayed unchanged |
| Credentials | B rejected A's session ticket, including after reconnection |
| Craft-initiated disconnect | Only Craft1↔B disappeared; other three sessions and A's draft survived |
| Defold-initiated disconnect | Only B↔Craft2 disappeared; other three sessions survived |
| Reverse handshake | Defold-initiated rebind restored B↔Craft2 after native consent |
| Editor offline/restart | Both Crafts independently marked B offline; A remained usable; B reconnected with a fresh editor identity |
| Final Defold binary | Both patch.30 editors restored all four edges; packaged selector rendered without source hot loading |

Machine-readable evidence is stored beside this report. `${DEFOLD_REPO}` denotes the checkout directory; session IDs are ephemeral and are not authentication tickets.

## Patch verification

Defold used the exact installed patch.22 inventory, then verified the local 22→29 development update and final 29→30 update through the application dialog. Both downloads, baseline/hash checks, installation and actual process restart passed. The final target matched all 168 recorded files, including permissions. Patch.29 was an unpublished test build. Formal CI builds the next transition from the last published platform inventories.

PhotoCraft retains the published patch.3 bootstrap and its exact signed baseline. Patch-only packaging uses the native updater's block-delta format, preserving HEIF support, bundled helpers and icon. No new DMG or full application archive is produced. Patch.3→5 passed local-feed download, installation, restart health acknowledgement and exact signed target inventory verification. A separate patch.3 copy downloaded patch.5 from the published GitHub feed, installed, started and matched the same full inventory. The published immutable release is [PhotoCraft patch.5](https://github.com/iricwang/photocraft/releases/tag/patch-ai-p0-5), built with HEIF from `744d4f340e15ba719ba86fb45efc41b0bbb14759`.

Local verification feeds: Defold `http://127.0.0.1:8774/manifest.json`; PhotoCraft `http://127.0.0.1:18794/manifest-macos-aarch64.json`. These are isolated test overrides, not production application configuration.

## Automated checks

- Defold: 28 tests / 160 assertions, zero failures or errors; project snapshot/publication integration: 13 tests / 97 assertions, zero failures or errors. Binding module and regression tests: clj-kondo zero errors/warnings. Shared `app_view.clj` has 20 existing warnings, confirmed identical to baseline `5743c8d3b0`; no new warnings were introduced.
- PhotoCraft initial full run: 5218 passed, 6 failed, 41 ignored. The six failures exposed malformed legacy catalog rows, an obsolete async-sync assertion, the Help-menu update entry expectation, and a short-panel primary-action layout regression. These were repaired before the final release build. The final UI rerun passed 3084 tests with 18 ignored. The unchanged engine/integration portion passed 2140 with 23 ignored: 5224 passed / 41 ignored in total, zero final failures. A flaky socket fixture discovered during reruns was fixed by accepting blocking streams explicitly and waiting on protocol completion.
- Adversarial command input (`panic_hunt`, ignored test explicitly enabled): passed.
- Architecture (33 checks), strict Clippy across all targets, WASM, and command parity (631/631 live) passed. The release binary was built with HEIF.
- Python patch/migration checks: 17 tests, 3 platform skips, zero failures.
- CI macOS ARM editor suite: 130 tests / 5692 assertions passed. Local game-runtime regression after the pinned Panthera URL repair passed twice (3152 and 3153 assertions).

## Screenshots

- [Initial local update](01-defold-update-available.png), [verified](02-defold-update-verified.png)
- [Craft retains two project connections and an unsaved GUI](03-craft-two-projects.png)
- [Project A published from Craft while connection B was selected](04-project-a-synced.png)
- [Defold lists two Craft connections](05-defold-two-crafts.png)
- [Packaged patch.30 target selector](06-defold-target-selection.png)
- [Final patch.30 update](07-final-update-available.png), [verified](08-final-update-verified.png)

## Final released Craft binary

Two patch.5 native Craft instances (ports 19157/19158) each connected to A and B while older QA peers remained independently online. Every new edge appeared in both ends' status. Selecting A and opening B from the native Defold destination picker loaded `box-b`. Editing X to 450 and using the top native Sync button published B to disk and cleared its dirty state; A remained at X=420. This repeats the routing check on the final signed release, without source hot loading.

- [Final Craft two-project selector](09-craft-patch5-two-projects.png)
- [Final Craft publishes B while A is selected](10-craft-patch5-project-b-synced.png)

The actual application on `/Applications` was not closed or changed by these QA runs. Updating both applications is required for many-to-many operation.

[Defold registered connections including the two final patch.5 peers](11-defold-final-craft-connections.png). Older QA clients were then closed after their unsaved drafts were saved to separate `.pcraft` backups; the status list retains their session registrations until removal/discovery.

The downloaded ARM CI patch.31 migration artifact was checked before publication: all 168 base entries matched the local patch.22 QA copy; every payload hash matched its plan; normal and compatibility patch target inventories were identical. ARM CI runtime completed twice with 2897 / 2869 passing assertions and no failures (see `ci31-arm64-runtime-summary.json`).

CI patch.31 succeeded on ARM macOS, Intel macOS and Windows x64. Intel game-runtime regression also completed twice (2931 / 2883 passing assertions, zero failures; `ci31-intel-runtime-summary.json`). All platform jobs completed before the publisher advanced the production feed.

## Published Defold patch.31 acceptance

[CI and publisher](https://github.com/iricwang/defold/actions/runs/39034936608) completed successfully on all three platforms. [Defold patch.31](https://github.com/iricwang/defold/releases/tag/patch-dev-31) contains normal incremental packages, the exact local patch.22 compatibility package and inventories. The production `patch-dev/manifest.json` reports revision 31.

The production `patch/migrate_local22.py` helper ran against an isolated exact copy of the installed local patch.22 baseline. QA added only nREPL/plorer launcher arguments; baseline validation, the running-process guard, HTTPS feed and feed handoff remained unchanged. The native update dialog downloaded from the published GitHub compatibility feed, verified SHA-256, installed and restarted. All 168 target hashes/modes matched the normal CI patch.31 inventory, and the helper restored the normal GitHub feed after the original process exited.

Both final CI patch.31 project processes then started on the original test addresses. Both released Craft patch.5 instances automatically reconnected, each with exactly two fresh editor identities; each editor had exactly the same two Craft IDs. The packaged CI selector rendered both targets and cancelled normally without source hot loading.

- [GitHub update available](12-github-ci31-available.png)
- [GitHub package verified](13-github-ci31-verified.png)
- [Final CI target selector](14-final-ci31-target-selection.png)
- `defold-ci-migration-validation.json`: complete download/install/restart and exact inventory evidence
- `final-ci31-patch5-matrix.json`: final strict 2×2 connection graph
- `ci31-status.json`: successful platform and publisher jobs

## Using the release

Update both sides: PhotoCraft patch.5 and Defold patch.31. In PhotoCraft, expand **Defold Sync → Defold editor connection**, add the other editor address, and select or disconnect a specific connection. In Defold, **File → Open GUI in PhotoCraft** chooses a target when multiple Craft sessions exist.

The old locally built Apple Silicon patch.22 must first join the CI inventory chain: save and fully quit that installation, then run `python3.12 patch/migrate_local22.py` from the Defold checkout and apply the offered patch. Other supported CI baselines use the normal in-app update flow. PhotoCraft patch.3 uses its normal **Help → Check for Updates** flow.
