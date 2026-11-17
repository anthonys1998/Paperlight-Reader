# Paperlight Reader (Android)

Paperlight is an offline-first Android ebook reader project. Imported files are copied into the app's private storage; no account or cloud sync is used.

## Current milestone: 0.5.4-dev

### Implemented in this source snapshot
- Native Jetpack Compose bookshelf with equal-sized covers, title search, a last-read marker, warm light and dark palettes, and per-book progress.
- Folder scanning lists supported files directly inside the selected folder, then imports only the checked files.
- EPUB Pages and Scroll modes share an approximate position. The selected mode is remembered. The reader and bookshelf show the whole-book page number after all chapters have been measured. The page count changes with viewport size or font size.
- Import of EPUB, PDF, TXT, MOBI, AZW/AZW3, CBZ and CBR files into app-private storage.
- Room-backed local library metadata.
- Initial DRM-free EPUB reader: reads EPUB 2/3 package metadata, follows spine order, loads XHTML chapters with local relative assets, and uses a horizontally paginated reading layout.
- EPUB 3 navigation document and EPUB 2 NCX table-of-contents parsing, with a chapter jump menu.
- Three reading palettes: paper/sepia, white, and dark.
- Text-size adjustment and previous/next page/chapter navigation.
- Coarse reading progress persistence and restore (chapter plus approximate page fraction).
- Basic archive safety checks (path traversal rejection, entry-count and expanded-size limits) and stripping of publication scripts/inline event handlers before WebView rendering.
- More consistent page-width calculations and an attempt to preserve the reader's relative position during device rotation.
- App follows the system light/dark theme in the library.

### Not implemented yet
- Page curl that follows your finger. Pages currently support a short perspective flip or a simple slide, triggered by the arrows or by a swipe.
- EPUB bookmarks, highlights, notes, search, dictionary, TTS, custom fonts, and advanced typography controls. The current TOC is a basic chapter jump list, not a full hierarchical navigator.
- PDF reader, TXT reader, CBZ/CBR image reader.
- MOBI/AZW3 conversion. These formats are accepted into the library but cannot yet be opened. DRM-protected Kindle files are not supported and no DRM circumvention is included.
- Collections, reading statistics, backup/export and accessibility polish.

## Build

Open the `PaperlightReader` folder in Android Studio. The project uses Kotlin, Jetpack Compose, Room and Android WebView. Android SDK 36 and JDK 17 are configured. Let Android Studio sync Gradle and install any missing SDK components.

This source snapshot has not been compiled or device-tested in this environment. Treat it as an in-progress development build, not a release APK. The project currently does not include a Gradle wrapper; Android Studio can open it using its configured Gradle installation.

## Data and privacy notes

- Imported books are stored under the app's private files directory.
- Removing a book deletes its imported file and EPUB extraction cache.
- Uninstalling the app removes app-private imports. Add backup/export before relying on the app as the only copy of a library.
- The app does not request the INTERNET permission.

## Build an APK on GitHub

Put this project at the repository root. GitHub Actions runs **Build Android APK**
when you push a branch. You can also start it from **Actions → Build Android APK →
Run workflow** once the workflow is on the default branch. After a successful run,
download **Paperlight-debug** from the run's **Artifacts** section and extract
`app-debug.apk`.
