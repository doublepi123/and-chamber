'use strict';

// Flow logic for the lifecycle gate (B3 mode/tab switching, B5 enlarge/restore scroll memory).
// The device side is createLifecycleGateAdapters() in verify_openchamber_provider_emulator.cjs.
// Every value used here is an awaited, normalised reply: tool results, lifecycle-diag fields, page JS
// metrics (__report/__geometry/__loads/__posts) and real status phases. Nothing is hard-coded to pass.
// A missing producer field is null and refuses the case; it is never coerced to false or to a pass.

const STAGE_MS = 20000;
const RESTORE_TOLERANCE_CSS = 1;

function isNum(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

/** Restore is Restored only inside the 1 CSS px tolerance. A clamped readback is never a restored one. */
function restoredWithin(saved, actual) {
  if (!saved || !actual || !isNum(saved.y) || !isNum(actual.y)) return null;
  return Math.abs(actual.y - saved.y) <= RESTORE_TOLERANCE_CSS;
}

/**
 * Same document means the same tab, the same native view handle, and the page's own performance.timeOrigin.
 * The presenter's global documentEpoch is not used here: revokeAll/select advance it without a new page.
 * Any missing identity field makes the answer null, which never passes.
 */
function sameDocument(a, b) {
  if (!a || !b) return null;
  if (a.selectedTabId == null || b.selectedTabId == null) return null;
  if (a.viewHandle == null || b.viewHandle == null) return null;
  if (a.timeOrigin == null || b.timeOrigin == null) return null;
  return a.selectedTabId === b.selectedTabId && a.viewHandle === b.viewHandle && a.timeOrigin === b.timeOrigin;
}

function brief(result) {
  if (!result) return null;
  return { ok: result.ok === true, code: result.code || '', detail: String(result.detail || '').slice(0, 240) };
}

/** Refused state: a call returned an error, a missing field, or the case fence. Never a success. */
function fenced(result) {
  return Boolean(result) && result.code === 'FENCED';
}

/**
 * Each wait stage has its own budget (STAGE_MS). The whole scene has a separate, explicit budget owned by the
 * runner. A stage records its start, end, elapsed time, the number of reads and the last complete snapshot, so a
 * timeout can state which stage stopped and what the page looked like then. No secret or command text is kept.
 */
async function waitFor(deps, predicate, trace, label, budgetMs = STAGE_MS) {
  const started = deps.now();
  const reads = { count: 0 };
  const stage = { label, startedMs: started, endedMs: null, elapsedMs: null, reads: 0, budgetMs, outcome: null, last: null };
  trace.push({ stage: label, event: 'start', at: started });
  while (deps.now() - started < budgetMs) {
    const value = await deps.readLifecycle();
    reads.count += 1;
    trace.push(`wait:${label}`);
    stage.reads = reads.count;
    if (value) stage.last = summary(value);
    if (predicate(value)) {
      stage.endedMs = deps.now();
      stage.elapsedMs = stage.endedMs - started;
      stage.outcome = 'matched';
      trace.push({ stage: label, event: 'end', outcome: 'matched', elapsedMs: stage.elapsedMs, reads: reads.count });
      deps.stages && deps.stages.push(stage);
      return value;
    }
    await deps.sleep(250);
  }
  stage.endedMs = deps.now();
  stage.elapsedMs = stage.endedMs - started;
  stage.outcome = 'budget-exhausted';
  trace.push({ stage: label, event: 'end', outcome: 'budget-exhausted', elapsedMs: stage.elapsedMs, reads: reads.count });
  deps.stages && deps.stages.push(stage);
  return null;
}

/** Field summary of a complete read. Only producer and page facts; never a token, command or raw text. */
function summary(value) {
  return {
    selectedTabId: value.selectedTabId,
    viewHandle: value.viewHandle,
    timeOrigin: value.timeOrigin,
    enlarged: value.enlarged,
    surface: value.surface,
    restore: value.restore,
    sy: value.metrics ? value.metrics.sy : null,
    loads: value.loads,
    grantForSelected: value.grantForSelected,
    pageReady: value.pageReady,
    // Diagnosis only: the B5 target observation travels with the snapshot. No pass predicate reads it.
    restoreTarget: value.restoreTarget === undefined ? null : value.restoreTarget,
  };
}

/**
 * A restore result is accepted only when it belongs to this cycle: the window is small again (Restore gone,
 * Enlarge visible), the surface is back, and the restore object differs from the one that existed before
 * Enlarge. A stale result from an earlier cycle is never taken as this cycle's answer.
 */
function freshRestore(value, priorRestore) {
  if (!value || !value.restore || !value.restore.kind) return false;
  if (value.enlarged !== false) return false;
  if (!value.surface) return false;
  // The window cycle clears the previous result (restore === null) while enlarged. A result is fresh only
  // after that clear has been observed in this cycle, so an identical legitimate coordinate is not refused.
  return value.clearedThisCycle === true;
}

function stop(trace, reason, evidence = {}) {
  return { ok: false, trace, reason, ...evidence };
}

/** Carries the observed clear of this cycle onto a reading. The clear itself was checked before Restore. */
function withClearedFlag(value, cleared) {
  return value ? { ...value, clearedThisCycle: cleared === true } : value;
}

/**
 * B5-1: long page scrolled to a real non-zero offset, Enlarge once, Restore once. The restore kind must be
 * Restored (not Clamped), and the actual scrollY must return to the saved one within 1 CSS px. Document,
 * load count and native view handle must not change.
 */
async function runEnlargeRestoreSameDoc(deps) {
  const trace = [];
  // The runner passes its own stage list, so a cut flow still leaves every finished stage in the record.
  const stages = Array.isArray(deps.stages) ? deps.stages : [];
  const flowDeps = { ...deps, stages };
  const withStages = (extra) => ({ stages, ...extra });
  // The offset saved for this cycle is the one present at Enlarge. The setup already produced a non-zero offset.
  const saved = await readStage(flowDeps, trace, stages, 'before-read');
  if (!saved || !isNum(saved.metrics && saved.metrics.sy) || saved.metrics.sy <= 0) return stop(trace, 'precondition-no-scroll', withStages({ saved: saved && summary(saved) }));
  const priorRestore = saved.restore;
  const enlarged = await tapStage(flowDeps, trace, stages, 'tap-enlarge', () => deps.tapEnlarge());
  if (!enlarged.ok) return stop(trace, 'enlarge-tap-failed', withStages({ enlarged, saved: summary(saved) }));
  const expanded = await waitFor(flowDeps, (value) => value && value.enlarged === true, trace, 'enlarged');
  if (!expanded) return stop(trace, 'enlarge-not-observed', withStages({ saved: summary(saved) }));
  // The window cycle clears the previous restore result. Observe that clear before taking any new result.
  if (expanded.restore !== null) return stop(trace, 'restore-not-cleared-on-enlarge', withStages({ restore: expanded.restore, saved: summary(saved), expanded: summary(expanded) }));
  const restored = await tapStage(flowDeps, trace, stages, 'tap-restore', () => deps.tapRestore());
  if (!restored.ok) return stop(trace, 'restore-tap-failed', withStages({ restored, saved: summary(saved), expanded: summary(expanded) }));
  const result = await waitFor(flowDeps, (value) => freshRestore(withClearedFlag(value, true), priorRestore), trace, 'restore-fresh');
  if (!result) return stop(trace, 'restore-result-not-fresh', withStages({ saved: summary(saved), expanded: summary(expanded) }));
  const after = await readStage(flowDeps, trace, stages, 'after-read');
  const sameDoc = sameDocument(saved, after);
  const actualY = result.restore.actual ? result.restore.actual.y : null;
  // Restored only when the producer's readback matches the saved offset within 1 CSS px. Clamped never counts.
  const pass = result.restore.kind === 'Restored' &&
    restoredWithin({ y: saved.metrics.sy }, { y: actualY }) === true &&
    isNum(after.metrics && after.metrics.sy) && restoredWithin({ y: saved.metrics.sy }, { y: after.metrics.sy }) === true &&
    sameDoc === true && after.loads === saved.loads && after.posts === saved.posts &&
    saved.grantForSelected === after.grantForSelected && saved.grantForSelected != null;
  return withStages({
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'enlarge-restore-same-doc-failed',
    saved: { sy: saved.metrics.sy, loads: saved.loads, viewHandle: saved.viewHandle, timeOrigin: saved.timeOrigin, witness: saved.witness },
    expanded: { enlarged: expanded.enlarged, restore: expanded.restore, sy: expanded.metrics && expanded.metrics.sy, surface: expanded.surface },
    restore: result.restore,
    after: { sy: after.metrics && after.metrics.sy, loads: after.loads, viewHandle: after.viewHandle, timeOrigin: after.timeOrigin, witness: after.witness, enlarged: after.enlarged, surface: after.surface },
    sameDoc,
  });
}

/** A read stage: one complete lifecycle read, timed and recorded. Stage names are fixed; nothing else is logged. */
async function readStage(deps, trace, stages, label) {
  const started = deps.now();
  const value = await deps.readLifecycle();
  const endedMs = deps.now();
  stages.push({ label, startedMs: started, endedMs, elapsedMs: endedMs - started, outcome: value ? 'read' : 'null', last: value ? summary(value) : null });
  trace.push({ stage: label, event: 'read', elapsedMs: endedMs - started, present: Boolean(value) });
  return value;
}

/** A tap stage: one UI tap, timed and recorded. It is called exactly once and never retried. */
async function tapStage(deps, trace, stages, label, action) {
  const started = deps.now();
  const result = await action();
  const endedMs = deps.now();
  stages.push({ label, startedMs: started, endedMs, elapsedMs: endedMs - started, outcome: result && result.ok ? 'sent' : 'refused', code: result && result.code ? result.code : null });
  trace.push({ stage: label, event: 'tap', elapsedMs: endedMs - started, ok: Boolean(result && result.ok) });
  return result;
}

/**
 * B5-2: old offset saved, Enlarge, then a real two-finger scroll chooses a new Y that differs from the saved
 * one and is still inside the small range. Restore must not return to the old offset. The restore result
 * must be Discarded (user moved) or an explicit unchanged user position, and the actual scrollY must be the
 * new user position.
 */
async function runUserScrollWins(deps) {
  const trace = [];
  const stages = Array.isArray(deps.stages) ? deps.stages : [];
  const withStages = (extra) => ({ stages, ...extra });
  const saved = await readStage(deps, trace, stages, 'before-read');
  if (!saved || !isNum(saved.metrics && saved.metrics.sy) || saved.metrics.sy <= 0) return stop(trace, 'precondition-no-scroll', withStages({ saved: saved && summary(saved) }));
  const priorRestore = saved.restore;
  const enlarged = await tapStage(deps, trace, stages, 'tap-enlarge', () => deps.tapEnlarge());
  if (!enlarged.ok) return stop(trace, 'enlarge-tap-failed', withStages({ enlarged, saved: summary(saved) }));
  const expanded = await waitFor(deps, (value) => value && value.enlarged === true, trace, 'enlarged');
  if (!expanded) return stop(trace, 'enlarge-not-observed', withStages({ saved: summary(saved) }));
  // The window cycle must clear the previous result, and it is observed here, not inferred. Same rule as B5-1.
  if (expanded.restore !== null) return stop(trace, 'restore-not-cleared-on-enlarge', withStages({ restore: expanded.restore, saved: summary(saved), expanded: summary(expanded) }));
  // A real two-finger move on the enlarged page. The move is measured against the enlarged Y, not the saved one:
  // Enlarge may already have clamped the page, so a no-op wheel must not count as a user position.
  const userScroll = await tapStage(deps, trace, stages, 'user-scroll', () => deps.twoFingerScroll({ dy: 120 }));
  if (!userScroll.ok) return stop(trace, 'user-scroll-failed', withStages({ userScroll, saved: summary(saved), expanded: summary(expanded) }));
  const movedUser = await readStage(deps, trace, stages, 'after-user-scroll-read');
  if (!movedUser || !isNum(movedUser.metrics && movedUser.metrics.sy)) return stop(trace, 'user-scroll-unreadable', withStages({ saved: summary(saved), expanded: summary(expanded) }));
  const expandedY = expanded.metrics && expanded.metrics.sy;
  const userScrollDidMove = isNum(expandedY) && movedUser.metrics.sy !== expandedY;
  if (!userScrollDidMove) return stop(trace, 'user-scroll-did-not-move', withStages({ saved: summary(saved), expanded: summary(expanded), movedUser: summary(movedUser) }));
  // The user Y must fit the small range too. Otherwise a natural clamp could look like an applied saved offset.
  const smallRange = deps.smallRangeMax;
  if (isNum(smallRange) && movedUser.metrics.sy > smallRange) return stop(trace, 'user-y-outside-small-range', withStages({ userY: movedUser.metrics.sy, smallRange }));
  const restored = await tapStage(deps, trace, stages, 'tap-restore', () => deps.tapRestore());
  if (!restored.ok) return stop(trace, 'restore-tap-failed', withStages({ restored, saved: summary(saved), movedUser: summary(movedUser) }));
  const result = await waitFor(deps, (value) => freshRestore(withClearedFlag(value, true), priorRestore), trace, 'restore-fresh');
  if (!result) return stop(trace, 'restore-result-not-fresh', withStages({ saved: summary(saved), expanded: summary(expanded), movedUser: summary(movedUser) }));
  const after = await readStage(deps, trace, stages, 'after-read');
  const notOld = isNum(after.metrics && after.metrics.sy) && Math.abs(after.metrics.sy - saved.metrics.sy) > RESTORE_TOLERANCE_CSS;
  const userKept = isNum(after.metrics && after.metrics.sy) && Math.abs(after.metrics.sy - movedUser.metrics.sy) <= RESTORE_TOLERANCE_CSS;
  const identity = sameDocument(saved, after) === true && after.loads === saved.loads;
  // The one accepted outcome is the intentional drop of a moved user position: NotSaved(INTERACTION_CHANGED).
  // Every other NotSaved reason, Restored, Clamped, Discarded or an unknown kind fails. The reason is the exact one
  // ScrollMemory produces for a user interaction, so a coincidental position alone can never pass.
  const acceptedRestore = result.restore.kind === 'NotSaved' && result.restore.reason === 'INTERACTION_CHANGED';
  const pass = acceptedRestore && notOld && userKept && identity;
  return withStages({
    ok: pass,
    trace,
    reason: pass ? 'completed' : (acceptedRestore ? 'user-scroll-wins-failed' : 'user-scroll-restore-unexpected-kind'),
    saved: { sy: saved.metrics.sy, loads: saved.loads, viewHandle: saved.viewHandle, timeOrigin: saved.timeOrigin },
    expanded: { enlarged: expanded.enlarged, restore: expanded.restore, sy: expandedY },
    userMoved: { sy: movedUser.metrics.sy, deltaFromExpanded: movedUser.metrics.sy - expandedY },
    restore: result.restore,
    after: { sy: after.metrics && after.metrics.sy, loads: after.loads, viewHandle: after.viewHandle, timeOrigin: after.timeOrigin },
    checks: { acceptedRestore, notOld, userKept, identity },
  });
}

/**
 * B5-3: a page is enlarged and its offset saved, then a real document change happens (browser.open of a
 * different tab on the same owner). Restore must not apply the old saved offset to the new document.
 */
async function runDocumentChangeDiscards(deps) {
  const trace = [];
  const saved = await deps.readLifecycle();
  if (!saved || !isNum(saved.metrics && saved.metrics.sy) || saved.metrics.sy <= 0) return stop(trace, 'precondition-no-scroll', { saved });
  const priorRestore = saved.restore;
  const enlarged = await deps.tapEnlarge();
  if (!enlarged.ok) return stop(trace, 'enlarge-tap-failed', { enlarged });
  const expanded = await waitFor(deps, (value) => value && value.enlarged === true, trace, 'enlarged');
  if (!expanded) return stop(trace, 'enlarge-not-observed');
  // Same rule as the same-document flow: the previous result must be cleared by this window cycle first.
  if (expanded.restore !== null) return stop(trace, 'restore-not-cleared-on-enlarge', { restore: expanded.restore });
  // A real document change on the same owner. Its own identity must differ from the saved document.
  const opened = await deps.openNewDocument();
  if (!opened.ok) return stop(trace, 'open-new-document-failed', { opened });
  const restored = await deps.tapRestore();
  if (!restored.ok) return stop(trace, 'restore-tap-failed', { restored });
  const result = await waitFor(deps, (value) => freshRestore(withClearedFlag(value, true), priorRestore), trace, 'restore-fresh');
  if (!result) return stop(trace, 'restore-result-not-fresh');
  const after = await deps.readLifecycle();
  // The new document must be a different page identity (tab, native handle or timeOrigin). Unknown is not a pass.
  const identity = sameDocument(saved, after);
  const changedDoc = identity === false;
  // The old saved offset must never be applied to the new document. Discarded or NotSaved is the honest answer.
  const kindOk = result.restore.kind === 'Discarded' || result.restore.kind === 'NotSaved';
  const notApplied = isNum(after.metrics && after.metrics.sy) && Math.abs(after.metrics.sy - saved.metrics.sy) > RESTORE_TOLERANCE_CSS;
  const pass = changedDoc && kindOk && notApplied;
  return {
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'document-change-failed',
    saved: { sy: saved.metrics.sy, timeOrigin: saved.timeOrigin, viewHandle: saved.viewHandle },
    restore: result.restore,
    after: { sy: after.metrics && after.metrics.sy, timeOrigin: after.timeOrigin, viewHandle: after.viewHandle },
    changedDoc,
  };
}

/**
 * B3: desktop switch and tab round-trip. A (mobile) -> desktop via explicit Computer layout + Confirm ->
 * open B -> select B -> select A. A must keep its UA, root viewport, witness, loads and native handle,
 * with no reconfigure after select. The reverse (desktop -> mobile) is the same shape.
 */
async function runTabRoundTripMode(deps) {
  const trace = [];
  const a0 = await deps.readLifecycle();
  if (!a0 || a0.pageReady !== true) return stop(trace, 'precondition-not-ready', { a0 });
  // The case names the direction. An already-desktop page cannot be switched "to desktop" again.
  const target = deps.direction;
  if (target !== 'computer' && target !== 'phone') return stop(trace, 'direction-unknown', { direction: target });
  const expectDesktop = target === 'computer';
  if (a0.desktopMode !== !expectDesktop) return stop(trace, 'precondition-wrong-start-mode', { desktopMode: a0.desktopMode, target });
  const switched = await deps.switchMode(target);
  if (!switched.ok) return stop(trace, 'mode-switch-failed', { switched });
  const a1 = await waitFor(deps, (value) => value && value.pageReady === true && value.desktopMode === expectDesktop, trace, 'mode-switched');
  if (!a1) return stop(trace, 'mode-not-observed', { a0 });
  const tabA = a1.selectedTabId;
  if (!tabA) return stop(trace, 'tab-a-unknown', { a1 });
  const b = await deps.openOtherTab();
  if (!b.ok) return stop(trace, 'open-b-failed', { b });
  if (!b.tabId || b.tabId === tabA) return stop(trace, 'tab-b-not-distinct', { b });
  const selectedB = await deps.selectTab(b.tabId);
  if (!selectedB.ok) return stop(trace, 'select-b-failed', { selectedB });
  const selectedA = await deps.selectTab(tabA);
  if (!selectedA.ok) return stop(trace, 'select-a-failed', { selectedA });
  const a2 = await waitFor(deps, (value) => value && value.selectedTabId === tabA && value.pageReady === true, trace, 'a-back');
  if (!a2) return stop(trace, 'a-not-back', { tabA });
  // Observable mode contract only: UA, root clientWidth and the media query, on the same native handle and document.
  const uaSame = a2.metrics && a1.metrics && a2.metrics.ua === a1.metrics.ua;
  const widthSame = a2.metrics && a1.metrics && a2.metrics.clientWidth === a1.metrics.clientWidth;
  const mediaSame = a2.metrics && a1.metrics && a2.metrics.media1000 === a1.metrics.media1000;
  const uaMatchesTarget = a2.metrics && a2.metrics.ua === (expectDesktop ? 'desktop' : 'mobile');
  const handleSame = a2.viewHandle != null && a2.viewHandle === a1.viewHandle;
  const sameDoc = sameDocument(a1, a2);
  const loadsSame = a2.loads != null && a2.loads === a1.loads;
  const postsSame = a2.posts != null && a2.posts === a1.posts;
  const arrivalsSame = a2.postArrivals != null && a2.postArrivals === a1.postArrivals;
  const agentOff = a2.agentOn === false;
  const pass = Boolean(uaSame && widthSame && mediaSame && uaMatchesTarget && handleSame && sameDoc === true &&
    loadsSame && postsSame && arrivalsSame && agentOff);
  return {
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'tab-roundtrip-mode-failed',
    direction: target,
    a1: { ua: a1.metrics && a1.metrics.ua, clientWidth: a1.metrics && a1.metrics.clientWidth, viewHandle: a1.viewHandle, timeOrigin: a1.timeOrigin, loads: a1.loads, posts: a1.posts, postArrivals: a1.postArrivals },
    a2: { ua: a2.metrics && a2.metrics.ua, clientWidth: a2.metrics && a2.metrics.clientWidth, viewHandle: a2.viewHandle, timeOrigin: a2.timeOrigin, loads: a2.loads, posts: a2.posts, postArrivals: a2.postArrivals, agentOn: a2.agentOn },
    // modeSeq is not produced by the contract. It is reported as unavailable and is not used as proof.
    modeSeq: 'unavailable',
    sameDoc,
  };
}

/**
 * B3 POST variant: a submitted login POST lands on a welcome page (posts +1, arrival +1). Then A -> B -> A.
 * The POST page must keep its UA, witness, loads, posts and arrival count, and no GET is issued to fix it.
 */
async function runPostRoundTripMode(deps) {
  const trace = [];
  // The POST baseline is this case's own submit (returned by the real humanSubmit in setup), never a0 or an old cookie.
  const baseline = deps.baseline;
  if (!baseline || !isNum(baseline.beforePosts) || !isNum(baseline.beforeArrivals)) {
    return stop(trace, 'post-baseline-missing', { baseline: baseline || null });
  }
  const landing = baseline.finalState && baseline.finalState.page ? baseline.finalState.page : null;
  const landed = await waitFor(deps, (value) => value && value.posts === baseline.beforePosts + 1 &&
    value.postArrivals === baseline.beforeArrivals + 1 && value.pageReady === true, trace, 'post-landed');
  if (!landed) return stop(trace, 'post-not-landed', { beforePosts: baseline.beforePosts, beforeArrivals: baseline.beforeArrivals, landing });
  const tabPost = landed.selectedTabId;
  if (!tabPost) return stop(trace, 'post-tab-unknown', { landed });
  const b = await deps.openOtherTab();
  if (!b.ok || !b.tabId || b.tabId === tabPost) return stop(trace, 'open-b-failed', { b });
  if (!(await deps.selectTab(b.tabId)).ok) return stop(trace, 'select-b-failed');
  if (!(await deps.selectTab(tabPost)).ok) return stop(trace, 'select-post-failed');
  const after = await waitFor(deps, (value) => value && value.selectedTabId === tabPost && value.pageReady === true, trace, 'post-back');
  if (!after) return stop(trace, 'post-not-back', { tabPost });
  // POST contract: UA, same document, loads/posts/arrivals unchanged, and the baseline +1 counts. Agent state is
  // reported but is not a POST condition (the B3 agent-off rule belongs to the explicit mode switch).
  const sameUa = after.metrics && landed.metrics && after.metrics.ua === landed.metrics.ua;
  const pass = Boolean(sameUa) && sameDocument(landed, after) === true && after.loads === landed.loads &&
    after.posts === landed.posts && after.postArrivals === landed.postArrivals &&
    after.posts === baseline.beforePosts + 1 && after.postArrivals === baseline.beforeArrivals + 1;
  return {
    ok: Boolean(pass),
    trace,
    reason: pass ? 'completed' : 'post-roundtrip-failed',
    baseline: { beforePosts: baseline.beforePosts, beforeArrivals: baseline.beforeArrivals },
    landed: { posts: landed.posts, postArrivals: landed.postArrivals, loads: landed.loads, ua: landed.metrics && landed.metrics.ua, timeOrigin: landed.timeOrigin },
    after: { posts: after.posts, postArrivals: after.postArrivals, loads: after.loads, ua: after.metrics && after.metrics.ua, timeOrigin: after.timeOrigin, agentOn: after.agentOn },
  };
}

module.exports = {
  runEnlargeRestoreSameDoc,
  runUserScrollWins,
  runDocumentChangeDiscards,
  runTabRoundTripMode,
  runPostRoundTripMode,
  restoredWithin,
  sameDocument,
  brief,
  fenced,
  RESTORE_TOLERANCE_CSS,
};
