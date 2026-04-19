# AGENTS.md

## Scope

MVP reading app:

- local library
- open book
- restore position
- simple word frequency per book

No sync, highlights, notes, search, complex navigation.

---

## Tech Stack

- Kotlin Multiplatform
- Decompose (navigation + components)
- Android → Readium Kotlin
- iOS → Readium Swift Toolkit

---

## Project Structure

    app/
    ├─ shared/
    │  ├─ model/
    │  ├─ data/
    │  ├─ root/
    │  ├─ library/
    │  └─ reader/
    │
    ├─ app-android/
    │  ├─ root-ui/
    │  ├─ library-ui/
    │  └─ reader-platform/
    │
    ├─ app-ios/
    │  └─ framework integration
    │
    └─ app-ios-swift/
       ├─ root-ui/
       ├─ library-ui/
       └─ reader-platform/

---

## Decompose Rules

- one root component
- one ChildStack
- one component per feature
- no nested flows in MVP

Navigation:

    RootComponent
    └─ ChildStack
       ├─ LibraryComponent
       └─ ReaderComponent(bookId)

---

## Shared Layer

Contains:

- models
- repositories
- Decompose components
- reader orchestration
- reading progress
- frequency data

Must NOT contain:

- Readium
- platform UI
- platform lifecycle

---

## Components

### RootComponent
- controls navigation

### LibraryComponent
- loads books
- handles selection

### ReaderComponent
- opens book
- restores position
- observes reader events
- saves progress
- provides frequency data

---

## Reader Architecture

Split strictly:

### Shared (`shared/reader`)
- ReaderComponent
- ReaderState
- ReaderGateway (contract)

### Android (`app-android/reader-platform`)
- Readium Kotlin
- Android reader screen
- maps events → shared

### iOS (`app-ios-swift/reader-platform`)
- Readium Swift Toolkit
- iOS reader screen
- maps events → shared

---

## Boundary Rule

Shared decides:

- what to open
- where to navigate
- when to save progress
- what data to show

Platform decides:

- how to render book
- how Readium works
- how events are produced

---

## Data Model

### Book
- id
- title
- localPath

### ReadingPosition
- bookId
- locator/progression

### BookFrequency
- bookId
- word
- count

---

## Frequency Rules

- calculated on import or first open
- stored in repository
- reader only reads result

---

## UI Rules

- reader UI is platform-specific
- library UI can be shared or platform-specific
- no shared reader rendering

---

## Complexity Rules

- no extra layers
- no heavy DI
- no over-modularization
- no platform abstraction leaks

Keep it simple.