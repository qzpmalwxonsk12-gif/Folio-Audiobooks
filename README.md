# Folio — PDF audiobooks for Android

Folio imports PDFs and reads their text through Android's installed text-to-speech engine. It prefers **Speech Recognition & Synthesis from Google** (`com.google.android.tts`) when available. The recognition/microphone side of Google's app is not needed; Folio uses its synthesis voices.

## Install and start

1. Copy `artifacts/Folio-1.0.1.apk` to an Android 8.0 or newer phone and open it to install. Allow installation from the file manager if Android asks. This is a locally signed personal APK (non-debuggable), not a Play Store release.
2. Open **Folio**. Choose **Try a little sample**, then press Play.
3. In **Settings → Android voice settings**, choose Google's engine and download your preferred voice if needed. Return to Folio and tap **Reload voices**.
4. Choose a language and offline voice in Folio. **Automatic** prefers the selected accent and the engine's highest-quality installed offline voice. Use **Hear sample** to compare voices and **Normal pitch & pace** to undo pitch/speed changes. Voice availability depends on the installed engine; Folio does not supply or modify Google's app.
5. Tap **Import PDF**, select a file, and wait for extraction to finish. Image-only pages can take longer because they use local text recognition.

Google's app may appear as “Speech Services by Google” on some phones. Other installed Android TTS engines can also be selected. If Google is missing, Folio tries the system default.

## Features

- Local PDF library, duplicate detection by SHA-256, rename, search, and removal of app-private copies.
- Text extraction, password prompt for encrypted text PDFs, and bundled on-device OCR for image-only Latin-script pages.
- Offline voice picker, engine-reported quality labels, and voice samples that do not change reading progress or statistics. Samples pause when the app leaves the foreground.
- Playback at **0.5–3×**, independent **0–5 second sentence pauses**, voice pitch, and language/voice selection.
- Foreground media playback for listening with the screen off, lock-screen/notification controls, and media-button support.
- Pause when headphones disconnect or another app interrupts audio.
- Saved reading position; resume restarts the current spoken segment so an unfinished sentence is not skipped.
- Previous/next sentence, progress slider, page jump, passage search, and original PDF page preview.
- Sentence highlighting, plus word/range highlighting when supplied by the TTS engine.
- Bookmarks, sleep timer (5–90 minute presets), and stop at the end of the current page.
- Speaking hours, completed spoken words, a daily goal, weekly activity, streaks, and completed books.
- Warm light and dark themes, visible Android system bars, and layouts that respect keyboard/cutout insets.
- JSON export of aggregate statistics, the last seven activity days, and library metadata.

Audio is synthesized during playback. **This version does not export MP3/WAV audiobooks.** It has no microphone permission and no account/login requirement.

## Statistics and privacy

Time is counted between TTS start/completion callbacks, using a monotonic clock and periodic checkpoints. Explicit sentence gaps and paused time are excluded. Words are credited only after an entire spoken segment completes. Replays count again. Very long sentences are split below the engine's input limit. Most checkpoints happen every second; a sudden process kill can lose the uncheckpointed fraction of a second. Calendar-day buckets use the phone's current timezone at each checkpoint.

“Books finished” means books still in the library that have reached the end at least once. Streaks count consecutive calendar days with speaking time and remain active if the most recent day is yesterday. Speed and remaining-time estimates are approximate; voices have different natural cadences.

PDFs, extracted text, progress, and bookmarks are stored in app-private storage. Removing a book does not delete the source PDF. Backups/device transfer are disabled for app data. Uninstalling the app clears its private library/history. Statistics export is not a restorable library backup.

Folio itself has **no INTERNET permission**. It uses a bundled OCR model and does not fetch remote web content. Folio 1.0.1 only selects voices which the separate Android TTS engine reports as installed and not requiring a network connection. It stops instead of falling back to an online voice. Downloading additional voice data in the engine's own settings may require internet. These checks rely on the engine's reported capabilities; Folio cannot control a third-party engine's implementation.

## Boundaries

- Import limits: 150 MB, 3,000 pages, and 10 million extracted characters per PDF.
- OCR supports Latin-script scans, not every writing system. Tables, multiple columns, decorative fonts, mixed text/image pages, and poor scans may need correction outside Folio.
- Blank/unreadable pages are counted and skipped; the reader shows the skipped-page count. OCR is attempted only on pages with no extractable text. An encrypted scan cannot use the Android renderer/OCR fallback in this version.
- Passwords are used during import and are not saved. Original-page previews of encrypted PDFs may be unavailable even after successful text extraction.
- Pausing or changing speech settings restarts the current segment on resume. There is no sample-accurate audio scrubbing because playback uses live TTS.
- Speech engines may impose their own rate/pitch behavior and natural punctuation pauses. Word-level highlighting depends on engine range callbacks.
- Some manufacturers restrict background services. If playback stops when the phone locks, check that phone's battery setting for Folio. Permission to show notifications improves access to playback controls.
- Keep Android System WebView updated. The bundled interface uses modern JavaScript.

## Build

Uses Java 17, Android SDK 35, Android Gradle Plugin 8.7.3, and Gradle 8.14.3. `local.properties` points to this machine's SDK and is ignored by Git; change it for another computer.

```powershell
.\gradlew.bat :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
npm.cmd ci
npm.cmd test
npm.cmd run test:ui
```

The Windows UI test uses the installed Google Chrome executable and a temporary local HTTP server. Its native bridge is mocked: browser tests verify the interface and requested native actions, not Android speech, OCR, or device playback. `tests/output/` contains screenshots and UI results.

Native service regression tests use Robolectric with simulated speech callbacks. They do not establish real Google voice quality or OnePlus background playback behavior. The release variant uses the same local signing certificate as 1.0.0 for in-place updates; keep the signing key to build future compatible updates. Native libraries are compressed for a smaller transfer, with all original ABIs and bundled OCR retained.

See `artifacts/VALIDATION-1.0.1.md` for the actual checks completed for this build and the remaining phone checks.

## Implementation

The Java activity serves only four bundled web assets from an allowlisted local HTTPS origin. External requests/navigation are blocked, file/content access is disabled in WebView, and PDF strings are escaped before rendering. A native Android foreground service owns TextToSpeech, audio focus, MediaSession, wake locks, sleep timers, and progress. SQLite/WAL holds the library and statistics. Import and PDF rendering run on worker threads.

Core references: [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech), [foreground media services](https://developer.android.com/develop/background-work/services/fgs/service-types#media), [ML Kit bundled text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), and [PDFBox Android](https://github.com/TomRoush/PdfBox-Android).

Dependencies: PDFBox Android 2.0.27.0 (Apache-2.0), ML Kit text recognition 16.0.1 and its bundled model (Google's applicable terms), and their transitive dependencies. No online fonts, images, or web libraries are loaded at runtime.

## Update from 1.0.0

Copy `Folio-1.0.1.apk` to the phone by the working USB method and install over Folio. Do not uninstall first if you want to retain the library and statistics. The previous APK remains in artifacts. The reported Drive download failure was bypassed successfully by direct transfer; its exact cloud-side cause has not been established.
