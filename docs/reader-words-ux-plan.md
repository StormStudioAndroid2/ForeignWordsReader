# Reader Words UX Plan

## Status

Product and implementation plan. No application code is introduced by this
document.

## Goal

Make the reader-scoped words overlay explain itself clearly without turning it
into a tutorial. A reader should understand that the list shows frequent words
from the active book, that forms are grouped under a primary display word, and
that the number in each row is the number of appearances in this book.

The plan applies to both Android and iOS so the same reader feature has the
same product meaning on both platforms.

## Current UX Assessment

The current screen is structurally simple, but several labels make the feature
feel technical instead of reader-facing:

- `Words in book` is too broad. It does not say whether these are all words,
  important words, vocabulary candidates, search hits, or frequent words.
- `59 words loaded` sounds like a loading/debug status rather than a result
  summary.
- The number at the right side of each row is not labeled, so users have to
  infer whether it is rank, score, page position, frequency, or something else.
- Rows such as `diary`, `diary`, and `DIARY, diaries, Diary` can look like raw
  NLP output because the UI does not name the base form or grouped forms.

The main UX gap is not visual density. The list already scans well. The gap is
missing context for what the data means.

## Product Decision

Keep the overlay compact and preserve the current list-first layout.

- Rename the overlay title from `Words in book` to `Frequent words`.
- Replace the loaded summary with `{count} frequent words found`.
- Show one short helper line in the loaded state:
  `Grouped forms from this book. Numbers show how many times each word appears.`
- Render each row count as the existing `totalCount` value with a compact text
  label: `times`, or `time` when the value is `1`.
- Add an accessibility label for the count, such as
  `107 times in this book`.
- Show `Base form: {lemma}` only when `lemma` differs from `displayWord` after
  trimming and case-insensitive comparison.
- Show `Forms: {forms}` only when additional surface forms exist after the
  primary `displayWord`.
- Keep row actions, dictionary handoff, translation handoff, filtering, and
  sorting out of this plan.

This version should explain frequency directly while avoiding TF-IDF, global
frequency, or scoring terminology in the UI.

## Architecture And Ownership

This is a platform UI copy and presentation change only.

- Do not change shared reader contracts, SQL schema, preprocessing behavior,
  persistence, or list ordering.
- Use the existing `ReaderWordItem` fields:
  `displayWord`, `lemma`, `totalCount`, and `surfaceWords`.
- Android owns the Compose rendering in the reader words overlay.
- iOS owns the SwiftUI rendering in the reader words overlay.
- The shared reader/search domain continues to own the words component state
  and platform-neutral item model.

The current ordering remains whatever `BookLibraryStore.getLemmaCounts(...)`
returns. The UI should not imply a different ranking model.

## Empty And Error Copy

Use frequency-specific copy for non-loaded states:

- Empty: `No frequent words are ready for this book yet.`
- Error fallback: `Could not load frequent words.`

Loading can remain short:

- `Loading frequent words...`

## Accessibility

- The count must be understandable to screen readers as a frequency count in
  the current book.
- Text must not clip under Android font scaling or iOS Dynamic Type.
- The count column should remain narrow and stable, but its label must not
  overlap with long words or long form lists.
- Visual labels such as `Base form:` and `Forms:` should be part of readable
  text, not only visual styling.

## Acceptance Criteria

- The overlay title tells the user that the screen is about frequent words.
- The loaded summary tells how many frequent words were found.
- The helper text explains grouped forms and the count meaning without adding
  a large instructional block.
- The right-side number is explicitly understandable as appearances in the
  book.
- Duplicate-looking lemma rows no longer read as raw technical output.
- Android and iOS communicate the same meaning, even if native typography and
  spacing differ.
- No shared model, storage, preprocessing, or sorting contract changes are
  required.

## Verification Plan

Android:

- Build with `./gradlew :app-android:assembleDebug`.
- Visually verify the words overlay on a reader with loaded words.
- Check long words, mixed-case forms, and a `totalCount` of `1` when available.

iOS:

- Build with the simulator `xcodebuild` runbook from
  `docs/domains/ios-swift-product-app.md`.
- Visually verify the words overlay on a reader with loaded words.
- Check Dynamic Type behavior for count labels and form labels when practical.

Docs-only updates to this plan should use the repository docs checks rather
than Android or iOS builds.

## Assumptions

- The app UI remains English until a broader localization system is introduced.
- TF-IDF, global Zipf frequency, and score explanations stay out of this first
  UX improvement.
- This document is intentionally separate from
  `docs/external-dictionary-translation-plan.md`, which covers dictionary and
  translation handoff behavior.
