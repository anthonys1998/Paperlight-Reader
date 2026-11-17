## 0.11.0-dev
- Replaced the stationary bookshelf backdrop with scrollable three-book shelf rows and a dedicated shelf-row image.
- Added a hamburger library drawer, library counts, list view, sorting and reading-status filters.
- Library creation and moves now wait for database success and open the destination. Added rename/delete, duplicate-name feedback and direct import into the active library.
- Added EPUB metadata import and refresh for existing books with missing authors.
- Set page-turn sheets and chapter snapshots to full opacity.
- Added PDF/comic page notes, bookmarks, jump-to-page, fit controls, continuous scrolling and shared immersive controls.
- Added PDF page-text selection and page-linked highlights on Android 15+.
- Added portable local backup/restore of original book files, libraries, progress, notes and bookmarks. Restore adds missing books and skips existing IDs.
- Added active reading-time statistics and web-novel download progress/cancellation.
- Completed warm library palettes to remove the default purple component colors.
- Note summarization remains deferred.
- Source syntax reviewed. This version has not been APK-compiled or device-tested in this environment.

## 0.10.8-dev
- Make the bookshelf artwork the library's full-screen background and keep cover shadows visible over the shelves.
- Add named libraries, per-library filtering, long-press multi-selection, bulk move and bulk delete.
- Add a coroutine based web novel importer with Jsoup chapter discovery and epub4j EPUB generation; expose URL and optional CSS selectors in the library.
- Replace synchronous full-size WebView drawing at chapter boundaries with asynchronous, half-resolution PixelCopy capture to reduce shared EPUB/MOBI turn stalls.

## 0.10.7-dev
- Replace the library's flat background with a warm, empty bookshelf image and add depth shadows to cover art.
- Use the finger-following slide transition for unusually large chapters instead of cloning their full DOM for a curl, reducing page-turn work on large MOBI/AZW sections.

## 0.8.3-dev
- Replace the narrow fold overlay with layered page content, a reflected sheet back, curved silhouette and fold shading. Backward turns reverse the forward geometry.
- Claim horizontal touch gestures in page mode and update slide position continuously. Preserve text selection and scroll mode.
- Remove Paper theme; migrate existing settings to Light/Dark. Add a sun/moon bottom action.
- Add persistent None, Grain and Linen texture options independent of theme.
- Hide Android system bars during reading and restore them on exit. Reapply after focus/resume; retain display-cutout safety.
- Add a real Chromium touch/rendering regression test in addition to the navigation harness.

Validation: navigation harness passes. Chromium could not launch in the authoring environment (socket access denied); Android SDK/Gradle unavailable. APK compilation, on-device gesture feel, system bars and visual curl are NOT verified. Adjacent chapter content is loaded only after a committed turn, so chapter-boundary previews do not yet show the neighboring chapter during a drag.

# 0.8.2-dev

- Darken Paper and Light menus and add shadows at the reading edges.
- Center and fit image-only EPUB covers.
- Track finger movement in Slide mode and render a curved fold in Flip mode, with reverse unfolding on backward turns.

# 0.8.1-dev

- Remove the duplicate Kotlin import that prevents the reader screen from compiling.

# 0.8.0-dev

- Add persistent type, size, spacing, margin, alignment and page color controls, including local font import.
- Track page drags and retain the visible passage during layout changes where a text anchor is available.
- Improve EPUB contents hierarchy, section links, fallback resources and UTF-16 chapter support.
- Simplify reader bars and redesign notes and highlights across the three reading palettes.

# 0.7.0-dev

- Add EPUB highlights, notes, whole-book search, browser dictionary lookup, and chapter read-aloud.
- Start with reading controls hidden and let readers choose single or double tap to toggle them.

# 0.6.0-dev

- Apply the selected transition when turning between EPUB chapters.
- Add a Bookmarks tab for saving, reopening, and removing reading positions.

# 0.5.6-dev

- Open chapters in a compact side drawer with the current chapter marked and a footer that returns to it.

# 0.5.5-dev

- Align the final page of each EPUB section to the same horizontal margins as the other pages.
- Show page numbers for the current section and overall progress as a percentage.
- Open contents in a scrollable sheet with the current section highlighted and EPUB chapter titles.

# 0.5.4-dev

- Keep an explicit page index and stable layout metrics independent of animation.
- Animate only the fixed-size viewport rather than transforming the complete chapter.
- Reassert page position after transitions; cancel transitions safely during mode, font and size changes.
- Add Node regression checks for navigation, native position resets, modes and resizing.
- Validation: navigation simulation passed. Android build and real EPUB rendering remain unverified in this environment.

# 0.5.3-dev

- Advance to the next EPUB section from the last numbered page even if a narrow trailing margin remains.

# 0.5.2-dev

- Size the EPUB reading viewport separately from the book's styles and measure pages in that same viewport.

# 0.5.1-dev

- Give EPUB columns the full reader height and use the same layout for whole-book page counts.

# 0.5.0-dev

- Added a page flip and a simple slide option in EPUB Pages mode.
- Added whole-book page counts to the reader and bookshelf. Existing reading positions are retained.

# 0.4.0-dev

- Reworked the library into a searchable bookshelf with a last-read marker and progress indicators.
- Added folder scanning with selection before import.
- Added EPUB scroll mode alongside pages and a Room migration for the last viewed chapter page count.

# Changelog

## 0.3.0-dev
- Added EPUB 3 navigation-document and EPUB 2 NCX table-of-contents parsing.
- Added a chapter jump menu and basic internal chapter-link navigation in the EPUB reader.
- Added Paper, White and Dark reading palettes.
- Improved CSS multi-column page-width and page-count calculations.
- Preserved approximate reading position after font-size changes and device resizing.
- Corrected initial saved-position setup to avoid opening the wrong chapter during restore.
- Added ZIP path traversal checks and limits for archive entry count and expanded size.
- Removed publication scripts, inline event handlers, embedded frames/objects and JavaScript URLs before rendering.
- Kept non-EPUB formats as library-only placeholders; no PDF/comic/TXT/Kindle reader has been added yet.

## 0.2.0-dev
- Initial EPUB reader prototype with chapter loading, basic pagination, two palettes and text-size controls.
