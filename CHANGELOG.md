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
