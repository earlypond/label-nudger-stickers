# LabelNudger - Agent Context

## Project Overview

LabelNudger is an Android utility app for printing label sheets with precise positional calibration. It solves the problem of printer misalignment by allowing users to apply X/Y offset adjustments (in millimeters) to PDFs before printing.

### Key Features
- **PDF Label Printing**: Select PDFs from device storage and print with calibrated positioning
- **Nudge Calibration**: Adjust label positions in 0.5mm increments using directional controls
- **Stickers Library**: Download pre-designed label sheets from a remote server
- **Multi-Copy Printing**: Print multiple copies of the same label sheet
- **Print Job Monitoring**: View and cancel active print jobs

## Tech Stack

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose with Material Design 3
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36
- **Build System**: Gradle with Kotlin DSL
- **Java Compatibility**: VERSION_11

### Key Dependencies
- `androidx.activity:activity-compose` - Activity integration with Compose
- `androidx.compose.material3:material3` - Material Design 3 components
- `androidx.compose.material:material-icons-extended` - Extended icon set
- `androidx.lifecycle:lifecycle-runtime-ktx` - Lifecycle awareness
- `com.tom-roush:pdfbox-android` - Vector PDF manipulation (no rasterization)

## Project Structure

```
LabelNudger/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/labelnudger/
│   │   │   ├── MainActivity.kt          # All application logic (~700 lines)
│   │   │   └── ui/theme/
│   │   │       ├── Color.kt              # Color definitions
│   │   │       ├── Theme.kt              # Theme configuration
│   │   │       └── Type.kt               # Typography
│   │   ├── res/
│   │   │   ├── values/                   # Strings, colors, themes
│   │   │   ├── drawable/                 # Launcher icons
│   │   │   ├── mipmap/                   # App icons
│   │   │   └── xml/                      # Backup rules
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                  # App dependencies
├── gradle/
│   └── libs.versions.toml                # Version catalog
├── build.gradle.kts                      # Root build config
└── settings.gradle.kts
```

## Key Files

### `MainActivity.kt`
The main source file containing all application logic. Key components:

**Composables:**
- `LabelNudgerApp()` - Root composable wrapper
- `PrintScreen()` - Main UI with 3-step wizard (PDF selection, nudge controls, stickers)
- `NudgeCircleButton()` - Circular directional button for nudge controls

**Print System:**
- `printShiftedPdf()` - Creates print job with shift parameters
- `ShiftedPdfPrintAdapter` - Custom `PrintDocumentAdapter` that:
  - Renders PDF pages at 300 DPI
  - Applies matrix transformations for X/Y shifts
  - Handles multi-copy output

**Network:**
- `fetchStickersList()` - Downloads sticker index from remote server
- `downloadStickerPdfAndSelect()` - Downloads and caches sticker PDFs

**Utilities:**
- `mmToPoints()` - Converts millimeters to PDF points (72 points = 1 inch)
- `PageRangeUtils` - Helper for print page range calculations

### `AndroidManifest.xml`
- Package: `com.example.labelnudger`
- Requires: `INTERNET` permission
- Single activity: `MainActivity`

## Core Concepts

### Nudge/Shift System
The app applies positional offsets to printed labels:
- Values stored as `shiftXmm` and `shiftYmm` (Float, in millimeters)
- Converted to PDF points using: `mm * 72f / 25.4f`
- Applied via Canvas matrix transformation during PDF rendering
- Positive X shifts right, positive Y shifts down

### PDF Transformation Pipeline (Vector-based, no rasterization)
1. Load source PDF using PdfBox (`PDDocument.load()`)
2. Import each page into output document (`outputDoc.importPage()`)
3. Prepend translation matrix to page content stream (`PDPageContentStream` with `AppendMode.PREPEND`)
4. Apply shift via `Matrix.getTranslateInstance(shiftX, -shiftY)` (Y negated for PDF coordinate system)
5. Save output PDF directly to print system

This approach preserves vector quality and uses minimal memory compared to rasterization.

### Stickers System
- Remote index at: `https://blueislandpress.github.io/label-nudger-stickers/index.json`
- JSON format: `[{"name": "Label Name", "url": "https://...pdf"}, ...]`
- Downloaded PDFs cached in app's cache directory

## Building and Running

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

## State Management

UI state is managed with Compose's `rememberSaveable`:
- `shiftXmm`, `shiftYmm` - Current nudge values
- `copies` - Number of copies to print
- `selectedPdfUri` - Currently selected PDF
- `wizardStep` - Current screen (0=select, 1=nudge, 2=stickers)
- `activePrintJob` - Current print job reference

## Notes for Development

- The entire app logic is in `MainActivity.kt` - consider refactoring if adding features
- Uses `GlobalScope` for coroutines - could be improved with proper ViewModel lifecycle
- No local database - calibration settings reset on app restart
- Stickers are cached but cache is not managed (no cleanup)
