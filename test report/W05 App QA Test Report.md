# Weekly Tester Report

## App: Family Memory Preservation

### Tester: Shanik

### Test Area: Account Persistence, Archive Management, Invitation Flow

---

## 1. Sign In / Account Persistence

### TC-SI-01 — Existing account recovers previously created Archives

**Previous issue:**

During the previous testing session, an existing account did not properly recover the Archives associated with it after signing out and signing back in.

**Retest:**

1. Signed into an existing account using the previously registered email and password.
2. Verified the flow presented after authentication.
3. Checked the Archives associated with the account.
4. Verified the options available to the user.

**Expected:**

After signing in with an existing account, the application should display the Archives associated with that account and allow the user to either open an existing Archive or create a new one.

**Actual:**

The previous issue has been resolved.

After signing in, the application now correctly presents the Archives associated with the account. The user can select an existing Archive to enter or choose to create a new Archive.

**Status:** PASS — FIX VERIFIED

**Tester note:**

The Archive persistence flow now behaves as expected and clearly separates returning to an existing Archive from creating a new one.

---

## 2. Invite Someone / Invitation Flow

### INV-01 — Invitation functionality implemented and functional

**Previous issue:**

During the previous testing session, the Invite Someone functionality had not yet been implemented.

**Retest:**

1. User 1 created a family Archive.
2. User 1 opened the family/member section and generated an invitation code.
3. User 2 created or signed into a separate account.
4. User 2 selected the option to join using an invitation code.
5. User 2 entered the invitation code.
6. User 2 entered the Archive and synchronized the Archive data.
7. Verified access to the Archive, Stories, and family/member information.

**Expected:**

The invitation code should allow a new user to join the existing Archive and access its shared content.

**Actual:**

The invitation functionality has now been implemented and is working correctly. The invited user can use the invitation code, enter the Archive, synchronize its data, and access the Archive's Stories and members.

**Status:** PASS — FUNCTIONALITY VERIFIED

---

### Final Flow Observation — Member Identification

Although the main invitation functionality is working, one final detail was identified during testing of the complete flow.

**Scenario:**

A new user is invited to an existing family Archive using an invitation code.

**Steps:**

1. User 1 creates a family Archive.
2. User 1 opens the family/member section and generates an invitation code for a new member.
3. User 2 creates or signs into their own account using an email address and password.
4. User 2 selects the option to join using an invitation code.
5. User 2 enters the invitation code.
6. User 2 successfully enters the Archive.
7. User 2 synchronizes the Archive data.
8. The family/member section is opened to inspect the members associated with the Archive.

**Expected:**

The invitation flow should not only grant access to the Archive, but should also establish the identity of the new family member being invited.

For example, after entering the invitation code, the application could ask the new user for the information needed to identify them, such as their name, and then associate that family-member record with their account.

The expected flow would therefore be approximately:

**New account → Invitation code → Identify new member → Add member to Archive**

After completing the process, the newly invited person should appear as a member of the Archive and remain associated with that account when the user signs in from another device.

**Actual:**

The invited user successfully receives access to the Archive. After synchronization, the user can see the existing family members and Stories.

However, the invitation flow never asks the new user for their name or otherwise clearly identifies which family member they represent.

This means that the application successfully establishes **Archive access**, but it is unclear whether the invitation has also established **a new family-member identity**.

**Additional observation:**

This also creates a distinction that should remain clear between two different use cases:

**New family member:**

**New account → Invitation code → Identify member → Join Archive**

**Existing family member using another device:**

**Existing account → Sign in → Recover existing Archive membership**

An existing member accessing their account from another device should not need to use an invitation code or be added as a second family member. Their existing Archive memberships should already be associated with their account and restored after login.

**Concern:**

The current behavior makes it unclear what the invitation code represents after the user successfully joins the Archive: whether it is intended specifically to create/associate a new family member, or whether it is primarily being used to grant an account access to an existing Archive.

Clarifying this final step would make the invitation flow more complete and make the distinction between **inviting a new person** and **accessing an existing account from another device** clearer.

**Status:** FLOW DETAIL / FOLLOW-UP

**Priority:** Medium

**Tester note:**

This does not prevent the invited user from accessing the Archive, synchronizing the data, or viewing Stories and family information. The core invitation functionality is therefore working.

This appears to be a small final refinement to the flow rather than a major functional issue. Adding a clear member-identification step would make the relationship between the invited account and the family-member record explicit.

---

# Weekly Summary

## Fixes Verified

* Existing accounts now correctly recover their associated Archives after signing in.
* Users can now choose between entering an existing Archive or creating a new one.
* The previously unimplemented Invite Someone functionality is now working.
* Invited users can successfully join, synchronize, and access the Archive.

## Remaining Observation

* The invitation flow could use one additional step to identify and associate the newly invited user with a specific family member.

## Overall Assessment

This week's testing focused on retesting the issues identified during the previous testing session and verifying the new invitation functionality.

The previously reported Archive persistence issue has been resolved, and the Invite Someone feature has now been implemented and successfully tested. The application is performing well across the flows tested this week.

The only remaining observation is a small detail in the final invitation flow regarding the identification of the new family member. Since the core invitation, Archive access, and synchronization functionality are working correctly, this appears to be a straightforward final refinement.

Overall, it was very positive to see the application reaching a much more complete and stable state by the end of the testing period.

