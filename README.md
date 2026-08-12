# Arv — Team 03

A private family memory archive for Android. Record a family member telling their
stories, transcribe them, and keep them on a timeline the family owns.

Arv is the Swedish word for inheritance.

## Team

- Angela Reinhold — lead, architecture and data
- Moriah Perez — interface and design
- Shanik — joining, area TBD

## Stack

Kotlin, Jetpack Compose (no XML), Room for local storage, Firebase planned for sync.

## Where things are

- `app/src/main/java/com/arv/app/core/` — data model, local database, permission rules
- `app/src/main/java/com/arv/app/feature/` — one folder per screen area
- `app/src/test/` — unit tests

## Running it

Open the repo folder in Android Studio, let Gradle sync, then Run. No extra setup.

## Plan for the month

1. Recording, saving, and transcription working end to end on a device
2. Family feed, timeline, and family tree
3. Prompt library and the librarian search screen
4. Test pass on real hardware, then the month-end build review

## Notes

- Every memory carries its own visibility setting and a separate rule for what AI may
  do with it. Those checks live in `core/ai/MemoryAccess.kt` and are unit tested.
- Recorded voices are never synthesized. Provenance is required on every record.

Licensed under the MIT License. See `LICENSE`.
