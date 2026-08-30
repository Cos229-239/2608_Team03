# Arv: Product & Technical Spec

Native Android. Kotlin, Jetpack Compose, MVVM + repository. Offline-first with Room as the local source of truth and Firebase as the sync target.

---

## 1. Sitemap

```
Launch
├── Onboarding (01)                    unauthenticated
│   ├── Sign in / Sign up
│   └── Join your family (02)
│       ├── Enter invite code
│       └── Start a family
│
└── Main shell (bottom nav, 4 tabs)
    │
    ├── FAMILY (17)  ── default tab
    │   ├── Private feed: updates, milestones, new recordings
    │   ├── Compose post
    │   ├── Archive view (03)          same data, archival lens
    │   ├── Story Detail (07)
    │   │   ├── Transcript / Photos / People / Map
    │   │   └── Who can see this (20)
    │   └── Collection / Tradition (12)
    │
    ├── TIMELINE (09)
    │   ├── Decade filter, person filter
    │   ├── Gap card → Prompt Library (10)
    │   └── → Story Detail (07)
    │
    ├── TREE (18)
    │   ├── Person Profile, living (11)
    │   ├── Person Profile, memorial (19)
    │   ├── Add a person / claim my profile
    │   └── "Needs a person" suggestions from the librarian
    │
    ├── LIBRARIAN (21)
    │   ├── My librarian        scope = PERSONAL
    │   ├── Our family librarian scope = FAMILY
    │   └── Search (08)          same retrieval, no generation
    │
    ├── RECORD (FAB, reachable from any tab)
    │   ├── Record active (04)
    │   ├── Review & Save (05)
    │   └── Add Media (06)
    │
    └── SETTINGS (overflow)
        ├── Family & Permissions (13)
        ├── Legacy & Consent (14)
        ├── Sync & Offline (15)
        ├── Export (16)
        └── Account
```

**Depth rule:** nothing the keeper does regularly is more than two taps from a tab root. Record is one tap from everywhere.

---

## 2. Screen inventory

| # | Screen | Owner role | Primary job | Key states |
|---|--------|-----------|-------------|------------|
| 01 | Onboarding | UI | Explain value in 8 seconds | 3 carousel panels |
| 02 | Join your family | Backend | Get into or create a permission boundary | code valid / invalid / expired / creating |
| 03 | Archive | UI | Show what exists, offer the next question | empty / populated / offline banner |
| 04 | Record active | Capture | Capture long-form audio reliably | idle / recording / paused / interrupted / low storage |
| 05 | Review & Save | Capture | Capture metadata while memory is fresh | new / editing existing |
| 06 | Add Media | Capture | Bulk photo and document ingest | selecting / faces found / OCR running |
| 07 | Story Detail | Capture + AI | Listen and read together | transcribing / ready / failed / corrected |
| 08 | Search | AI | Find by meaning across all media | empty / results / none |
| 09 | Timeline | UI | Show the shape of a life, including holes | by decade / by person / gap cards |
| 10 | Prompt Library | AI | Never run out of the next question | suggested / by category / custom |
| 11 | Person Profile, living | UI | One person's whole record | claimed / unclaimed / hours-preserved meter |
| 12 | Collection | AI + UI | Assemble a tradition from fragments | recipe / song / language; disputed variants |
| 13 | Family & Permissions | Backend | Who can do what | owner / keeper / contributor / viewer |
| 14 | Legacy & Consent | Backend | What happens after | consent on file / missing / steward named |
| 15 | Sync & Offline | Backend + Capture | Trust that nothing is lost | offline / queued / uploading / conflict |
| 16 | Export | Backend | Leave with everything | selecting / building / ready |
| 17 | Family Feed | UI | The reason to open it on a Tuesday | all / my branch / stories only / empty |
| 18 | Family Tree | UI | Navigate people, find who is missing | claimed / unclaimed / uncertain link / suggested person |
| 19 | Memorial Profile | UI | A life, kept on that person's terms | memorial / steward assigned / no steward |
| 20 | Who can see this | Backend | Per-memory visibility and AI policy | 4 visibility levels × 3 AI policies |
| 21 | Librarians | AI | Answer from the archive, never from invention | personal / family / grounded / withheld / no sources |
| 22 | Memory Garden | phase 4 | Walk back into a memory | authentic audio / reconstructed room |

---

## 3. Data model

### Firestore collections

```
families/{familyId}
  name, createdBy, createdAt
  legacyPlan: { mode: TRANSFER|FREEZE|DELETE, successorId, inactivityMonths }

families/{familyId}/members/{userId}
  role: OWNER | KEEPER | CONTRIBUTOR | VIEWER
  personId                                     -- their own profile in the tree
  branchRootPersonId                           -- used by BRANCH visibility
  joinedAt, invitedBy

families/{familyId}/people/{personId}          -- subjects, not accounts
  displayName, alsoKnownAs[], birthYear, deathYear, birthPlace
  linkedUserId?                                -- only if they have an account
  state: LIVING | MEMORIAL
  memoryStewardUserId?
  consent: { status, grantedAt, method, postMortemOk, audioConsentAssetId? }

families/{familyId}/relationships/{edgeId}
  fromPersonId, toPersonId
  kind: PARENT|CHILD|SIBLING|SPOUSE|PARTNER|GRANDPARENT|...
  uncertain: bool                              -- marked, never silently resolved

families/{familyId}/stories/{storyId}
  title, kind: AUDIO | PHOTO_SET | DOCUMENT | COLLECTION | UPDATE
  narratorIds[], mentionedPersonIds[]
  eraStart, eraEnd, eraPrecision: EXACT|RANGE|UNKNOWN
  place: { label, lat?, lng? }
  tags[], aiTags[]
  visibility: PRIVATE | SELECTED | BRANCH | FAMILY
  sharedWithUserIds[]                          -- used when visibility == SELECTED
  branchRootPersonId?                          -- used when visibility == BRANCH
  aiUsePolicy: NONE | QUOTE_ONLY | SUMMARY_OK
  provenance: AUTHENTIC_RECORDING | AUTHENTIC_DOCUMENT | HUMAN_WRITTEN
              | AI_TRANSCRIBED | AI_ORGANIZED
  restricted: bool, restrictionNote
  createdBy, createdAt, updatedAt
  primaryAssetId, assetCount, durationMs

families/{familyId}/assets/{assetId}
  storyId, type: AUDIO | IMAGE | DOCUMENT
  storagePath, mimeType, bytes, sha256
  durationMs?, width?, height?, capturedAt?
  uploadState: LOCAL_ONLY | UPLOADING | SYNCED | FAILED
  ocrText?, thumbPath?

families/{familyId}/transcripts/{assetId}
  status: PENDING | RUNNING | READY | FAILED
  language, provider, modelVersion
  segments[]: { startMs, endMs, text, confidence, speakerId?, originalText?, humanVerified }
  fullText
  editedAt?, editedBy?

families/{familyId}/embeddings/{chunkId}
  sourceType: TRANSCRIPT | OCR | CAPTION
  sourceId, storyId, startMs?, endMs?
  ownerUserId                                  -- carried so retrieval can filter
  visibility, aiUsePolicy                      -- denormalized, see §5
  text, vector: number[384]

families/{familyId}/prompts/{promptId}
  text, category, targetPersonId?
  origin: LIBRARY | GAP_DETECTED | USER
  rationale                                    -- "Ruth mentioned this at 06:18 but never sang it"
  status: SUGGESTED | SAVED | ANSWERED | SKIPPED
  answeredStoryId?

families/{familyId}/invites/{code}
  createdBy, expiresAt, usesLeft, role
```

**Private vault.** Memories with `visibility == PRIVATE` live in the same collections with the same shape. There is no second database. The vault is a *query result*, not a storage location, which means there is exactly one permission path to audit instead of two.

### Room (local), mirrors the above plus

```
outbox
  id, opType: CREATE|UPDATE|DELETE|UPLOAD
  collection, docId, payloadJson, localFilePath?
  attempts, lastError, createdAt
```

The outbox is the whole offline story. Every write goes to Room and the outbox first; a `WorkManager` job drains it. The UI never waits on the network.

---

## 4. Security rules: the shape

```
function canRead(story) {
  return isMember(familyId)
    && ( !story.restricted || role in [OWNER, KEEPER] )
    && ( story.visibility == FAMILY
         || (story.visibility == BRANCH   && myBranchRoot == story.branchRootPersonId)
         || (story.visibility == SELECTED && uid in story.sharedWithUserIds)
         || (story.visibility == PRIVATE  && story.createdBy == uid) );
}

allow create if role in [OWNER, KEEPER, CONTRIBUTOR]
allow update if story.createdBy == uid || role in [OWNER, KEEPER]
allow delete if story.createdBy == uid || role in [OWNER, KEEPER]
```

Two rules that are easy to get wrong and must be tested explicitly:

- **Visibility can be narrowed by anyone who can edit, but widened only by the creator.** A keeper cleaning up the archive must not be able to publish someone's private memory to the family.
- **A memorial profile freezes visibility.** A steward manages the profile; they do not gain read access to material the person kept private.

Write the rule tests before the rules. This is the one place where a bug is a privacy incident, not a defect.

---

## 5. The librarians

Both librarians are the same retrieval pipeline with a different filter. That is deliberate: one code path, one place to audit.

```
question
   ↓
resolve person names through people/ and relationships/   ("grandma" -> Ruth, personId)
   ↓
embed the expanded query
   ↓
vector search over embeddings/
   ↓
PERMISSION FILTER  ← the only thing that differs between the two scopes
   ↓
rank, take top k, load the source stories
   ↓
compose answer with inline citations
   ↓
if sources.isEmpty() -> say so. Never generate an unsourced answer.
```

**Permission filter**

| Scope | Reads |
|---|---|
| `PERSONAL` | Everything where `createdBy == uid`, plus everything the family scope allows |
| `FAMILY` | Only what `canRead` allows for this user, and only where `aiUsePolicy != NONE` |

`QUOTE_ONLY` material may be cited verbatim but must not be paraphrased into the answer body.

**Two non-negotiables, enforced in code and not in the prompt:**

1. `LibrarianAnswer.sources` must be non-empty or the answer does not render.
2. Generated connective text carries `Provenance.AI_ORGANIZED` and is labeled wherever it appears. Quoted material carries the source's own provenance.

**Withheld counting.** When memories match but fail the permission filter, report the count and never the content, the title, or the owner. "2 memories matched but are private" is honest. Naming them is a leak.

---

## 6. Transcription pipeline

```
[phone] record → Room + outbox
       ↓ WorkManager, Wi-Fi only by default
[Cloud Storage] resumable upload
       ↓ storage trigger
[Cloud Function] enqueue transcription job
       ↓
[STT provider behind TranscriptionService]
       ↓ word-level timestamps
[Cloud Function] write transcripts/{assetId}
       ↓
[Cloud Function] chunk ~400 tokens, embed, write embeddings/ with visibility carried through
       ↓
[Cloud Function] extract entities → suggest people, places, years, tags
       ↓
[Cloud Function] gap analysis → prompts/ with rationale
       ↓
[phone] listener updates the UI
```

Keep `TranscriptionService` an interface with a real and a fake implementation from day one. Nothing else in the codebase knows which provider is in use.

**Correction loop:** when someone fixes a word, store the machine's original alongside the correction. Corrections are training signal and provenance at the same time. The archive knows what a machine wrote and what a human verified.

---

## 7. Search

Query text is embedded with the same model as the chunks and cosine-ranked across transcript chunks, OCR text, and captions. Person names resolve through `people/` before embedding, which is why "grandma" can match "her mother's": the query is expanded with the aliases the archive already knows.

Search runs the same permission filter as the family librarian. It just does not generate anything.

Fallback when embeddings are unavailable: Firestore `fullText` substring plus tag filter. Search must never return an error screen. Degrading to keyword search is correct.

---

## 8. Accessibility: functional requirements, not polish

The storyteller is 70+, and the keeper is often operating the phone for them.

- Minimum body text 16sp, scaling to 200% without clipping
- Touch targets at least 48dp
- Contrast at least 4.5:1 for text and 3:1 for controls, verified in light and dark
- Content descriptions on every control; the transcript reads in order under TalkBack
- The record button is reachable one-handed on a 6.7" phone
- No meaning carried by color alone. Restricted and generated content get labels, not tints.
- Recording works with the screen off and announces its state in the notification

---

## 9. Definition of done for the term

On a real device, with the network off for part of it:

1. Invite a second member and have them join with a code
2. Record a 5-minute story with the screen locking partway through
3. Save it with a person, an approximate year range, and a place
4. Reconnect and watch it upload and transcribe
5. Correct one wrong word in the transcript
6. Ask the family librarian a question and get an answer that cites her actual voice at a timestamp
7. Confirm a memory marked private does not appear in the family scope, and that the withheld count says so
8. See the story land in the right decade on the timeline
9. Export the archive and open the offline index in Chrome

If all nine work, the project is done. Everything else is stretch.

---

## 10. Roadmap beyond this term

**PnP3.** The Family Hive across profiles, richer tree editing, memorial succession, conflict resolution UI.

**Memory Garden, 3D web.** A browser scene per memory, built from the photos and descriptions already in the archive. Same data, same permission filter, different renderer.

**Memory Garden, VR.** Walk paths by person, decade, or place. Sit in a room. Hear the actual recording. Visit together and leave notes.

The boundary from PITCH.md slide 6 carries all the way through. Reconstructed rooms are labeled as interpretation. Recorded voices are never synthesized. The app does not put words in a dead person's mouth.

---

## 11. Getting these wireframes into Figma

The wireframe file is `design/wireframes-lofi.html`, 22 frames at 360 × 800 on one sheet.

**Fastest path, keeps editable layers:**

1. Open the file in Chrome.
2. In Figma, install the **html.to.design** plugin.
3. Choose "Import from HTML" and paste the file contents, or serve the folder and paste the local URL.
4. Every frame arrives as a named Figma frame with real layers, so the team restyles without redrawing structure.

**Fallback:** open in Chrome, screenshot each frame, drop the images into a Figma board as reference and rebuild only the frames being iterated on.

Either way the structure decisions are already made, which is the part that normally eats the week.
