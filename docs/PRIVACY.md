# Privacy Policy

**Effective 24 September 2026.**

Arv is a student project, built by Team 03 for COS229.

Four things can leave your phone. Your email address, when you make an account. A request to
a speech-model server, once, only if you choose to turn on transcription. Only if you invite
somebody or accept an invitation, the family's name, your standing in it, and the invitation
codes themselves, so that a code read out on one phone can be typed into another. And only if
you turn on sharing for an archive, the part of it the family is meant to see: stories set to
Family, Branch or Selected with the recordings, photographs and scans attached to them, the
family tree, and the consent records that go with it, so that the family's other phones hold
them too. That is the whole list, and the rest of this document is the detail behind it.

Some things never go anywhere, whatever you turn on: transcripts, private stories and their
files, health records and their files, and profile pictures. There is no analytics, no
advertising, no crash reporting and no tracking of any kind in this app.

## What stays on your phone

All of this is written to the app's private storage and never sent anywhere, whether or not
sharing is on:

- **Transcripts.** Speech is turned into text by Vosk, running on the phone itself. The audio
  is not sent to a server to be transcribed.
- **Private stories**, everything about them, their recordings, photographs and scans
  included.
- **Health information** a family chose to record, whatever it is set to, and any file
  attached to it.
- **Profile pictures.** A picture you put in somebody's circle is copied into the app's
  own storage, downscaled, and kept there. It is not uploaded and it is not shared.
- **The words you use for somebody**, like "Grandma" or "my cousin's wife". They are said
  from where you stand, so they would be wrong on anybody else's phone.

And this stays on the phone too, unless you turn on sharing for the archive (below):

- **People and relationships.** Names, nicknames, birth and death years, birthplaces, notes,
  how people are related, and how certain the archive is about each of those.
- **What you wrote about a story**: its title, year, place and tags, and who may see it.
- **Consent records.** Whether a person agreed to be archived, who wrote that answer down,
  when, and how it reached them.
- **Recordings, photographs and scans** attached to a story set to Family, Branch or
  Selected. Discarding a take deletes its file straight away, and if the delete fails the app
  says so rather than telling you it worked.

Uninstalling the app deletes all of it from this phone. What sharing already sent to the
family stays with the family; see Keeping and deleting.

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

**3. The family's name, your standing in it, and invitation codes, to Google, only if you
use invitations.**

An invitation has to work on a phone that has never seen it, and the only way to do that
is for a server to hold the code. When somebody in a family issues their first code, the
app writes three things to Firestore, Google's database service, in a project Team 03
controls: the name the family gave its archive; a row for each member holding their account
identifier, their role, when they joined and who invited them; and each invitation code,
with who issued it, when it expires, and who used it. When somebody types a code in, the
app reads that one code, checks it the same way it checks a code on the phone, and writes
its own membership and marks the code used in one step. Rules on the database, which we
wrote and test, refuse any other write: a membership without a live code, a code spent by
somebody else, a role the code did not grant. The one removal they accept is the family's
owner taking another member out. No program of ours runs on any server.

That is everything invitations put on the server. A family that never issues a code sends
nothing under this heading. Team 03 can read what is in that database, and Google's handling
of the request itself is covered by their privacy policy.

**4. The shared part of an archive, to Google, only if you turn on sharing for it.**

Sharing is a switch in Settings, one for each archive, and it starts off. With it on, the app
writes to the same Firestore database, and to Cloud Storage, Google's file storage in the same
project, so that every phone in the family holds the same archive, and reads back what the
family's other phones wrote:

- **Each story set to Family, Branch or Selected:** its title, year, place and tags; who told
  it and who it is about; who may see it and what the family librarian may do with it; how
  it was made, how long it is and how many pieces it has; who made it and when; and, if it
  was deleted, when and by whom.
- **Each person in the family tree:** their name and other names, birth and death years and
  birthplace, notes, how well established they are and the source, whether they are living
  or remembered, who stewards their memory, which account they are, and their consent
  record.
- **Each link in the tree**, and whether it is marked uncertain.
- **The recording, photograph or scan attached to each of those stories**, to Cloud Storage,
  with a small record of it in the database: what kind of file it is, its size and length,
  where it is stored, who made it and when, and a copy of its story's answer about who may
  see it. The rules on file storage read that record, so a file is readable by exactly the
  people who may read its story.

A private story never goes, and neither does anything recorded as health information,
whatever it is set to, and neither do their files. Transcripts, profile pictures and the
words you use for somebody do not go either. The code that talks to the server takes no
transcript, and every story passes one check before it or its files are sent, tested on its
own, that turns away anything private and anything recorded as health information. A phone
that receives one anyway drops it. A story made private after it was shared is taken off the
database and its files off file storage, and every phone that downloaded them deletes its
copy the next time it syncs. A story narrowed to fewer people narrows its files the same way.
A deleted story stays there, hidden on every phone, so that the person who made it or a
keeper can bring it back.

Rules on the database and on file storage decide who can read each story and its files, the
same way the app does: every
member for Family, the descendants of the named ancestor for Branch, the people chosen for
Selected, and restricted material for keepers only. The rules are tested against a local
stand-in for the database, holding made-up families, before anything ships. Turning sharing
off stops this phone sending and receiving; it does not take back what the family already
has. Team 03 can read what is in that database and that file storage, as with invitations.

File storage needs Google's paid plan. On a project without it, everything above except the
files still travels, and each file stays on the phone that made it, which Settings says,
until the project has it.

## What we do not do

- No analytics or usage tracking. No SDK in this app reports what you do.
- No advertising, and no advertising identifiers.
- No crash or error reporting.
- No selling or sharing of anything, because there is nothing on our side to sell or share.
- No servers of our own. The database is Google's; the rules that guard it are ours.

The app includes a Google text-recognition library that **is not used**. No code in the app
calls it. The Firebase database and file storage libraries are used for exactly the
invitation and sharing traffic described above and for nothing else. If that changes, this
document changes with it before the feature ships.

## Permissions, and what each is for

| Permission | Why |
|---|---|
| Record audio | Recording someone telling a story. This is the app. |
| Foreground service, foreground service microphone | Keeps a 45 minute interview recording when the screen locks. Without it Android kills the recording. |
| Post notifications | Shows the notification that says a recording is running. |
| Internet, network state | The four kinds of request above, and waiting for a connection before sharing. Nothing else. |

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
family. That information is treated like everything else here: it stays on the phone
unless the family turns on sharing, and then only what is described above goes.

## Keeping and deleting

For an archive that is not shared there is no retention schedule, because none of it is held
on a server. It lives on your phone for as long as you keep it there.

For a shared archive, what sharing sent stays in the database for as long as the family
keeps the archive.

Deleting a story hides it for thirty days, on this phone and on every phone the archive is
shared with, so a mis-tap can be undone. After that it is erased for good: the recording,
the transcript, the record of it, and the copy on the server. Recently deleted, in Settings,
lists what is waiting, says the day each one goes, and has a Delete forever button that does
it now. There is no in-app way yet to erase a whole shared archive from the database;
`docs/PLAY_DATA_SAFETY.md` lists that as a blocker.

The family name, membership rows and invitation codes described above stay in the database
until the family's owner asks for them to be removed. The owner can remove a member from
inside the app, under Settings, which deletes that member's row. There is no in-app way yet
to remove the family name or the invitation codes; `docs/PLAY_DATA_SAFETY.md` lists that as
a blocker.

To delete everything on this phone: uninstall the app, or clear its data in Android settings.
Both are immediate and neither is recoverable.

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
Data Safety declaration.
