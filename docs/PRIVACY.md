# Privacy Policy

**Effective 9 September 2026.**

Arv is a student project, built by Team 03 for COS229.

Two things leave your phone. Your email address, when you make an account. And a request to
a speech-model server, once, only if you choose to turn on transcription. That is the whole
list, and the rest of this document is the detail behind it.

Nothing else goes anywhere. Not the recordings, not the transcripts, not the people, not the
health records. There is no analytics, no advertising, no crash reporting and no tracking of
any kind in this app.

## What stays on your phone

All of this is written to the app's private storage and never sent anywhere:

- **Audio recordings.** Discarding a take deletes its file straight away, and if the delete
  fails the app says so rather than telling you it worked.
- **Transcripts.** Speech is turned into text by Vosk, running on the phone itself. The audio
  is not sent to a server to be transcribed.
- **People and relationships.** Names, nicknames, birth and death years, birthplaces, notes,
  how people are related, and how certain the archive is about each of those.
- **Stories, documents and photographs**, and anything you wrote about them.
- **Health information** a family chose to record.
- **Consent records.** Whether a person agreed to be archived, who wrote that answer down,
  when, and how it reached them.
- **Invitation codes** you issued or redeemed, including which account admitted which.

Uninstalling the app deletes all of it.

## What leaves your phone

**1. Your email address and password, to Google.**

Accounts are handled by Firebase Authentication, which is Google's service. When you create
an account or sign in, your email address and password go to Google, and Google's servers
hold your email address and an identifier for your account. Google will also see technical
information that comes with any internet request, including your IP address.

Google's handling of it is covered by their privacy policy, not this one. We do not receive
your password, and we do not have a server that receives anything else about you.

**2. A request to alphacephei.com, only if you ask for transcription.**

Transcription needs a speech model on the device, about 41 MB, which is not shipped with the
app. If you open Settings and choose to download it, the app makes one request to
`https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`, run by the maintainers
of Vosk. They will see your IP address, the same as any website you visit.

The model is downloaded once. Your recordings are never sent there, or anywhere. If you never
download it, the app never contacts that server, and recordings simply save without a
transcript rather than being sent away to get one.

## What we do not do

- No analytics or usage tracking. No SDK in this app reports what you do.
- No advertising, and no advertising identifiers.
- No crash or error reporting.
- No selling or sharing of anything, because there is nothing on our side to sell or share.
- No servers of our own. Team 03 operates no backend.

The app does include Google Firebase libraries for cloud storage, databases and server
functions, and a Google text-recognition library. **None of them are used.** No code in the
app calls them. They are dependencies that were added ahead of features that do not exist
yet, and they ship inside the app without doing anything. If that changes, this document
changes with it before the feature ships.

## Permissions, and what each is for

| Permission | Why |
|---|---|
| Record audio | Recording someone telling a story. This is the app. |
| Foreground service, foreground service microphone | Keeps a 45 minute interview recording when the screen locks. Without it Android kills the recording. |
| Post notifications | Shows the notification that says a recording is running. |
| Internet, network state | The two requests above. Nothing else. |

Arv does **not** ask for access to your photo library. Documents and photographs are added
through the system file picker, which hands the app one file that you chose and no standing
permission to look at anything else.

## Backup

**Cloud backup: off, deliberately.** Your recordings are never copied to Google Drive.
Beyond the privacy reason, Google's Auto Backup is capped at 25 MB per app, so one long
recording would put an archive over the limit and the backup would skip the audio while
appearing to have worked.

**Moving to a new phone: yes, on Android 12 and higher.** Your archive transfers during the
new phone's setup, if you pick that option when it asks. The transfer is local, phone to
phone, and passes through neither us nor Google. The speech model is left behind on purpose,
because Settings can download it again and carrying it would only slow down the transfer of
the part that cannot be replaced.

**On Android 11 and lower it does not transfer.** Those versions have a single setting
covering cloud backup and phone-to-phone transfer together, so they cannot be answered
separately, and the 25 MB cap would have defeated the transfer anyway. On those devices the
export below is the only way to move an archive.

**Transfer to an iPhone: off.** There is no iPhone version of Arv, so a transfer would put
your recordings on a device with nothing that can open them.

**Either way, export.** Settings writes the whole archive to a zip file that opens in a
browser without this app. A phone that is lost, stolen or broken takes the archive with it,
and a transfer only helps while you still have the old phone in your hand. Where you put
that file is up to you.

## Other people's information

Most of what an archive holds is about people other than you, and some of them are dead.

Arv records whether a person consented to be archived, and treats no answer and an answer of
no as different things. It restricts a memory whose narrator has no consent on file. A health
record is controlled by the person it is about, or the memory steward they named, rather than
by whoever entered it. A memory marked private stays readable only by the person who recorded
it, including from the owner of the archive.

Those rules are enforced in the app's code and covered by its tests. They are not a promise
about what other members of your family will do with what they can already see.

If you invite somebody into your archive, they can see what their role permits. Deciding
that is your decision, and it is recorded.

## Children

Arv is not directed at children and does not knowingly collect information from them. A
family archive will often contain information about children, entered by an adult in that
family. That information stays on the phone with everything else.

## Keeping and deleting

There is no retention schedule, because there is nothing held on a server to retain. Your
archive lives on your phone for as long as you keep it there.

To delete everything: uninstall the app, or clear its data in Android settings. Both are
immediate and neither is recoverable.

Deleting your **account** is separate, because the account lives at Google. Signing out of
your account does not delete anything on the phone, and deleting the archive does not delete
the account.

## Changes

This document describes the app as committed on the effective date above. If a feature
changes what leaves the phone, this gets updated in the same change that ships the feature.

## Contact

`[CONTACT EMAIL]`

---

`[CONTACT EMAIL]` is a placeholder and has to be filled in before this is shown to anybody
outside the team.

This document was written for Arv as a student project and has not been reviewed by a
lawyer. A Play Store listing requires a privacy policy hosted at a public URL plus a separate
Data Safety declaration, and a hosted or synced version of Arv would need this rewritten,
since the first sentence would no longer be true.
