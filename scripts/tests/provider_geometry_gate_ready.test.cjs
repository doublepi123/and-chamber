'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const harness = require('../verify_openchamber_provider_emulator.cjs');

// Fake clock: sleeping advances time, so the bounded waits are exercised without real delays.
function clock() {
  let t = 0;
  return { now: () => t, sleep: async (ms) => { t += ms; await new Promise((r) => setImmediate(r)); }, tick: () => t };
}

const TAB = 'tab-fresh';
const SESSION = 'ses_owner';

function readyDiag(extra = {}) {
  return {
    phase: 'geometry-diag',
    selectedTabId: TAB,
    currentOwner: SESSION,
    connected: true,
    eligible: true,
    hostAttached: true,
    pageReady: true,
    registryGrantPresent: false,
    agentOn: false,
    mode: 'MANUAL',
    outcomeUnknown: false,
    dispatchCount: 0,
    ...extra,
  };
}

/**
 * Real adapter, fake device. The device reports readiness after `readyAfter` reads; before that the
 * Allow button is disabled, so a tap has no effect and the grant is never granted.
 */
function deviceReady(opts = {}) {
  const dev = { reads: 0, taps: 0, grant: false, calls: [], opened: 0 };
  const io = {
    call: async (action, input = {}) => {
      dev.calls.push(action);
      if (action === 'browser.open') {
        dev.opened += 1;
        dev.grant = false;
        return { ok: true, data: { tabId: opts.openedTab === undefined ? TAB : opts.openedTab, url: input.url } };
      }
      if (action === 'browser.snapshot') {
        return dev.grant
          ? { ok: true, data: { elements: [{ name: 'note', role: 'textbox', selector: '[data-andchamber-ref="r1"]' }] } }
          : { ok: false, error: { code: 'AUTHORIZATION_REQUIRED' } };
      }
      return { ok: true, data: {} };
    },
    tapAllow: async () => {
      dev.taps += 1;
      // A disabled button never grants. Only a ready page with the expected tab can grant.
      const ready = dev.reads >= (opts.readyAfter == null ? 1 : opts.readyAfter) && !opts.neverReady;
      if (ready) dev.grant = true;
      return true;
    },
    readDiagRaw: async () => {
      dev.reads += 1;
      if (opts.diagFor) return opts.diagFor(dev.reads);
      const ready = dev.reads >= (opts.readyAfter == null ? 1 : opts.readyAfter) && !opts.neverReady;
      return readyDiag({ pageReady: ready, selectedTabId: opts.selected === undefined ? TAB : opts.selected, registryGrantPresent: dev.grant, agentOn: dev.grant });
    },
    deviceCmd: async () => true,
    readStatus: async () => null,
    dragTitleRaw: async () => false,
    pageEvalRaw: async () => '',
    removeFile: async () => {},
    saveCapture: async () => false,
    fileExists: async () => false,
    now: null,
    sleepMs: null,
  };
  const c = clock();
  io.now = c.now;
  io.sleepMs = c.sleep;
  return { dev, io, clock: c };
}

test('ready wait: a pending page is not tapped until it is ready, then exactly one Allow', async () => {
  const { dev, io } = deviceReady({ readyAfter: 3 });
  const { adapters } = harness.createGeometryGateAdapters(io);
  const setup = await harness.prepareGeometryCaseForTest(adapters, 'https://example.com/openchamber-provider', { expectedSessionId: SESSION, now: io.now, sleepMs: io.sleepMs });
  assert.equal(setup.ok, true, JSON.stringify(setup));
  assert.equal(dev.taps, 1, 'exactly one tap after readiness');
  assert.ok(dev.reads >= 3, 'waited for readiness with read-only diag');
  assert.match(setup.selector, /^\[data-andchamber-ref=/);
});

test('ready wait expires: no tap, no type, and the case reports page-ready-timeout', async () => {
  const { dev, io, clock: c } = deviceReady({ neverReady: true });
  const { adapters } = harness.createGeometryGateAdapters(io);
  const setup = await harness.prepareGeometryCaseForTest(adapters, 'https://example.com/openchamber-provider', { expectedSessionId: SESSION, now: io.now, sleepMs: io.sleepMs });
  assert.equal(setup.ok, false);
  assert.equal(setup.reason, 'page-ready-timeout');
  assert.equal(dev.taps, 0, 'no Allow tap while the page is not ready');
  assert.equal(dev.calls.includes('browser.type'), false, 'no mutation');
  assert.ok(c.tick() >= 15000, 'bounded by the ready deadline');
});

test('wrong current tab is refused: no tap, page-ready-timeout reason', async () => {
  const { dev, io } = deviceReady({ selected: 'tab-other' });
  const { adapters } = harness.createGeometryGateAdapters(io);
  const setup = await harness.prepareGeometryCaseForTest(adapters, 'https://example.com/openchamber-provider', { expectedSessionId: SESSION, now: io.now, sleepMs: io.sleepMs });
  assert.equal(setup.ok, false);
  assert.equal(dev.taps, 0);
});

test('wrong owner session is refused', async () => {
  const { dev, io } = deviceReady({ diagFor: () => readyDiag({ currentOwner: 'ses_other' }) });
  const { adapters } = harness.createGeometryGateAdapters(io);
  const setup = await harness.prepareGeometryCaseForTest(adapters, 'https://example.com/openchamber-provider', { expectedSessionId: SESSION, now: io.now, sleepMs: io.sleepMs });
  assert.equal(setup.ok, false);
  assert.equal(dev.taps, 0);
});

// A fake probe that does real device IO after its deadline. The fence must stop every one of these.
function lateProbe(io, { hold, settleAfterMs = null, resumeAfterDeadline = true }) {
  return async (deps) => {
    // Stage 1: wait for the test to pass the deadline. Stage 2 would be a new mutation.
    await hold();
    if (!resumeAfterDeadline) return { ok: false, reason: 'stopped-by-test' };
    // Each step is a real adapter call, so the fence sees it.
    const out = { typed: await deps.type('late-1'), opened: await deps.openFresh(), allowed: await deps.allow(), dragged: await deps.dragTitle() };
    if (settleAfterMs != null) await deps.sleep(settleAfterMs);
    return { ok: out.typed.ok === true, reason: 'late-probe', out };
  };
}

function countingIo(base) {
  const counts = { mutating: 0, opens: 0, taps: 0, drags: 0, markers: [] };
  return {
    counts,
    io: {
      ...base,
      call: async (action, input) => {
        if (action === 'browser.type' || action === 'browser.click') counts.mutating += 1;
        if (action === 'browser.open') counts.opens += 1;
        return base.call(action, input);
      },
      tapAllow: async () => { counts.taps += 1; return base.tapAllow(); },
      dragTitleRaw: async () => { counts.drags += 1; return base.dragTitleRaw(); },
      deviceCmd: async (text, phase) => { if (text.startsWith('marker')) counts.markers.push(text); return base.deviceCmd(text, phase); },
    },
  };
}

test('timeout fences the case: a resumed old probe sends no new mutation, no open, no tap, no drag', async () => {
  const saved = {};
  const checks = [];
  const base = deviceReady({ readyAfter: 1 });
  const { io, counts } = countingIo(base.io);
  let release;
  const gate = new Promise((r) => { release = r; });
  const probeModule = require('../lib/provider_geometry_gate_probe.cjs');
  const original = probeModule.runDispatchGeometry;
  // The probe is parked; the runner times out, fences, then the probe resumes and tries to act.
  probeModule.runDispatchGeometry = lateProbe(io, { hold: () => gate, settleAfterMs: 0 });
  const fakeClock = { now: 0 };
  try {
    const run = harness.runGeometryGateCases(io, {
      url: 'https://example.com/openchamber-provider',
      save: (name, flow) => { saved[name] = flow; },
      check: (name, flow) => checks.push({ name, ok: flow && flow.ok }),
      cases: [{ name: 'geometry-b1-dispatch', flow: 'runDispatchGeometry' }, { name: 'geometry-b2a-field', flow: 'runFieldGeometry' }],
      expectedSessionId: SESSION,
      probeDeadlineMs: 30,
    });
    // Wait past the deadline (the probe sees the fence), then let the probe resume.
    await new Promise((r) => setTimeout(r, 120));
    release();
    const flows = await run.catch((error) => ({ aborted: error.name, detail: error.message }));
    // Setup for case 1 is one open and one Allow tap. The resumed probe's type/open/allow/drag were fenced.
    assert.equal(counts.opens, 1, 'only the setup open; the resumed probe never opened');
    assert.equal(counts.mutating, 0, 'no type/click reached the device after the fence');
    assert.equal(counts.taps, 1, 'only the setup Allow tap; the probe allow was fenced');
    assert.equal(counts.drags, 0, 'no drag after the fence');
    // Only this case's own hold is cleared, once. No other hold name appears in the marker traffic.
    const clears = counts.markers.filter((item) => item.startsWith('marker-clear:'));
    assert.deepEqual(clears, ['marker-clear:dhold'], 'exactly one owned hold cleared');
    assert.equal(counts.markers.some((item) => item.includes('fhold') || item.includes('shold') || item.includes('chold')), false, 'no other hold touched');
    assert.equal(saved['geometry-b1-dispatch.json'].terminal, 'client-timeout-no-retry');
    assert.ok(saved['geometry-b1-dispatch.json'].blockedAfterFence >= 1, 'the fence blocked at least one late action');
    assert.equal(flows.aborted, undefined, 'a settled late probe is not a focus abort');
  } finally {
    probeModule.runDispatchGeometry = original;
  }
  assert.equal(checks.length, 1, 'no second case ran after the timed-out case');
  assert.equal(saved['geometry-b2a-field.json'], undefined, 'next case did not start');
});

test('an unsettled probe after the fence is pending-failed and aborts the whole focus', async () => {
  const saved = {};
  const base = deviceReady({ readyAfter: 1 });
  const { io } = countingIo(base.io);
  const probeModule = require('../lib/provider_geometry_gate_probe.cjs');
  const original = probeModule.runDispatchGeometry;
  probeModule.runDispatchGeometry = () => new Promise(() => {});
  try {
    await assert.rejects(
      harness.runGeometryGateCases(io, {
        url: 'https://example.com/openchamber-provider',
        save: (name, flow) => { saved[name] = flow; },
        check: () => {},
        cases: [{ name: 'geometry-b1-dispatch', flow: 'runDispatchGeometry' }, { name: 'geometry-b2a-field', flow: 'runFieldGeometry' }],
        expectedSessionId: SESSION,
        probeDeadlineMs: 20,
      }),
      (error) => error.name === 'GeometryGateAbort',
    );
  } finally {
    probeModule.runDispatchGeometry = original;
  }
  assert.equal(saved['geometry-b1-dispatch-pending-failed.json'].terminal, 'pending-failed');
  assert.equal(saved['geometry-b2a-field.json'], undefined, 'focus stopped; next case never ran');
});

test('a settled-late timeout records the late result once and is never a success', async () => {
  const saved = {};
  const base = deviceReady({ readyAfter: 1 });
  const { io } = countingIo(base.io);
  let resolveLate;
  const probeModule = require('../lib/provider_geometry_gate_probe.cjs');
  const original = probeModule.runDispatchGeometry;
  probeModule.runDispatchGeometry = () => new Promise((resolve) => { resolveLate = resolve; });
  const run = harness.runGeometryGateCases(io, {
    url: 'https://example.com/openchamber-provider',
    save: (name, flow) => { saved[name] = flow; },
    check: () => {},
    cases: [{ name: 'geometry-b1-dispatch', flow: 'runDispatchGeometry' }],
    expectedSessionId: SESSION,
    probeDeadlineMs: 20,
  });
  // Settle the old probe inside the grace window with a "successful" verdict. It must still not count.
  setTimeout(() => resolveLate({ ok: true, reason: 'completed' }), 40);
  try {
    const flows = await run;
    const flow = flows['geometry-b1-dispatch'];
    assert.equal(flow.ok, false);
    assert.equal(flow.terminal, 'client-timeout-no-retry');
    assert.equal(flow.late.ok, true, 'late result is recorded read-only');
    assert.equal(Object.keys(saved).filter((k) => k === 'geometry-b1-dispatch.json').length, 1, 'one verdict only');
  } finally {
    probeModule.runDispatchGeometry = original;
  }
});

test('a normal case does not poison the next case: the fence is per case', async () => {
  const saved = {};
  const checks = [];
  const base = deviceReady({ readyAfter: 1 });
  const { io } = countingIo(base.io);
  const probeModule = require('../lib/provider_geometry_gate_probe.cjs');
  const originalDispatch = probeModule.runDispatchGeometry;
  const originalField = probeModule.runFieldGeometry;
  probeModule.runDispatchGeometry = async () => ({ ok: true, reason: 'completed' });
  probeModule.runFieldGeometry = async (deps) => {
    // The next case must be able to mutate even though the previous one finished normally.
    const typed = await deps.type('field-value');
    return { ok: typed.code !== FENCED_CODE_FOR_TEST, reason: 'field-ran' };
  };
  try {
    await harness.runGeometryGateCases(io, {
      url: 'https://example.com/openchamber-provider',
      save: (name, flow) => { saved[name] = flow; },
      check: (name, flow) => checks.push({ name, ok: flow.ok }),
      cases: [{ name: 'geometry-b1-dispatch', flow: 'runDispatchGeometry' }, { name: 'geometry-b2a-field', flow: 'runFieldGeometry' }],
      expectedSessionId: SESSION,
      probeDeadlineMs: 2000,
    });
  } finally {
    probeModule.runDispatchGeometry = originalDispatch;
    probeModule.runFieldGeometry = originalField;
  }
  assert.equal(checks.length, 2);
  assert.equal(saved['geometry-b2a-field.json'].ok, true);
});

const FENCED_CODE_FOR_TEST = 'FENCED';
