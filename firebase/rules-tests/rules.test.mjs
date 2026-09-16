// Proves firestore.rules and storage.rules say what core/ai/MemoryAccess.kt says.
//
// Run with `npm test` in this folder: it starts the emulators, runs this file against
// them, and stops them. Every test seeds the same family, so each one reads on its own.
//
// The family: u_owner (OWNER), u_keeper (KEEPER), u_contrib (CONTRIBUTOR), u_viewer
// (VIEWER), u_steward (VIEWER, steward of the late Ruth). u_ruth recorded things while
// alive and is no longer a member. u_other owns a different family entirely.

import test, { before, beforeEach, after } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} from '@firebase/rules-unit-testing'
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, collection, getDocs, query, where, writeBatch
} from 'firebase/firestore'
import { ref, uploadBytes, getBytes } from 'firebase/storage'

const PROJECT = 'arv-archive-rules-test'
const FAM = 'fam_1'
const OTHER = 'fam_2'
const BUCKET = `gs://${PROJECT}.appspot.com`

let env

const story = (over = {}) => ({
  familyId: FAM,
  area: 'STORIES',
  visibility: 'FAMILY',
  sharedWithUserIds: [],
  branchRootPersonId: null,
  restricted: false,
  createdBy: 'u_contrib',
  subjectPersonIds: [],
  narratorIds: [],
  aiUsePolicy: 'SUMMARY_OK',
  title: 'A story',
  ...over
})

const member = (role, personId, ancestorPersonIds) => ({
  role, personId, branchRootPersonId: null, ancestorPersonIds, joinedAt: 1, invitedBy: null
})

// The shape InviteEntity.kt writes, with createdBy for the issuer because that is the
// name every other collection in these rules uses for the same idea.
const invite = (code, createdBy, over = {}) => ({
  code, familyId: FAM, createdBy, role: 'CONTRIBUTOR', createdAt: 1,
  expiresAt: Date.now() + 14 * 24 * 3600 * 1000,
  usedAt: null, usedBy: null, revokedAt: null, familyName: 'Delaney', ...over
})

// What a phone writes when it redeems: its own row naming the code, and the code spent
// by that account, in one batch. Exactly what FirebaseInviteRemote.redeem sends.
const rowVia = (code, over = {}) => ({
  role: 'CONTRIBUTOR', personId: null, branchRootPersonId: null, ancestorPersonIds: [],
  joinedAt: 5, invitedBy: 'u_owner', viaCode: code, ...over
})
const join = (uid, code, over = {}) => {
  const db = as(uid)
  const b = writeBatch(db)
  b.set(doc(db, `families/${FAM}/members/${uid}`), rowVia(code, over))
  b.update(doc(db, `invites/${code}`), { usedAt: 5, usedBy: uid })
  return b.commit()
}

async function seed () {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore()
    const b = writeBatch(db)
    b.set(doc(db, `families/${FAM}`), { name: 'Delaney', createdBy: 'u_owner', createdAt: 1 })
    b.set(doc(db, `families/${FAM}/members/u_owner`), member('OWNER', 'p_owner', ['p_owner', 'p_ruth']))
    b.set(doc(db, `families/${FAM}/members/u_keeper`), member('KEEPER', 'p_keeper', ['p_keeper', 'p_ruth']))
    b.set(doc(db, `families/${FAM}/members/u_contrib`), member('CONTRIBUTOR', 'p_contrib', ['p_contrib']))
    b.set(doc(db, `families/${FAM}/members/u_viewer`), member('VIEWER', 'p_viewer', ['p_viewer', 'p_ruth']))
    b.set(doc(db, `families/${FAM}/members/u_steward`), member('VIEWER', 'p_steward', ['p_steward']))
    b.set(doc(db, `families/${FAM}/people/p_ruth`), {
      familyId: FAM, displayName: 'Ruth Delaney', state: 'MEMORIAL', memoryStewardUserId: 'u_steward'
    })
    b.set(doc(db, `families/${FAM}/stories/s_family`), story())
    b.set(doc(db, `families/${FAM}/stories/s_private`), story({ visibility: 'PRIVATE' }))
    b.set(doc(db, `families/${FAM}/stories/s_selected`), story({ visibility: 'SELECTED', sharedWithUserIds: ['u_viewer'] }))
    b.set(doc(db, `families/${FAM}/stories/s_branch`), story({ visibility: 'BRANCH', branchRootPersonId: 'p_ruth', createdBy: 'u_owner' }))
    b.set(doc(db, `families/${FAM}/stories/s_branch_noroot`), story({ visibility: 'BRANCH', branchRootPersonId: null, createdBy: 'u_owner' }))
    b.set(doc(db, `families/${FAM}/stories/s_restricted`), story({ restricted: true, createdBy: 'u_owner' }))
    b.set(doc(db, `families/${FAM}/stories/s_health`), story({ area: 'HEALTH', subjectPersonIds: ['p_viewer'] }))
    b.set(doc(db, `families/${FAM}/stories/s_ruth_private`), story({ visibility: 'PRIVATE', createdBy: 'u_ruth', narratorIds: ['p_ruth'] }))
    b.set(doc(db, `families/${FAM}/assets/a_family`), { ...story(), storyId: 's_family', type: 'AUDIO' })
    b.set(doc(db, `families/${FAM}/assets/a_private`), { ...story({ visibility: 'PRIVATE' }), storyId: 's_private', type: 'AUDIO' })
    b.set(doc(db, `families/${FAM}/transcripts/a_family`), { ...story(), storyId: 's_family', status: 'READY', fullText: 'words' })
    b.set(doc(db, `families/${FAM}/embeddings/e_ok`), { ...story(), storyId: 's_family', text: 'x' })
    b.set(doc(db, `families/${FAM}/embeddings/e_none`), { ...story({ aiUsePolicy: 'NONE' }), storyId: 's_family', text: 'x' })
    b.set(doc(db, 'invites/CODE1'), invite('CODE1', 'u_owner'))
    b.set(doc(db, 'invites/CODEX'), invite('CODEX', 'u_owner', { expiresAt: 1 }))
    b.set(doc(db, 'invites/CODER'), invite('CODER', 'u_owner', { revokedAt: 2 }))
    b.set(doc(db, 'invites/CODES'), invite('CODES', 'u_owner', { usedAt: 3, usedBy: 'u_viewer' }))
    b.set(doc(db, `families/${OTHER}`), { name: 'Other', createdBy: 'u_other', createdAt: 1 })
    b.set(doc(db, `families/${OTHER}/members/u_other`), member('OWNER', 'p_other', ['p_other']))
    b.set(doc(db, `families/${OTHER}/stories/s_other`), story({ familyId: OTHER, createdBy: 'u_other', visibility: 'SELECTED', sharedWithUserIds: ['u_viewer'] }))
    await b.commit()
  })
}

const as = (uid) => env.authenticatedContext(uid).firestore()
const anon = () => env.unauthenticatedContext().firestore()
const S = (id, fam = FAM) => `families/${fam}/stories/${id}`
const stories = (db) => collection(db, `families/${FAM}/stories`)

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8') },
    storage: { rules: readFileSync(new URL('../../storage.rules', import.meta.url), 'utf8') }
  })
})

beforeEach(async () => {
  await env.clearFirestore()
  await env.clearStorage()
  await seed()
})

after(async () => {
  await env.cleanup()
})

// ---------------------------------------------------------------- reading stories

test('nobody signed in reads nothing', async () => {
  await assertFails(getDoc(doc(anon(), S('s_family'))))
})

test('a signed-in stranger is not in the family and reads nothing', async () => {
  await assertFails(getDoc(doc(as('u_other'), S('s_family'))))
})

test('a viewer reads a family story', async () => {
  await assertSucceeds(getDoc(doc(as('u_viewer'), S('s_family'))))
})

test('restricted is keeper-only whatever the visibility', async () => {
  await assertFails(getDoc(doc(as('u_viewer'), S('s_restricted'))))
  await assertFails(getDoc(doc(as('u_contrib'), S('s_restricted'))))
  await assertSucceeds(getDoc(doc(as('u_keeper'), S('s_restricted'))))
})

test('private is the creator alone, and a keeper is not an exception', async () => {
  await assertSucceeds(getDoc(doc(as('u_contrib'), S('s_private'))))
  await assertFails(getDoc(doc(as('u_keeper'), S('s_private'))))
  await assertFails(getDoc(doc(as('u_owner'), S('s_private'))))
})

test('a memorial freezes visibility: the steward does not gain the dead person\'s private memories', async () => {
  await assertFails(getDoc(doc(as('u_steward'), S('s_ruth_private'))))
  await assertFails(getDoc(doc(as('u_owner'), S('s_ruth_private'))))
})

test('selected is readable by those named and by the creator', async () => {
  await assertSucceeds(getDoc(doc(as('u_viewer'), S('s_selected'))))
  await assertSucceeds(getDoc(doc(as('u_contrib'), S('s_selected'))))
  await assertFails(getDoc(doc(as('u_keeper'), S('s_selected'))))
})

test('branch follows the ancestor set on the member row', async () => {
  await assertSucceeds(getDoc(doc(as('u_viewer'), S('s_branch'))))
  await assertFails(getDoc(doc(as('u_contrib'), S('s_branch'))))
})

test('a branch with no root named is unreadable, even by its creator', async () => {
  await assertFails(getDoc(doc(as('u_owner'), S('s_branch_noroot'))))
  await assertFails(getDoc(doc(as('u_viewer'), S('s_branch_noroot'))))
})

test('being named on another family\'s memory does not make you a member of it', async () => {
  await assertFails(getDoc(doc(as('u_viewer'), S('s_other', OTHER))))
})

test('rules are not filters: an unconstrained list is refused, the right shape is allowed', async () => {
  const db = as('u_viewer')
  await assertFails(getDocs(stories(db)))
  await assertSucceeds(getDocs(query(
    stories(db),
    where('familyId', '==', FAM),
    where('visibility', '==', 'FAMILY'),
    where('restricted', '==', false)
  )))
  const mine = as('u_contrib')
  await assertSucceeds(getDocs(query(
    stories(mine),
    where('familyId', '==', FAM),
    where('createdBy', '==', 'u_contrib'),
    where('visibility', '==', 'PRIVATE'),
    where('restricted', '==', false)
  )))
})

// ---------------------------------------------------------------- creating stories

test('a contributor creates a story as themselves', async () => {
  await assertSucceeds(setDoc(doc(as('u_contrib'), S('s_new')), story({ createdBy: 'u_contrib' })))
})

test('a viewer creates nothing', async () => {
  await assertFails(setDoc(doc(as('u_viewer'), S('s_new')), story({ createdBy: 'u_viewer' })))
})

test('a story cannot be created in somebody else\'s name', async () => {
  await assertFails(setDoc(doc(as('u_contrib'), S('s_new')), story({ createdBy: 'u_owner' })))
})

test('a story cannot claim a different family than the one it is filed under', async () => {
  await assertFails(setDoc(doc(as('u_contrib'), S('s_new')), story({ familyId: OTHER })))
})

test('a branch story without a root cannot be created', async () => {
  await assertFails(setDoc(doc(as('u_contrib'), S('s_new')), story({ visibility: 'BRANCH', branchRootPersonId: null })))
})

// ---------------------------------------------------------------- updating stories

test('the creator may widen their own memory', async () => {
  await assertSucceeds(updateDoc(doc(as('u_contrib'), S('s_private')), { visibility: 'FAMILY' }))
})

test('a keeper may narrow but never widen', async () => {
  await assertSucceeds(updateDoc(doc(as('u_keeper'), S('s_family')), { visibility: 'PRIVATE' }))
  await assertFails(updateDoc(doc(as('u_keeper'), S('s_private')), { visibility: 'FAMILY' }))
})

test('adding a reader to a selected memory is widening', async () => {
  await assertFails(updateDoc(doc(as('u_keeper'), S('s_selected')), { sharedWithUserIds: ['u_viewer', 'u_keeper'] }))
  await assertSucceeds(updateDoc(doc(as('u_contrib'), S('s_selected')), { sharedWithUserIds: ['u_viewer', 'u_keeper'] }))
  await assertSucceeds(updateDoc(doc(as('u_keeper'), S('s_selected')), { sharedWithUserIds: [] }))
})

test('restricting is a keeper\'s act', async () => {
  await assertFails(updateDoc(doc(as('u_contrib'), S('s_family')), { restricted: true }))
  await assertSucceeds(updateDoc(doc(as('u_keeper'), S('s_family')), { restricted: true }))
})

test('who made it and which family it is in never change', async () => {
  await assertFails(updateDoc(doc(as('u_contrib'), S('s_family')), { createdBy: 'u_owner' }))
  await assertFails(updateDoc(doc(as('u_owner'), S('s_family')), { familyId: OTHER }))
})

test('a health record follows the person it is about', async () => {
  await assertSucceeds(updateDoc(doc(as('u_viewer'), S('s_health')), { title: 'Corrected by its subject' }))
  await assertSucceeds(updateDoc(doc(as('u_contrib'), S('s_health')), { title: 'Corrected by its recorder' }))
  await assertFails(updateDoc(doc(as('u_keeper'), S('s_health')), { title: 'A role is not standing' }))
  await assertFails(updateDoc(doc(as('u_owner'), S('s_health')), { title: 'Not even the owner' }))
})

test('a viewer edits nothing that is not about them', async () => {
  await assertFails(updateDoc(doc(as('u_viewer'), S('s_family')), { title: 'x' }))
})

// ---------------------------------------------------------------- deleting stories

test('deleting follows editing', async () => {
  await assertSucceeds(deleteDoc(doc(as('u_contrib'), S('s_family'))))
  await assertSucceeds(deleteDoc(doc(as('u_keeper'), S('s_selected'))))
  await assertFails(deleteDoc(doc(as('u_viewer'), S('s_branch'))))
  await assertFails(deleteDoc(doc(as('u_keeper'), S('s_health'))))
})

// ---------------------------------------------------------------- families and members

test('a new account creates a family and its own owner row in one batch', async () => {
  const db = as('u_new')
  const b = writeBatch(db)
  b.set(doc(db, 'families/fam_new'), { name: 'New', createdBy: 'u_new', createdAt: 1 })
  b.set(doc(db, 'families/fam_new/members/u_new'), member('OWNER', 'p_new', ['p_new']))
  await assertSucceeds(b.commit())
})

test('nobody writes themselves an owner row in a family they did not create', async () => {
  await assertFails(setDoc(doc(as('u_new'), `families/${FAM}/members/u_new`), member('OWNER', 'p_new', ['p_new'])))
  await assertFails(setDoc(doc(as('u_viewer'), `families/${FAM}/members/u_viewer2`), member('OWNER', 'p_x', [])))
})

test('the owner writes other members; a keeper does not', async () => {
  await assertSucceeds(setDoc(doc(as('u_owner'), `families/${FAM}/members/u_new`), member('VIEWER', null, [])))
  await assertFails(setDoc(doc(as('u_keeper'), `families/${FAM}/members/u_new`), member('VIEWER', null, [])))
})

test('the owner removes a member; nobody else removes anyone, and nobody removes themselves', async () => {
  await assertFails(deleteDoc(doc(as('u_keeper'), `families/${FAM}/members/u_viewer`)))
  await assertFails(deleteDoc(doc(as('u_contrib'), `families/${FAM}/members/u_contrib`)))
  await assertFails(deleteDoc(doc(as('u_owner'), `families/${FAM}/members/u_owner`)))
  await assertFails(deleteDoc(doc(as('u_other'), `families/${FAM}/members/u_viewer`)))
  await assertSucceeds(deleteDoc(doc(as('u_owner'), `families/${FAM}/members/u_contrib`)))
})

test('a removed member reads nothing of the family afterwards', async () => {
  await assertSucceeds(getDoc(doc(as('u_contrib'), S('s_family'))))
  await assertSucceeds(deleteDoc(doc(as('u_owner'), `families/${FAM}/members/u_contrib`)))
  await assertFails(getDoc(doc(as('u_contrib'), S('s_family'))))
  await assertFails(getDoc(doc(as('u_contrib'), `families/${FAM}/members/u_owner`)))
})

test('nobody promotes themselves or rewrites the ancestor set branch reads', async () => {
  await assertFails(updateDoc(doc(as('u_keeper'), `families/${FAM}/members/u_keeper`), { role: 'OWNER' }))
  await assertFails(updateDoc(doc(as('u_viewer'), `families/${FAM}/members/u_viewer`), { ancestorPersonIds: ['p_viewer', 'p_owner'] }))
  await assertSucceeds(updateDoc(doc(as('u_owner'), `families/${FAM}/members/u_owner`), { personId: 'p_owner' }))
  await assertFails(updateDoc(doc(as('u_owner'), `families/${FAM}/members/u_owner`), { ancestorPersonIds: ['p_owner'] }))
  await assertFails(updateDoc(doc(as('u_owner'), `families/${FAM}/members/u_owner`), { role: 'VIEWER' }))
})

test('members are visible inside the family and nowhere else', async () => {
  await assertSucceeds(getDoc(doc(as('u_viewer'), `families/${FAM}/members/u_owner`)))
  await assertFails(getDoc(doc(as('u_other'), `families/${FAM}/members/u_owner`)))
})

test('the family record is the owner\'s to change and nobody\'s to delete', async () => {
  await assertSucceeds(updateDoc(doc(as('u_owner'), `families/${FAM}`), { name: 'Delaney-Reyes' }))
  await assertFails(updateDoc(doc(as('u_keeper'), `families/${FAM}`), { name: 'x' }))
  await assertFails(updateDoc(doc(as('u_owner'), `families/${FAM}`), { createdBy: 'u_keeper' }))
  await assertFails(deleteDoc(doc(as('u_owner'), `families/${FAM}`)))
})

// ---------------------------------------------------------------- everything else

test('people are added by anyone who can contribute', async () => {
  await assertSucceeds(setDoc(doc(as('u_contrib'), `families/${FAM}/people/p_new`), { familyId: FAM, displayName: 'New' }))
  await assertFails(setDoc(doc(as('u_viewer'), `families/${FAM}/people/p_new`), { familyId: FAM, displayName: 'New' }))
  await assertFails(setDoc(doc(as('u_contrib'), `families/${FAM}/people/p_new`), { familyId: OTHER, displayName: 'New' }))
})

test('invites are written by keepers of the family they open', async () => {
  await assertSucceeds(setDoc(doc(as('u_keeper'), 'invites/CODE2'), invite('CODE2', 'u_keeper')))
  await assertFails(setDoc(doc(as('u_contrib'), 'invites/CODE3'), invite('CODE3', 'u_contrib')))
  await assertFails(setDoc(doc(as('u_other'), 'invites/CODE8'), invite('CODE8', 'u_other')))
})

test('an invite must say who issued it and which code it is, and cannot arrive spent or grant OWNER', async () => {
  await assertFails(setDoc(doc(as('u_keeper'), 'invites/CODE4'), invite('CODE4', 'u_owner')))
  await assertFails(setDoc(doc(as('u_keeper'), 'invites/CODE5'), invite('CODE9', 'u_keeper')))
  await assertFails(setDoc(doc(as('u_keeper'), 'invites/CODE7'), invite('CODE7', 'u_keeper', { role: 'OWNER' })))
  await assertFails(setDoc(doc(as('u_keeper'), 'invites/CODE6'), invite('CODE6', 'u_keeper', { usedAt: 1, usedBy: 'u_keeper' })))
})

test('a code is read by whoever holds it and listed by nobody', async () => {
  await assertSucceeds(getDoc(doc(as('u_new'), 'invites/CODE1')))
  await assertFails(getDocs(collection(as('u_new'), 'invites')))
  await assertFails(getDoc(doc(anon(), 'invites/CODE1')))
})

test('a keeper can withdraw a code but cannot rewrite who issued it or unspend it', async () => {
  await assertSucceeds(updateDoc(doc(as('u_keeper'), 'invites/CODE1'), { revokedAt: 5 }))
  await assertFails(updateDoc(doc(as('u_keeper'), 'invites/CODE1'), { createdBy: 'u_keeper' }))
  await assertFails(updateDoc(doc(as('u_keeper'), 'invites/CODES'), { usedAt: null, usedBy: null }))
  await assertFails(updateDoc(doc(as('u_contrib'), 'invites/CODE1'), { revokedAt: 5 }))
})

test('a joiner admits themselves by spending the code in the same batch, once', async () => {
  await assertSucceeds(join('u_new', 'CODE1'))
  await assertFails(join('u_late', 'CODE1'))
  await assertSucceeds(getDoc(doc(as('u_new'), `families/${FAM}/members/u_new`)))
})

test('neither half of a join is accepted on its own', async () => {
  await assertFails(setDoc(doc(as('u_new'), `families/${FAM}/members/u_new`), rowVia('CODE1')))
  await assertFails(updateDoc(doc(as('u_new'), 'invites/CODE1'), { usedAt: 5, usedBy: 'u_new' }))
})

test('a joiner cannot choose their role, their inviter, their lineage, or somebody else as the spender', async () => {
  await assertFails(join('u_new', 'CODE1', { role: 'KEEPER' }))
  await assertFails(join('u_new', 'CODE1', { role: 'OWNER' }))
  await assertFails(join('u_new', 'CODE1', { invitedBy: 'u_new' }))
  await assertFails(join('u_new', 'CODE1', { ancestorPersonIds: ['p_ruth'] }))
  await assertFails(join('u_new', 'CODE1', { personId: 'p_ruth' }))
  const db = as('u_new'); const b = writeBatch(db)
  b.set(doc(db, `families/${FAM}/members/u_new`), rowVia('CODE1'))
  b.update(doc(db, 'invites/CODE1'), { usedAt: 5, usedBy: 'u_someone_else' })
  await assertFails(b.commit())
})

test('expired, withdrawn, spent and your own code all refuse a join', async () => {
  await assertFails(join('u_new', 'CODEX'))
  await assertFails(join('u_new', 'CODER'))
  await assertFails(join('u_new', 'CODES'))
  await assertFails(join('u_owner', 'CODE1', { invitedBy: 'u_owner' }))
  await assertFails(join('u_new', 'NOSUCH'))
})

test('the librarian index respects the story and the owner\'s NONE', async () => {
  await assertSucceeds(getDoc(doc(as('u_viewer'), `families/${FAM}/embeddings/e_ok`)))
  await assertFails(getDoc(doc(as('u_viewer'), `families/${FAM}/embeddings/e_none`)))
  await assertSucceeds(getDoc(doc(as('u_contrib'), `families/${FAM}/embeddings/e_none`)))
  await assertFails(setDoc(doc(as('u_owner'), `families/${FAM}/embeddings/e_new`), { ...story(), storyId: 's_family', text: 'x' }))
})

test('assets and transcripts read like their story', async () => {
  await assertSucceeds(getDoc(doc(as('u_viewer'), `families/${FAM}/assets/a_family`)))
  await assertFails(getDoc(doc(as('u_keeper'), `families/${FAM}/assets/a_private`)))
  await assertSucceeds(getDoc(doc(as('u_viewer'), `families/${FAM}/transcripts/a_family`)))
  await assertFails(setDoc(doc(as('u_owner'), `families/${FAM}/transcripts/a_new`), { ...story(), storyId: 's_family', status: 'READY' }))
  await assertSucceeds(updateDoc(doc(as('u_contrib'), `families/${FAM}/transcripts/a_family`), { fullText: 'corrected words' }))
  await assertFails(updateDoc(doc(as('u_viewer'), `families/${FAM}/transcripts/a_family`), { fullText: 'x' }))
})

// ---------------------------------------------------------------- storage

const bytes = new Uint8Array([1, 2, 3])
const filePath = `families/${FAM}/assets/a_family/audio.m4a`
const privatePath = `families/${FAM}/assets/a_private/audio.m4a`

test('a contributor uploads the bytes for their own asset; a viewer does not', async () => {
  await assertSucceeds(uploadBytes(ref(env.authenticatedContext('u_contrib').storage(BUCKET), filePath), bytes))
  await assertFails(uploadBytes(ref(env.authenticatedContext('u_viewer').storage(BUCKET), filePath), bytes))
})

test('the file is readable exactly when its asset is', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await uploadBytes(ref(ctx.storage(BUCKET), filePath), bytes)
    await uploadBytes(ref(ctx.storage(BUCKET), privatePath), bytes)
  })
  await assertSucceeds(getBytes(ref(env.authenticatedContext('u_viewer').storage(BUCKET), filePath)))
  await assertFails(getBytes(ref(env.unauthenticatedContext().storage(BUCKET), filePath)))
  await assertFails(getBytes(ref(env.authenticatedContext('u_keeper').storage(BUCKET), privatePath)))
  await assertSucceeds(getBytes(ref(env.authenticatedContext('u_contrib').storage(BUCKET), privatePath)))
})

test('nothing outside a family path is reachable', async () => {
  await assertFails(uploadBytes(ref(env.authenticatedContext('u_owner').storage(BUCKET), 'loose/file.bin'), bytes))
})

assert.ok(true)
