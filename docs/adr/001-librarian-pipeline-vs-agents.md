# ADR 001: The librarian is a pipeline, not an agent

**Status:** Accepted
**Decided:** August 13, 2026, in `docs/IDEAS.md`. Built August 17, 2026. Written up September 20, 2026.
**Owner:** Angela Reinhold

## The question

Arv has two librarians, one for a person and one for the family. Somebody asks a question in plain words and gets an answer from the archive.

There are two ways to build that. An agent is a language model that reads the question, decides for itself what to look up, and writes the reply. A pipeline is a fixed set of steps that runs the same way every time. This records why Arv uses the second.

## The decision

The librarian is a deterministic pipeline that runs on the phone. No model decides what it may read, and no model writes a sentence on a person's behalf. An agent, or any model-backed step, goes in only when a test shows it answers better, and only behind the same permission check.

## Why

**Permission has to sit at a fixed place.** A private memory stays private even from the family's owner, and a health record is controlled by the person it is about. That promise is only testable if the permission check happens at one known step on every run. An agent chooses its own path, so there is no single place to prove the check always happened.

**An archive cannot invent.** The thing being kept is often the voice of someone who has died. A model can produce a fluent sentence nobody ever said. In Arv the ground of every answer is a word-for-word quote with a timestamp, and the connecting text around it is labeled as the machine's.

**Private material stays on the phone.** A hosted model would need the transcripts sent to it. Private and health material never leave the device, so a hosted model could not see them, and a family librarian that skips them silently would be worse than one that says what it withheld.

**It runs with no key, no network, and no bill.** The class repo is public, so it can never need a secret to build. It also means the librarian works in a kitchen with no signal, which is where these recordings get made.

**The same question gives the same answer.** That is what makes it testable at all. Sixty unit tests cover retrieval and permission today: 19 on the hive, 11 on the flat pipeline, 4 on matching, 26 on who may read what.

**Scope.** This is a course build with four-week months. Professor Kelly's advice on scope and an outside reviewer's advice on auditability pointed the same way without knowing about each other.

## What it looks like in the code

All of it lives in `app/src/main/java/com/arv/app/core/ai/`.

1. `QuestionParse` reads the question without a model. Names come from the family's own list of people, years come from digits, and what is left becomes search terms.
2. `Signals` scores every story against the question: who told it, who it is about, the year, the place, the title and tags, and what was said in the recording. Each signal has a strength. Something stated on the record outranks something written, which outranks something said in passing.
3. `MemoryAccess.partition` applies permission after scoring and before anything is shown. Doing it in that order keeps the withheld count honest: "two memories matched and are private" is real information, and the titles are never revealed.
4. `AnswerAssembly` ranks what is left and builds the answer from quotes and timestamps. When the best match is only something said in passing, the answer says it is a guess.
5. `GroundingEnforcer` wraps whichever librarian is installed and refuses any answer that has no sources. It is a decorator, not a prompt, so it holds even if a different implementation is swapped in later. `ServiceLocator` is the only place a librarian is constructed, and it always applies the wrapper.

`LibrarianHive` is the same pipeline arranged as shelves. Every person, era, place, and archive area has its own small librarian, the ones that recognize something in the question nominate stories, and nominations for the same story add up. The answer names the shelves it came through, so a family member can see how it was found. The hive and the flat pipeline call the same `Signals` code, so they cannot rank differently.

## What this costs

The librarian only finds what shares words with the question. "Grandma" finds Ruth because the archive knows that name for her. A question phrased far from the archive's own words finds nothing, and the librarian says so.

It does not combine several stories into a new summary, and it does not hold a conversation. Each question stands alone.

`docs/SPEC.md` section 5 describes an embedding step that would close part of that gap. It is not built. `QuestionParse` is written so embeddings can arrive as one more signal without changing anything after it.

## When to revisit

When there is a fixed set of questions with known right answers against a test archive, and a model-backed step beats the pipeline on that set by enough to be worth what it costs.

Even then it comes in as a signal or as a way of wording the answer. It does not get to decide what it may read, and `GroundingEnforcer` still stands between it and the screen.

## What was considered

**A hosted model with retrieval.** Better at loose phrasing. Needs a key and a network, sends family transcripts to a third party, and can write things nobody said.

**An agent with tools.** The most flexible option and the hardest to audit. The permission check would become one of the tools the agent is trusted to call.

**An on-device model.** Keeps everything on the phone. Too large and too slow for the phones this is meant for, and it can still invent.

**Deterministic pipeline.** Chosen. It is the least clever and the only one where every step can be read, tested, and explained to the family using it.
