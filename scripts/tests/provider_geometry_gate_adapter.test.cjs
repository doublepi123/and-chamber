'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const harness = require('../lib/provider_gate_adapters.cjs');
const probe = require('../lib/provider_geometry_gate_probe.cjs');

test('geometry input reads preserve fixture strings through WebView JSON encoding', async () => {
  const vm = require('node:vm');
  for (const input of ['', 'gate-b1', 'gate-b1-again']) {
    const { adapters } = harness.createGeometryGateAdapters({
      pageEvalRaw: async (script) => {
        const value = vm.runInNewContext(script, {
          document: { querySelector: () => ({ value: input }) },
        });
        return harness.decodeEval({ phase: 'eval', value: JSON.stringify(value) });
      },
    });
    assert.equal(await adapters.readInput(), input);
  }
});

const FIXTURE_URL = 'https://example.com/openchamber-provider';
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52, 0, 0, 0x04, 0, 0, 0, 0x03, 0]);

/**
 * A simulated device. It has one status file, a real grant registry, a page input, a
 * dispatch counter and PixelCopy counters. The tool calls are async and only report what
 * the simulated device did, so no test can pass by hard-coded values.
 */
function makeDevice(opts = {}) {
  const dev = {
    status: null,
    holds: new Set(),
    grant: false,
    agentOn: false,
    mode: 'MANUAL',
    outcomeUnknown: false,
    input: '',
    dispatched: 0,
    epoch: 1,
    refSeq: 1,
    deadRefs: new Set(),
    frameOffset: 0,
    pixelCopy: { queued: 0, completed: 0, forwarded: 0 },
    bitmapRecycled: false,
    captureFile: false,
    calls: [],
    pending: [],
    capture: opts.capture || null,
    tapOk: opts.tapOk !== false,
    dragOk: opts.dragOk !== false,
    clock: 0,
  };
  const io = {
    now: () => dev.clock || 0,
    sleepMs: async (ms) => { dev.clock = (dev.clock || 0) + ms; await new Promise((r) => setImmediate(r)); },
    call: async (action, input = {}) => {
      dev.calls.push(action);
      await new Promise((r) => setImmediate(r));
      if (action === 'browser.open') {
        // An explicit fresh open replaces the page: the unknown outcome belongs to the old document.
        // The presenter grants default agent control once the page is ready; model that here.
        dev.grant = true; dev.agentOn = true; dev.mode = 'AGENT'; dev.outcomeUnknown = false;
        return { ok: true, data: { tabId: 'tab-a', url: input.url } };
      }
      if (action === 'browser.capture') {
        if (!dev.grant) return { ok: false, error: { code: 'AUTHORIZATION_REQUIRED', message: 'AUTHORIZATION_REQUIRED' } };
        return opts.captureReply || { ok: true, data: { path: 'home/capture.png' } };
      }
      if (!dev.grant) return { ok: false, error: { code: 'AUTHORIZATION_REQUIRED', message: 'AUTHORIZATION_REQUIRED' }, redacted: 'AUTHORIZATION_REQUIRED' };
      if (action === 'browser.snapshot') {
        return { ok: true, data: { elements: [{ name: 'note', role: 'textbox', selector: `[data-andchamber-ref="ref-${dev.refSeq}"]` }] } };
      }
      if (action === 'browser.type') {
        if (dev.deadRefs.has(input.selector)) return { ok: false, error: { code: 'REF_EXPIRED', message: 'REF_EXPIRED' } };
        if (dev.outcomeUnknown) return { ok: false, error: { code: 'OUTCOME_UNKNOWN', message: 'OUTCOME_UNKNOWN' } };
        dev.dispatched += 1;
        dev.input = input.value;
        if (dev.holds.has('dhold')) {
          dev.status = { phase: 'dispatched' };
          // The grant is gone and the page is isolated while the reply is held.
          dev.grant = false; dev.agentOn = false; dev.mode = 'MANUAL'; dev.outcomeUnknown = true;
          await new Promise((resolve) => dev.pending.push(resolve));
          // The held mutation really ran; once the page was isolated its reply is unknown, not ok.
          return { ok: false, error: { code: 'OUTCOME_UNKNOWN', message: 'OUTCOME_UNKNOWN' } };
        }
        return { ok: true, data: {} };
      }
      if (action === 'browser.click') {
        if (dev.deadRefs.has(input.selector)) return { ok: false, error: { code: 'REF_EXPIRED', message: 'REF_EXPIRED' } };
        return { ok: true, data: {} };
      }
      return { ok: true, data: {} };
    },
    tapAllow: async () => {
      dev.tapAllows = (dev.tapAllows || 0) + 1;
      await new Promise((r) => setImmediate(r));
      if (!dev.tapOk) return false;
      // An isolated page (outcome unknown) refuses a grant even though the tap landed.
      if (dev.outcomeUnknown) return true;
      // The real registry grant is what the device reports afterwards; the tap alone is not authority.
      if (opts.grantOnTap !== false) { dev.grant = true; dev.agentOn = true; dev.mode = 'AGENT'; }
      return true;
    },
    deviceCmd: async (text, phase) => {
      if (text.startsWith('marker:')) { dev.holds.add(text.slice(7)); return true; }
      if (text.startsWith('marker-clear:')) {
        const name = text.slice(13);
        dev.holds.delete(name);
        if (name === 'dhold') { dev.pending.splice(0).forEach((r) => r()); }
        return true;
      }
      if (text === 'frame-offset') { dev.frameOffset += 8; dev.status = { phase: 'frame-offset', applied: true }; return true; }
      if (text === 'frame-restore') { dev.frameOffset -= 8; dev.status = { phase: 'frame-restored', applied: true }; return true; }
      return phase === 'marker-set' || phase === 'marker-cleared';
    },
    readStatus: async (phase) => (dev.status && dev.status.phase === phase ? dev.status : null),
    readDiagRaw: async () => ({
      phase: 'geometry-diag',
      agentOn: dev.agentOn,
      registryGrantPresent: dev.grant,
      mode: dev.mode,
      outcomeUnknown: dev.outcomeUnknown,
      dispatchCount: dev.dispatched,
      geometryEpoch: dev.epoch,
      pixelCopyQueued: dev.pixelCopy.queued,
      pixelCopyCompleted: dev.pixelCopy.completed,
      pixelCopyForwarded: dev.pixelCopy.forwarded,
      bitmapRecycled: dev.bitmapRecycled,
    }),
    dragTitleRaw: async () => {
      await new Promise((r) => setImmediate(r));
      if (!dev.dragOk) return false;
      dev.epoch += 1;
      dev.refSeq += 1;
      dev.deadRefs.add(`[data-andchamber-ref="ref-${dev.refSeq - 1}"]`);
      return true;
    },
    pageEvalRaw: async () => dev.input,
    removeFile: async () => { dev.captureFile = false; },
    saveCapture: async (relative, name) => { dev.captureFile = true; return Boolean(relative) && name !== '' && opts.saveCapture !== false; },
    fileExists: async () => dev.captureFile,
  };
  return { dev, io };
}

test('adapter awaits the tool reply: normalised, never a Promise', async () => {
  const { io } = makeDevice();
  const { adapters } = harness.createGeometryGateAdapters(io);
  await adapters.openFresh(FIXTURE_URL);
  await adapters.allow();
  const typed = await adapters.type('[data-andchamber-ref="ref-1"]', 'x');
  assert.equal(typeof typed, 'object');
  assert.equal(typeof typed.ok, 'boolean');
  assert.equal(typeof typed.code, 'string');
});

test('an unauthorized reply stays ok:false with its raw AUTHORIZATION_REQUIRED code', async () => {
  const { io, dev } = makeDevice();
  const { adapters } = harness.createGeometryGateAdapters(io);
  await adapters.openFresh(FIXTURE_URL);
  // An explicit takeover removes control; the next tool call is refused, not re-granted.
  dev.grant = false; dev.agentOn = false; dev.mode = 'MANUAL';
  const refused = await adapters.snapshotOnce();
  assert.equal(refused.ok, false);
  assert.equal(refused.code, 'AUTHORIZATION_REQUIRED');
  assert.match(refused.redacted, /AUTHORIZATION_REQUIRED/);
  assert.equal(probe.refused(refused), true);
});

test('NO_PROVIDER is a setup failure, not an authorization refusal', () => {
  const noProvider = { ok: false, code: 'NO_PROVIDER', detail: 'No Android browser provider' };
  assert.equal(probe.refused(noProvider), false);
  assert.equal(probe.noProvider(noProvider), true);
});

test('allow reads the default grant without a tap; a tap can never be the proof', async () => {
  const { io, dev } = makeDevice({ grantOnTap: false });
  // Model a default open that never produced its grant: allow() must report that, not tap.
  const open = io.call;
  io.call = async (action, input) => {
    const reply = await open(action, input);
    if (action === 'browser.open') dev.grant = false;
    return reply;
  };
  const { adapters } = harness.createGeometryGateAdapters(io);
  await adapters.openFresh(FIXTURE_URL);
  const allowed = await adapters.allow();
  assert.equal(allowed.ok, false);
  assert.equal(allowed.source, 'no-default-grant');
  assert.equal(allowed.tapped, false);
  assert.equal(allowed.grantPresent, false);
  assert.equal(dev.grant, false);
  assert.equal(dev.tapAllows || 0, 0, 'no UI tap without an explicit manual-takeover hand-back');
});

test('an explicit hand-back is opt-in and still needs the registry read', async () => {
  const { io, dev } = makeDevice({ grantOnTap: false });
  const open = io.call;
  io.call = async (action, input) => {
    const reply = await open(action, input);
    if (action === 'browser.open') { dev.grant = false; dev.agentOn = false; dev.mode = 'MANUAL'; }
    return reply;
  };
  const { adapters } = harness.createGeometryGateAdapters(io);
  await adapters.openFresh(FIXTURE_URL);
  const tapped = await adapters.allow({ handback: true });
  assert.equal(tapped.ok, false);
  assert.equal(tapped.source, 'manual-handback');
  assert.equal(tapped.tapped, true);
  assert.equal(tapped.grantPresent, false);
  assert.equal(dev.tapAllows, 1, 'one hand-back tap, never retried');
});

test('each case reruns open, real Allow and a snapshot that yields a real note ref', async () => {
  const { io, dev } = makeDevice();
  const { adapters } = harness.createGeometryGateAdapters(io);
  const first = await adapters.snapshotOnce();
  assert.equal(first.ok, false);
  await adapters.openFresh(FIXTURE_URL);
  await adapters.allow();
  const ok = await adapters.snapshotOnce();
  assert.equal(ok.ok, true);
  assert.match(ok.data.elements[0].selector, /^\[data-andchamber-ref=/);
  assert.ok(dev.calls.includes('browser.open'));
});

test('runGeometryGateCases runs the real device adapter for every case with a fresh setup', async () => {
  const { io, dev } = makeDevice();
  const saved = {};
  const checks = [];
  const flows = await harness.runGeometryGateCases(io, {
    url: FIXTURE_URL,
    save: (name, flow) => { saved[name] = flow; },
    check: (name, flow) => checks.push({ name, ok: flow && flow.ok }),
  });
  assert.deepEqual(Object.keys(flows).sort(), ['geometry-b1-dispatch', 'geometry-b2a-field', 'geometry-b2b-snapshot', 'geometry-b2c-capture'].sort());
  // Every case opens a fresh page and grants again: at least four opens and four allows.
  assert.ok(dev.calls.filter((item) => item === 'browser.open').length >= 4);
  assert.equal(checks.length, 4);
});

test('uncertain first mutation: a refused second mutation leaves the input unchanged', async () => {
  const { io, dev } = makeDevice();
  const { adapters } = harness.createGeometryGateAdapters(io);
  await adapters.openFresh(FIXTURE_URL);
  await adapters.allow();
  dev.holds.add('dhold');
  const flow = await probe.runDispatchGeometry({
    ...adapters,
    type: (value) => adapters.type('[data-andchamber-ref="ref-1"]', value),
    useOldRef: () => adapters.useOldRef('[data-andchamber-ref="ref-1"]'),
    openFresh: () => adapters.openFresh(FIXTURE_URL),
    allow: () => adapters.allow(),
  });
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 800));
  assert.equal(flow.after.outcomeUnknown, true);
  assert.equal(flow.after.registryGrantPresent, false);
  assert.equal(flow.allow.ok, false);
  assert.equal(flow.input.afterFirst, flow.input.afterSecond);
  assert.equal(flow.dispatchCount.afterAgain, flow.dispatchCount.held);
  assert.equal(flow.restored.ok, true);
});

test('snapshot geometry compares real selector fingerprints, not nonce fields', () => {
  const a = { data: { elements: [{ name: 'note', role: 'textbox', selector: '[data-andchamber-ref="a"]' }] } };
  const b = { data: { elements: [{ name: 'note', role: 'textbox', selector: '[data-andchamber-ref="b"]' }] } };
  assert.notEqual(probe.refFingerprint(a), probe.refFingerprint(b));
  assert.equal(probe.refFingerprint({ data: {} }), null);
});

test('a missed hold stage aborts without moving the window', async () => {
  const trace = [];
  let clock = 0;
  const flow = await probe.runDispatchGeometry({
    now: () => clock,
    sleep: async (ms) => { clock += ms; },
    setMarker: async () => true,
    releaseMarker: async (name) => { trace.push(`release:${name}`); return true; },
    readStatus: async () => null,
    readDiag: async () => ({ dispatchCount: 0 }),
    dragTitle: async () => { trace.push('drag'); return { moved: true }; },
    type: async () => ({ ok: false, code: 'AUTHORIZATION_REQUIRED' }),
  });
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'dispatch-stage-missing');
  assert.equal(trace.includes('drag'), false);
  assert.equal(trace.includes('release:dhold'), true);
});

test('PixelCopy: a copy that was never queued cannot pass the capture case', async () => {
  const flow = await probe.runCaptureGeometry({
    now: () => 0,
    sleep: async () => {},
    setMarker: async () => true,
    releaseMarker: async () => true,
    readStatus: async () => ({ phase: 'capture-enqueued' }),
    readDiag: async () => ({ pixelCopyQueued: 0, pixelCopyCompleted: 0, pixelCopyForwarded: 0, bitmapRecycled: false }),
    dragTitle: async () => ({ moved: true }),
    offsetFrame: async () => ({ moved: true }),
    restoreFrame: async () => true,
    capture: async () => ({ ok: false, code: 'AUTHORIZATION_REQUIRED' }),
    readCapture: async () => false,
    mode: 'frame',
  });
  assert.equal(flow.ok, false);
});

test('gate report never claims a phase pass', () => {
  const pass = harness.result([{ name: 'geometry-b1-dispatch', result: 'PASS', detail: 'ok' }], 'geometry-gate');
  assert.equal(pass.phaseComplete, false);
  assert.equal(pass.result, 'FAIL');
  assert.equal(pass.scope, 'geometry-race-targeted');
});

