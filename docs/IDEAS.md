# Idea parking lot

New ideas land here, one entry each, so the pitch and the sprint stay frozen. Nothing here is lost and nothing here is scheduled. The team pulls from this list at sprint planning, not before.

---

## Photos as story triggers · team feedback, Aug 6

Two connected pieces, suggested in the group chat as first feedback on the pitch:

1. **Map images to the story as it plays.** Link pictures to a recording so they rotate in sync with the audio, the way someone would flip through photos while telling you the story.
2. **The picture is the prompt.** Instead of only text questions, hand the storyteller a photo: "Explain this picture." A photo of the porch gets ten minutes of story out of someone who would freeze at "describe 1963."

Why it fits: photos already attach to stories (CAP-6) and the prompt engine already generates questions (AI-8). This connects two planned tickets instead of adding a third. The rotate-with-audio piece extends the story detail screen with timestamped photo links, which is the same shape as timestamped transcript segments.

Extra weight: photo-triggered remembering is how reminiscence work is actually done in dementia care, so this strengthens the roadmap case too.

---

## From an external peer review of the README · Aug 6

A developer outside the team reviewed the README and returned a forty-comment, color-coded review. The wording fixes went straight into the README. These are the design and engineering items, each real enough to be a ticket.

**Relationship edges are sensitive records.** Revealing that a relationship exists can expose as much as revealing a memory. Edges need what memories already have: visibility, provenance, dates, direction, and dispute status. Uncertainty and CHOSEN exist today; visibility on edges does not.

**Harden the withheld count against inference.** Repeated narrow queries against "2 memories matched but are private" can triangulate what is hidden. Options: minimum thresholds before a count is shown, generic unavailable messaging for sensitive categories, rate limiting. Needs an actual inference-attack test, not just a fix.

**Granular AI-use policy.** "Never touch, quote only, summarize" collapses meaningfully different permissions: transcription, OCR, embedding and indexing, search, quoting, summarization, translation, prompt generation, third-party processing. The enum needs to grow, carefully, without becoming a wall of toggles nobody understands.

**Retraction model.** Distinguish preservation of originals and version history from a person's right to retract content or withdraw access, including copies already exported. The README now states the principle; the mechanism is unbuilt.

**Consent edge cases.** Revocation, co-owned recordings (two voices, one file), third parties mentioned or pictured, minors, deceased people, changing capacity, and family disputes. The current consent record covers none of these explicitly.

**Steward governance.** How a steward is appointed, replaced, challenged, and recovered. Consider dual stewards or a quorum for high-risk actions so no single person controls a memorial.

**Data-flow diagram.** Exactly which originals, derivatives, embeddings, logs, and metadata leave the device, and why. Makes "only speech leaves the phone" checkable. Also define who can technically decrypt the vault, what admins and model providers can see, and what metadata remains visible.

**Durability policy.** Integrity hashes, redundant backups, restore testing, format migration, and a continuity plan if the service or company ends. "No decay" has to be engineering, not wording.

**Export hardening.** Selective and encrypted export, a manifest with hashes and provenance, schema versioning, and a tested import and restore path so portability is genuinely reversible.

**Destructive and recovery tests for the demo path.** App killed mid-recording, storage full, phone lost, interrupted upload, corrupted audio, revoked member, conflicting edits, failed export and restore.

**Deterministic pipeline before agents.** Policy check, permission-scoped retrieval, cited answer. Agents only when tests show a measurable benefit. The instructor's scope instinct and the external reviewer's audit instinct agree here, independently.
