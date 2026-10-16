# OpenNotes ✍️🎨

[![Android](https://img.shields.io/badge/Platform-Android_8.0+-3DDC84?logo=android&logoColor=white)](#)
[![API](https://img.shields.io/badge/API-26%20--%2034+-blue.svg)](#)
[![License](https://img.shields.io/badge/License-Apache_2.0-orange.svg)](#)
[![Privacy](https://img.shields.io/badge/Recognition-On--device-success.svg)](#)

A digital notebook designed for Android tablets and phones, with stylus drawing, student lecture notes, calligraphy, an expandable canvas, and on-device handwriting recognition.

---

## ✨ Key Features

### ⚡ 1. Expandable Canvas & Spatial Viewport Culling
- **Infinite Vertical Document Pages**: Notes expand downward seamlessly as you write.
- **Display-List Geometry Caching**: Every committed stroke pre-caches its `Path` and bounding box; redraws run at an instantaneous $O(1)$ GPU rate without point recalculations.
- **Spatial Viewport Culling**: Off-screen strokes and shapes are skipped before rendering. Paper templates (Ruled, Grid, Dots, Cornell) render only lines inside the active viewport, keeping frame rates locked at 60/120 FPS even on documents with thousands of strokes.
- **Zero-Allocation Rendering**: Eliminated in-loop object allocations (`Path`, `Paint`) during continuous drawing to prevent Garbage Collector (GC) stalls.

### 🧠 2. On-Device Handwriting Recognition
- Uses ML Kit Digital Ink Recognition for English, Hindi, and Marathi instead of geometric character guessing.
- Recognition runs asynchronously; original handwriting stays on the page if recognition fails.
- Language models download on first use and run on-device afterward. The first download requires an internet connection.
- **Undo/Redo Support**: Converted text can be undone with a single tap, immediately restoring the original handwriting.

### 🔤 3. External TTF & OTF Custom Font Import
- Import custom `.ttf` and `.otf` fonts directly from device storage via Android's SAF document picker.
- Bundled typefaces:
  - **Kalam**: Authentic Indian handwriting (ideal for Marathi & Hindi).
  - **Caveat**: Natural cursive script for English student notes.
  - **Poppins**: Clean modern geometric sans-serif.
  - **Yatra One**: Classic Devanagari literary calligraphy.

### 📐 4. StarNote-Style Triple-Row UI & Presentation Tools
- **Row 1**: Multi-document tab management with close buttons and quick-tab creation.
- **Row 2**: Notch/status-bar safe navigation dock: Fullscreen mode, Ruler, Templates, Background picker, Page manager, Export, and Settings.
- **Row 3**: Tool shelf: Ballpoint Pen, Fountain Pen with continuous width filtering, Stroke Eraser, Lasso, Highlighter, Pencil, Neon Laser Pointer, Auto-Beautify toggle, Shape tool, and Text tool.
- **Floating Viewport & Page Pills**: Quick zoom presets (20% to 1600%), viewport lock, 1-tap zoom reset, and page navigation.
- **Super Dark Black (AMOLED `#000000`)**: True black background option with auto-contrast ink switching to bright white.

### 📄 5. Ultra-HD 300 DPI RAW PDF Export & Direct Share
- Vector-crisp 300 DPI print-quality PDF generation (2480 × 3508 A4 standard).
- Proportionally handles extended infinite heights without content clipping.
- Export dialog displays file name, file size, exact device path, and one-tap **Share**, **Open File**, and **Copy Path** buttons.

### 🔒 6. 100% Offline & Private
- Zero network permissions requested in `AndroidManifest.xml`.
- No cloud dependencies, no analytics, no user tracking, and no subscriptions. All processing is 100% on-device.

---

## 🛠️ Project Structure

```
OpenNotes/
├── app/
│   ├── src/main/
│   │   ├── java/org/opennotes/app/
│   │   │   ├── model/         # Stroke, DrawingPoint, ShapeItem, TextItem, Page, Notebook
│   │   │   ├── ui/            # MainActivity, NoteEditorActivity, CanvasView
│   │   │   └── util/          # HandwritingRecognitionEngine, TypefaceManager, PdfExporter, StorageHelper
│   │   ├── res/
│   │   │   ├── drawable/      # Tool icons, backgrounds, selector states
│   │   │   ├── layout/        # Activity layouts, dialogs, custom action bars
│   │   │   └── values/        # Colors, themes, strings
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── gradle/
├── gradlew
├── gradlew.bat
├── settings.gradle
└── build.gradle
```

---

## 🚀 Building from Source

### Prerequisites
- Android Studio Hedgehog / Iguana / Jellyfish or newer
- Android SDK (API 34, Build Tools 34.0.0+)
- JDK 17+

### Build Debug APK
```bash
# Clone the repository
git clone https://github.com/pimpareshubham/opennotes.git
cd opennotes

# Build debug APK using Gradle wrapper
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License

Licensed under the [Apache License, Version 2.0](LICENSE).
