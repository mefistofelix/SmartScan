# SmartScan

Document scanner for Android, built for one-handed use without a keyboard.

[![Android CI](../../actions/workflows/android.yml/badge.svg)](../../actions/workflows/android.yml)

Download the APK from the **Releases** page. It is a debug build signed with the fixed CI debug key, so every release installs over the previous one.

The interface is in English, with an Italian translation that is used automatically on phones set to Italian.

## Features

| Area | What it does |
|---|---|
| **Capture** | Camera preview with real-time corner detection by a **neural network** (DocAligner). It also works on busy, colorful backgrounds and with objects lying on the page; OpenCV is the fallback while the model loads. **Automatic capture** when the page is still and in focus, **waits for the next page** (notices the scene change), tap to focus, **volume keys as shutter**. |
| **Continuous flow** | The app opens straight into the camera (can be turned off in Settings). No modes to pick: shoot the pages; bottom left shows the last page and the page count (tap = strip of pages: tap one to view it full screen with zoom, long press and drag to reorder, X deletes); bottom right picks **PDF / IMG** (default PDF) and **Done**. The document goes to the archive, OCR and uploads run in the background, and the camera is ready for the next document right away. Closing the camera or the app does not save and does not lose anything: next time the camera opens with the same pages, ready for **Done** or more pages. A saved document can be reopened in the camera to add pages (camera icon on its page). |
| **Quick toggles** | Icons at the top of the camera, one tap each, remembered for next time (a short notice says what changed; long press shows the name): **detection** as one three-state button (Auto: edges and automatic capture → Edges: edges and deskew, you shoot → Off: whole frame as it is), **preview** full screen (nicer, but a tall screen hides about 40% of the captured width) or whole frame, **HQ** (full sensor resolution, low compression, no downscaling on export), **GPS** (location saved with every page), **light** (a torch: it stays on, it does not flash at capture; it is restored when the app returns to the foreground), and, only when an endpoint with automatic upload exists, **send to storage** (off: the document stays local at Done; it can still be sent from its page). The folder icon opens the document list; Back from the list returns to the camera. |
| **Voice notes** | Note button at the bottom left: dictate a note for the current document, as many times as needed. Each note belongs to the last captured page (a note dictated before the first page goes to the document notes). Page notes are shown per page on the document page, in the full-screen viewer, and can be edited page by page in Edit. Until the document is saved, the badge (or a long press) lists the notes, each one deletable; the list closes by itself. If the document has no title yet, the first words of the first note become its name (tap the title on the document page to rename). Without notes the name is automatic («Scan date time»). |
| **QR codes and barcodes** | During OCR every page is also read for QR codes and barcodes (all formats, more than one per page). The decoded values go into the metadata. |
| **File metadata** | PDF: title, notes (Subject), categories and tags (Keywords), codes and location in the Info dictionary, plus a non-printing note on every page with that page's voice note, codes and location. Images: EXIF (description, comment, GPS) and XMP with the full text (title, notes, tags, page note and codes). |
| **Processing** | Perspective correction, **automatic deskew** (on text lines), manual 4-corner editor with magnifier, **edge expand / shrink**, «full page», rotation, page reorder and delete. |
| **Filters** | Original, Enhanced (shadow removal and illumination normalization on lightness only, so colors stay true), Grayscale, Black and white (adaptive threshold, stored as 1 bit). |
| **OCR** | ML Kit on device (offline), in the background with WorkManager. Searchable from the archive. |
| **Export** | PDF, **searchable PDF** (invisible OCR text layer), JPEG/PNG, TXT. Own PDF writer: JPEGs are embedded without re-encoding and B/W pages as 1-bit Flate images, so files stay small. High/Medium/Compact quality and Auto/A4/Letter page size. Share or «Save as…». |
| **Archive** | Local (Room plus app-private files). Search on title, notes, OCR text and tags, also **by voice** (microphone inside the search box, X clears it); filter by a branch of the category tree. Long press a document for **multi-select**: share all selected documents as **one ZIP** (each in its own format: searchable PDF or a folder of images), **delete** them, or **reorder** the list by dragging the handle next to each document. |
| **Categories and tags** | Two trees of any depth (categories, tags), several per document. Local trees are editable; **remote trees** come from the endpoints (read-only, merged by stable ids). |
| **Storage endpoints** | Added from a **QR code** (or a `smartscan://add?cfg=…` link) with an explicit host confirmation. HTTP multipart or WebDAV. **Background upload** with retries, «Wi-Fi only» option, automatic or manual sending. |
| **Voice** | Dictation only, no voice commands. Metadata can be dictated **before** (when the camera opens), **during** or **after** (at Done, before saving) a document. After Done the camera is ready again, with no screen in between. Swear words are not masked. |

### Voice metadata

The dictation parser understands Italian keywords: `titolo` (title), `categoria` (category), `tag`/`etichetta`, `nota` (note), `data` (date). Example:

> «titolo fattura Enel categoria bollette tag urgente e casa nota pagare entro venerdì data 12 marzo 2025»

Categories and tags are matched loosely (accents, small recognition errors) against the local and remote trees. Unknown tags can be created on the fly. Without keywords, recognized tag/category names are assigned and the rest of the sentence becomes the title.

## Endpoint protocol

### Configuration QR

The QR holds this JSON (or `smartscan://add?cfg=<base64url JSON>`):

```json
{
  "v": 1,
  "name": "Office archive",
  "type": "http",
  "url": "https://scan.example.com/api/upload",
  "headers": { "Authorization": "Bearer <token>" },
  "taxonomy": "https://scan.example.com/api/taxonomy",
  "auto": true,
  "format": "pdf_ocr"
}
```

- `type`: `http` (multipart POST) or `webdav` (PUT into the `url` folder).
- `format`: `pdf`, `pdf_ocr`, `images`. It is the endpoint default: the PDF/IMG choice made in the camera for each document wins. For PDF documents, `pdf` sends the PDF without text and the other values send the searchable PDF.
- `headers`: sent with every request (upload and taxonomy).

The QR contains a token: treat it like a password.

### HTTP upload

`POST url` with `multipart/form-data`:

- `metadata`: JSON (see below)
- `file`: one or more files (PDF or images)

A `2xx` response counts as success. If the body is `{"id": "..."}`, the id is stored as the remote reference. On network errors, 408, 429 and 5xx the app retries with exponential backoff. Other 4xx are final errors. Saving a document that was already sent sends it again: the server must update the existing document using `deviceDocId`.

```json
{
  "schema": "smartscan/1",
  "deviceDocId": 42,
  "title": "Electricity bill",
  "notes": "pay by Friday",
  "date": "2025-03-12",
  "createdAt": "2025-03-14T09:12:00Z",
  "updatedAt": "2025-03-14T09:13:10Z",
  "pages": 2,
  "format": "pdf_ocr",
  "categories": [{ "name": "Bills", "path": "Home/Bills", "id": "c-12", "source": "endpoint" }],
  "tags": [{ "name": "urgent", "path": "urgent", "source": "local" }],
  "files": ["Electricity bill_42.pdf"],
  "ocrText": "…",
  "pageInfo": [
    { "page": 1, "note": "pay by Friday", "lat": 45.4642, "lon": 9.19,
      "codes": [{ "format": "qr", "value": "https://example.com/f/123" }] },
    { "page": 2, "codes": [] }
  ]
}
```

`id` is only present for entries that come from **this** endpoint's taxonomy. `pageInfo` (since 0.3) has one entry per page: `note`, `lat`/`lon` (only when GPS was on) and `codes` (decoded QR codes and barcodes; `format` is `qr`, `ean13`, `code128`, `datamatrix`, `pdf417`, …). Older servers can ignore it.

### WebDAV

`PUT {url}/{file}` for each file and `PUT {url}/{name}.json` with the same metadata.

### Remote taxonomy

`GET taxonomy` →

```json
{
  "categories": [
    { "id": "home", "name": "Home", "children": [ { "id": "c-12", "name": "Bills" } ] }
  ],
  "tags": [ { "id": "t-1", "name": "urgent" } ]
}
```

The `id`s must stay stable, so assignments survive synchronization. Sync runs when the app starts, after an endpoint is added, and on demand.

## Build

Requirements: JDK 17 and Android SDK 35 (or a recent Android Studio).

```bash
gradle assembleDebug        # or open the folder in Android Studio
```

The repository does not include the Gradle wrapper jar. Android Studio uses `gradle/wrapper/gradle-wrapper.properties`, and CI uses Gradle 8.11.1 through `gradle/actions/setup-gradle`. To create the wrapper locally: `gradle wrapper`.

CI ([.github/workflows/android.yml](.github/workflows/android.yml)) builds every push and PR. A `v*` tag publishes a release with the APK.

## Stack

Kotlin, Jetpack Compose (Material 3), CameraX 1.4, OpenCV 4.14, ONNX Runtime 1.22 with the DocAligner LC100 model ([DocsaidLab](https://github.com/DocsaidLab/DocAligner), Apache-2.0, license in `app/src/main/assets/licenses`), ML Kit Text Recognition and Barcode Scanning, Room, WorkManager, OkHttp, kotlinx.serialization, Coil. minSdk 26, targetSdk 35.

## Status and known limits

- Tested mainly on one device (Samsung Galaxy A16). Detection and auto-capture thresholds ([`AutoCaptureController`](app/src/main/java/com/smartscan/scan/AutoCaptureController.kt)) may need tuning on other phones.
- The APK only ships `arm64-v8a` native libraries (almost every recent phone). For 32-bit devices or x86 emulators, remove `abiFilters` from `app/build.gradle.kts`.
- Releases are debug builds. A signed release build needs a keystore in the CI secrets.
- Voice metadata keywords are Italian (see above); the recognition language is set in Settings.
- Dictation uses the system `SpeechRecognizer`. Some devices play a sound when listening starts.
- The app is portrait only, and the screen does not go to sleep while the app is in the foreground.
- Samsung phones block the light when the battery is below about 15%; the app shows a notice.
- Cleartext traffic (`http://`) is allowed for LAN servers. The app warns about endpoints that are not https.
