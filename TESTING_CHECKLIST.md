## 0.8.3 verification
- Run `node tests/pagination.test.cjs`.
- For visual regression: `npm install --no-save playwright@1.58.2`, `npx playwright install --with-deps chromium`, then `node tests/rendering.test.cjs`. Set CHROMIUM_PATH to use a system Chromium. This test is provided but was blocked from running in the authoring environment.
- Check forward/back curl halfway through a gesture, cancellation and release in both themes.
- Drag slide slowly, pause while holding, reverse direction, then release. Repeat on long chapters and at chapter boundaries.
- Test None/Grain/Linen, reopen book/app, toggle theme and resize/rotate without losing reading position.
- Reader should hide status/navigation bars; swipe from system edge temporarily reveals them; leaving reader restores them. Check return from dialogs and font picker.
- Build APK using the included GitHub workflow and confirm app version 0.8.3-dev.

# Manual test checklist — EPUB milestone

## Accessibility and library navigation

- Tap the library title and switch among All books and several named libraries. Confirm the displayed book set updates and the active library is marked.
- Open the drawer's Accessibility screen. Increase and reduce interface text size; confirm the setting persists after restarting the app.
- Change book text size, open an EPUB or text book, and confirm the saved default is applied. Confirm the normal appearance controls can still adjust it.
- Enable Reduce motion and confirm EPUB page turns are immediate and PDF/comic page transitions do not slide. Confirm canceled swipes settle immediately.
- Reset accessibility settings and confirm interface/book text return to 100% and reduced motion is disabled.

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

Known first-pass limitations: no finger-tracking 3D page curl, incomplete handling of unusual EPUB URL encodings and scripted/fixed-layout content. The app-private storage model means uninstalling the app deletes imported copies.


## 0.5.4 pagination regression

- Run `node tests/pagination.test.cjs` for navigation-state checks (not rendering).
- On Android, open the supplied Lord of Mysteries volume 7 EPUB. Flip through every TOC page into the first chapter, then back.
- In a long chapter, turn at least 20 pages in each direction with both transition options. Confirm text advances and page numbers agree.
- Switch Pages/Scroll mid-chapter, change font size, rotate the device, then close/reopen. Verify approximate position is preserved.
- Switch reading mode or change font size while a transition is running; verify no jump back or stuck controls.

Android build and device rendering have not been executed for this revision.


## 0.7.0 reader tools

- Open an EPUB. Double tap to reveal/hide the controls while staying on the same page. Change to Single tap in Reading tools → Reader settings, reopen the book, and confirm it still works.
- Swipe pages and scroll in Scroll mode. Long press text, adjust both handles, and save a highlight and a note. Reopen the book, change font size, then jump to the note. Edit and remove it and confirm the highlight clears.
- With two EPUBs containing notes, change books in the Highlights & notes picker and check that edits apply to the selected book.
- Search for a repeated word and a phrase in another chapter. Tap a result to jump to it. Try a search with no results.
- Select a word and open Wiktionary. Return to the app and check your reading position.
- With an Android offline voice installed, start and stop Read chapter aloud. Check that changing chapters or leaving the app stops speech.

## 0.8.0 reader appearance and EPUB navigation

- Reveal the bars and check the three top controls and five bottom actions on a small screen and in landscape.
- Switch Paper, Light, and Dark. Check the page, selection toolbar, contents, search, settings, and notes against each palette.
- Change text size, line spacing, margins, and alignment. Import a TTF or OTF font, close and reopen the book, and check that the chosen settings persist.
- Drag partway then release or cancel. Turn across a chapter boundary with both effects. Rotate and check the retained passage.
- Open both EPUB 2 and EPUB 3 examples with nested contents. Tap links to two different anchors in the same chapter and to an anchor in another chapter.
- Save and edit a note, jump to its passage, and open notes from a second book.
