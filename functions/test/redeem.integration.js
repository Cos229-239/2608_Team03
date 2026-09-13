// Runs the real function against the Firestore emulator. Start it with `npm run
// test:emulator` in this folder; it needs the emulators, so it is not part of `npm test`.
//
// Seeds one family the way a phone would leave it, then redeems as a joiner over HTTP the
// way the app does, and checks both what came back and what landed in the database.

const assert = require("node:assert/strict");
const admin = require("firebase-admin");

const PROJECT = process.env.GCLOUD_PROJECT || "demo-arv";
const HOST = process.env.FUNCTIONS_EMULATOR_HOST || "127.0.0.1:5001";
const URL = `http://${HOST}/${PROJECT}/us-central1/redeemInvite`;

admin.initializeApp({ projectId: PROJECT });
const db = admin.firestore();

// The emulator decodes bearer tokens without verifying them, so an unsigned token stands
// in for a real sign-in. Never valid anywhere but the emulator, by construction.
const tokenFor = (uid) => {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  return `${b64({ alg: "none", typ: "JWT" })}.${b64({
    sub: uid, user_id: uid, uid,
    aud: PROJECT, iss: `https://securetoken.google.com/${PROJECT}`,
    iat: now, exp: now + 3600
  })}.`;
};

async function redeem(code, uid) {
  const headers = { "Content-Type": "application/json" };
  if (uid) headers.Authorization = `Bearer ${tokenFor(uid)}`;
  const res = await fetch(URL, { method: "POST", headers, body: JSON.stringify({ data: { code } }) });
  return { http: res.status, body: await res.json() };
}

const status = async (code, uid) => (await redeem(code, uid)).body.result.status;

async function seed() {
  const fam = db.doc("families/fam_1");
  const invite = (code, over = {}) => ({
    code, familyId: "fam_1", createdBy: "u_owner", role: "CONTRIBUTOR", createdAt: 1,
    expiresAt: Date.now() + 14 * 24 * 3600 * 1000, usedAt: null, usedBy: null, revokedAt: null,
    familyName: "Delaney", ...over
  });
  await fam.set({ name: "Delaney", createdBy: "u_owner", createdAt: 1 });
  await fam.collection("members").doc("u_owner").set({
    role: "OWNER", personId: "p_owner", branchRootPersonId: null, ancestorPersonIds: [], joinedAt: 1, invitedBy: null
  });
  await fam.collection("invites").doc("K7M2QX").set(invite("K7M2QX"));
  await fam.collection("invites").doc("EXPRD2").set(invite("EXPRD2", { expiresAt: Date.now() - 1000 }));
  await fam.collection("invites").doc("WTHDR2").set(invite("WTHDR2", { revokedAt: 2 }));
  await fam.collection("invites").doc("NEWCDE").set(invite("NEWCDE"));
}

(async () => {
  await seed();
  let checks = 0;
  const check = (actual, expected, why) => { assert.equal(actual, expected, why); checks++; };

  check((await redeem("k7m-2qx", null)).http, 401, "no account means no standing");
  check(await status("k7m", "u_dana"), "NOT_A_CODE");
  check(await status("ZZZZZZ", "u_dana"), "UNKNOWN");
  check(await status("exprd2", "u_dana"), "EXPIRED");
  check(await status("wthdr2", "u_dana"), "REVOKED");
  check(await status("k7m2qx", "u_owner"), "YOUR_OWN");

  const ok = await redeem("k7m-2qx", "u_dana");
  check(ok.http, 200);
  check(ok.body.result.status, "ACCEPTED");
  check(ok.body.result.familyId, "fam_1");
  check(ok.body.result.familyName, "Delaney");
  check(ok.body.result.role, "CONTRIBUTOR");
  check(ok.body.result.invitedBy, "u_owner");

  const member = (await db.doc("families/fam_1/members/u_dana").get()).data();
  check(member.role, "CONTRIBUTOR", "the member row the joiner could not write for themselves");
  check(member.invitedBy, "u_owner");
  assert.deepEqual(member.ancestorPersonIds, []); checks++;
  const spent = (await db.doc("families/fam_1/invites/K7M2QX").get()).data();
  check(spent.usedBy, "u_dana", "spent, and by whom");
  assert.ok(spent.usedAt > 0); checks++;

  // Spent answers before membership, in the same order as the phone: a spent code is
  // spent whoever is holding it.
  check(await status("k7m-2qx", "u_dana"), "ALREADY_USED");
  check(await status("k7m-2qx", "u_kev"), "ALREADY_USED", "single use, on the server");
  // A member holding a fresh code is told so, and the attempt does not spend the code.
  check(await status("newcde", "u_dana"), "ALREADY_IN_THIS_FAMILY");
  check((await db.doc("families/fam_1/invites/NEWCDE").get()).data().usedAt, null, "not spent");

  console.log(`redeemInvite against the emulator: ${checks} checks passed`);
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
