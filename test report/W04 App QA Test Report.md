# W04 App QA Test Report

**Tester:** Shanik  
**Week:** W04  
**Branch tested:** ShanikPersonal (merged with latest AngelaPersonal)  
**Platform:** Android Studio Emulator

## Testing Approach

I tested the latest build from a user perspective, focusing on family identity, story creation and editing, input validation, recording behavior, filters, and Librarian functionality.

---

# QA Summary

## 🔴 High-Priority Functional / UX Issues

### 1. Recording continues after navigating back without a visible indication

**Action:**  
Started recording audio and pressed the Back button while the recording was still active.

**Actual:**  
The app navigates back, but the audio recording continues in the background.

There is currently no visible indication in the app that recording is still active after navigating away from the recording screen.

**Impact:**  
A user may reasonably assume that leaving the recording screen has stopped the recording, while the app continues recording audio in the background.

**Suggested solutions:**

- Disable the Back button while a recording is active; or
- Allow navigation but display a persistent visual indicator showing that recording is still active and clearly explain how to stop it.

**Result:**  
🔴 High-priority UX / recording-state issue.

---

### 2. Invalid years are accepted without warning

The year field accepts invalid values, including:

- `0`
- Negative years
- Text
- Special characters such as `!!!`

When an invalid negative year is entered and the user saves the story, the story is ultimately displayed as **Unknown**.

**Impact:**  
Although the app prevents the invalid value from appearing as the final saved year, it does not inform the user that the entered value was invalid before saving.

A user could make a typing mistake and save the story without realizing that the date was rejected or converted to **Unknown**.

**Expected:**  
The app should validate the year before saving and display a clear message when the value is invalid.

For example:

> "Please enter a valid year."

The user should then be able to correct the input before saving the story.

**Result:**  
🔴 Functional validation issue.

---

# 🟠 Functional / Implementation Issues

### 3. Current user identity is still unclear

It is still difficult to determine which family member represents the current user.

**Impact:**  
This creates confusion about who is using the app and who is creating or interacting with family stories.

**Suggestion:**  
The current user's profile or identity should be clearly visible somewhere in the app so users always know which family member they are currently acting as.

---

### 4. "Add Family Member" does not respond

The "Add Family Member" button is visible, but tapping it produces no response.

**Result:**  
🟠 Potential unimplemented or disconnected functionality.

---

### 5. "Whole Family" filter does not display additional options

The "Whole Family" control appears to be a dropdown/filter, but tapping it does not display any additional options.

It is currently unclear what the intended functionality of this control is or what the user is expected to filter.

**Result:**  
🟠 Potential unimplemented functionality / unclear interaction.

---

### 6. Audio Play button does not play the story audio

Stories containing audio display a triangular Play button. However, tapping it does not start audio playback.

Instead, the interaction navigates to the Story Details screen.

**Expected:**  
The Play button should start playing the associated audio.

**Actual:**  
The user is taken to the Story Details screen instead.

**Result:**  
🔴 Functional issue.

---

# 🟡 Important Story Management / UX Finding

### 7. Stories should be editable after creation

After creating and saving a new story, I attempted to edit its title but could not find an editing option.

This is an important usability issue because users may make mistakes while entering story information.

At minimum, the story creator/owner should be able to edit relevant metadata after saving, including:

- Title
- Date
- Location
- Tags
- Privacy settings

### Privacy settings should also be editable

Privacy should not necessarily be permanent at the moment a story is created.

For example, a user may initially choose to keep a story private or restrict it from the Librarian and later decide to share it. The opposite situation is also possible: a user may later decide that a previously visible story should become restricted.

The story owner should therefore be able to modify privacy settings after creation.

The app should retain information about which user owns or created each story so appropriate editing permissions can be enforced.

**Result:**  
🟡 Important product / story-management finding.

---

# 🟢 Duplicate Story Behavior

### 8. Stories with identical data are created separately

I tested creating multiple stories with the same data.

**Actual:**  
Each story was created successfully as a separate story. Existing stories were not overwritten.

**Result:**  
🟢 PASS.

**Note:**  
This behavior appears appropriate because family members may have multiple different memories with similar metadata.

---

# 🟢 Positive Findings

## Librarian

The Librarian performed well during the tested flows.

### Privacy works correctly

I marked a story as restricted from the Librarian and then asked a question about that story's year.

The restricted story was not included in the Librarian's response.

**Result:**  
🟢 PASS — Story privacy restrictions appear to be respected.

---

### Year, tag, and title-based retrieval

The Librarian was able to retrieve relevant information based on:

- Years
- Tags
- Story titles

**Result:**  
🟢 PASS.

---

# 🟡 Important Librarian Suggestion: Place-Based Retrieval

The Librarian currently does not appear to use the **Place/Location** field when retrieving memories.

This can become a significant limitation because the same location may appear in many different family stories across different years.

For example, the following questions should produce different results:

> **What happened in Vicksburg?**

and:

> **What happened in Vicksburg in 1953?**

The first question should return relevant stories associated with Vicksburg generally.

The second should narrow the results to stories that occurred in **Vicksburg during 1953**.

If the Librarian does not use the Place field, it cannot reliably distinguish between stories that happened in the same location at different points in time.

**Example:**

- Story A → Place: Vicksburg | Year: 1953
- Story B → Place: Vicksburg | Year: 1980

A user asking about Vicksburg in 1953 should receive Story A rather than Story B.

**Suggestion:**  
The Librarian should index and retrieve information from the Place field and combine it with other metadata such as:

- Year
- Tags
- Title
- Speaker/person

This would make the Librarian significantly more useful for exploring a family archive, where multiple events occurring in the same place across different years are likely to be very common.

**Result:**  
🟡 Important product / retrieval functionality suggestion.

---

# Overall QA Assessment

The Librarian functionality appears to have improved significantly. In particular, privacy restrictions are respected, and the Librarian can retrieve information related to years, tags, and story titles.

The most important issues identified during this QA pass are:

1. **Recording continues after leaving the recording screen without any visible indication.**
2. **Invalid years are accepted without validation or warning before saving.**
3. **The current user's identity remains unclear.**
4. **The audio Play button does not play audio and instead opens Story Details.**
5. **Stories do not appear to have a clear editing flow after creation.**
6. **Story privacy settings should be editable after creation.**
7. **The Librarian should use the Place field to distinguish stories occurring in the same location across different years.**

Other implementation findings include the non-responsive "Add Family Member" button and the "Whole Family" filter, which currently does not display additional options.

The most significant discovery during this QA pass is the recording behavior: leaving the recording screen does not stop the recording, but the user receives no persistent indication that recording is still active. This could easily lead users to believe that recording has stopped when it has not.

The input validation issue is also important. Although invalid negative years are converted to **Unknown**, the app should provide immediate validation before saving so users can correct accidental input errors.