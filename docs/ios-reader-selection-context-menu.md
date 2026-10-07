# iOS Reader Selection Context Menu Investigation

## Purpose

This note investigates the iOS APIs available for an EPUB selected-text menu
with three separate actions:

- `Dictionary`;
- `Translate`;
- `Copy`.

It records technical feasibility and platform constraints only. It does not
define feature architecture or an implementation plan.

## Current Environment

ForeignWordsReader has an iOS 15.2 deployment target and uses Readium Swift
Toolkit 3.8.0. EPUB content is rendered by `EPUBNavigatorViewController` inside
`IosEpubReaderViewController`.

Readium 3.8.0 exposes a supported menu customization API through
`EPUBNavigatorViewController.Configuration.editingActions`. This is the
appropriate integration point for selected EPUB text. Directly modifying the
internal `WKWebView`, installing a competing `UIEditMenuInteraction`, or
calling private selectors is unnecessary.

## Can The Menu Be Customized?

Yes. Readium's configuration accepts an array of `EditingAction` values. The
default array is:

```swift
[.copy, .share, .lookup, .translate]
```

The menu can instead be limited to Copy, a custom Dictionary command, and the
native Translate command:

```swift
editingActions: [
    .copy,
    EditingAction(
        title: "Dictionary",
        action: #selector(openDictionaryForSelection)
    ),
    .translate,
]
```

A custom `EditingAction` uses normal UIKit target-action dispatch. Its selector
must be implemented by an object above the navigator in the responder chain,
typically the view controller that contains `EPUBNavigatorViewController`.

Readium currently implements custom actions with `UIMenuItem` and
`UIMenuController`. Apple deprecated those types in iOS 16 in favor of
`UIEditMenuInteraction`, but Readium 3.8.0 intentionally encapsulates that
compatibility. The app should use Readium's public `editingActions` API rather
than attach a second edit-menu interaction to its internal web view.

## Selected Text

`EPUBNavigatorViewController` implements `SelectableNavigator`. The selected
text is available synchronously on the main actor:

```swift
let text = navigator.currentSelection?
    .locator
    .text
    .highlight?
    .trimmingCharacters(in: .whitespacesAndNewlines)
```

The selection also contains `frame`, expressed in navigator-view coordinates,
which can anchor a popover. `navigator.clearSelection()` removes the selection
handles after an action when that behavior is desired.

The value can be `nil` or blank if the selection disappears before the action
runs. Selected content can also be a phrase or paragraph rather than one word.

Readium's `SelectableNavigatorDelegate` provides two useful checks:

- `shouldShowMenuForSelection` can suppress the menu for a selection;
- `canPerformAction` can hide a particular action for that selection.

## Copy

Readium's native `.copy` action is preferable to writing directly to
`UIPasteboard`. It retrieves `locator.text.highlight`, checks the publication's
copy rights, and reports `NavigatorError.copyForbidden` when protected content
cannot be copied.

Manual `UIPasteboard.general.string = text` is technically possible, but it can
bypass Readium's publication-rights enforcement and should not replace
`.copy` for EPUB content.

## Dictionary Options

### Custom `Dictionary` action

For an exact `Dictionary` command, a custom Readium `EditingAction` can present
UIKit's system reference-library controller:

```swift
@objc private func openDictionaryForSelection() {
    guard
        let term = navigator.currentSelection?
            .locator
            .text
            .highlight?
            .trimmingCharacters(in: .whitespacesAndNewlines),
        !term.isEmpty
    else {
        return
    }

    let controller = UIReferenceLibraryViewController(term: term)
    present(controller, animated: true)
}
```

`UIReferenceLibraryViewController.dictionaryHasDefinition(forTerm:)` can be
used before presentation when the menu should hide or disable Dictionary for
terms with no installed definition.

This API displays Apple's standard reference-library UI. It does not open an
arbitrary third-party dictionary app.

### Native `.lookup`

Readium's `.lookup` action delegates to the native iOS Look Up command. It is
the simplest system behavior, but it does not produce an exact
Dictionary-only menu: Readium documents that iOS 16 and later show both
`Look Up` and `Search Web` when this action is enabled.

### External alternatives

- `UIActivityViewController` can share the text to extensions offered by
  installed apps, but no dictionary target is guaranteed.
- A universal link or custom URL scheme can open a specific dictionary app or
  website, but it is provider-specific and requires URL encoding and fallback
  behavior.

iOS has no universal public intent equivalent to Android `ACTION_DEFINE` for
opening any installed dictionary application.

## Translation Options

### Native Readium `.translate`

Readium's `.translate` action enables the system Translate command in the
native selection menu. This is the only public system-translation route that
fits the complete iOS 15.2 deployment range.

The item remains separate from Dictionary and is localized by iOS. Its
visibility and behavior depend on the OS, selected text, supported languages,
and system capabilities. The app receives no translation result.

The app should not call selectors such as `translate:` or `_translate:`
itself. Readium uses the selectors only to participate in UIKit's standard edit
action routing; invoking underscored or undocumented selectors directly would
rely on private behavior.

### Translation framework

Apple's Translation framework is available on iOS 18 and later. Its SwiftUI
`translationPresentation` modifier can present the built-in translation UI for
text supplied by the app. `TranslationSession` can perform translations when
the product needs a custom result experience.

This is a valid implementation behind an iOS 18 availability check, but it
cannot be the sole translation path while the deployment target remains iOS
15.2.

### Share sheet

`UIActivityViewController(activityItems: [text], applicationActivities: nil)`
can hand text to installed share extensions. A translator may appear, but iOS
does not guarantee Apple Translate or any third-party translator as a target.
This is a general share fallback, not a dedicated translation API.

### External alternatives

A universal link or custom URL scheme can open a specific translation service.
This introduces vendor coupling and still requires a browser or installed app.
iOS does not provide a universal public URL scheme for arbitrary translators.

## Can Dictionary And Translate Be Separate?

Yes. With the current minimum OS version, the strongest three-action menu is:

1. Readium `.copy`, which preserves EPUB copy-rights checks.
2. A custom `Dictionary` action presenting
   `UIReferenceLibraryViewController`.
3. Readium `.translate`, which keeps access to the system Translate command on
   iOS 15.2 and later.

Using Readium `.lookup` instead of the custom Dictionary action is also
possible, but it may add `Search Web` and uses the system label `Look Up`.

A fully custom Translate action with app-controlled system presentation is
possible only on iOS 18 and later through the Translation framework. On iOS
15-17, replacing Readium `.translate` with a custom selector would lose the
only supported direct path to the system translation surface.

## Technical Constraints

- UIKit decides the final appearance, localization, grouping, and placement of
  native edit commands.
- Accessibility or system services can contribute additional edit-menu
  behavior even when the app supplies a focused action list.
- Dictionary availability depends on installed system dictionaries and the
  selected term.
- Translation availability depends on OS version, language support, downloads,
  region, and system state.
- The selection can disappear before a selector reads it; blank text must be
  ignored.
- Presenting a dictionary or translation surface must occur from a visible
  view controller on the main actor.
- `UIReferenceLibraryViewController` and native Translate are system surfaces;
  they do not return reusable definition or translation models to the reader.
- External apps cannot be discovered or opened through one universal iOS
  dictionary or translation API.

## Conclusion

The requested iOS menu is technically supported by the pinned Readium version,
and Dictionary and Translate can be exposed as separate commands. Readium's
menu configuration and selection APIs should remain the integration surface.

For the current iOS 15.2 baseline, use Readium's rights-aware `.copy`, a custom
Dictionary action backed by `UIReferenceLibraryViewController`, and Readium's
native `.translate`. The iOS 18 Translation framework is an additional modern
translation option, while share sheets and provider URLs have weaker or
vendor-specific semantics.

## References

- Readium Swift Toolkit 3.8.0 editing actions:
  <https://readium.org/swift-toolkit/3.8.0/documentation/readiumnavigator/epubnavigatorviewcontroller/configuration/editingactions/>
- Readium 3.8.0 custom selection-action example:
  <https://readium.org/swift-toolkit/3.8.0/documentation/readium/highlights/>
- Readium `EditingAction` source:
  <https://github.com/readium/swift-toolkit/blob/3.8.0/Sources/Navigator/EditingAction.swift>
- Apple `UIReferenceLibraryViewController`:
  <https://developer.apple.com/documentation/uikit/uireferencelibraryviewcontroller>
- Apple `UIMenuController`:
  <https://developer.apple.com/documentation/uikit/uimenucontroller>
- Apple `UIEditMenuInteraction`:
  <https://developer.apple.com/documentation/uikit/uieditmenuinteraction>
- Apple Translation framework:
  <https://developer.apple.com/documentation/translation>
- Apple `translationPresentation`:
  <https://developer.apple.com/documentation/swiftui/view/translationpresentation(ispresented:text:attachmentanchor:arrowedge:replacementaction:)>
- Apple `UIActivityViewController`:
  <https://developer.apple.com/documentation/uikit/uiactivityviewcontroller>
