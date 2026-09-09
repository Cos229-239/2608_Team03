# Arv, Team 03

A private family memory archive for Android. Record a family member telling their
stories, transcribe them on the device, and keep them in an archive the family owns.

Arv is the Swedish word for inheritance.

## Where the build stands

Updated 2026-09-09. 197 unit tests, 0 failures.

Working end to end:

- Record, play back, and discard a take. Clipping is detected on save.
- Transcription on the device with Vosk, offline, after a one time model download.
- Documents and photographs added as their own kind of record.
- People, and a family tree drawn from whoever you are looking at.
- Sides of the family worked out per person, including sides with nobody on them yet.
- Import a compiled family history without flattening what it was unsure about.
- Export the whole archive to a zip that opens in a browser without this app.
- Accounts, and an archive that belongs to one rather than to whoever holds the phone.
- Invitations. One code per person, spent on first use, recording who admitted whom.
- Permission rules on every read and every edit, unit tested.
- Consent enforced on reads, including the decision a family made after a death.
- Profile pictures. Upload one into the circle, or take one from a photograph already
  filed under that person. A face belongs to the person, like their name, so it needs no
  record behind it. The permission check happens once, when a photograph is taken out of
  the archive, so a private one cannot be promoted onto the people list. Pictures are
  downscaled on the way in, and no face means initials, which is a design not a blank.
- Dark mode, plus eight named palettes and an auto setting that follows the system.
- Room schema at version 10 with real migrations and no destructive fallback.

Partly built:

- Librarian and search screens exist and answer from local data. No embeddings yet.
- Timeline shows dated memories and gaps. Undated memories still need a home.
- Sync has a database outbox and nothing that drains it. Joining writes a standing,
  not a library, so a code redeemed on a second phone opens an archive with nothing
  in it. The join screen says so rather than letting it look like a failed load.
- Invitations always grant CONTRIBUTOR. The role travels on the invitation and the
  other roles are built and tested, so what is missing is a picker, not a mechanism.

Not started:

- Family forest, the zoomed out view across households.
- Compose UI tests. `androidTest` holds the migration tests only.

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
- `docs/SPEC.md` the spec the permission rules cite
- `docs/PLAY_DATA_SAFETY.md` the Play form filled in against the code, plus the
  account-deletion blocker that has to be built before a listing is possible
- `docs/TERMS.md`, `docs/PRIVACY.md` terms of use and privacy policy, both written
  against the code as committed. Two placeholders in them, jurisdiction and a contact
  address, are deliberately unfilled.

## Running it

Open the repo folder in Android Studio, let Gradle sync, then Run. Android Studio
writes `local.properties` itself.

You also need `app/google-services.json`, which is gitignored and is not in the repo.
The google-services plugin reads it at configuration time, so a fresh clone without it
fails before it compiles anything. It is in the team channel. Ask rather than guess:
the error it produces does not say what is missing.

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
- Private means private on the way in as well as out. A role is not a key: a keeper
  cannot read, edit or delete what somebody kept to themselves.
- Nothing goes to a cloud because a default said so. Auto Backup is refused in every
  place the OS looks, and it would fail anyway at its 25 MB cap. Phone to phone transfer
  is allowed and wanted, on 12 and up where it can be answered separately from cloud:
  it is local, the person picks it during setup, and an archive should survive a new
  handset. On 11 and below the two share one switch, so neither happens and the export
  zip is the only way across.

Licensed under the MIT License. See `LICENSE`.
