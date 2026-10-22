# Global Engineering Directives & Rule Book

## Core Directive: Forward Progress Only (Never Break What Works)
We are meant to progress forward, not backward. Under NO circumstances should existing, working functionality be broken, regressed, disabled, or removed.

### 1. Zero-Regression Invariant
- **Never Break Working Features**: When fixing a bug, optimizing code, or adding features, all existing functional code, UI behaviors, gestures, and user flows must remain 100% intact.
- **Strict Scope Boundaries**: Only modify what the user has explicitly requested to change. Do NOT touch, simplify, alter, or remove unrelated features, settings, or implementations.
- **No Deletion or Downgrading**: Never revert, remove, or degrade existing capabilities, persistence mechanisms, optimizations, animations, or settings.

### 2. State & Reading Position Persistence
- **Always Preserve User State**: User position persistence (page index, scroll offset, pan, zoom level) must always remain active, accurate, and protected. It must never reset to default (page 0 / scroll 0) on re-entry, exit, or view recycling.
- **Per-Notebook Configurations**: Never reset or bypass per-notebook preferences (drawing tool, stroke width, color, lasso mode, laser mode, continuous scroll, dark mode).
- **Defensive Lifecycle Hooks**: Ensure state is cleanly saved in all exit pathways (`btnBack`, `onBackPressed`, `finish`, `onPause`, `onStop`, `onDestroy`).

### 3. Non-Destructive Data Operations & Corruption Prevention
- **Unhydrated Shell Guard**: Never overwrite database records with unhydrated shells or empty data.
- **Atomic Disk Writes**: All data persistence must write through `.tmp` files first, flushing completely before atomically renaming to `.dat`.
- **Defensive Fallbacks**: Fall back cleanly and non-destructively if data or files encounter unexpected states.

### 4. Build, Test & Artifact Protocol
- **Verification**: Run unit tests (`.\gradlew.bat testDebugUnitTest`) before finalizing code.
- **APK Naming**: When building the debug APK (`.\gradlew.bat assembleDebug`), always copy the output APK using the short commit hash (`git rev-parse --short HEAD`):
  1. `OpenNotes/OpenNotes.apk` and `OpenNotes/OpenNotes-<commitHash>.apk`
  2. `starnote/OpenNotes.apk` and `starnote/OpenNotes-<commitHash>.apk`
