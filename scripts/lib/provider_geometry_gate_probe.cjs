'use strict';

// Flow order for the geometry race gate. The device side is createGeometryGateAdapters() in
// verify_openchamber_provider_emulator.cjs. Every value used here is a normalised, awaited tool
// reply, a real geometry-diag read, or a real status phase. Nothing is hard-coded to pass.

const STAGE_MS = 20000;
const REFUSAL = /AUTHORIZATION_REQUIRED|REF_EXPIRED|OUTCOME_UNKNOWN/;

function noProvider(result) {
  return /NO_PROVIDER|No Android browser provider|BROWSER_PAGE_UNAVAILABLE/i.test(`${result && result.code} ${result && result.detail}`);
}

/** True only for a real page refusal. A missing provider is a setup problem, not an authorization result. */
function refused(result) {
  return Boolean(result) && result.ok !== true && !noProvider(result) &&
    REFUSAL.test(`${result.code || ''} ${result.detail || ''}`);
}

/** Fingerprint of the real selectors and roles in a snapshot reply (data.elements). Null when none are listed. */
function refFingerprint(result) {
  const elements = result && result.data && Array.isArray(result.data.elements) ? result.data.elements : null;
  if (!elements || elements.length === 0) return null;
  return elements.map((item) => `${item.name}|${item.role}|${item.selector}`).sort().join('\n');
}

function brief(result) {
  if (!result) return null;
  return { ok: result.ok === true, code: result.code || '', detail: String(result.detail || '').slice(0, 240) };
}

async function waitStage(deps, phase, trace, label) {
  const started = deps.now();
  while (deps.now() - started < STAGE_MS) {
    const status = await deps.readStatus(phase);
    trace.push(`wait:${label}:${status && status.phase}`);
    if (status && status.phase === phase) return status;
    await deps.sleep(200);
  }
  return null;
}

async function releaseOwned(deps, name, trace) {
  trace.push(`release:${name}`);
  return deps.releaseMarker(name);
}

/** Releases our own hold, then waits for the request that was parked behind it. */
async function abandon(deps, name, trace, pending) {
  await releaseOwned(deps, name, trace);
  await pending;
}

function stop(trace, reason, evidence = {}) {
  return { ok: false, trace, reason, ...evidence };
}

async function runDispatchGeometry(deps) {
  const trace = [];
  if (!await deps.setMarker('dhold')) return stop(trace, 'dhold-not-set');
  trace.push('marker:dhold');
  const base = await deps.readDiag();
  const pending = deps.type('gate-b1');
  const held = await waitStage(deps, 'dispatched', trace, 'type');
  if (!held) {
    await abandon(deps, 'dhold', trace, pending);
    return stop(trace, 'dispatch-stage-missing');
  }
  const atHeld = await deps.readDiag();
  trace.push('geometry');
  const moved = await deps.dragTitle();
  if (!moved.moved) {
    await abandon(deps, 'dhold', trace, pending);
    return stop(trace, 'geometry-not-moved', { moved });
  }
  await releaseOwned(deps, 'dhold', trace);
  // The first type really ran in the page before the hold. Its outcome is unknown, not refused.
  const late = await pending;
  const after = await deps.readDiag();
  const inputAfterFirst = await deps.readInput();
  const snapshot = await deps.snapshotFresh();
  // The isolated page must refuse a second mutation. The page input proves nothing else changed it.
  const again = await deps.type('gate-b1-again');
  const inputAfterSecond = await deps.readInput();
  const afterAgain = await deps.readDiag();
  const allow = await deps.allow();
  const reopened = await deps.openFresh();
  const restored = reopened.ok ? await deps.allow() : { ok: false, grantPresent: false, detail: 'open-failed' };
  const pass = late.ok !== true &&
    Boolean(base && atHeld && after && afterAgain) &&
    atHeld.dispatchCount === base.dispatchCount + 1 &&
    after.dispatchCount === atHeld.dispatchCount &&
    after.registryGrantPresent === false && after.agentOn === false && after.mode === 'MANUAL' &&
    after.outcomeUnknown === true &&
    refused(snapshot) && refused(again) &&
    afterAgain.dispatchCount === atHeld.dispatchCount &&
    typeof inputAfterFirst === 'string' && inputAfterSecond === inputAfterFirst &&
    allow.ok === false && allow.grantPresent === false &&
    reopened.ok === true && restored.ok === true && restored.grantPresent === true;
  return {
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'dispatch-geometry-failed',
    late: brief(late),
    moved,
    dispatchCount: { base: base && base.dispatchCount, held: atHeld && atHeld.dispatchCount, afterAgain: afterAgain && afterAgain.dispatchCount },
    after,
    input: { afterFirst: inputAfterFirst, afterSecond: inputAfterSecond },
    snapshot: brief(snapshot),
    again: brief(again),
    allow,
    reopened: brief(reopened),
    restored,
  };
}

async function runFieldGeometry(deps) {
  const trace = [];
  if (!await deps.setMarker('fhold')) return stop(trace, 'fhold-not-set');
  trace.push('marker:fhold');
  const base = await deps.readDiag();
  const inputBefore = await deps.readInput();
  const pending = deps.type('gate-b2a');
  const held = await waitStage(deps, 'field-held', trace, 'field');
  if (!held) {
    await abandon(deps, 'fhold', trace, pending);
    return stop(trace, 'field-stage-missing');
  }
  const atHeld = await deps.readDiag();
  trace.push('geometry');
  const moved = await deps.dragTitle();
  if (!moved.moved) {
    await abandon(deps, 'fhold', trace, pending);
    return stop(trace, 'geometry-not-moved', { moved });
  }
  await releaseOwned(deps, 'fhold', trace);
  const late = await pending;
  const after = await deps.readDiag();
  const input = await deps.readInput();
  const stale = await deps.useOldRef();
  const afterStale = await deps.readDiag();
  const fresh = await deps.snapshotFresh();
  // Field preflight is not a mutation: no dispatch, no unknown outcome, page value unchanged.
  const pass = late.ok !== true &&
    Boolean(base && atHeld && after && afterStale) &&
    typeof inputBefore === 'string' && typeof input === 'string' &&
    atHeld.dispatchCount === base.dispatchCount &&
    after.dispatchCount === base.dispatchCount && afterStale.dispatchCount === base.dispatchCount &&
    after.outcomeUnknown === false && input === inputBefore &&
    refused(stale) && fresh.ok === true;
  return {
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'field-geometry-failed',
    late: brief(late),
    moved,
    dispatchCount: { base: base && base.dispatchCount, held: atHeld && atHeld.dispatchCount, after: after && after.dispatchCount, afterStale: afterStale && afterStale.dispatchCount },
    after,
    input: { before: inputBefore, after: input },
    stale: brief(stale),
    fresh: brief(fresh),
  };
}

async function runSnapshotGeometry(deps) {
  const trace = [];
  // The baseline is a real snapshot taken before any hold exists.
  const baseline = await deps.snapshotFresh();
  const baselineFingerprint = refFingerprint(baseline);
  if (!await deps.setMarker('shold')) return stop(trace, 'shold-not-set');
  trace.push('marker:shold');
  const pending = deps.snapshotOnce();
  const held = await waitStage(deps, 'snapshot-held', trace, 'snapshot');
  if (!held) {
    await abandon(deps, 'shold', trace, pending);
    return stop(trace, 'snapshot-stage-missing');
  }
  const before = await deps.readDiag();
  trace.push('geometry');
  const moved = await deps.dragTitle();
  if (!moved.moved) {
    await abandon(deps, 'shold', trace, pending);
    return stop(trace, 'geometry-not-moved', { moved });
  }
  await releaseOwned(deps, 'shold', trace);
  const late = await pending;
  const after = await deps.readDiag();
  const stale = await deps.useOldRef();
  const afterStale = await deps.readDiag();
  const fresh = await deps.snapshotFresh();
  const freshFingerprint = refFingerprint(fresh);
  const refsChanged = freshFingerprint !== null && baselineFingerprint !== null && freshFingerprint !== baselineFingerprint;
  const pass = late.ok !== true && refused(stale) &&
    Boolean(before && after && afterStale) &&
    afterStale.dispatchCount === before.dispatchCount &&
    fresh.ok === true && refsChanged &&
    after.geometryEpoch !== before.geometryEpoch;
  return {
    ok: pass,
    trace,
    reason: pass ? 'completed' : 'snapshot-geometry-failed',
    late: brief(late),
    moved,
    stale: brief(stale),
    fresh: brief(fresh),
    refsChanged,
    geometryEpoch: { before: before && before.geometryEpoch, after: after && after.geometryEpoch },
  };
}

async function runCaptureGeometry(deps) {
  const trace = [];
  if (!await deps.setMarker('chold')) return stop(trace, 'chold-not-set');
  trace.push('marker:chold');
  const before = await deps.readDiag();
  const pending = deps.capture();
  const held = await waitStage(deps, 'capture-enqueued', trace, 'capture');
  if (!held) {
    await abandon(deps, 'chold', trace, pending);
    return stop(trace, 'capture-stage-missing');
  }
  const frame = deps.mode === 'frame';
  trace.push(frame ? 'frame-offset' : 'geometry');
  const change = frame ? await deps.offsetFrame() : await deps.dragTitle();
  if (!change.moved) {
    await abandon(deps, 'chold', trace, pending);
    return stop(trace, 'capture-change-missing', { change });
  }
  try {
    await releaseOwned(deps, 'chold', trace);
    // The frame offset stays until the late result has been handled.
    const late = await pending;
    const file = await deps.readCapture();
    const after = await deps.readDiag();
    const delta = (key) => (before && after ? after[key] - before[key] : NaN);
    const pass = Boolean(before && after) && late.ok !== true && file === false &&
      delta('pixelCopyQueued') === 1 && delta('pixelCopyCompleted') === 1 && delta('pixelCopyForwarded') === 1 &&
      after.bitmapRecycled === true;
    return {
      ok: pass,
      trace,
      reason: pass ? 'completed' : 'capture-geometry-failed',
      late: brief(late),
      file,
      change,
      pixelCopy: {
        queued: delta('pixelCopyQueued'),
        completed: delta('pixelCopyCompleted'),
        forwarded: delta('pixelCopyForwarded'),
        bitmapRecycled: after ? after.bitmapRecycled : null,
      },
    };
  } finally {
    if (frame) {
      trace.push('frame-restore');
      await deps.restoreFrame();
    }
  }
}

module.exports = { runDispatchGeometry, runFieldGeometry, runSnapshotGeometry, runCaptureGeometry, refused, noProvider, refFingerprint, brief };
