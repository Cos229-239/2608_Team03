# Weekly Tester Report

## App: Family Memory Preservation
### Tester: Shanik
### Test Area: Sign In, Account Persistence, Archive Management, Librarian

---

## 1. Sign In / Account Persistence

### TC-SI-01 — Existing account does not recover previously created Archives

**Steps:**
1. Created an account using an email and password.
2. Entered a Family/Archive name and user name during account creation.
3. Signed out from the account.
4. Signed in again using the same email and password.

**Expected:**
After signing in with an existing account, the application should retrieve the Archives already associated with that account. The user should be able to open an existing Archive or create a new one.

**Actual:**
The application accepts the existing email and password but immediately asks for the family/archive name and user name again. Previously created Archive information is not displayed or recovered. Entering different information allows another Archive to be created using the same account.

**Additional observation:**
In Settings, the Sign Out option displays the message:

> "Leaving does not delete anything. It closes the archive so a different one can be opened on this phone."

This creates an expectation that the previously created Archive remains available after signing out. However, after signing in again, there is no visible Archive-selection screen and no apparent way to recover the previous Archives.

**Status:** FAIL

**Priority:** High

**Potential area:** Account persistence / Archive association / Archive selection

---

## 2. Sign In / Duplicate Account Prevention

### TC-SI-02 — Existing email cannot be registered again

**Steps:**
1. Used an email address that was already associated with an existing account.
2. Attempted to create another account using the same email but a different password.

**Expected:**
The application should prevent creation of a duplicate account for an email address that is already registered and direct the user to Sign In instead.

**Actual:**
The application correctly prevented account creation and displayed:

> "There is already an account with that email. Sign in instead."

**Status:** PASS

---

## 3. Sign In / Invalid Email Validation

### TC-SI-03 — Invalid email formats are rejected

**Test inputs:**

| Input | Result | Status |
|---|---|---|
| `test` | Rejected | PASS |
| `test@` | Rejected | PASS |
| `@gmail.com` | Rejected | PASS |
| `test@gmail` | Rejected | PASS |
| `test@@gmail.com` | Rejected | PASS |
| `test..test@gmail.com` | Rejected | PASS |

For these invalid inputs, the application displayed the message:

> "The email or password are not valid."

**Additional observation:**
An email with spaces before and after the address (` test@gmail.com `) was accepted. This may be intentional input trimming and was not classified as a bug based on the current test.

**Status:** PASS

---

## 4. Librarian / Location-Based Story Retrieval

### TC-LIB-01 — Librarian does not retrieve Stories by saved location

**Setup:**
Created two Stories with the same explicit saved location: `Mom's house`.

**Steps:**
1. Created two Stories associated with `Mom's house`.
2. Asked the Librarian: `What happened in Mom's house?`
3. Repeated the query using the exact location name saved in the Stories.
4. Asked: `Which stories are explicitly associated with Mom's house?`

**Expected:**
The Librarian should prioritize and retrieve the Stories whose stored location is `Mom's house`.

**Actual:**
The Librarian repeatedly returned a different Story that was not one of the two Stories explicitly associated with that location.

The returned Story contains a semantic reference to the location/context in its transcription, including:

> "Sunday mornings the whole house smell like biscuits and coffee before anybody was even dressed, Mama kept the radio on the gospel station..."

However, this Story was not one of the Stories saved with the `Mom's house` location.

**Status:** FAIL

**Priority:** High

**Potential area:** Librarian retrieval / location metadata / semantic search

---

## 5. Librarian / AI Response Reliability

### TC-LIB-02 — Librarian presents a semantically related Story without indicating uncertainty

**Observation:**
The Librarian appears to prioritize semantic similarity in the Story transcription over the explicit location metadata saved with the Stories.

**Expected:**
When an exact metadata match exists, the Librarian should return the matching Stories. When only a semantic or inferred relationship exists, the response should clearly communicate uncertainty, for example by indicating that a Story *might* be related rather than presenting it as a definitive answer.

**Actual:**
The Librarian returned a semantically related Story as the answer to a location-specific question without clearly indicating that the connection was inferred rather than explicitly supported by the saved location metadata.

**Concern:**
This may reduce user trust because the Librarian can blur the distinction between stored facts and AI inference.

**Status:** FAIL / AI Reliability Concern

**Priority:** High

---

## 6. Invite Someone

### INV-01 — Invite Someone functionality not implemented

**Expected:**
The user should be able to invite another person to access or participate in the Archive.

**Actual:**
The inviting flow does not appear to be implemented in the current build and no functional invitation action is available.

**Status:** NOT IMPLEMENTED / N/A FOR CURRENT BUILD

**Note:**
This is recorded separately from functional bugs because the feature appears to be pending implementation rather than malfunctioning.

---

# Weekly Summary

## Passed

- Existing email addresses cannot be registered as duplicate accounts.
- Basic invalid email formats are rejected during Sign In.

## Failed / Issues Found

- Existing Archives are not retrieved after signing out and signing back into the same account.
- There is no apparent Archive-selection flow for choosing an existing Archive or creating a new Archive after Sign In.
- The Librarian does not correctly retrieve Stories based on their saved location metadata.
- The Librarian may present semantically related Stories as definitive answers without communicating uncertainty.

## Not Implemented

- Invite Someone functionality.

## Overall Assessment

The current build successfully handles basic account authentication and invalid email validation, but there are significant issues with Archive persistence/retrieval and Librarian reliability. The Archive issue affects the user's ability to return to previously created family memories, while the Librarian issue affects the reliability of AI-assisted memory retrieval.
