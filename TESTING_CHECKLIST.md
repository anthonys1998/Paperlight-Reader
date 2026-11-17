# Manual test checklist — EPUB milestone

The project has not been compiled or run in this environment. Once opened in Android Studio and installed on a device, validate the following before treating this milestone as usable:

1. Import a small EPUB with at least 3 chapters and confirm it appears in the library.
2. Open it; confirm chapter text, inline images and relative stylesheets load.
3. Open the contents menu; verify EPUB 3 navigation or EPUB 2 NCX entries jump to the expected chapter. Try an internal chapter link too.
4. Navigate forward/backward with the bottom controls and horizontal swipes.
5. Confirm the page counter and progress bar update without repeatedly reloading the chapter.
6. Close the reader, reopen the same book and check that it returns near the saved chapter/page.
7. Test Paper, White and Dark palettes; confirm text/background contrast changes.
8. Increase/decrease text size and confirm pagination recalculates.
9. Rotate portrait to landscape and confirm the page layout reflows without crashing or jumping excessively.
10. Import malformed EPUBs and confirm a readable error is shown rather than a crash.
11. Try EPUBs with nested chapter folders, spaces in filenames, SVGs, fonts and unusual metadata; record failures for parser hardening.
12. Confirm non-EPUB formats are not falsely presented as readable yet.
13. Remove a book and confirm both the imported file and its extracted EPUB cache are removed.

Known first-pass limitations: no table-of-contents screen, no bookmarks/highlights/notes, no 3D page curl, incomplete handling of unusual EPUB URL encodings and scripted/fixed-layout content. The app-private storage model means uninstalling the app deletes imported copies.
