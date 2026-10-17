# Handwriting & Selection Invariants

1. **Angle-Invariant Recognition & Deciphering**:
   - Google ML Kit's digital ink neural network is trained on horizontal text. To recognize handwriting written at any angle or incline without failing with "could not decipher", level the strokes horizontally along the detected writing angle ($\theta$) before passing them to the recognizer.
   - When placing converted text back onto the page, anchor the text cleanly with `angleDegrees = 0f` and compute `fontSize` from stroke height. Never force slanted text boxes onto the page.
   - Provide multi-hypothesis waterfall fallback ($\pm 12^\circ, \pm 25^\circ, \pm 15^\circ, \pm 30^\circ, \pm 45^\circ$) and cross-language fallback (Devanagari ⟷ English) so inclined writing is never dropped or rejected.

2. **Continuous Single-Line Writing Preservation**:
   - Single-line writing (even when inclined at an angle) must NEVER be chopped into multiple lines.
   - Analyze stroke center vertical alignment (`hasDistinctMultipleLines`). If strokes form a continuous single line along the writing baseline, replace any spurious newlines from ML Kit with spaces.
   - Only preserve newlines when strokes have genuinely stacked multi-line vertical separation ($\ge 1.75\times$ median stroke height) with overlapping horizontal ranges.

3. **Lasso Selection Handle Placement**:
   - In direct-manipulation selection tools (e.g. Lasso), the rotation and tilt handle must NEVER be positioned at the top where it is obscured by the top floating contextual action bar (`lassoFloatingActionBar`).
   - Position the rotation stalk and knob on the **right side** of the selection bounding box (`rotKnobX = selectionBounds.right + stalkLength; rotKnobY = selectionBounds.centerY()`), with touch hit-testing centered on the right-side knob.

4. **APK Build Artifact Naming**:
   - Whenever building the debug APK, always copy the output APK to `Desktop/starnote/OpenNotes-<commit_id>.apk` using the short git commit hash (`git rev-parse --short HEAD`).

5. **Multi-Line Bounding Ceiling Invariant**:
   - In hierarchical agglomerative or single-linkage clustering across lines/rows, an unbounded vertical gap allowance causes tall ascenders, low descenders, or diacritics to cascade and collapse distinct lines into a single massive cluster.
   - Enforce a strict line height ceiling ($H \le 1.85 \times \text{median\_line\_height}$) on candidate line merges, preventing runaway cascading across unlimited lines and rows.

