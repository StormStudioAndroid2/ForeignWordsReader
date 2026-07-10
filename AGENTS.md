# AGENTS.md

This file is the mandatory entrypoint for AI agents working in this repository.
It is intentionally short. The detailed routing and domain rules live in
`docs/agent-harness.md` and the domain docs under `docs/domains/`.

## Mandatory workflow

Before making code changes:

1. Read `docs/agent-harness.md`.
2. Use the task rank matrix to choose the verification depth.
3. Identify every domain area touched by the requested change.
4. Read the relevant domain docs before editing.
5. Check that the planned change fits the owning domain responsibility.
6. Implement only inside the correct ownership boundary.
7. Re-check adjacent domains after the implementation shape is clear.
8. Add or update focused tests where the changed contract needs protection.
9. Update docs when ownership, flow, persistence, platform behavior, or test
   expectations change.
10. Run the smallest relevant verification set and report skipped checks.

## Core boundary rule

Shared code owns:

- public contracts and domain models;
- Decompose components and state transitions;
- root navigation semantics;
- library, reader, search, preprocessing, and storage behavior;
- SQL schema and migration contracts;
- platform-neutral test contracts.

Platform code owns:

- Android Compose and iOS SwiftUI/UIKit rendering;
- Readium Kotlin and Readium Swift Toolkit runtime details;
- Android SAF permissions and iOS security-scoped bookmarks;
- platform file locations and asset installation;
- native provider wiring and lifecycle;
- device/simulator launch flows.

Do not leak Android, iOS, Readium, SwiftUI, UIKit, Fragment, native handle,
permission, bookmark, or platform file-system objects into `commonMain`.

## Domain docs

Use these source-of-truth documents:

- Agent routing: `docs/agent-harness.md`
- Root/navigation: `docs/domains/app-architecture.md`
- Library: `docs/domains/library-domain.md`
- Reader/search: `docs/domains/reader-search-domain.md`
- Book preprocessing: `docs/domains/book-preprocessing.md`
- Database/storage: `docs/domains/database-storage.md`
- Native UDPipe runtime: `docs/domains/native-udpipe-runtime.md`
- Android platform app: `docs/domains/android-product-app.md`
- iOS Swift platform app: `docs/domains/ios-swift-product-app.md`

When a domain doc has `Test contract`, `Coverage expectations`, or `Change
playbook` sections, follow them for that domain.

## Verification defaults

- Task rank and domain verification matrix: `docs/agent-harness.md`
- Shared/common behavior: `./gradlew :shared:test`
- Android build/run: follow `docs/domains/android-product-app.md`
- iOS simulator build/run: follow `docs/domains/ios-swift-product-app.md`
- Preprocessing-stage changes: use skill `add-book-preprocessing-stage`
- Docs-only changes: run ASCII and `rg` reference checks

Do not run destructive commands such as app uninstall, simulator erase, or data
reset unless the user explicitly asks for a clean state.
