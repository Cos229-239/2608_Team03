// The one thing Arv runs on a server, and the reason it exists: a code read down a phone
// line has to work on the other person's phone, and the other person's phone has never
// seen it. Firestore rules say nobody may read /families/{id}/invites, so a joiner cannot
// look a code up, and they do not know which family to look in anyway. This function can,
// because the Admin SDK is not subject to the rules. It reads the invite, decides exactly
// as the app would have, and writes the member row the joiner is not allowed to write for
// themselves.
//
// Nothing else leaves a phone through here. No recordings, no stories, no people.

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { normalize, decide } = require("./invitation");

initializeApp();

/**
 * Redeems an invitation code for the signed-in account.
 *
 * Input:  { code: string }        anything a person might have typed
 * Output: { status: string, ... } one of NOT_A_CODE, UNKNOWN, REVOKED, ALREADY_USED,
 *                                 EXPIRED, YOUR_OWN, ALREADY_IN_THIS_FAMILY, ACCEPTED.
 *                                 ACCEPTED also carries familyId, familyName, role,
 *                                 invitedBy and joinedAt.
 *
 * Member row and spent code land in one transaction, so two phones typing the same code
 * at the same moment cannot both be admitted. Single use is the whole design.
 */
exports.redeemInvite = onCall({ region: "us-central1" }, async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Sign in first. A standing in a family belongs to an account.");
  }

  const code = normalize(request.data && request.data.code);
  if (!code) return { status: "NOT_A_CODE" };

  const db = getFirestore();
  const matches = await db.collectionGroup("invites").where("code", "==", code).limit(2).get();
  if (matches.empty) return { status: "UNKNOWN" };
  // 887 million codes across every family, so two families minting the same one is
  // possible, and guessing which was meant is not. Refuse rather than admit somebody
  // to the wrong archive.
  if (matches.size > 1) return { status: "UNKNOWN" };

  const inviteRef = matches.docs[0].ref;
  const familyRef = inviteRef.parent.parent;
  const memberRef = familyRef.collection("members").doc(uid);
  const now = Date.now();

  return db.runTransaction(async (tx) => {
    const [inviteSnap, memberSnap] = await Promise.all([tx.get(inviteRef), tx.get(memberRef)]);
    const invite = inviteSnap.exists ? inviteSnap.data() : null;
    const result = decide(invite, memberSnap.exists, uid, now);
    if (result.status !== "ACCEPTED") return result;

    // The joiner has a standing and no profile. personId and the ancestor set stay empty
    // until somebody places them in the tree, which makes BRANCH material fail closed on
    // the server exactly as it does on the phone.
    tx.set(memberRef, {
      role: invite.role,
      personId: null,
      branchRootPersonId: null,
      ancestorPersonIds: [],
      joinedAt: now,
      invitedBy: invite.createdBy
    });
    tx.update(inviteRef, { usedAt: now, usedBy: uid });

    return {
      status: "ACCEPTED",
      familyId: familyRef.id,
      familyName: invite.familyName == null ? null : invite.familyName,
      role: invite.role,
      invitedBy: invite.createdBy,
      joinedAt: now
    };
  });
});
