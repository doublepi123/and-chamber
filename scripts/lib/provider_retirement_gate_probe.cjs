'use strict';

// Flow logic for the B4 retirement gate. The device side is createRetirementGateAdapters() in
// provider_gate_adapters.cjs. Every value used here is a real read: lifecycle-diag (producer),
// fixture-state, the RETIRE fixture's own __report() observations, and the native recorder counters. A
// missing value is null and refuses the step; it is never coerced to 0 or false to pass.
//
// Two cases are proven here, nothing more:
//   A  primary drag lock: PRESS is real, a secondary pointer does not reinterpret the drag, the primary
//      release is a normal RELEASE once, and the leftover secondary UP is swallowed, not a click or retire.
//   B  true CANCEL retirement: retirement itself adds no click, the new view is clean after a fresh DOWN,
//      the old authority is gone, and reopening is an explicit native action only.

const STAGE_MS = 20000;

function isNum(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function stop(trace, reason, evidence = {}) {
  return { ok: false, trace, reason, ...evidence };
}

/** Waits (read-only) until predicate holds. Each read is recorded in the trace. */
async function waitState(deps, predicate, trace, label, budgetMs = STAGE_MS) {
  const started = deps.now();
  while (deps.now() - started < budgetMs) {
    const state = await deps.readState();
    trace.push(`wait:${label}`);
    if (predicate(state)) return state;
    await deps.sleep(200);
  }
  return null;
}

/** The old view's click count is a separate counter read from the recorder, not from the page. */
function clicksOf(record) {
  return record && isNum(record.clicks) ? record.clicks : null;
}

function sameRecord(a, b) {
  return a && b && a.viewId != null && a.viewId === b.viewId && a.origin != null && String(a.origin) === String(b.origin);
}

/** DOWN never moves the virtual cursor. Steer it with short real relative moves, then verify hit. */
async function hoverTarget(deps, id) {
  const before = await deps.readTarget(id);
  if (!before || !before.pointer || !before.frame || !before.page || !isNum(clicksOf(before.record))) return null;
  const { width:w, height:h } = before.frame;
  if (![w,h,before.pointer.x,before.pointer.y,before.target?.x,before.target?.y].every(isNum) ||
      w <= 4 || h <= 4 || before.pointer.x < 0 || before.pointer.y < 0 || before.pointer.x >= w || before.pointer.y >= h ||
      before.target.x < 0 || before.target.y < 0 || before.target.x >= w || before.target.y >= h) return null;
  if (!before.hit) {
    const dx = Math.round(before.target.x-before.pointer.x), dy = Math.round(before.target.y-before.pointer.y);
    // Never turn a zero/tiny hover into a click. Small unsupported corrections stop the case.
    if (Math.hypot(dx,dy) <= 32 || Math.abs(dx) > w-4 || Math.abs(dy) > h-4) return null;
    const start = {x:(w-dx)/2,y:(h-dy)/2};
    const sent = await deps.atomicFinger({...start,dx,dy,steps:4,viewHandle:before.viewHandle,origin:before.origin,frame:before.frame});
    if (!sent.ok) return null;
  }
  const after = await deps.readTarget(id);
  if (!after || !after.hit || after.viewHandle !== before.viewHandle || String(after.origin)!==String(before.origin) ||
      JSON.stringify(after.frame)!==JSON.stringify(before.frame) || !sameRecord(before.record,after.record) ||
      after.record.clicks!==before.record.clicks || after.page.clicks!==before.page.clicks ||
      after.page.releases!==before.page.releases || after.page.buttons!==0 ||
      !isNum(after.pointer.x) || !isNum(after.pointer.y)) return null;
  const presses = (page) => Array.isArray(page.logTail) ? page.logTail.filter(e=>e.t==='pointerdown'&&e.p==='mouse').length : null;
  if (presses(after.page)==null || presses(after.page)!==presses(before.page)) return null;
  return after;
}

function dragPlan(aim) {
  const w=aim?.frame?.width,h=aim?.frame?.height;
  if (!isNum(w)||!isNum(h)||w<160||h<80) return null;
  const origin={x:Math.round(w*.25),y:Math.round(h*.4)};
  return {origin,held:{x:origin.x+45,y:origin.y+5},move:{x:origin.x+55,y:origin.y+5},secondary:{x:origin.x+95,y:origin.y+15}};
}

/**
 * A: primary drag lock. The press is real (native mousePressActive), the page sees a mouse-source down
 * with buttons=1 captured on the box, the secondary pointer does not turn the drag into a wheel, the primary
 * release is one normal RELEASE, the leftover secondary UP is swallowed, and only this normal end may click.
 */
async function runPrimaryDragLock(deps) {
  const trace = [];
  const start = await deps.readState();
  if (!start || start.pageReady !== true) return stop(trace, 'precondition-not-ready', { start });
  if (start.mousePressActive !== false) return stop(trace, 'precondition-press-already-active', { start });
  if (start.grantForSelected === null || start.agentOn === null || start.mode === null) {
    return stop(trace, 'precondition-authority-unreadable', { start: { grant: start.grantForSelected, agentOn: start.agentOn, mode: start.mode } });
  }
  // An ordinary drag on the page is not a takeover: control must stay exactly as it was. Only an
  // explicit takeover, hide/close/background or an owner change may change it.
  const aim = await hoverTarget(deps,'box');
  const plan = dragPlan(aim);
  if (!aim || !plan) return stop(trace, 'box-hover-unproved');
  const origin = plan.origin;
  const down = await deps.finger({ action: 'down', ...origin });
  if (!down.ok) return stop(trace, 'down-not-sent', { down });
  // MouseInput emits PRESS only after a held MOVE, so the drag is DOWN -> hold -> MOVE, then PRESS is observed.
  await deps.sleep(900);
  const moved = await deps.finger({ action: 'move', ...plan.held });
  if (!moved.ok) return stop(trace, 'move-not-sent', { moved });
  const held = await waitState(deps, (s) => s && s.mousePressActive === true, trace, 'press-active');
  if (!held) return stop(trace, 'press-never-active');
  const pageDuringDrag = await deps.readPage();
  // boxCaptured is read on the actual DOM pointer id; null means unreadable and refuses the step.
  if (!pageDuringDrag || !isNum(pageDuringDrag.pointerId) || pageDuringDrag.boxCaptured !== true || pageDuringDrag.buttons !== 1 ||
      !pageDuringDrag.logTail?.some(e=>e.t==='pointerdown'&&e.p==='mouse'&&e.bs===1)) {
    return stop(trace, 'page-not-captured-during-drag', { page: pageDuringDrag });
  }
  const second = await deps.finger({ action: 'secondary-down', ...plan.secondary });
  if (!second.ok) return stop(trace, 'secondary-not-sent', { second });
  const moved2 = await deps.finger({ action: 'move', ...plan.move });
  if (!moved2.ok) return stop(trace, 'move-after-secondary-not-sent', { moved2 });
  const stillDrag = await deps.readPage();
  if (!stillDrag || stillDrag.wheel !== aim.page.wheel || stillDrag.pointerId !== pageDuringDrag.pointerId ||
      stillDrag.boxCaptured !== true || stillDrag.buttons !== 1) return stop(trace, 'secondary-became-wheel', { page: stillDrag });
  // Only the primary (7) lifts: the remaining contact is then a single pointer, released with a normal UP.
  const primaryUp = await deps.finger({ action: 'primary-up', ...plan.move });
  if (!primaryUp.ok) return stop(trace, 'primary-up-not-sent', { primaryUp });
  const released = await waitState(deps, (s) => s && s.mousePressActive === false, trace, 'released');
  if (!released) return stop(trace, 'release-not-observed');
  // The remaining pointer (11) ends with a single-pointer UP. It must not click or retire anything.
  const leftover = await deps.finger({ action: 'up', ...plan.secondary });
  if (!leftover.ok) return stop(trace, 'leftover-up-not-sent', { leftover });
  await deps.sleep(400);
  const end = await deps.readState();
  const page = await deps.readPage();
  const clicksAfter = clicksOf(end && end.recorder && end.recorder.current);
  // Releases are counted from the page's own mouse pointerup counter, not inferred.
  const releases = page && isNum(page.releases) ? page.releases : null;
  // Ordinary page input does not revoke control: the grant, agent flag and mode stay exactly as
  // they were at the start. The page is not retired, and at most one normal click may result.
  const authorityKept = end && end.grantForSelected === start.grantForSelected &&
    end.agentOn === start.agentOn && end.mode === start.mode;
  const pass = Boolean(end && end.pageRetired === false && end.recorderReady === true && releases - aim.page.releases === 1 &&
    authorityKept === true &&
    sameRecord(aim.record,end.recorder.current) && clicksAfter != null && clicksAfter-aim.record.clicks >= 0 && clicksAfter-aim.record.clicks <= 1 &&
    page.buttons===0 && page.boxCaptured===false &&
    end.viewHandle === start.viewHandle);
  return {
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'primary-drag-lock-failed',
    start: { viewHandle: start.viewHandle, grant: start.grantForSelected, mode: start.mode, agentOn: start.agentOn },
    end: { viewHandle: end && end.viewHandle, pageRetired: end && end.pageRetired, mousePressActive: end && end.mousePressActive, grant: end && end.grantForSelected, mode: end && end.mode, agentOn: end && end.agentOn, authorityKept },
    page: { boxCaptured: pageDuringDrag.boxCaptured, buttons: pageDuringDrag.buttons, releases, wheel: stillDrag.wheel },
    clicks: clicksAfter,
  };
}

/**
 * B: true CANCEL retirement. The press is real and captured, the recorder is ready and the old document has no
 * clicks. Retirement is done by a raw CANCEL. Retirement itself must not add a click, so the old counter must
 * be unchanged. After retire: no grant, no current view, agent off. An agent snapshot must be refused as
 * AUTHORIZATION_REQUIRED (never NoProvider). Reopen is an explicit native CTA only. The fresh view is clean.
 */
async function runTrueCancelRetire(deps) {
  const trace = [];
  const start = await deps.readState();
  if (!start || start.recorderReady !== true) return stop(trace, 'recorder-not-ready', { start });
  if (start.mousePressActive !== false) return stop(trace, 'precondition-press-already-active', { start });
  const oldRecord = start.recorder && start.recorder.current;
  if (!oldRecord || !isNum(oldRecord.clicks)) return stop(trace, 'old-record-missing', { start });
  const baselineClicks = oldRecord.clicks;
  // The cookie is read once, on the old document, before any press. It is never read from the old page afterwards.
  const cookieBefore = await deps.readOldCookie();
  if (typeof cookieBefore !== 'string' || !cookieBefore.includes('oc_fixture_retire=')) {
    return stop(trace, 'cookie-unreadable-before-press', { cookieBefore });
  }
  const aim = await hoverTarget(deps,'box');
  const plan = dragPlan(aim);
  if (!aim || !plan) return stop(trace, 'box-hover-unproved');
  const origin = plan.origin;
  if (!sameRecord(oldRecord, aim.record) || aim.record.clicks !== baselineClicks) return stop(trace,'old-source-changed-before-press');
  const down = await deps.finger({ action: 'down', ...origin });
  if (!down.ok) return stop(trace, 'down-not-sent', { down });
  await deps.sleep(900);
  const moved = await deps.finger({ action: 'move', ...plan.held });
  if (!moved.ok) return stop(trace, 'move-not-sent', { moved });
  const held = await waitState(deps, (s) => s && s.mousePressActive === true, trace, 'press-active');
  if (!held) return stop(trace, 'press-never-active');
  // Safety: an interruption while a real mouse press is active retires the page. This is the
  // hide/close/background path, not an ordinary scroll or drag; it must keep retiring, and the
  // interrupted mutation keeps its unknown outcome rather than being relaxed to a success.
  if (held.mousePressActive !== true) return stop(trace, 'press-not-active-at-interruption');
  const captured = await deps.readPage();
  if (!captured || !isNum(captured.pointerId) || captured.boxCaptured!==true || captured.buttons!==1 ||
      !captured.logTail?.some(e=>e.t==='pointerdown'&&e.p==='mouse'&&e.bs===1)) return stop(trace,'page-not-captured-during-drag',{captured});
  const cancel = await deps.finger({ action: 'cancel', ...plan.held });
  if (!cancel.ok) return stop(trace, 'cancel-not-sent', { cancel });
  const retired = await waitState(deps, (s) => s && s.pageRetired === true && s.currentViewPresent === false, trace, 'retired');
  if (!retired) return stop(trace, 'retire-not-observed');
  // Retirement itself adds no click: the independent old-view counter is unchanged.
  const oldAfter = retired.recorder && retired.recorder.lastRetired;
  const clicksAdded = sameRecord(oldRecord,oldAfter) && isNum(oldAfter.clicks) ? oldAfter.clicks - baselineClicks : null;
  const refused = await deps.agentSnapshot();
  const grantGone = retired.grantForSelected === false;
  const agentOff = retired.agentOn === false;
  const pressCleared = retired.mousePressActive === false;
  // AUTHORIZATION_REQUIRED is the only accepted refusal. NoProvider or a transport failure is not isolation.
  const snapshotRefused = Boolean(refused) && refused.code === 'AUTHORIZATION_REQUIRED';
  const allowMissing = await deps.allowAttempt();
  const allowRefused = Boolean(allowMissing) && allowMissing.granted === false;
  const retireOk = clicksAdded === 0 && grantGone && agentOff && pressCleared && snapshotRefused && allowRefused;
  if (!retireOk) {
    return stop(trace, 'retirement-not-isolated', {
      clicksAdded, grantGone, agentOff, pressCleared, snapshot: refused, allow: allowMissing,
    });
  }
  // Reopen only through the native Reopen CTA. It is an explicit user open: the new view starts
  // retired-free, keeps the cookie, and gets its own fresh default grant once ready. The old grant
  // must never be carried over.
  const reopened = await deps.tapReopen();
  if (!reopened.ok) return stop(trace, 'reopen-tap-failed', { reopened });
  const fresh = await waitState(deps, (s) => s && s.pageRetired === false && s.currentViewPresent === true &&
    s.pageReady === true && s.viewHandle != null && s.viewHandle !== retired.viewHandle, trace, 'fresh-view');
  if (!fresh) return stop(trace, 'fresh-view-not-observed', { oldHandle: retired.viewHandle });
  // The fresh view must receive the new default grant from its own ready page (AGENT/agentOn true).
  const freshGranted = await waitState(deps, (s) => s && s.viewHandle === fresh.viewHandle &&
    s.grantForSelected === true && s.agentOn === true && s.mode === 'AGENT', trace, 'fresh-default-grant');
  if (!freshGranted) {
    return stop(trace, 'fresh-default-grant-not-observed', {
      oldHandle: retired.viewHandle,
      freshHandle: fresh.viewHandle,
      grantForSelected: fresh.grantForSelected,
      agentOn: fresh.agentOn,
      mode: fresh.mode,
    });
  }
  const freshPage = await deps.readPage();
  const cookieAfter = await deps.readOldCookie();
  if (typeof cookieAfter !== 'string') return stop(trace, 'cookie-unreadable-after-reopen', { freshPage });
  const cookieKept = cookieAfter === cookieBefore;
  // The new view and its document must be genuinely new, never the retired one with a grant patched on.
  const oldTimeOrigin = aim.page && isNum(aim.page.timeOrigin) ? aim.page.timeOrigin : null;
  const freshTimeOrigin = freshPage && isNum(freshPage.timeOrigin) ? freshPage.timeOrigin : null;
  const freshDocument = oldTimeOrigin != null && freshTimeOrigin != null && freshTimeOrigin !== oldTimeOrigin;
  const freshRecord = freshGranted.recorder && freshGranted.recorder.current;
  const freshRecordChanged = Boolean(freshRecord && oldRecord && !sameRecord(oldRecord, freshRecord));
  // The fresh view's own counters start at zero.
  const freshGrantPresent = freshGranted.grantForSelected === true && freshGranted.agentOn === true && freshGranted.mode === 'AGENT';
  const freshClicks = freshRecord ? freshRecord.clicks : null;
  const pass = retireOk && cookieKept && freshGrantPresent && freshDocument && freshRecordChanged &&
    freshClicks === 0 && freshPage && freshPage.clicks === 0;
  if (!pass) {
    return {
      ok: false,
      trace,
      reason: 'retire-reopen-failed',
      clicksAdded,
      snapshot: refused,
      allow: allowMissing,
      reopen: { cookieKept, freshGrantPresent, freshDocument, freshRecordChanged, freshClicks, oldHandle: retired.viewHandle, freshHandle: fresh.viewHandle },
    };
  }
  // Fresh-view continuation is part of the proof. It runs only after the reopen itself passed.
  const continuation = await runFreshViewContinuation(deps, freshGranted, trace);
  return {
    ok: continuation.ok === true,
    trace,
    reason: continuation.ok ? 'completed' : continuation.reason,
    clicksAdded,
    snapshot: refused,
    allow: allowMissing,
    reopen: { cookieKept, freshGrantPresent, freshDocument, freshRecordChanged, freshClicks, oldHandle: retired.viewHandle, freshHandle: fresh.viewHandle },
    continuation,
  };
}

/**
 * Continuation on the fresh view, after explicit Reopen and a new production-menu Mouse enable.
 * The fresh default grant is kept through the mode switch, button-free hover/wheel and the one normal
 * click; the old view's retirement record is never rescued or changed, and no synthetic input replays.
 */
async function runFreshViewContinuation(deps, fresh, trace) {
  const enable=await deps.enableMouse();
  const enabled=await deps.readState();
  if (!enable.ok || enabled?.mouseMode!==true || enabled?.grantForSelected!==true || enabled?.agentOn!==true ||
      enabled?.mode!=='AGENT' || enabled?.viewHandle!==fresh.viewHandle) {
    return stop(trace,'fresh-mouse-not-enabled', {
      enabled: enabled && {
        mouseMode: enabled.mouseMode,
        grantForSelected: enabled.grantForSelected,
        agentOn: enabled.agentOn,
        mode: enabled.mode,
        viewHandle: enabled.viewHandle,
      },
    });
  }
  const native = async (spec) => deps.nativeGate(spec);
  for (const spec of ['enter,40,40', 'hover,60,60', 'exit,60,60', 'enter,40,40', 'hover,70,70']) {
    const step = await native(spec);
    if (!step || step.sent !== true) return stop(trace, 'native-no-press-not-sent', { spec, step });
  }
  const beforeWheel = await deps.readPage();
  if (!beforeWheel || beforeWheel.boxCaptured === true || beforeWheel.buttons !== 0 || beforeWheel.clicks !== 0) {
    return stop(trace, 'fresh-view-not-clean-before-wheel', { page: beforeWheel });
  }
  const wheel = await native('wheel,60,60,-120');
  if (!wheel || wheel.sent !== true) return stop(trace, 'wheel-not-sent', { wheel });
  const afterWheel = await deps.readPage();
  if (!afterWheel || !isNum(afterWheel.wheel) || afterWheel.wheel < 1) {
    return stop(trace, 'wheel-not-observed', { page: afterWheel });
  }
  // Hover/wheel are not takeover: the fresh grant must still be there after them.
  const afterWheelState = await deps.readState();
  if (!afterWheelState || afterWheelState.viewHandle !== fresh.viewHandle ||
      afterWheelState.grantForSelected !== true || afterWheelState.agentOn !== true || afterWheelState.mode !== 'AGENT') {
    return stop(trace, 'fresh-grant-lost-after-wheel', {
      grantForSelected: afterWheelState && afterWheelState.grantForSelected,
      agentOn: afterWheelState && afterWheelState.agentOn,
      mode: afterWheelState && afterWheelState.mode,
    });
  }
  // Re-measure the fixed next button after the wheel: the target is never reused from before the scroll.
  const target = await hoverTarget(deps,'next');
  if (!target) return stop(trace, 'next-hover-unproved');
  const before = await deps.readState();
  const clicksBefore = clicksOf(before && before.recorder && before.recorder.current);
  if (!before || before.viewHandle!==fresh.viewHandle || before.grantForSelected!==true ||
      before.agentOn!==true || before.mode!=='AGENT' || !sameRecord(before.recorder.current,target.record)) {
    return stop(trace,'fresh-click-source-changed');
  }
  const pageClicksBefore = afterWheel.clicks;
  const click = await deps.atomicFinger({x:target.frame.width/2,y:target.frame.height/2,dx:0,dy:0,steps:1,viewHandle:target.viewHandle,origin:target.origin,frame:target.frame});
  if (!click.ok) return stop(trace,'atomic-click-not-sent');
  await deps.sleep(500);
  const after = await deps.readState();
  const page = await deps.readPage();
  const clicksAfter = clicksOf(after && after.recorder && after.recorder.current);
  const pageClicksDelta = page && isNum(page.clicks) ? page.clicks - pageClicksBefore : null;
  const nativeDelta = clicksAfter != null && clicksBefore != null ? clicksAfter - clicksBefore : null;
  const oldRecord=after?.recorder?.lastRetired, originalRetired=fresh.recorder?.lastRetired;
  const ok = nativeDelta === 1 && pageClicksDelta === 1 && after && after.grantForSelected === true && after.agentOn===true &&
    after.mode==='AGENT' && sameRecord(before.recorder.current,after.recorder.current) &&
    sameRecord(oldRecord,originalRetired) && oldRecord.clicks===originalRetired.clicks &&
    page.buttons===0 && page.boxCaptured!==true && page.nextClicks===1 && page.boxClicks===0 &&
    page.logTail?.filter(e=>e.t==='click'&&e.p==='mouse').length===1 &&
    page.logTail?.filter(e=>e.t==='pointerdown'&&e.p==='mouse').length===1;
  return { ok, trace, reason: ok ? 'completed' : 'fresh-click-not-single', nativeDelta, pageClicksDelta, grantForSelected: after && after.grantForSelected };
}

module.exports = { runPrimaryDragLock, runTrueCancelRetire, runFreshViewContinuation, hoverTarget, dragPlan, sameRecord, clicksOf, waitState, isNum, STAGE_MS };
