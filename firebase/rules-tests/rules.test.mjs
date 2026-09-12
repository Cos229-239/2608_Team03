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
    b.set(doc(db, `families/${FAM}/invites/CODE1`), { createdBy: 'u_owner', role: 'VIEWER', expiresAt: 9, usesLeft: 1 })
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

test('invites are written by keepers and read by nobody', async () => {
  await assertSucceeds(setDoc(doc(as('u_keeper'), `families/${FAM}/invites/CODE2`), { createdBy: 'u_keeper', role: 'VIEWER', expiresAt: 9, usesLeft: 1 }))
  await assertFails(setDoc(doc(as('u_contrib'), `families/${FAM}/invites/CODE3`), { createdBy: 'u_contrib', role: 'VIEWER', expiresAt: 9, usesLeft: 1 }))
  await assertFails(getDoc(doc(as('u_owner'), `families/${FAM}/invites/CODE1`)))
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
