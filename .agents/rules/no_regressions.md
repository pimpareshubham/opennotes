# Forward Progress & Zero-Regression Rule (Never Break What Works)

## Core Directive
We are meant to progress forward, not backward. Under NO circumstances should existing, working functionality be broken, regressed, disabled, or removed.

## 1. Zero-Regression Invariant
- **Never Break Working Features**: When fixing a bug, refactoring, or adding a new feature, existing functional behaviors, UI interactions, and features must remain 100% intact.
- **Explicit Scope Only**: Only modify what the user has explicitly requested to change. Do NOT touch, simplify, alter, or remove unrelated features, settings, or implementations.
- **No Deletion/Downgrading of Capabilities**: Never revert or remove persistence logic, optimizations, gestures, animations, tools, or configuration options unless the user explicitly tells you to do so.

## 2. Unbreakable Persistence & State Preservation
- **Preserve Reading & Viewport Position**: Always maintain and respect user position persistence (page index, scroll offset, pan, zoom level). Never allow state to reset to page 0 or scroll 0 on app restarts, view reloads, or navigation exits.
- **Per-Notebook Configurations**: Never wipe or bypass per-notebook settings (active tool, stroke widths, colors, continuous scroll, lasso mode, laser mode, AMOLED dark mode).
- **Safe Lifecycle Saves**: Viewport state and page data must be saved cleanly and defensively before any view detaches or destroys.

## 3. Non-Destructive Data Operations & Corruption Prevention
- **Never Overwrite with Empty/Unhydrated Data**: Never save unhydrated page shells or empty data over existing database rows or assets.
- **Atomic Writes**: Always write through temporary files (`.tmp`) and atomically rename to avoid partial writes or corruptions on crashes.
- **Self-Healing & Defensive Fallbacks**: Never crash or wipe user files when encountering unexpected data formats; fall back gracefully and repair missing references non-destructively.

## 4. Verification & Testing
- Always run unit tests (`.\gradlew.bat testDebugUnitTest`) before finalizing changes.
- Verify that previous flows, gestures, and persistence remain fully operational before delivering any build.
