# AGENTS.md

Guidance for coding agents working on this repository.

## Project

SmartScan: single-module Android app (`:app`), Kotlin + Jetpack Compose, package `com.smartscan`.
User-facing language is Italian. Keep UI strings in Italian.

## Build and verify

- No Gradle wrapper jar is committed. Build with a local Gradle 8.11.x: `gradle assembleDebug`.
  CI (`.github/workflows/android.yml`) does the same on ubuntu with JDK 17.
- There are no unit tests yet. At minimum, a change must pass `gradle assembleDebug`.
  Pure-Kotlin logic (`voice/MetadataParser.kt`, `scan/AutoCaptureController.kt`, `export/PdfWriter.kt`)
  is the best place to add JVM unit tests under `app/src/test`.
- Releases: push a `vX.Y.Z` tag. CI attaches `smartscan-vX.Y.Z.apk` to a GitHub release.
  Bump `versionCode` / `versionName` in `app/build.gradle.kts` first.

## Layout

```
app/src/main/java/com/smartscan/
  SmartScanApp.kt      Application + AppContainer (manual DI), HardwareKeys, DeepLinks
  MainActivity.kt      Nav graph (routes: archive, scanner/{docId}, review/{docId}, crop/{pageId},
                       doc/{docId}, endpoints, qr, taxonomy, settings), volume-key shutter
  data/                Room entities/DAOs/DB, Repository, AppSettings (SharedPreferences)
  scan/                OpenCV: DocumentDetector (edges, sharpness, scene signature),
                       ImageProcessor (EXIF normalize, warp, deskew, filters), AutoCaptureController
  ocr/                 ML Kit OCR -> text + normalized line boxes
  export/              PdfWriter (hand-written PDF 1.4, invisible OCR layer), Exporter
  sync/                EndpointConfig (QR parsing), SyncManager (upload, taxonomy merge), OCR/Upload workers
  voice/               VoiceController (SpeechRecognizer loop), VoiceCommands, MetadataParser
  ui/                  Compose screens, one ViewModel per screen in the same file
```

## Conventions and invariants

- Page geometry: `PageEntity.corners` holds normalized corners `tl,tr,br,bl` relative to the **upright
  original** (`originalPath`, EXIF already applied by `ImageProcessor.normalizeCapture`). Never store
  pixel coordinates.
- `processedPath` gets a new file name on every render, so Coil caches never go stale. `Repository.rerender`
  deletes the old file and resets OCR (`ocrText = null` means "OCR pending").
- All page rendering goes through `Repository` and its `renderMutex`, to keep memory bounded and page
  positions consistent. Do not call `ImageProcessor.renderPage` from UI code.
- OpenCV `Mat`s must be `release()`d. The camera analyzer runs about 10 fps and leaks quickly.
- OpenCV is loaded with `OpenCVLoader.initLocal()` in `SmartScanApp`. Images are BGR.
- Taxonomy: `TaxonomyNode.endpointId == null` means local and editable. Otherwise the node is remote
  and read-only, and is merged by `(kind, remoteId)` in `SyncManager.merge`. Do not delete and
  re-insert remote nodes: that cascades away document assignments.
- Document lifecycle: `DRAFT` (scanning/review) -> `PROCESSING` (saved; OCR queued) -> `READY`.
- Scanner flow is continuous: no single/multi mode. «Fine» (`ScannerViewModel.finishDocument`) saves the draft
  and scanning goes on into a new draft. Closing the scanner saves pending pages. `DocumentEntity.output`
  (PDF/IMAGES, picked in the scanner) overrides the endpoint format in uploads (`SyncManager.effectiveFormat`).
- Room schema changes need a `Migration` in `AppDatabase` (current version 3). Never use destructive migration.
  `SyncManager.enqueueFinalize` chains OCR and then the auto-uploads (unique work `finalize-<docId>`).
- Scanner quick toggles (HQ, Bordi, Raddrizza, GPS) persist in `AppSettings`. HQ pages are recognized by their
  original size (`ImageProcessor.isHighQuality`), so re-renders and exports keep the quality without a schema field.
  The light is a torch (`enableTorch`, re-applied on camera open and after each capture), never the capture flash.
- Per-page metadata (`PageEntity.note`, `lat`/`lon`, `codes`) is written into exports by `Exporter`
  (PDF Info + page annotation, EXIF + XMP) and into the upload JSON `pageInfo`. Codes are decoded with the OCR
  (`ocr/CodeReader`), and `Repository.rerender` resets them.
- The endpoint protocol (QR JSON, multipart fields, metadata schema `smartscan/1`, taxonomy JSON) is
  documented in README.md. If you change it, update README in the same change and keep it backward
  compatible (`ignoreUnknownKeys`).
- `SpeechRecognizer` must be used on the main thread only (`VoiceController`).
- Security: a scanned QR config must never be saved without the user confirming the dialog
  (`ConfirmEndpointDialog`). Do not log header values (they hold tokens).

## Style

- Kotlin official code style, 4-space indent, trailing commas allowed.
- Material 3 components. Opt in to experimental APIs locally with `@OptIn`.
- Keep the ergonomics: primary actions sit in the bottom thumb zone, and every scanner action is
  reachable by voice or volume key.
