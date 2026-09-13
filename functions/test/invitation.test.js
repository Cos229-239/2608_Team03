// Mirrors app/src/test/java/com/arv/app/core/data/InvitationTest.kt. Same fixtures,
// same cases, same expected answers. Run with `npm test` in this folder; needs nothing
// installed beyond Node.

const test = require("node:test");
const assert = require("node:assert/strict");
const { normalize, decide } = require("../invitation");

const invite = (over = {}) => ({
  code: "K7M2QX",
  familyId: "fam_1",
  createdBy: "u_ruth",
  role: "CONTRIBUTOR",
  createdAt: 1000,
  expiresAt: null,
  usedAt: null,
  usedBy: null,
  revokedAt: null,
  familyName: "Delaney",
  ...over
});

test("normalize accepts every way a code gets written down", () => {
  assert.equal(normalize("k7m-2qx"), "K7M2QX");
  assert.equal(normalize("K7M2QX"), "K7M2QX");
  assert.equal(normalize("k 7 m 2 q x"), "K7M2QX");
});

test("normalize refuses what could never be a code", () => {
  assert.equal(normalize("K7M"), null);
  assert.equal(normalize("K7M2QXA"), null);
  assert.equal(normalize("K7M2Q0"), null, "0 is not in the alphabet");
  assert.equal(normalize("K7M2QI"), null, "nor is I");
  assert.equal(normalize(null), null);
  assert.equal(normalize(undefined), null);
  assert.equal(normalize(42), null);
});

test("a good code is accepted", () => {
  assert.deepEqual(decide(invite(), false, "u_dana", 5000), { status: "ACCEPTED" });
});

test("no such code", () => {
  assert.deepEqual(decide(null, false, "u_dana", 5000), { status: "UNKNOWN" });
});

test("a spent code says so", () => {
  assert.deepEqual(decide(invite({ usedAt: 2000, usedBy: "u_kev" }), false, "u_dana", 5000), { status: "ALREADY_USED" });
});

test("a withdrawn code says so", () => {
  assert.deepEqual(decide(invite({ revokedAt: 3000 }), false, "u_dana", 5000), { status: "REVOKED" });
});

test("a code past its end is expired", () => {
  assert.deepEqual(decide(invite({ expiresAt: 4000 }), false, "u_dana", 5000), { status: "EXPIRED" });
});

test("a code is expired the instant it reaches its end", () => {
  assert.deepEqual(decide(invite({ expiresAt: 5000 }), false, "u_dana", 5000), { status: "EXPIRED" });
});

test("a code minted before codes had an end never runs out", () => {
  assert.deepEqual(decide(invite({ expiresAt: null }), false, "u_dana", Number.MAX_SAFE_INTEGER), { status: "ACCEPTED" });
});

test("being spent is a better answer than having run out", () => {
  assert.deepEqual(decide(invite({ usedAt: 2000, expiresAt: 3000 }), false, "u_dana", 5000), { status: "ALREADY_USED" });
});

test("being withdrawn is a better answer than having run out", () => {
  assert.deepEqual(decide(invite({ revokedAt: 2000, expiresAt: 3000 }), false, "u_dana", 5000), { status: "REVOKED" });
});

test("nobody invites themselves", () => {
  assert.deepEqual(decide(invite({ createdBy: "u_dana" }), false, "u_dana", 5000), { status: "YOUR_OWN" });
});

test("already in the family is not an error, just nothing to do", () => {
  assert.deepEqual(decide(invite(), true, "u_dana", 5000), { status: "ALREADY_IN_THIS_FAMILY" });
});
