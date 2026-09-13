// The decision, kept pure so it can be tested without Firestore and read next to
// app/src/main/java/com/arv/app/core/data/Invitation.kt, which it mirrors line for line
// and in the same order. If the two ever disagree, a code is accepted on one phone and
// refused on another, and the person holding it has no way to know which to believe.

/** Deliberately missing 0, O, 1, I and L. See InviteCode.kt for why. */
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const LENGTH = 6;
const CANONICAL = new RegExp(`^[${ALPHABET}]{${LENGTH}}$`);

/**
 * What the person typed, turned into what we can look up, or null if it could never be a
 * code. Case and separators are ignored on the way in, because the person typing it did
 * not choose how it was written down.
 */
function normalize(input) {
  if (typeof input !== "string") return null;
  const stripped = input.toUpperCase().replace(/[^A-Z0-9]/g, "");
  return CANONICAL.test(stripped) ? stripped : null;
}

/**
 * Seven answers, never "invalid". Each one tells the person what to do next.
 *
 * Withdrawn and spent are checked before expiry on purpose: those name something a person
 * did, and saying which is more use than saying time passed.
 *
 * @param {object|null} invite    the invite document, or null when no such code exists
 * @param {boolean} alreadyMember whether this account already has a standing in the family
 * @param {string} uid            the account redeeming
 * @param {number} nowMillis      the clock, passed in so the decision is testable
 */
function decide(invite, alreadyMember, uid, nowMillis) {
  if (!invite) return { status: "UNKNOWN" };
  if (invite.revokedAt != null) return { status: "REVOKED" };
  if (invite.usedAt != null) return { status: "ALREADY_USED" };
  if (invite.expiresAt != null && nowMillis >= invite.expiresAt) return { status: "EXPIRED" };
  if (invite.createdBy === uid) return { status: "YOUR_OWN" };
  if (alreadyMember) return { status: "ALREADY_IN_THIS_FAMILY" };
  return { status: "ACCEPTED" };
}

module.exports = { normalize, decide, ALPHABET, LENGTH };
