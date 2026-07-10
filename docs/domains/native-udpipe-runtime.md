# Native UDPipe runtime domain

This document describes the native UDPipe runtime boundary: how the shared
preprocessing pipeline reaches platform-native NLP execution, how model assets
are installed, how the C adapter is owned, and which rules apply when changing
the model, adapter ABI, Android JNI bridge, iOS cinterop, or lifecycle behavior.

## Responsibility

The native UDPipe runtime is responsible for:

- installing the bundled UDPipe model on each platform;
- creating native UDPipe engine instances;
- loading a model into an engine;
- analyzing UTF-8 text into CoNLL-U output;
- reporting the native provider/runtime version;
- translating native errors into platform/shared failures;
- freeing native strings and result objects;
- destroying native engines when the provider lifecycle ends.

The main source files are:

- `shared/src/androidMain/kotlin/com/example/myapplication/shared/processing/AndroidTextAnalysisProvider.kt`
- `shared/src/iosMain/kotlin/com/example/myapplication/shared/processing/IosTextAnalysisProvider.kt`
- `native/udpipe/adapter/udpipe_adapter.h`
- `native/udpipe/adapter/udpipe_adapter.cpp`
- `native/udpipe/adapter/udpipe_jni.cpp`
- `native/udpipe/CMakeLists.txt`
- `native/udpipe/build-ios.sh`
- `shared/src/nativeInterop/cinterop/udpipeAdapter.def`
- `shared/src/main/assets/udpipe/english-ewt.udpipe`
- `app-ios-swift/app-ios-swift/Resources/udpipe/english-ewt.udpipe`

The shared boundary contracts used by this runtime are defined in
`shared/src/commonMain/kotlin/com/example/myapplication/shared/processing/BookProcessingModels.kt`.
That file belongs to the preprocessing model layer, not to the native runtime
itself.

## Out of scope

The native UDPipe runtime does not own:

- preprocessing stage ordering;
- CoNLL-U parsing semantics after output is returned;
- lemma filtering, scoring, or chunking;
- SQL storage or migration rules;
- global frequency database generation;
- Readium EPUB extraction;
- library or reader UI;
- app-level navigation.

Those responsibilities belong to the preprocessing, database storage, global
frequency, library, reader, or root architecture domains.

## Current architecture

Shared preprocessing depends on two common contracts:

- `ModelRepository`: checks, installs, locates, and deletes analysis models.
- `TextAnalysisProvider`: analyzes `TextAnalysisRequest` values and returns
  `TextAnalysisResult`.

`UdpipeAnalysisStage` consumes only those shared contracts. It does not know
about Android JNI, iOS cinterop, C++ pointers, model copy locations, or native
build details.

Platform implementations provide the native runtime:

- Android uses `AndroidModelRepository`, `AndroidTextAnalysisProvider`,
  `AndroidUdpipeNative`, `udpipe_jni.cpp`, and a CMake-built shared library.
- iOS uses `IosModelRepository`, `IosTextAnalysisProvider`, Kotlin/Native
  cinterop generated from `udpipeAdapter.def`, and static libraries built by
  `build-ios.sh`.
- Both platforms call the same C adapter declared in `udpipe_adapter.h`.

Kotlin and Swift-facing code should not call UDPipe upstream APIs directly.
They should go through the shared provider contracts and the stable C adapter.

## Model asset contract

The MVP bundles one English UDPipe model.

Current shared constants:

- `DefaultAnalysisLanguage`: `en`
- `DefaultAnalysisProvider`: `udpipe`
- `DefaultAnalysisModelId`: `english-ewt`
- `DefaultAnalysisModelVersion`: `ud-2.5-191206`

Bundled model assets:

- Android/shared asset: `shared/src/main/assets/udpipe/english-ewt.udpipe`
- iOS app resource:
  `app-ios-swift/app-ios-swift/Resources/udpipe/english-ewt.udpipe`

Installed model locations:

- Android copies the model to `filesDir/udpipe-models/english-ewt.udpipe`.
- iOS copies the model to Application Support under
  `udpipe-models/english-ewt.udpipe`.

Unsupported language behavior:

- platform repositories support only `DefaultAnalysisLanguage`;
- installing another language must fail clearly;
- `BookAnalysisProcessor` also fails non-English analysis before reaching the
  stage for the current MVP.

Adding another language requires a deliberate model contract change, not just a
new file in resources.

## Native adapter contract

`udpipe_adapter.h` is the stable C boundary used by Android JNI and iOS
Kotlin/Native cinterop.

It exposes:

- `udpipe_adapter_create_engine()`;
- `udpipe_adapter_load_model(engine, model_path)`;
- `udpipe_adapter_analyze_utf8(engine, utf8_text)`;
- `udpipe_adapter_version()`;
- `udpipe_adapter_free_string(value)`;
- `udpipe_adapter_result_free(result)`;
- `udpipe_adapter_destroy_engine(engine)`.

Ownership rules:

- an engine returned by `create_engine` must eventually be passed to
  `destroy_engine`;
- an error string returned by `load_model` must be freed with `free_string`;
- a result returned by `analyze_utf8` must be freed with `result_free`;
- `result_free` owns both `output` and `error` strings inside the result;
- `version` returns a static string and must not be freed by callers.

Failure rules:

- null engine creation means engine creation failed;
- non-null load error means the model did not load;
- null analysis result means processing failed before a result object was
  created;
- result error means UDPipe rejected or failed the analysis request;
- successful result output is CoNLL-U text.

Changing the adapter function names, argument types, return ownership, or result
layout is an ABI change. Android JNI and iOS cinterop must be updated in the
same change.

## Android runtime

Android loads the native library through:

```kotlin
System.loadLibrary("foreignwords_udpipe")
```

Runtime shape:

- `AndroidUdpipeNative` declares the JNI methods.
- `udpipe_jni.cpp` implements JNI symbols for those methods.
- Engine handles are passed through Kotlin as `Long`.
- `AndroidTextAnalysisProvider` caches engines by model path.
- Provider analysis is synchronized to protect the engine map and native calls.
- `close()` destroys every cached engine and clears the map.

Android model install:

- `AndroidModelRepository.installModel(...)` copies the bundled asset into
  app file storage when it is missing or empty.
- `getModelPath(...)` returns a non-empty installed model path.

Android build:

- `native/udpipe/CMakeLists.txt` builds `foreignwords_udpipe` as a shared
  library.
- The build includes UDPipe upstream sources plus `udpipe_adapter.cpp` and
  `udpipe_jni.cpp`.
- Rest server sources, the upstream command-line binary, and one unused
  dictionary encoder source are excluded.
- C++11, exceptions, and RTTI are required.

## iOS runtime

iOS reaches the same C adapter through Kotlin/Native cinterop.

Runtime shape:

- `udpipeAdapter.def` exposes `udpipe_adapter.h`.
- The cinterop package is
  `com.example.myapplication.shared.udpipe.cinterop`.
- `IosTextAnalysisProvider` caches engine pointers by model path.
- It explicitly frees load-error strings and analysis result objects.
- `close()` destroys every cached engine and clears the map.

iOS model install:

- `IosModelRepository.installModel(...)` copies the bundled model from the app
  bundle into Application Support when it is missing.
- `getModelPath(...)` returns the installed model path for English only.

iOS build:

- `native/udpipe/build-ios.sh` builds static libraries for `iosArm64`,
  `iosX64`, and `iosSimulatorArm64`.
- Gradle wires the cinterop and iOS link tasks to depend on the iOS native
  build.
- The script compiles UDPipe upstream sources plus `udpipe_adapter.cpp`.
- The script excludes rest server sources, the upstream command-line binary,
  and one unused dictionary encoder source.

## Build ownership

The native runtime owns build inputs needed to make UDPipe callable from
platform code.

Android build ownership:

- CMake library name: `foreignwords_udpipe`;
- JNI bridge file: `udpipe_jni.cpp`;
- Gradle Android external native build points at `native/udpipe/CMakeLists.txt`.

iOS build ownership:

- static library output under `native/udpipe/build/ios/{target}`;
- cinterop definition: `udpipeAdapter.def`;
- Gradle linker options point to the per-target static library.

Do not change build flags, excluded sources, library names, cinterop package, or
JNI symbol ownership without updating this document.

## Lifecycle rules

Provider instances own native engines.

Rules:

- engine handles must not escape provider ownership;
- callers that create a provider for a processing run must close it;
- long-lived providers need an explicit lifecycle owner;
- model files may be cached on disk across app runs;
- native result memory must be freed by the platform binding;
- `close()` should destroy cached engines and make repeated processing safe for
  future provider instances.

The provider lifecycle is separate from preprocessing stage lifecycle.
`UdpipeAnalysisStage` asks the provider to analyze; it does not close provider
resources.

## Failure rules

Failures should surface as either install/load errors or
`TextAnalysisResult.Failure`.

Important failure cases:

- bundled model file is missing;
- requested language is unsupported;
- installed model file is missing or empty;
- native engine creation returns null;
- model loading returns an error string;
- analysis returns null;
- analysis returns a result with an error string;
- native code throws a C++ exception and the adapter converts it to an error;
- provider cannot produce a runtime version.

Platform providers should include clear error messages because preprocessing
stores failures in `BookProcessingStatus.errorMessage`.

## Versioning rules

Changing native UDPipe behavior can affect stored preprocessing output.

When changing the bundled model:

- update `DefaultAnalysisModelId` if the model identity changes;
- update `DefaultAnalysisModelVersion` when the same model id gets new
  semantics;
- consider whether `DefaultAnalysisIndexVersion` must change;
- consider whether the pipeline stage version or pipeline fingerprint must
  change;
- rebuild the global frequency database if it was generated with the old model.

When changing adapter or provider behavior:

- update this document if ABI, lifecycle, error handling, or output semantics
  change;
- update preprocessing versioning when tokenization, lemma, UPOS, or CoNLL-U
  output semantics change;
- keep Android JNI and iOS cinterop in sync with `udpipe_adapter.h`.

Changing native build flags without changing analysis output may not require a
preprocessing version bump, but it should still be documented when it affects
supported targets, binary compatibility, or lifecycle behavior.

## Change playbook

### Add a language or model

1. Add the model asset on every supported platform.
2. Extend shared language/model constants deliberately.
3. Update Android and iOS model repositories.
4. Decide index version, pipeline fingerprint, and global frequency database
   impact.
5. Update preprocessing and native runtime documentation.

### Change the adapter ABI

1. Update `udpipe_adapter.h`.
2. Update `udpipe_adapter.cpp`.
3. Update Android JNI bridge.
4. Update iOS cinterop usage.
5. Rebuild Android and iOS native outputs.
6. Update this document with ownership and memory rules.

### Change provider lifecycle

1. Identify who owns provider creation and close.
2. Keep engine handles inside provider ownership.
3. Update Android and iOS behavior together when possible.
4. Document any platform gap explicitly.

### Update upstream UDPipe

1. Update upstream sources.
2. Verify excluded source lists for Android and iOS still make sense.
3. Verify adapter compile and link behavior.
4. Decide whether runtime version, model version, index version, or pipeline
   fingerprint must change.
5. Update docs that mention versioning or build ownership.

### Change build or linking

1. Update Android CMake or iOS build script.
2. Update Gradle wiring when task dependencies or linker paths change.
3. Keep library names and cinterop package stable unless the ABI change is
   intentional.
4. Document supported target changes.

## Boundary with preprocessing

`docs/domains/book-preprocessing.md` owns pipeline behavior.

The native UDPipe runtime only provides this capability:

```text
TextSection text -> native UDPipe analysis -> CoNLL-U -> shared parser
```

After CoNLL-U text is returned, shared preprocessing owns parsing, candidate
building, filtering, scoring, persistence, and freshness semantics.

## Verification

Use `docs/agent-harness.md` for the full verification matrix.

Common preprocessing tests do not prove native runtime correctness. Use native
build and platform smoke checks when changing the C adapter, JNI bridge,
cinterop, model assets, provider lifecycle, or analysis output semantics.

Useful build checks are:

```bash
./gradlew :app-android:assembleDebug
./gradlew :shared:buildUdpipeIos
./gradlew :shared:linkIosSimulatorArm64
```

Run the Android or iOS platform runbook only when the runtime change needs real
device or simulator verification of model install, native load, analysis, or
provider cleanup.

## Coverage expectations

Do not add native tests just because this document changes.

Current common tests cover parser, pipeline, processor, index builder, and
lemma filter behavior. They do not prove native runtime behavior.

Native or platform smoke coverage becomes important when changing:

- model install paths or bundled model assets;
- adapter ABI or memory ownership;
- JNI symbol names or library loading;
- cinterop header/package/linking;
- native build scripts or supported targets;
- provider lifecycle, caching, or close behavior;
- analysis output semantics.

The amount of coverage should match the runtime change. Documentation-only
changes do not require app tests.
