# Google Play Data Safety, prepared answers

Not a Play listing yet. This is the form filled in against the code as committed, so that
whoever submits it is copying verified answers rather than guessing under a deadline.

Re-check it whenever a feature changes what leaves the phone. `docs/PRIVACY.md` is the same
facts written for a person rather than a form.

## The word the whole form turns on

Play defines **collected** as user data transmitted off the device. Not stored, not read,
not held. Transmitted.

That single definition is why this card is almost empty. Arv records voices, transcribes
them, files photographs, and holds health information a family wrote down, and none of it
is collected, because none of it goes anywhere. Two things leave: an email address and a
password to Firebase Authentication, and one HTTP request for a speech model if somebody
turns transcription on.

A reviewer looking at an app with `RECORD_AUDIO` and a health feature will expect audio and
health data to be declared. The justification for declaring neither is above, and it is
worth having ready.

## Section 1, data collection and sharing

**Does your app collect or share any of the required user data types?** Yes.

## Section 2, data types

Shared means transferred to a third party. Nothing here is shared: Firebase is a processor
acting for the app, which Play does not count as sharing.

| Category | Type | Collected | Shared | Required or optional | Purpose | Ephemeral |
|---|---|---|---|---|---|---|
| Personal info | Email address | **Yes** | No | Required | Account management | No |
| Personal info | User IDs | **Yes** | No | Required | Account management | No |
| Personal info | Name, address, phone, race, beliefs, orientation | No | No | | | |
| Audio | Voice or sound recordings | **No** | No | | | |
| Audio | Music files, other audio | No | No | | | |
| Photos and videos | Photos, videos | **No** | No | | | |
| Health and fitness | Health info, fitness info | **No** | No | | | |
| Files and docs | Files and docs | **No** | No | | | |
| Messages | Emails, SMS, in-app messages | No | No | | | |
| App activity | Interactions, search history, other user-generated content | **No** | No | | | |
| App info and performance | Crash logs, diagnostics | **No** | No | | | |
| Device or other IDs | Device or other IDs | No | No | | | |
| Location | Approximate, precise | No | No | | | |
| Financial info | All | No | No | | | |
| Calendar, Contacts | All | No | No | | | |
| Web browsing | History | No | No | | | |

The five bolded "No" answers are the ones to be able to defend:

- **Voice recordings.** Written to `filesDir/recordings` and never uploaded. Sync has a
  database outbox and nothing that drains it. Firebase Storage is a declared dependency
  and no code path calls it.
- **Photos and files.** Copied into `filesDir/documents` from the system document picker.
  Never uploaded. The app does not hold a media permission at all.
- **Health info.** An archive area in the local database. Never uploaded.
- **User-generated content.** Stories, notes, transcripts, consent records, people,
  relationships. All local.
- **Crash logs and diagnostics.** There is no analytics, crash reporting or advertising SDK
  in the app. Grep the dependency list; the answer holds.

## Section 3, security practices

| Question | Answer |
|---|---|
| Is data encrypted in transit? | **Yes.** The only transmission is Firebase Authentication, over TLS. |
| Do you provide a way for users to request data deletion? | **Not yet. See the blocker below.** |
| Have you committed to Play Families Policy? | No. Arv is not directed at children. |
| Has your app undergone an independent security review? | No. |

## Blockers before this can be submitted

**1. Account deletion. This is a policy blocker, not a form field.**

Play requires that an app which lets people create accounts also lets them delete those
accounts, both from inside the app and through a publicly reachable web URL declared in
Play Console. Arv creates Firebase accounts and offers sign out only. `AuthScreen` has
`createUserWithEmailAndPassword` and `signInWithEmailAndPassword` and nothing that deletes.

What it needs:

- An in-app "delete my account" path, which for Firebase means reauthenticating and then
  calling `FirebaseUser.delete()`, since Firebase refuses deletion on a stale credential.
- A decision, written down, about what happens to the archive on that phone when the
  account behind it is deleted. Today an archive would keep working with an orphaned
  `authUid`, because `ActiveSession` deliberately separates being authenticated from having
  an archive open. That is defensible and it should be a choice rather than an accident.
- A public URL where somebody can request deletion without installing the app.

**2. A hosted privacy policy URL.** Play requires the policy at a public address.
`docs/PRIVACY.md` is written and has an unfilled contact placeholder.

**3. The two placeholders** in `docs/TERMS.md` and `docs/PRIVACY.md`: governing-law
jurisdiction and a contact address.

## Judgment calls somebody should agree with rather than inherit

**The speech model download.** The app fetches a model from `alphacephei.com`, which means
that server sees the user's IP the way any website would. No user data is transmitted, so
this is a plain file download and is not declarable as collection. Named here because it is
the only other outbound request in the app, and a reviewer who reads the manifest will find
`INTERNET` and ask what it is for.

**The unused Firebase and MLKit libraries.** Firebase Firestore, Storage and Functions and
the MLKit text recogniser all ship inside the APK and no code calls any of them. That
changes no answer above, since the form asks about behaviour rather than dependencies, but
the day one of them is wired up this document and the privacy policy both change, and they
change before the feature ships rather than after.

---

Prepared against the code as committed. Not legal advice, and Play's own definitions are
the authority if they have moved since.
