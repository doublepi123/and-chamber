'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const probe = require('../lib/provider_geometry_gate_probe.cjs');

// Pure flow tests: the device adapter is covered by provider_geometry_gate_adapter.test.cjs.
function flowDeps(options = {}) {
  const trace = [];
  let clock = 0;
  const status = { phase: options.phase || null };
  const deps = {
    now: () => clock,
    sleep: async (ms) => { clock += ms; },
    setMarker: async (name) => { trace.push(`set:${name}`); return options.marker !== false; },
    releaseMarker: async (name) => { trace.push(`release:${name}`); status.phase = 'released'; return true; },
    readStatus: async () => status,
    readDiag: async () => options.diag || { dispatchCount: 0, registryGrantPresent: false, agentOn: false, mode: 'MANUAL', outcomeUnknown: false, geometryEpoch: 1, pixelCopyQueued: 0, pixelCopyCompleted: 0, pixelCopyForwarded: 0, bitmapRecycled: false },
    dragTitle: async () => { trace.push('drag'); return { moved: options.drag !== false }; },
    offsetFrame: async () => { trace.push('offset'); return { moved: true }; },
    restoreFrame: async () => { trace.push('restore'); return true; },
    type: () => { trace.push('type'); return Promise.resolve(options.late || { ok: false, code: 'AUTHORIZATION_REQUIRED' }); },
    snapshotOnce: () => { trace.push('snapshot-once'); return Promise.resolve(options.once || { ok: false, code: 'AUTHORIZATION_REQUIRED' }); },
    snapshotFresh: async () => { trace.push('snapshot-fresh'); return options.fresh || { ok: true, data: { elements: [] } }; },
    useOldRef: async () => ({ ok: false, code: 'REF_EXPIRED' }),
    allow: async () => ({ ok: false, grantPresent: false }),
    openFresh: async () => ({ ok: true }),
    readInput: async () => options.input == null ? '' : options.input,
    capture: () => { trace.push('capture'); return Promise.resolve(options.capture || { ok: false }); },
    readCapture: async () => options.file === true,
  };
  return { trace, deps, status };
}

test('dispatch geometry stops when the held reply stage is missing and never drags', async () => {
  const { trace, deps } = flowDeps({ phase: 'idle' });
  const flow = await probe.runDispatchGeometry(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'dispatch-stage-missing');
  assert.equal(trace.includes('drag'), false);
  assert.equal(trace.includes('release:dhold'), true);
});

test('field geometry does not continue after a missed hold', async () => {
  const { trace, deps } = flowDeps({ phase: 'idle' });
  const flow = await probe.runFieldGeometry(deps);
  assert.equal(flow.ok, false);
  assert.equal(trace.includes('drag'), false);
});

test('snapshot geometry: a missing hold stage aborts before any drag', async () => {
  const { trace, deps } = flowDeps({ phase: 'idle' });
  const flow = await probe.runSnapshotGeometry(deps);
  assert.equal(flow.ok, false);
  assert.equal(trace.includes('drag'), false);
});

test('capture does not move the frame before PixelCopy is enqueued', async () => {
  const { trace, deps } = flowDeps({ phase: 'idle' });
  const flow = await probe.runCaptureGeometry({ ...deps, mode: 'frame' });
  assert.equal(flow.ok, false);
  assert.equal(trace.includes('offset'), false);
  assert.equal(trace.includes('release:chold'), true);
});

test('dispatch: an allow that only taps (no registry grant) cannot pass', async () => {
  const { deps } = flowDeps({ phase: 'dispatched' });
  const flow = await probe.runDispatchGeometry({
    ...deps,
    readDiag: async () => ({ dispatchCount: 1, registryGrantPresent: false, agentOn: false, mode: 'MANUAL', outcomeUnknown: true }),
    allow: async () => ({ ok: true, tapped: true, grantPresent: false }),
  });
  assert.equal(flow.ok, false);
});

test('capture geometry: a late bitmap that is not queued once cannot pass', async () => {
  const { deps, status } = flowDeps({ phase: 'capture-enqueued' });
  status.phase = 'capture-enqueued';
  const flow = await probe.runCaptureGeometry({
    ...deps,
    mode: 'frame',
    readDiag: async () => ({ pixelCopyQueued: 0, pixelCopyCompleted: 0, pixelCopyForwarded: 0, bitmapRecycled: false }),
    capture: async () => ({ ok: true, css: {}, png: 'x' }),
  });
  assert.equal(flow.ok, false);
});

test('refusal is a real page refusal, not NO_PROVIDER', () => {
  assert.equal(probe.refused({ ok: false, code: 'AUTHORIZATION_REQUIRED' }), true);
  assert.equal(probe.refused({ ok: false, code: 'NO_PROVIDER', detail: 'No Android browser provider' }), false);
});

test('verify script keeps geometry-gate as a focused, non-phase result', () => {
  const src = fs.readFileSync(path.join(__dirname, '../verify_openchamber_provider_emulator.cjs'), 'utf8');
  assert.match(src, /focus === 'geometry-gate'/);
  assert.doesNotMatch(src, /prepared\.typeInput/);
  assert.doesNotMatch(src, /\.fresh\.nonce|\.before\.nonce/);
});
