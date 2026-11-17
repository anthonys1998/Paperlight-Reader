# Paperlight Reader — Technical Specification

## Product
An Android-only, local-first ebook reader intended to approach the core reading experience of Kindle without depending on cloud services.

## User requirements
- Formats planned: EPUB, PDF, MOBI, AZW3, TXT, CBZ and CBR.
- Kindle-like reading presentation with a realistic paper page and page-turn animation.
- Light and dark modes.
- Portrait and landscape orientation.
- Local storage only; no cloud sync.

## Current architecture
- Kotlin + Jetpack Compose + Material 3 for the app UI.
- MVVM-style ViewModel and repository separation.
- Room for library metadata and reading progress.
- Android Storage Access Framework for importing documents.
- App-private file storage for imported documents.
- Android WebView for the first EPUB XHTML rendering milestone.
- A small EPUB package parser that reads `META-INF/container.xml`, the OPF manifest and spine, then renders spine XHTML in order.

## EPUB milestone behavior
1. Import copies the selected EPUB to the app-private `files/books` directory.
2. Opening an EPUB extracts its ZIP contents to `files/epub/<book-id>`.
3. The parser resolves the OPF package document, manifest resources and spine order.
4. XHTML spine resources are shown in a horizontally paginated WebView. Relative images and stylesheets are loaded from the extracted package directory.
5. The reader provides light/sepia and dark backgrounds, font-size adjustment, page/chapter navigation and approximate progress restore.

This is a deliberately limited first reader implementation. It does not yet implement every EPUB edge case (for example, encrypted resources, complex scripted publications, all navigation-document variants or robust handling of unusual URL-encoded resource paths). Publisher CSS is overridden for the initial page layout, which can affect books with intricate fixed-layout or custom styling.

## Page-turn animation decision
The current implementation uses smooth horizontal movement and is not a realistic page curl. The Readium Kotlin 3.2.0 release added animated page turns for paginated EPUB navigation, and should be evaluated for the production navigator rather than attempting to hand-roll a fragile 3D effect. Readium's navigator is a Fragment-based integration in the stable API; its newer Compose navigators are documented as experimental. Reference: https://readium.org/kotlin-toolkit/3.2.0/guides/navigator/navigator/ and https://blog.readium.org/release-note-kotlin-toolkit-version-3-2-0/.

## Format roadmap
- EPUB: initial implementation present; harden parser, add TOC, bookmarks, annotations and proper navigator/animation.
- PDF: integrate a dedicated PDF renderer and preserve page index/zoom.
- TXT: implement configurable encoding, typography and pagination.
- CBZ: ZIP image extraction, ordered page list and fit/zoom modes.
- CBR: add a maintained RAR extraction dependency or convert to a supported internal page stream; validate licensing and Android compatibility.
- MOBI/AZW3: prefer a documented DRM-free conversion/import path. Do not bypass Kindle DRM.

## Local data
- Room stores book metadata and coarse progress.
- Imported files and EPUB extraction cache live in app-private storage.
- Backup/export is required before the app can be considered safe as a sole library manager.
- No INTERNET permission is requested in the manifest.

## Current technical baseline
- minSdk 26
- compileSdk 36
- targetSdk 36
- JDK 17
- Kotlin 2.3.20 / Android Gradle Plugin 9.0.0
