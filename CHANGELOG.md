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
