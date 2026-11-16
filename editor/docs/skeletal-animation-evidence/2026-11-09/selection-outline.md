# Smooth selection outlines

Replace binary pixel-square edges (512-pixel cap) with alpha-interpolated marching squares (aspect-preserving 1024-pixel cap). Trace at half peak alpha to avoid transparent fringes while preserving fully translucent textures. Simplification tolerance is 0.2 sampling pixels instead of a fixed UV error. Contours remain split and bound to mesh triangles for deformation. Round stroke caps close segment seams. Retain up to 32 recent selection bindings to avoid repeated extraction when switching images.

Verification: 244 tests / 3048 assertions, zero failures/errors. Selection namespace and tests: clj-kondo zero errors/warnings. Coverage includes antialiased circle accuracy, holes/islands, image boundaries, translucent/empty textures, clipping and deformation reuse.

Live session: alpha-interpolated outlines and capped selection cache hotloaded and viewed on rat attachments. Tail 849×847 image produced 249 bound segments versus old 529. First optimized extraction measured 127.6 ms versus old 98.8 ms (higher source sampling resolution); extraction is cached and not performed every drag frame. Screenshot shows front-right attachment selected. No disabled editor timers at visual verification.

The final peak-relative alpha threshold addition passed tests, but its hotload connection was reset when the Defold process exited. Process inspection then showed no running Defold editor and REPL port refused connections. Thus latest supplemental change is source-only. No package installed; runtime unchanged; no asset or mesh data edited.
