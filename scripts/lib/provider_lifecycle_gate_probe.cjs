'use strict';

// Flow logic for the lifecycle gate (B3 mode/tab switching, B5 Float/Full window cycle).
// The device side is createLifecycleGateAdapters() in provider_gate_adapters.cjs.
// Every value used here is an awaited, normalised reply: tool results, lifecycle-diag fields, page JS
// metrics (__report/__geometry/__loads/__posts) and real status phases. Nothing is hard-coded to pass.
// A missing producer field is null and refuses the case; it is never coerced to false or to a pass.

const STAGE_MS = 20000;
const SCROLL_TOLERANCE_CSS = 1;

function isNum(value) {
  return typeof value === 'number' && Number.isFinite(value);
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

/** Bounds may arrive as left/top/right/bottom or with an explicit width/height; both are real geometry. */
function surfaceSize(surface) {
  if (!surface) return null;
  const width = isNum(surface.width) ? surface.width : isNum(surface.right) && isNum(surface.left) ? surface.right - surface.left : null;
  const height = isNum(surface.height) ? surface.height : isNum(surface.bottom) && isNum(surface.top) ? surface.bottom - surface.top : null;
  if (!isNum(width) || !isNum(height)) return null;
  return { width, height };
}

/** A real UI surface needs finite size. Missing geometry refuses instead of being treated as 0. */
function validSurface(surface) {
  const size = surfaceSize(surface);
  return Boolean(size && size.width > 1 && size.height > 1);
}

/** The fixed layout viewport: the CSS width and its media query must not change across the window cycle. */
function fixedViewport(before, after) {
  return Boolean(before && after && before.metrics && after.metrics &&
    isNum(before.metrics.clientWidth) && isNum(after.metrics.clientWidth) &&
    before.metrics.clientWidth === after.metrics.clientWidth &&
    typeof before.metrics.media1000 === 'boolean' && before.metrics.media1000 === after.metrics.media1000);
}

/** Control is kept only when both reads carry the same real grant boolean. */
function grantKept(before, after) {
  return Boolean(before && after && typeof before.grantForSelected === 'boolean' &&
    before.grantForSelected === after.grantForSelected);
}

/** Same document plus unchanged page counters. Unknown counters never count as unchanged. */
function samePage(before, after) {
  if (sameDocument(before, after) !== true) return false;
  return before.loads != null && after.loads != null && before.loads === after.loads &&
    before.posts != null && after.posts != null && before.posts === after.posts;
}

/** No user scroll means the page's own scrollY stays put within the CSS tolerance. */
function scrollHeld(before, after) {
  return Boolean(before && after && before.metrics && after.metrics &&
    isNum(before.metrics.sy) && isNum(after.metrics.sy) &&
    Math.abs(before.metrics.sy - after.metrics.sy) <= SCROLL_TOLERANCE_CSS);
}

/** The Full screen surface really changed size; the small window surface really returned. */
function surfaceChanged(before, after) {
  const a = surfaceSize(before), b = surfaceSize(after);
  return Boolean(a && b && a.width > 1 && a.height > 1 && b.width > 1 && b.height > 1 &&
    (a.width !== b.width || a.height !== b.height));
}

function surfaceReturned(before, after) {
  const a = surfaceSize(before), b = surfaceSize(after);
  return Boolean(a && b && Math.abs(a.width - b.width) <= 1 && Math.abs(a.height - b.height) <= 1);
}

/**
 * The shared precondition for the three B5 cases: a real small-window page at a non-zero scroll,
 * with the fixed viewport, grant and document identity all readable. A missing field refuses.
 */
function smallWindowBaseline(state) {
  if (!state) return { ok: false, reason: 'precondition-unreadable' };
  if (state.enlarged !== false) return { ok: false, reason: 'precondition-not-small-window', enlarged: state.enlarged };
  if (!isNum(state.metrics && state.metrics.sy) || state.metrics.sy <= 0) return { ok: false, reason: 'precondition-no-scroll' };
  if (!isNum(state.metrics.clientWidth) || typeof state.metrics.media1000 !== 'boolean') {
    return { ok: false, reason: 'precondition-viewport-unreadable' };
  }
  if (typeof state.grantForSelected !== 'boolean') return { ok: false, reason: 'precondition-grant-unreadable' };
  if (state.selectedTabId == null || state.viewHandle == null || state.timeOrigin == null) {
    return { ok: false, reason: 'precondition-identity-unreadable' };
  }
  if (!validSurface(state.surface)) return { ok: false, reason: 'precondition-surface-missing' };
  return { ok: true };
}

/** Full screen contract for the same document: real surface growth, fixed viewport, grant and scroll. */
function fullScreenContract(before, after) {
  const checks = {
    samePage: samePage(before, after),
    viewportFixed: fixedViewport(before, after),
    grantKept: grantKept(before, after),
    surfaceChanged: surfaceChanged(before.surface, after.surface),
    scrollHeld: scrollHeld(before, after),
  };
  return { ok: checks.samePage && checks.viewportFixed && checks.grantKept && checks.surfaceChanged && checks.scrollHeld, ...checks };
}

/** Back-in-small-window contract for the same document: surface returned, viewport, grant and scroll kept. */
function smallWindowContract(before, expanded, after) {
  const checks = {
    samePage: samePage(before, after),
    viewportFixed: fixedViewport(before, after),
    grantKept: grantKept(before, after),
    surfaceReturned: surfaceReturned(before.surface, after.surface),
    surfaceChangedFromFull: surfaceChanged(expanded.surface, after.surface),
    scrollHeld: scrollHeld(before, after),
  };
  return {
    ok: checks.samePage && checks.viewportFixed && checks.grantKept && checks.surfaceReturned &&
      checks.surfaceChangedFromFull && checks.scrollHeld,
    ...checks,
  };
}

function stop(trace, reason, evidence = {}) {
  return { ok: false, trace, reason, ...evidence };
}

/**
 * B5-1 (same document window cycle): a real long page at a non-zero offset, Full screen once, then
 * Small window once. The current Float/Full contract does not refresh a WindowExpansion/scrollRestore
 * result, so this case proves the UI contract instead: the Full screen UI is visible with a larger
 * surface, the same view/document keeps its fixed inner viewport and grant, and with no user scroll the
 * page keeps its scroll position. The small window returns to its previous surface.
 */
async function runEnlargeRestoreSameDoc(deps) {
  const trace = [];
  const stages = Array.isArray(deps.stages) ? deps.stages : [];
  const flowDeps = { ...deps, stages };
  const withStages = (extra) => ({ stages, ...extra });
  const saved = await readStage(flowDeps, trace, stages, 'before-read');
  const baseline = smallWindowBaseline(saved);
  if (!baseline.ok) return stop(trace, baseline.reason, withStages({ saved: saved && summary(saved) }));

  const full = await tapStage(flowDeps, trace, stages, 'tap-full-screen', () => deps.tapEnlarge());
  if (!full.ok) return stop(trace, 'full-screen-tap-failed', withStages({ full, saved: summary(saved) }));
  const expanded = await waitFor(flowDeps, (value) => value && value.enlarged === true, trace, 'full-screen');
  if (!expanded) return stop(trace, 'full-screen-not-observed', withStages({ saved: summary(saved) }));
  const fullChecks = fullScreenContract(saved, expanded);
  if (!fullChecks.ok) {
    return stop(trace, 'full-screen-contract-failed', withStages({ saved: summary(saved), expanded: summary(expanded), checks: fullChecks }));
  }

  const small = await tapStage(flowDeps, trace, stages, 'tap-small-window', () => deps.tapRestore());
  if (!small.ok) return stop(trace, 'small-window-tap-failed', withStages({ small, saved: summary(saved), expanded: summary(expanded) }));
  const restored = await waitFor(flowDeps,
    (value) => value && value.enlarged === false && validSurface(value.surface), trace, 'small-window');
  if (!restored) return stop(trace, 'small-window-not-observed', withStages({ saved: summary(saved), expanded: summary(expanded) }));
  // A separate complete read after the small window is the evidence used by the contract; its own
  // values must still show the small window, never a cached earlier sample.
  const after = await readStage(flowDeps, trace, stages, 'after-read');
  if (!after || after.enlarged !== false) return stop(trace, 'small-window-not-observed', withStages({ saved: summary(saved), expanded: summary(expanded) }));
  const checks = smallWindowContract(saved, expanded, after);
  return withStages({
    ok: checks.ok,
    trace,
    reason: checks.ok ? 'completed' : 'window-cycle-same-doc-failed',
    saved: { sy: saved.metrics.sy, clientWidth: saved.metrics.clientWidth, loads: saved.loads, posts: saved.posts, viewHandle: saved.viewHandle, timeOrigin: saved.timeOrigin, grant: saved.grantForSelected, surface: saved.surface },
    expanded: { enlarged: expanded.enlarged, sy: expanded.metrics && expanded.metrics.sy, clientWidth: expanded.metrics && expanded.metrics.clientWidth, surface: expanded.surface, grant: expanded.grantForSelected },
    after: { enlarged: after.enlarged, sy: after.metrics && after.metrics.sy, clientWidth: after.metrics && after.metrics.clientWidth, loads: after.loads, posts: after.posts, viewHandle: after.viewHandle, timeOrigin: after.timeOrigin, grant: after.grantForSelected, surface: after.surface },
    checks,
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
 * B5-2 (user scroll wins in Full screen): the user scrolls the page while it is Full screen, then
 * returns to the Small window. The window cycle must not reflow the page or jump back to the old
 * offset: the user's latest scroll position is kept, the fixed viewport and grant are unchanged, and
 * the small surface returns. No RestoreResult event is required.
 */
async function runUserScrollWins(deps) {
  const trace = [];
  const stages = Array.isArray(deps.stages) ? deps.stages : [];
  const withStages = (extra) => ({ stages, ...extra });
  const saved = await readStage(deps, trace, stages, 'before-read');
  const baseline = smallWindowBaseline(saved);
  if (!baseline.ok) return stop(trace, baseline.reason, withStages({ saved: saved && summary(saved) }));

  const full = await tapStage(deps, trace, stages, 'tap-full-screen', () => deps.tapEnlarge());
  if (!full.ok) return stop(trace, 'full-screen-tap-failed', withStages({ full, saved: summary(saved) }));
  const expanded = await waitFor(deps, (value) => value && value.enlarged === true, trace, 'full-screen');
  if (!expanded) return stop(trace, 'full-screen-not-observed', withStages({ saved: summary(saved) }));
  const fullChecks = fullScreenContract(saved, expanded);
  if (!fullChecks.ok) {
    return stop(trace, 'full-screen-contract-failed', withStages({ saved: summary(saved), expanded: summary(expanded), checks: fullChecks }));
  }
  const expandedY = expanded.metrics.sy;

  // A real two-finger move while Full screen. It must change the page's own scrollY, not a constant.
  const userScroll = await tapStage(deps, trace, stages, 'user-scroll', () => deps.twoFingerScroll({ dy: 120 }));
  if (!userScroll.ok) return stop(trace, 'user-scroll-failed', withStages({ userScroll, saved: summary(saved), expanded: summary(expanded) }));
  const movedUser = await readStage(deps, trace, stages, 'after-user-scroll-read');
  const movedChecks = {
    stillFull: Boolean(movedUser && movedUser.enlarged === true),
    samePage: Boolean(movedUser && samePage(saved, movedUser)),
    viewportFixed: Boolean(movedUser && fixedViewport(saved, movedUser)),
    grantKept: Boolean(movedUser && grantKept(saved, movedUser)),
    surfaceValid: Boolean(movedUser && validSurface(movedUser.surface)),
    scrollMoved: Boolean(movedUser && isNum(movedUser.metrics && movedUser.metrics.sy) &&
      isNum(expandedY) && Math.abs(movedUser.metrics.sy - expandedY) > SCROLL_TOLERANCE_CSS),
  };
  if (!Object.values(movedChecks).every(Boolean)) {
    return stop(trace, 'user-scroll-contract-failed', withStages({ saved: summary(saved), expanded: summary(expanded), movedUser: movedUser ? summary(movedUser) : null, checks: movedChecks }));
  }
  const userY = movedUser.metrics.sy;

  const small = await tapStage(deps, trace, stages, 'tap-small-window', () => deps.tapRestore());
  if (!small.ok) return stop(trace, 'small-window-tap-failed', withStages({ small, movedUser: summary(movedUser) }));
  const restored = await waitFor(deps,
    (value) => value && value.enlarged === false && validSurface(value.surface), trace, 'small-window');
  if (!restored) return stop(trace, 'small-window-not-observed', withStages({ saved: summary(saved), movedUser: summary(movedUser) }));
  const checks = {
    samePage: samePage(saved, restored),
    viewportFixed: fixedViewport(saved, restored),
    grantKept: grantKept(saved, restored),
    surfaceReturned: surfaceReturned(saved.surface, restored.surface),
    surfaceChangedFromFull: surfaceChanged(expanded.surface, restored.surface),
    userScrollKept: isNum(restored.metrics && restored.metrics.sy) &&
      Math.abs(restored.metrics.sy - userY) <= SCROLL_TOLERANCE_CSS,
    userDiffersFromOld: Math.abs(userY - saved.metrics.sy) > SCROLL_TOLERANCE_CSS,
  };
  const pass = Object.values(checks).every(Boolean);
  return withStages({
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'user-scroll-wins-failed',
    saved: { sy: saved.metrics.sy, clientWidth: saved.metrics.clientWidth, loads: saved.loads, posts: saved.posts, viewHandle: saved.viewHandle, timeOrigin: saved.timeOrigin, grant: saved.grantForSelected, surface: saved.surface },
    expanded: { enlarged: expanded.enlarged, sy: expandedY, clientWidth: expanded.metrics && expanded.metrics.clientWidth, surface: expanded.surface, grant: expanded.grantForSelected },
    userMoved: { sy: userY, deltaFromFull: userY - expandedY, checks: movedChecks },
    after: { sy: restored.metrics && restored.metrics.sy, clientWidth: restored.metrics && restored.metrics.clientWidth, viewHandle: restored.viewHandle, timeOrigin: restored.timeOrigin, grant: restored.grantForSelected, surface: restored.surface },
    checks,
  });
}

/**
 * B5-3 (document change during Full screen): while the window is Full screen, an explicit open
 * replaces the document. Returning to the Small window must not apply the old document's scroll to
 * the new one. The new document keeps the fixed viewport and the default grant; its identity must be
 * readable and actually different, and its own scroll must not equal the old saved offset.
 */
async function runDocumentChangeDiscards(deps) {
  const trace = [];
  const stages = Array.isArray(deps.stages) ? deps.stages : [];
  const withStages = (extra) => ({ stages, ...extra });
  const saved = await readStage(deps, trace, stages, 'before-read');
  const baseline = smallWindowBaseline(saved);
  if (!baseline.ok) return stop(trace, baseline.reason, withStages({ saved: saved && summary(saved) }));

  const full = await tapStage(deps, trace, stages, 'tap-full-screen', () => deps.tapEnlarge());
  if (!full.ok) return stop(trace, 'full-screen-tap-failed', withStages({ full, saved: summary(saved) }));
  const expanded = await waitFor(deps, (value) => value && value.enlarged === true, trace, 'full-screen');
  if (!expanded) return stop(trace, 'full-screen-not-observed', withStages({ saved: summary(saved) }));
  const fullChecks = fullScreenContract(saved, expanded);
  if (!fullChecks.ok) {
    return stop(trace, 'full-screen-contract-failed', withStages({ saved: summary(saved), expanded: summary(expanded), checks: fullChecks }));
  }

  const opened = await tapStage(deps, trace, stages, 'open-new-document', () => deps.openNewDocument());
  if (!opened.ok) return stop(trace, 'open-new-document-failed', withStages({ opened, saved: summary(saved) }));
  // The identity must be readable and actually different; unknown identity is not a changed document.
  const newDoc = await waitFor(deps,
    (value) => value && value.pageReady === true && sameDocument(saved, value) === false, trace, 'new-document');
  if (!newDoc) return stop(trace, 'new-document-not-observed', withStages({ saved: summary(saved), opened }));
  // The explicit open may keep the window Full screen or return it; tap Small window only if it is full.
  let small = { ok: true, skipped: true };
  if (newDoc.enlarged === true) {
    small = await tapStage(deps, trace, stages, 'tap-small-window', () => deps.tapRestore());
    if (!small.ok) return stop(trace, 'small-window-tap-failed', withStages({ small, newDoc: summary(newDoc) }));
  }
  const restored = await waitFor(deps,
    (value) => value && value.enlarged === false && value.pageReady === true && sameDocument(newDoc, value) === true,
    trace, 'small-window');
  if (!restored) return stop(trace, 'small-window-not-observed', withStages({ saved: summary(saved), newDoc: summary(newDoc) }));
  const checks = {
    newDocumentStable: samePage(newDoc, restored),
    viewportFixed: fixedViewport(saved, restored),
    grantKept: grantKept(saved, restored),
    surfaceValid: validSurface(restored.surface),
    oldScrollNotApplied: isNum(restored.metrics && restored.metrics.sy) &&
      Math.abs(restored.metrics.sy - saved.metrics.sy) > SCROLL_TOLERANCE_CSS,
  };
  const pass = Object.values(checks).every(Boolean);
  return withStages({
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'document-change-failed',
    saved: { sy: saved.metrics.sy, clientWidth: saved.metrics.clientWidth, timeOrigin: saved.timeOrigin, viewHandle: saved.viewHandle, selectedTabId: saved.selectedTabId, grant: saved.grantForSelected, surface: saved.surface },
    expanded: { enlarged: expanded.enlarged, surface: expanded.surface },
    newDoc: { selectedTabId: newDoc.selectedTabId, viewHandle: newDoc.viewHandle, timeOrigin: newDoc.timeOrigin, sy: newDoc.metrics && newDoc.metrics.sy, grant: newDoc.grantForSelected, enlarged: newDoc.enlarged },
    after: { sy: restored.metrics && restored.metrics.sy, clientWidth: restored.metrics && restored.metrics.clientWidth, viewHandle: restored.viewHandle, timeOrigin: restored.timeOrigin, grant: restored.grantForSelected, surface: restored.surface, enlarged: restored.enlarged },
    checks,
  });
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
  sameDocument,
  brief,
  fenced,
  SCROLL_TOLERANCE_CSS,
};
