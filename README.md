# Arv, Team 03

A private family memory archive for Android. Record a family member telling their
stories, transcribe them on the device, and keep them in an archive the family owns.

Arv is the Swedish word for inheritance.

## Where the build stands

Updated 2026-08-28. Branch `AngelaPersonal`. 123 unit tests, 0 failures.

Working end to end:

- Record, play back, and discard a take. Clipping is detected on save.
- Transcription on the device with Vosk, offline, after a one time model download.
- Documents and photographs added as their own kind of record.
- People, and a family tree drawn from whoever you are looking at.
- Sides of the family worked out per person, including sides with nobody on them yet.
- Import a compiled family history without flattening what it was unsure about.
- Export the whole archive to a zip that opens in a browser without this app.
- Permission rules on every read, unit tested.
- Room schema at version 3 with real migrations and no destructive fallback.

Partly built:

- Librarian and search screens exist and answer from local data. No embeddings yet.
- Timeline shows dated memories and gaps. Undated memories still need a home.
- Sync has a database outbox and nothing that drains it. Firebase compiles, and
  `google-services.json` does not exist yet, so nothing leaves the phone.

Not started:

- Family forest, the zoomed out view across households.
- Dark mode.
- Compose UI tests. `androidTest` currently holds the migration test only.
- The post mortem consent flag is stored on a person and never checked on a read.

## Team

- Angela Reinhold, lead, architecture and data
- Moriah Perez, interface and design
- Shanik, testing and documentation

## Stack

Kotlin and Jetpack Compose, no XML layouts. Room for local storage. Vosk for offline
speech. OkHttp and Coil. Firebase is a declared dependency and is not configured.

minSdk 26, target and compile SDK 35.

## Where things are

- `app/src/main/java/com/arv/app/core/` model, database, permission rules, lineage
- `app/src/main/java/com/arv/app/feature/` one folder per screen area
- `app/src/test/` unit tests
- `app/src/androidTest/` migration test, needs a device or emulator
- `app/schemas/` exported Room schemas, one file per version

## Running it

Open the repo folder in Android Studio, let Gradle sync, then Run. Android Studio
writes `local.properties` itself.

For transcription, open Settings inside the app and download the speech model. It is
about 40 MB and only needs doing once. Without it, a recording saves and plays and
says it has not been transcribed yet, rather than inventing words for it.

From the command line:

    ./gradlew testDebugUnitTest
    ./gradlew installDebug

Do not run `connectedAndroidTest` against a device holding real recordings. It
uninstalls the app first, which erases the archive.

## Design rules

- Every memory carries its own visibility and a separate rule for what AI may do with
  it. Those checks live in `core/ai/MemoryAccess.kt` and are unit tested.
- Recorded voices are never synthesized. Provenance is required on every record.
- Nothing is promoted on the way in. An imported person keeps whatever certainty the
  source claimed, and the ones nobody has checked are listed so they can be.
- Uncertainty is stored, not resolved. A death recorded as 2021 or 2022 stays both.
- No destructive migration. This database can hold the only copy of someone's voice.

Licensed under the MIT License. See `LICENSE`.
