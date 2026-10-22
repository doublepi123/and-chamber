'use strict';

// Focus-level tests. They run the real adapter (readLifecycle merge, prepareLifecycleCase setup) against the
// producer shapes that the device actually returns, and then the real probe flows over that adapter. Missing
// sources must stay null, and a null field must never pass a case. The pure flow tests are kept separately.

const assert = require('node:assert/strict');
const test = require('node:test');
const harness = require('../lib/provider_gate_adapters.cjs');
const lifecycle = require('../lib/provider_lifecycle_gate_probe.cjs');

/**
 * Scene-budget device. Every serial device read advances a fake clock by [readMs], so one lifecycle read costs
 * several round trips. Reads are counted per stage, and the page keeps its real Enlarge/Restore state. A mutation
 * advances the clock by [tapMs] (a real tap is short and well inside the per-stage budget).
 */
function timedDevice({ readMs = 900, tapMs = 300, restoreKind = 'Restored', restoreY = 893, sy = 893 } = {}) {
  const clock = { t: 0 };
  const dev = {
    producer: { phase: 'lifecycle-diag', selectedTabId: 'tab-a', tabIds: ['tab-a'], pageRetired: false, agentOn: true, grantForSelected: true, mousePressActive: false, mode: 'MANUAL', desktopMode: false, mouseMode: true, pageReady: true, viewHandle: 7, documentEpoch: 1, restore: null, recorderReady: true, totalClicks: 0, recordedDocuments: {}, lastRetired: null },
    metrics: { ua: 'mobile', clientWidth: 390, media1000: false, sy, loads: 1, timeOrigin: 1700000000.5 },
    fixture: { page: { url: 'https://example.com/openchamber-provider-mouse', loads: 1, posts: 0 }, postArrivals: 0, native: null },
    enlarged: false,
    counts: { taps: 0, reads: 0, mutations: 0 },
    order: [],
  };
  const read = async (name, value) => {
    clock.t += readMs;
    dev.counts.reads += 1;
    dev.order.push(name);
    return value;
  };
  const io = {
    now: () => clock.t,
    sleepMs: async (ms) => { clock.t += ms; await new Promise((r) => setImmediate(r)); },
    readLifecycleRaw: () => read('diag', dev.producer),
    readFixtureRaw: () => read('fixture', dev.fixture),
    readMetricsRaw: () => read('metrics', { ...dev.metrics, sy: dev.metrics.sy }),
    readUiRaw: () => read('ui', { enlarged: dev.enlarged, surface: { left: 0, top: 0, right: 1024, bottom: 800 } }),
    tapLabelRaw: async (label) => {
      clock.t += tapMs;
      dev.counts.taps += 1;
      dev.counts.mutations += 1;
      dev.order.push(`tap:${label}`);
      if (label === 'Enlarge') { dev.enlarged = true; dev.producer.restore = null; return true; }
      if (label === 'Restore') {
        dev.enlarged = false;
        dev.producer.restore = restoreKind === 'Restored' ? { kind: 'Restored', actualX: 0, actualY: restoreY } : { kind: restoreKind, reason: 'PAGE_CHANGED' };
        dev.metrics.sy = restoreY;
        return true;
      }
      return false;
    },
    call: async () => { dev.counts.mutations += 1; return { ok: false, error: { code: 'UNSUPPORTED' } }; },
  };
  return { dev, io, clock };
}

test('scene budget: 90s covers serial reads that together exceed 20s, and a correct restore still passes', async () => {
  // Four serial reads of ~1.6 s each make one lifecycle read ~6.4 s, the real order of magnitude on the device.
  const { dev, io, clock } = timedDevice({ readMs: 1600, tapMs: 300 });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const started = clock.t;
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  const sceneMs = clock.t - started;
  assert.ok(sceneMs > 20000, `the scene really spent more than the old 20s limit: ${sceneMs}ms`);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 600));
  assert.equal(flow.restore.kind, 'Restored');
  // Every stage stays inside its own per-stage budget; the scene total is what exceeded 20s.
  for (const stage of flow.stages.filter((item) => item.elapsedMs != null && item.outcome !== 'read')) {
    assert.ok(stage.elapsedMs <= 20000, `${stage.label} ${stage.elapsedMs}ms is inside its own 20s stage budget`);
  }
  assert.deepEqual(dev.order.filter((name) => name.startsWith('tap:')), ['tap:Enlarge', 'tap:Restore'], 'one Enlarge and one Restore only');
});

test('a wrong restore value stays FAIL under the 90s scene budget (no product value is hidden by budget)', async () => {
  const { io, clock } = timedDevice({ readMs: 1600, tapMs: 300, restoreY: 488 });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  assert.ok(clock.t > 20000, 'the scene ran past the old 20s limit');
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'enlarge-restore-same-doc-failed');
  assert.equal(flow.restore.actual.y, 488, 'the actual wrong value is reported, not replaced');
});

test('a scene longer than its budget is cut in real time: the timeout keeps stages, last read and blocks later taps', async () => {
  // Real-time delays: the runner's deadline is wall-clock, so the device must really be slow for the cut to happen.
  // Each source read takes 40 ms (a full lifecycle read is ~160 ms). The 300 ms budget lets the first complete
  // read finish and cuts the next stage, so the last complete state is a real read, not a guess.
  const dev = { enlarged: false, restore: null, mutations: 0, reads: 0 };
  const slow = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const io = {
    now: () => Date.now(),
    sleepMs: async (ms) => slow(ms),
    readLifecycleRaw: async () => { dev.reads += 1; await slow(40); return { phase: 'lifecycle-diag', selectedTabId: 'tab-a', tabIds: ['tab-a'], pageRetired: false, agentOn: true, grantForSelected: true, mousePressActive: false, mode: 'MANUAL', desktopMode: false, mouseMode: true, pageReady: true, viewHandle: 7, documentEpoch: 1, restore: dev.restore, recorderReady: true, totalClicks: 0, recordedDocuments: {}, lastRetired: null }; },
    readFixtureRaw: async () => { await slow(40); return { page: { url: 'x', loads: 1, posts: 0 }, postArrivals: 0, native: null }; },
    readMetricsRaw: async () => { await slow(40); return { ua: 'mobile', clientWidth: 390, media1000: false, sy: 893, loads: 1, timeOrigin: 1700000000.5 }; },
    readUiRaw: async () => { await slow(40); return { enlarged: dev.enlarged, surface: { left: 0, top: 0, right: 1, bottom: 1 } }; },
    tapLabelRaw: async (label) => { dev.mutations += 1; await slow(5); if (label === 'Enlarge') { dev.enlarged = true; dev.restore = null; } return true; },
    call: async () => ({ ok: false, error: { code: 'UNSUPPORTED' } }),
  };
  const fence = harness.createCaseFence();
  const guarded = harness.createLifecycleGateAdapters(io, fence);
  const flow = await harness.runOneLifecycleCase(
    { name: 'lifecycle-b5-enlarge-restore', flow: 'runEnlargeRestoreSameDoc' },
    { deps: { ...guarded, readLifecycle: guarded.readLifecycle, lastRead: guarded.lastRead, now: io.now, sleep: io.sleepMs }, fence, adapters: guarded, probeDeadlineMs: 300, save: () => {}, now: io.now },
  );
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'scene-budget-exhausted');
  assert.equal(flow.sceneBudgetMs, 300);
  assert.ok(Array.isArray(flow.stages) && flow.stages.length > 0, 'the stages up to the cut are kept');
  assert.ok(flow.stages.every((stage) => typeof stage.label === 'string'), 'every stage is named');
  assert.ok(flow.lastRead && flow.lastRead.state && typeof flow.lastRead.state === 'object', 'the last complete read is kept');
  assert.equal(typeof flow.lastRead.at, 'number', 'and its time');
  // After the cut the fence refuses: a late tap is counted, not sent.
  assert.equal(fence.state.stopped, true, 'the fence was stopped at the cut');
  const sentAtCut = dev.mutations;
  const late = await guarded.tapEnlarge();
  assert.equal(late.code, 'FENCED', 'a post-cut tap is refused by the fence');
  assert.equal(dev.mutations, sentAtCut, 'and never reaches the device');
  assert.ok(fence.state.blocked >= 1, 'the refusal is counted');
});

/**
 * Cross-case state machine. One device lives across cases, exactly like the real app. The open request decides the
 * initial native mode (an undeclared open is mobile). A Computer/Phone menu switch and Mouse on/off change native
 * state only when the real menu is used. A stale desktop or mouse state from a previous case is kept until a real
 * action changes it, so a case can never silently inherit the wrong mode.
 */
function stateDevice(opts = {}) {
  const dev = {
    producer: { phase: 'lifecycle-diag', selectedTabId: null, tabIds: [], pageRetired: false, agentOn: false, grantForSelected: false, mousePressActive: false, mode: 'MANUAL', desktopMode: false, mouseMode: false, pageReady: false, viewHandle: 0, documentEpoch: 0, restore: null, recorderReady: true, totalClicks: 0, recordedDocuments: {}, lastRetired: null },
    fixture: { page: { url: 'x', loads: 1, posts: 0 }, postArrivals: 0, native: { physicalFrame: { left: 0, top: 0, width: 390, height: 800 } } },
    metrics: { ua: 'mobile', clientWidth: 390, media1000: false, sy: 0, loads: 1, timeOrigin: 1700000000.5 },
    ui: { enlarged: false, surface: null },
    openRequests: [],
    menuTaps: [],
    nextTab: 0,
    clock: 0,
  };
  // The fixture URLs the real focus sets on its io (focusLifecycleGate). Without them a case would open undefined.
  const io = {
    now: () => dev.clock,
    sleepMs: async (ms) => { dev.clock += ms; await new Promise((r) => setImmediate(r)); },
    longUrl: 'https://example.com/openchamber-provider-mouse',
    fixtureUrl: 'https://example.com/openchamber-provider-mouse',
    postLoginUrl: 'https://example.com/openchamber-provider-login',
    call: async (action, input = {}) => {
      if (action !== 'browser.open') return { ok: false, error: { code: 'UNSUPPORTED' } };
      dev.openRequests.push({ url: input.url, viewport: input.viewport ?? null });
      dev.nextTab += 1;
      const tabId = `tab-${dev.nextTab}`;
      // The request's viewport is applied natively. Without one the page opens mobile (the product default).
      dev.producer.desktopMode = input.viewport === 'desktop';
      dev.producer.mouseMode = false;
      dev.metrics.ua = dev.producer.desktopMode ? 'desktop' : 'mobile';
      dev.metrics.clientWidth = dev.producer.desktopMode ? 1024 : 390;
      dev.metrics.media1000 = dev.producer.desktopMode;
      dev.producer.selectedTabId = tabId;
      dev.producer.tabIds = [...dev.producer.tabIds, tabId];
      dev.producer.pageReady = true;
      dev.producer.viewHandle = 100 + dev.nextTab;
      dev.fixture.page.url = input.url;
      return { ok: true, data: { tabId, url: input.url } };
    },
    readLifecycleRaw: async () => ({ ...dev.producer, tabIds: [...dev.producer.tabIds] }),
    readFixtureRaw: async () => dev.fixture,
    readMetricsRaw: async () => ({ ...dev.metrics }),
    readUiRaw: async () => ({ ...dev.ui }),
    // Real menu taps. Mouse is "Mouse" from the Touch menu; Computer/Phone are the layout items.
    tapLabelRaw: async (label) => {
      dev.menuTaps.push(label);
      if (label === 'Mouse') { dev.producer.mouseMode = true; return true; }
      if (label === 'Touch') { dev.producer.mouseMode = false; return true; }
      if (label === 'Computer layout' || label === 'Phone layout') return opts.hideLayoutItems ? false : true;
      if (label === 'Confirm') {
        // Confirm applies the layout chosen from the menu (Computer -> desktop, Phone -> mobile).
        dev.pendingLayout = dev.pendingLayout || null;
        return true;
      }
      return true;
    },
    enableMouseRaw: async () => { dev.menuTaps.push('More'); dev.menuTaps.push('Mouse'); dev.producer.mouseMode = true; return true; },
    // A two-finger wheel scrolls the page by the requested amount (clamped to the long page). It is measured, not set.
    twoFingerRaw: async (dy) => {
      dev.menuTaps.push(`wheel:${dy}`);
      dev.metrics.sy = Math.max(0, Math.min(900, dev.metrics.sy - dy));
      return true;
    },
    switchModeRaw: async (target) => { dev.menuTaps.push(target); dev.producer.desktopMode = target === 'computer'; return true; },
    selectRaw: async () => true,
    submitLoginFresh: async () => ({ ok: true, tabId: 'tab-post', beforePosts: 0, beforeArrivals: 0, landing: { url: 'x' }, finalState: {} }),
    releaseOwnedRaw: async () => true,
  };
  return { dev, io };
}

test('each case opens with its declared viewport in the request; a stale desktop from a previous case is not inherited', async () => {
  const { dev, io } = stateDevice();
  // Case 1 (B5 long page) left the device in desktop with mouse on.
  const first = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b5-enlarge-restore', setup: 'longPageScrolled', viewport: 'desktop' },
    { adapters: harness.createLifecycleGateAdapters(io), io, expectedSessionId: 'session-a' },
  );
  assert.equal(first.ok, true, JSON.stringify(first).slice(0, 400));
  assert.equal(dev.openRequests[0].viewport, 'desktop', 'the desktop case asks for desktop in its open request');
  // Case 2 declares mobile. Its open request must say mobile, and the native state must read mobile afterwards.
  const second = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b3-mode-mobile-to-desktop', setup: 'mobileFixture', viewport: 'mobile' },
    { adapters: harness.createLifecycleGateAdapters(io), io, expectedSessionId: 'session-a' },
  );
  assert.equal(second.ok, true, JSON.stringify(second).slice(0, 400));
  assert.equal(dev.openRequests[1].viewport, 'mobile', 'the mobile case asks for mobile in its request, never inherits desktop');
  assert.equal(dev.producer.desktopMode, false, 'native reads mobile for the mobile case');
});

test('a declared viewport that native does not apply fails the case, and nothing is repaired by a menu click', async () => {
  const { dev, io } = stateDevice();
  // The device ignores the request viewport: the page stays desktop although mobile was declared.
  const origOpen = io.call;
  io.call = async (action, input) => {
    const res = await origOpen(action, input);
    if (action === 'browser.open') dev.producer.desktopMode = true;
    return res;
  };
  const setup = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b3-mode-mobile-to-desktop', setup: 'mobileFixture', viewport: 'mobile' },
    { adapters: harness.createLifecycleGateAdapters(io), io, expectedSessionId: 'session-a' },
  );
  assert.equal(setup.ok, false);
  assert.equal(setup.reason, 'setup-declared-viewport-not-applied');
  assert.equal(dev.menuTaps.length, 0, 'no menu click was used to repair the mode');
});

test('an active native Mouse state is not tapped again; an off state is tapped once and read back', async () => {
  // Direct check of the rule. The open request itself resets mouse to touch in the model (a new page), so the
  // "already on" branch is tested on the rule, not through an open that would reset it.
  const onAlreadyIo = stateDevice();
  const alreadyOn = await harness.ensureNativeMouseOn(onAlreadyIo.io, { mouseMode: true });
  assert.equal(alreadyOn.ok, true);
  assert.equal(alreadyOn.tapped, false, 'an active Mouse state is not tapped');
  assert.equal(onAlreadyIo.dev.menuTaps.filter((label) => label === 'Mouse').length, 0);

  const { dev: dev2, io: io2 } = stateDevice();
  const offCase = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b5-enlarge-restore', setup: 'longPageScrolled', viewport: 'desktop' },
    { adapters: harness.createLifecycleGateAdapters(io2), io: io2, expectedSessionId: 'session-a' },
  );
  assert.equal(offCase.ok, true, JSON.stringify(offCase).slice(0, 400));
  assert.equal(dev2.menuTaps.filter((label) => label === 'Mouse').length, 1, 'mouse off: exactly one real tap');
});

/**
 * Drives the real shared primitive switchMouseOn with injected device reads/taps and a fake clock. The Window menu
 * offers only "Touch" when Mouse is on, so the primitive must not tap a missing "Mouse" item in that state.
 */
function mouseDevice({ mouse = false, readable = true, mouseTapWorks = true, stayOff = false } = {}) {
  const dev = { mouse, taps: [], clock: 0 };
  const io = {
    readMouse: async () => (readable ? dev.mouse : null),
    tap: async (label) => {
      dev.taps.push(label);
      if (label === 'Mouse' && mouseTapWorks && !stayOff) dev.mouse = true;
      if (label === 'Mouse' && !mouseTapWorks) return false;
      return true;
    },
    wait: async (ms) => { dev.clock += ms; },
    now: () => dev.clock,
  };
  return { dev, io };
}

test('switchMouseOn when native mouse is already on: returns true and taps nothing (no missing Mouse item)', async () => {
  const { dev, io } = mouseDevice({ mouse: true });
  assert.equal(await harness.switchMouseOn({}, io), true);
  assert.deepEqual(dev.taps, [], 'the menu offers Touch in this state; no tap is made');
});

test('switchMouseOn when native mouse is off: exactly one More and one Mouse, then read back true', async () => {
  const { dev, io } = mouseDevice({ mouse: false });
  assert.equal(await harness.switchMouseOn({}, io), true);
  assert.deepEqual(dev.taps, ['More', 'Mouse'], 'one real More then one Mouse');
  assert.equal(dev.mouse, true, 'and the native state confirms it');
});

test('switchMouseOn with an unreadable native state refuses and taps nothing', async () => {
  const { dev, io } = mouseDevice({ readable: false });
  assert.equal(await harness.switchMouseOn({}, io), false);
  assert.deepEqual(dev.taps, [], 'nothing is tapped blind');
});

test('switchMouseOn that taps Mouse but native never reports it on: bounded refusal, no repeat tap', async () => {
  const { dev, io } = mouseDevice({ mouse: false, stayOff: true });
  assert.equal(await harness.switchMouseOn({}, io), false);
  assert.deepEqual(dev.taps, ['More', 'Mouse'], 'one attempt; the failure is reported, not retried');
});

test('an unreadable mouse state refuses the case rather than tapping blind', async () => {
  const { io } = stateDevice();
  io.readLifecycleRaw = async () => ({ phase: 'lifecycle-diag', selectedTabId: 'tab-x', tabIds: ['tab-x'], desktopMode: true, pageReady: true, mouseMode: undefined });
  const result = await harness.ensureNativeMouseOn(io, { mouseMode: undefined });
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'mouse-state-unreadable');
});

/**
 * B5-2 user-scroll-wins device. Saved offset 893. Enlarge clamps the page to the expanded Y (488). The user's
 * two-finger move sets the Y to `userY`. Restore returns `restoreKind`/`restoreReason`. Each knob is set by the case.
 * The restore is observed only after a clear: the device clears restore on Enlarge, as Core does.
 */
function userScrollDevice({ userY = 300, expandedY = 488, restoreKind = 'NotSaved', restoreReason = 'INTERACTION_CHANGED', restoreActualY = null, clearOnEnlarge = true, identityChanged = false, wheelMoves = true } = {}) {
  // The previous cycle's result is still present before Enlarge. When Enlarge does not clear it, it must be seen.
  const dev = { enlarged: false, sy: 893, restore: clearOnEnlarge ? null : { kind: 'Restored', reason: null, actual: { x: 0, y: 893 } }, taps: [], wheels: 0, loads: 1, timeOrigin: 1700000000.5, tabId: 'tab-a', handle: 7, identityChanged: false, clock: 0 };
  const io = {
    now: () => dev.clock || 0,
    sleepMs: async (ms) => { dev.clock = (dev.clock || 0) + ms; await new Promise((r) => setImmediate(r)); },
    readLifecycleRaw: async () => ({ phase: 'lifecycle-diag', selectedTabId: dev.tabId, tabIds: [dev.tabId], pageRetired: false, agentOn: false, grantForSelected: false, mousePressActive: false, mode: 'MANUAL', desktopMode: false, mouseMode: true, pageReady: true, viewHandle: dev.handle, documentEpoch: 1, restore: dev.restore, recorderReady: true, totalClicks: 0, recordedDocuments: {}, lastRetired: null }),
    readFixtureRaw: async () => ({ page: { url: 'https://example.com/openchamber-provider-mouse', loads: dev.loads, posts: 0 }, postArrivals: 0, native: { physicalFrame: { left: 0, top: 0, width: 390, height: 800 } } }),
    readMetricsRaw: async () => ({ ua: 'mobile', clientWidth: 390, media1000: false, sy: dev.sy, loads: dev.loads, timeOrigin: dev.identityChanged ? 1 : dev.timeOrigin }),
    readUiRaw: async () => ({ enlarged: dev.enlarged, surface: { left: 0, top: 0, right: 1, bottom: 1 } }),
    tapLabelRaw: async (label) => {
      dev.taps.push(label);
      if (label === 'Enlarge') {
        dev.enlarged = true;
        dev.sy = Math.min(dev.sy, expandedY);
        if (clearOnEnlarge) dev.restore = null;
      }
      if (label === 'Restore') {
        // A real document change happens during the restore cycle, not before the saved read.
        if (identityChanged) dev.identityChanged = true;
        dev.enlarged = false;
        // Restore is observed only once it is set: the case decides its kind, reason and actual Y.
        dev.restore = restoreKind === 'NotSaved'
          ? { kind: 'NotSaved', reason: restoreReason, actual: null }
          : { kind: restoreKind, reason: restoreReason, actual: restoreActualY == null ? null : { x: 0, y: restoreActualY } };
        if (restoreActualY != null) dev.sy = restoreActualY;
      }
      return true;
    },
    twoFingerRaw: async () => {
      dev.wheels += 1;
      if (wheelMoves) dev.sy = userY;
      return true;
    },
    call: async () => ({ ok: false, error: { code: 'UNSUPPORTED' } }),
    readLifecycle: undefined,
  };
  // identityChanged is applied only at the Restore tap (see tapLabelRaw), never before the saved read.
  return { dev, io };
}

function userScrollAdapters(io) {
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  return { ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs };
}

test('B5-2: a restore that is not cleared on enlarge stops before any user move', async () => {
  const { io, dev } = userScrollDevice({ clearOnEnlarge: false });
  const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'restore-not-cleared-on-enlarge');
  assert.equal(dev.wheels, 0, 'no user scroll was sent after the uncleared state was seen');
});

test('B5-2: a wheel that does not change the expanded Y is refused', async () => {
  const { io } = userScrollDevice({ wheelMoves: false, restoreKind: 'NotSaved', restoreReason: 'INTERACTION_CHANGED' });
  const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'user-scroll-did-not-move');
});

test('B5-2: the exact INTERACTION_CHANGED drop with the new user position kept passes', async () => {
  const { io } = userScrollDevice({ userY: 300, expandedY: 488, restoreKind: 'NotSaved', restoreReason: 'INTERACTION_CHANGED' });
  const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 400));
  assert.equal(flow.reason, 'completed');
  assert.equal(flow.restore.reason, 'INTERACTION_CHANGED');
  assert.equal(flow.after.sy, 300, 'the new user position is kept');
  assert.equal(flow.checks.acceptedRestore, true);
});

test('B5-2: the same intentional reason but after returns to the old 893 fails', async () => {
  const { io } = userScrollDevice({ userY: 300, restoreKind: 'NotSaved', restoreReason: 'INTERACTION_CHANGED', restoreActualY: 893 });
  const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
  assert.equal(flow.ok, false, 'an applied old offset never passes');
  assert.equal(flow.checks.notOld, false);
});

test('B5-2: every other NotSaved reason fails even when the position coincides', async () => {
  for (const reason of ['NO_TARGET_LAYOUT', 'CAPTURE_FAILED', 'NO_BOOKMARK', 'READBACK_FAILED', 'LOADING']) {
    const { io } = userScrollDevice({ userY: 300, restoreKind: 'NotSaved', restoreReason: reason });
    const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
    assert.equal(flow.ok, false, `${reason} must not pass`);
    assert.equal(flow.reason, 'user-scroll-restore-unexpected-kind', `${reason} is an unexpected kind`);
  }
});

test('B5-2: a Discarded or Restored restore is not the accepted outcome for a moved user position', async () => {
  for (const kind of ['Discarded', 'Restored', 'Clamped']) {
    const { io } = userScrollDevice({ userY: 300, restoreKind: kind, restoreReason: 'SUPERSEDED', restoreActualY: 300 });
    const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
    assert.equal(flow.ok, false, `${kind} must not pass`);
  }
});

test('B5-2: a changed document or load count keeps the case failing even with the right reason and position', async () => {
  const { io } = userScrollDevice({ userY: 300, restoreKind: 'NotSaved', restoreReason: 'INTERACTION_CHANGED', identityChanged: true });
  const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
  assert.equal(flow.ok, false);
  assert.equal(flow.checks.identity, false, 'identity is required');
});

test('B5-2: a failed case still carries its stages and the last read as evidence', async () => {
  const { io } = userScrollDevice({ wheelMoves: false });
  const flow = await lifecycle.runUserScrollWins(userScrollAdapters(io));
  assert.ok(Array.isArray(flow.stages) && flow.stages.length > 0, 'stages are kept on a normal failure');
  assert.ok(flow.expanded && flow.expanded.sy === 488, 'the expanded Y is recorded');
});

// B5 target observation: read-only, diagnosis only. These tests pin three things: the metadata is passed through with
// its real values, a missing or non-finite field stays null (never a default 0 or false), and no pass predicate reads it.

const sampleTarget = {
  targetPresent: true,
  transitionId: 4,
  next: false,
  layoutRevision: 1,
  floor: 0,
  targetWindowLeftPx: 198,
  targetWindowTopPx: 1464,
  targetWidthPx: 819,
  targetHeightPx: 252,
  bindingMatchesTarget: true,
  bindingSourceMatchesCurrent: true,
  nativeWidth: 819,
  nativeHeight: 252,
  latestPresent: true,
  latestSerial: 25,
  sampleAccepted: true,
  viewMeasuredWidth: 'diagnosticUnknown',
};

test('restoreTarget metadata is passed through with its real numbers, booleans and fixed code', () => {
  const normalised = harness.lifecycleNormalise({ phase: 'lifecycle-diag', selectedTabId: 'tab-a', restoreTarget: sampleTarget });
  assert.ok(normalised, 'the lifecycle-diag normalises');
  assert.equal(normalised.restoreTarget.targetHeightPx, 252);
  assert.equal(normalised.restoreTarget.nativeHeight, 252);
  assert.equal(normalised.restoreTarget.sampleAccepted, true);
  assert.equal(normalised.restoreTarget.bindingMatchesTarget, true);
  assert.equal(normalised.restoreTarget.viewMeasuredWidth, 'diagnosticUnknown', 'the fixed unknown code is kept as text');
});

test('a missing restoreTarget stays null: it is never turned into a correct target', () => {
  const normalised = harness.lifecycleNormalise({ phase: 'lifecycle-diag', selectedTabId: 'tab-a' });
  assert.equal(normalised.restoreTarget, null, 'missing metadata is null, not a target');
});

test('a non-finite number in the metadata becomes null, never a default 0', () => {
  const normalised = harness.lifecycleNormalise({
    phase: 'lifecycle-diag', selectedTabId: 'tab-a',
    restoreTarget: { targetPresent: true, targetHeightPx: null, nativeHeight: 'NaN-as-text', latestSerial: 1e999 },
  });
  assert.equal(normalised.restoreTarget.targetHeightPx, null, 'a null field stays null');
  assert.equal(normalised.restoreTarget.latestSerial, null, 'an infinite value becomes null, not 0');
  assert.equal(normalised.restoreTarget.nativeHeight, null, 'an overlong or non-fixed string is dropped');
});

test('the Stage snapshot keeps the restoreTarget metadata, not only the surface and sy', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 893 }, producer: { restore: null, restoreTarget: sampleTarget } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.tapLabelRaw = async (label) => {
    dev.tapOrder.push(label);
    if (label === 'Enlarge') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true, surface: { left: 0, top: 0, right: 1024, bottom: 800 } }); return true; }
    if (label === 'Restore') { dev.ui = uiJson({ enlarged: false }); dev.producer.restore = { kind: 'Clamped', actualX: 0, actualY: 488 }; return true; }
    return false;
  };
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  const withTarget = flow.stages.filter((stage) => stage.last && stage.last.restoreTarget);
  assert.ok(withTarget.length > 0, 'at least one stage snapshot keeps the target metadata');
  assert.equal(withTarget[0].last.restoreTarget.targetHeightPx, 252);
});

test('the target metadata never makes a Clamped restore pass: the real gate decides, not the observation', async () => {
  // The producer's observation says the target matched, but the restore is Clamped (488 vs saved 893).
  const { dev, io } = deviceFrom({ metrics: { sy: 893 }, producer: { restore: null, restoreTarget: { ...sampleTarget, sampleAccepted: true, nativeHeight: 252, targetHeightPx: 252 } } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.tapLabelRaw = async (label) => {
    dev.tapOrder.push(label);
    if (label === 'Enlarge') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true }); return true; }
    if (label === 'Restore') { dev.ui = uiJson({ enlarged: false }); dev.producer.restore = { kind: 'Clamped', actualX: 0, actualY: 488 }; return true; }
    return false;
  };
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false, 'a matching observation does not turn a Clamped restore into a pass');
  assert.equal(flow.restore.kind, 'Clamped');
});

test('--lifecycle-case refuses an unknown case and accepts only a whitelisted name', () => {
  const base = ['--serial', 'emulator-5582', '--out', '/tmp/opencode/case-filter-check', '--focus', 'lifecycle-gate', '--source', '/tmp/no-cli'];
  assert.throws(() => harness.parseArgs([...base, '--lifecycle-case', 'lifecycle-not-a-case']), /lifecycle-case must be one of/);
  const parsed = harness.parseArgs([...base, '--lifecycle-case', 'lifecycle-b5-enlarge-restore']);
  assert.equal(parsed['lifecycle-case'], 'lifecycle-b5-enlarge-restore');
});

test('--lifecycle-case selects exactly one of the six cases and the focus is still not a phase pass', () => {
  const names = harness.LIFECYCLE_CASES.map((item) => item.name);
  assert.equal(names.length, 6, 'the whitelist has six cases');
  const selected = names.filter((name) => name === 'lifecycle-b5-enlarge-restore');
  assert.deepEqual(selected, ['lifecycle-b5-enlarge-restore'], 'the filter keeps exactly one case');
  const flow = harness.result([{ name: 'lifecycle-b5-enlarge-restore', result: 'PASS', detail: 'ok' }], 'lifecycle-gate');
  assert.equal(flow.scope, 'lifecycle-targeted');
  assert.equal(flow.phaseComplete, false, 'a single-case pass is never phase complete');
});

test('lifecycle device reads serialize the shared command and status channel', async () => {
  let busy = false;
  const order = [];
  const read = async (name, value) => {
    assert.equal(busy, false, `${name} must not overwrite a pending device response`);
    busy = true;
    order.push(name);
    await new Promise((resolve) => setImmediate(resolve));
    busy = false;
    return value;
  };
  const adapter = harness.createLifecycleGateAdapters({
    readLifecycleRaw: () => read('native', { phase: 'lifecycle-diag' }),
    readFixtureRaw: () => read('fixture', { page: { url: 'https://example.com/fixture' }, native: { physicalFrame: { width: 819, height: 252 } } }),
    readMetricsRaw: () => read('metrics', { timeOrigin: 123 }),
    readUiRaw: () => read('ui', { enlarged: false, surface: { left: 0, top: 0, right: 819, bottom: 252 } }),
  });
  const state = await adapter.readLifecycle();
  assert.deepEqual(order, ['native', 'fixture', 'metrics', 'ui']);
  assert.equal(state.url, 'https://example.com/fixture');
  assert.equal(state.frame.width, 819);
});

const SESSION = 'ses_owner';
const URL_MOUSE = 'https://example.com/openchamber-provider-mouse';
const URL_SECOND = 'https://example.com/openchamber-provider-second';

// Real producer shape, as LifecycleGateCommands.diag() emits it. No enlarged/modeSeq/posts/loads fields.
function producerJson(overrides = {}) {
  return {
    phase: 'lifecycle-diag',
    selectedTabId: 'tab-a',
    tabIds: ['tab-a'],
    pageRetired: false,
    agentOn: true,
    grantForSelected: true,
    mousePressActive: false,
    mode: 'AGENT',
    desktopMode: false,
    mouseMode: false,
    pageReady: true,
    viewHandle: 7,
    documentEpoch: 3,
    restore: null,
    recorderReady: true,
    totalClicks: 0,
    ...overrides,
  };
}

// Real fixture-state shape (fixture-state command): page globals and native frame, top-level postArrivals.
function fixtureJson(overrides = {}) {
  return {
    phase: 'fixture-state',
    page: { url: URL_MOUSE, ready: 'complete', posts: 0, loads: 2, clicks: 0, submits: 0 },
    native: { selectedTabId: 'tab-a', viewHandle: 7, documentEpoch: 3, pageReady: true, physicalFrame: { left: 10, top: 20, width: 400, height: 500 } },
    postArrivals: 0,
    ...overrides,
  };
}

// Real window.__report() plus performance.timeOrigin, as the setup and readLifecycle read them.
function metricsJson(overrides = {}) {
  return { ua: 'mobile', sy: 893, clientWidth: 390, media1000: false, timeOrigin: 1700000000.5, ...overrides };
}

function uiJson(overrides = {}) {
  return { enlarged: false, surface: { left: 10, top: 20, right: 410, bottom: 520 }, ...overrides };
}

/** Fake device whose reads return the recorded producer JSON, and whose actions are counted. */
function deviceFrom(initial = {}) {
  const dev = {
    producer: producerJson(initial.producer),
    fixture: fixtureJson(initial.fixture),
    metrics: metricsJson(initial.metrics),
    ui: uiJson(initial.ui),
    counts: { opens: 0, taps: 0, twoFinger: 0, selects: 0, modeSwitch: 0, submits: 0, gets: 0 },
    tapOrder: [],
    clock: 0,
  };
  const io = {
    now: () => dev.clock,
    sleepMs: async (ms) => { dev.clock += ms; await new Promise((r) => setImmediate(r)); },
    call: async (action, input = {}) => {
      if (action === 'browser.open') {
        dev.counts.opens += 1;
        const tabId = input.url === URL_SECOND ? 'tab-b' : 'tab-fresh';
        dev.producer.selectedTabId = tabId;
        dev.producer.tabIds = [...new Set([...dev.producer.tabIds, tabId])];
        return { ok: true, data: { tabId, url: input.url } };
      }
      return { ok: false, error: { code: 'UNSUPPORTED' } };
    },
    readLifecycleRaw: async () => (dev.producerMissing ? null : dev.producer),
    readFixtureRaw: async () => dev.fixture,
    readMetricsRaw: async () => (dev.metricsMissing ? null : dev.metrics),
    readUiRaw: async () => dev.ui,
    tapLabelRaw: async (label) => {
      dev.counts.taps += 1;
      dev.tapOrder.push(label);
      // Core clears the previous restore result when a new window cycle starts (Enlarge). The fake models that.
      if (label === 'Enlarge') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true }); }
      return true;
    },
    enableMouseRaw: async () => { dev.counts.mouse = (dev.counts.mouse || 0) + 1; dev.tapOrder.push('mouse:on'); dev.producer.mouseMode = true; return true; },
    // A real mode switch revokes the agent grant (applyMode -> revokeAll), so agentOn is false afterwards.
    switchModeRaw: async (target) => { dev.counts.modeSwitch += 1; dev.tapOrder.push(`mode:${target}`); dev.producer.desktopMode = target === 'computer'; dev.producer.agentOn = false; dev.metrics.ua = target === 'computer' ? 'desktop' : 'mobile'; dev.metrics.clientWidth = target === 'computer' ? 1024 : 390; return true; },
    twoFingerRaw: async () => { dev.counts.twoFinger += 1; dev.metrics.sy = 600; return true; },
    selectRaw: async (tabId) => { dev.counts.selects += 1; dev.producer.selectedTabId = tabId; return true; },
    submitLoginFresh: async () => ({ ok: true, tabId: 'tab-post', beforePosts: 0, beforeArrivals: 0, landing: { url: URL_MOUSE }, finalState: { page: { posts: 1 } } }),
    releaseOwnedRaw: async () => true,
  };
  return { dev, io };
}

test('producer shape: lifecycle-diag fields are real; enlarged, modeSeq, posts, loads are not invented', async () => {
  const { io } = deviceFrom();
  const adapters = harness.createLifecycleGateAdapters(io);
  const state = await adapters.readLifecycle();
  assert.equal(state.selectedTabId, 'tab-a');
  assert.equal(state.grantForSelected, true);
  assert.equal(state.viewHandle, 7);
  assert.equal(state.timeOrigin, 1700000000.5, 'timeOrigin from page JS, not the presenter epoch');
  assert.equal(state.loads, 2, 'loads from fixture-state page globals');
  assert.equal(state.posts, 0, 'posts from fixture-state page globals');
  assert.equal(state.postArrivals, 0, 'postArrivals from fixture-state top-level');
  assert.equal(state.enlarged, false, 'enlarged from the live UI, not the producer');
  assert.equal(state.modeSeq, null, 'modeSeq is not produced, so it is null and never used as proof');
  assert.equal(state.restore, null, 'no restore yet');
});

test('missing source stays null: an absent metrics read gives null sy and null timeOrigin, never a default', async () => {
  const { dev, io } = deviceFrom();
  dev.metricsMissing = true;
  const adapters = harness.createLifecycleGateAdapters(io);
  const state = await adapters.readLifecycle();
  assert.equal(state.metrics, null);
  assert.equal(state.timeOrigin, null);
  assert.equal(state.witness, null);
});

test('missing fixture-state gives null loads, posts and postArrivals, never zero', async () => {
  const { io } = deviceFrom();
  io.readFixtureRaw = async () => null;
  const adapters = harness.createLifecycleGateAdapters(io);
  const state = await adapters.readLifecycle();
  assert.equal(state.loads, null);
  assert.equal(state.posts, null);
  assert.equal(state.postArrivals, null);
});

test('restore shape: producer actualX/actualY become restore.actual; reason/kind pass through as real strings', async () => {
  const { dev, io } = deviceFrom({ producer: { restore: { kind: 'Restored', actualX: 0, actualY: 893 } } });
  const adapters = harness.createLifecycleGateAdapters(io);
  const state = await adapters.readLifecycle();
  assert.deepEqual(state.restore, { kind: 'Restored', reason: null, actual: { x: 0, y: 893 } });
  dev.producer.restore = { kind: 'Discarded', reason: 'PAGE_CHANGED' };
  const discarded = await adapters.readLifecycle();
  assert.equal(discarded.restore.kind, 'Discarded');
  assert.equal(discarded.restore.reason, 'PAGE_CHANGED');
  assert.equal(discarded.restore.actual, null);
});

test('no producer phase: the adapter returns null and the focus refuses instead of passing', async () => {
  const { dev, io } = deviceFrom();
  dev.producerMissing = true;
  const adapters = harness.createLifecycleGateAdapters(io);
  assert.equal(await adapters.readLifecycle(), null);
});

test('prepareLifecycleCase (long page): fresh open, read-only ready, a real two-finger scroll to non-zero', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 0 } });
  dev.metrics.sy = 0;
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const setup = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b5-enlarge-restore', setup: 'longPageScrolled', viewport: 'mobile' },
    { adapters, io, expectedSessionId: SESSION },
  );
  assert.equal(setup.ok, true, JSON.stringify(setup).slice(0, 400));
  assert.equal(dev.counts.opens, 1, 'one fresh open for the case');
  assert.equal(dev.counts.twoFinger, 1, 'one real two-finger scroll, not a JS scrollTo');
  assert.equal(setup.scrollBefore, 0, 'the initial 0 is recorded, not failed');
  assert.ok(setup.scrollAfter > 0, 'the offset is non-zero after the real scroll');
});

test('prepareLifecycleCase (long page): a scroll that does not move the page is refused before the flow', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 0 } });
  io.twoFingerRaw = async () => { dev.counts.twoFinger += 1; return true; };
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const setup = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b5-enlarge-restore', setup: 'longPageScrolled', viewport: 'mobile' },
    { adapters, io, expectedSessionId: SESSION },
  );
  assert.equal(setup.ok, false);
  assert.equal(setup.reason, 'setup-scroll-not-applied');
});

test('prepareLifecycleCase (desktop start): the declared desktop must already be native; setup never repairs it by a menu', async () => {
  // The request asks for desktop and the device applies it: setup succeeds with no mode switch at all.
  const { dev, io } = deviceFrom({ producer: { desktopMode: true }, metrics: { ua: 'desktop', clientWidth: 1024 } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const setup = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b3-mode-desktop-to-mobile', setup: 'desktopFixture', viewport: 'desktop' },
    { adapters, io, expectedSessionId: SESSION },
  );
  assert.equal(setup.ok, true, JSON.stringify(setup).slice(0, 300));
  assert.equal(dev.counts.modeSwitch, 0, 'no menu switch: the request already produced desktop');
  // Native not desktop for a declared desktop: the case fails, it is never repaired by the menu.
  const { dev: dev2, io: io2 } = deviceFrom({ producer: { desktopMode: false }, metrics: { ua: 'mobile', clientWidth: 390 } });
  const refused = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b3-mode-desktop-to-mobile', setup: 'desktopFixture', viewport: 'desktop' },
    { adapters: harness.createLifecycleGateAdapters(io2, harness.createCaseFence()), io: io2, expectedSessionId: SESSION },
  );
  assert.equal(refused.ok, false);
  assert.equal(refused.reason, 'setup-declared-viewport-not-applied');
  assert.equal(dev2.counts.modeSwitch, 0, 'the refused setup did not use the menu');
});

test('prepareLifecycleCase (POST): the baseline comes from this submit, not a0', async () => {
  const { io } = deviceFrom();
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const setup = await harness.prepareLifecycleCase(
    { name: 'lifecycle-b3-post-roundtrip', setup: 'postFixture', viewport: 'mobile' },
    { adapters, io, expectedSessionId: SESSION },
  );
  assert.equal(setup.ok, true);
  assert.equal(setup.baseline.beforePosts, 0, 'baseline from the submit result');
  assert.equal(setup.baseline.beforeArrivals, 0);
});

test('B5-1 real flow over the real adapter: saved offset, fresh Restored, same document, no reload', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 893 }, producer: { restore: null } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  // The device answers Enlarge with a live surface, and Restore with a fresh Restored result at the saved offset.
  io.tapLabelRaw = async (label) => {
    dev.tapOrder.push(label);
    if (label === 'Enlarge') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true, surface: { left: 0, top: 0, right: 1024, bottom: 800 } }); return true; }
    if (label === 'Restore') {
      dev.ui = uiJson({ enlarged: false });
      dev.producer.restore = { kind: 'Restored', actualX: 0, actualY: 893 };
      return true;
    }
    return false;
  };
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 500));
  assert.deepEqual(dev.tapOrder, ['Enlarge', 'Restore'], 'Enlarge once, then Restore once');
  assert.equal(flow.restore.kind, 'Restored');
  assert.equal(flow.saved.sy, 893, 'saved offset is the real one at Enlarge');
});

test('B5-1: a stale restore from the previous cycle is never taken as this cycle result', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 893 }, producer: { restore: { kind: 'Restored', actualX: 0, actualY: 893 } } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.tapLabelRaw = async (label) => {
    if (label === 'Enlarge') { dev.ui = uiJson({ enlarged: true }); return true; }
    if (label === 'Restore') { dev.ui = uiJson({ enlarged: false }); return true; } // producer still shows the old result
    return false;
  };
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false);
  // The stale result is still present on the enlarged window: the clear never happened, so the case stops there.
  assert.equal(flow.reason, 'restore-not-cleared-on-enlarge');
});

test('B5-1: a Clamped restore never passes even when the offset is near', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 893 } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.tapLabelRaw = async (label) => {
    if (label === 'Enlarge') { dev.ui = uiJson({ enlarged: true }); return true; }
    if (label === 'Restore') { dev.ui = uiJson({ enlarged: false }); dev.producer.restore = { kind: 'Clamped', actualX: 0, actualY: 488 }; return true; }
    return false;
  };
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false);
  assert.equal(flow.restore.kind, 'Clamped');
});

test('B5-3 document change: a different timeOrigin is a changed document, the result is Discarded, nothing applied', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 300 } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.tapLabelRaw = async (label) => {
    if (label === 'Enlarge') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true }); return true; }
    if (label === 'Restore') { dev.ui = uiJson({ enlarged: false }); dev.producer.restore = { kind: 'Discarded', reason: 'PAGE_CHANGED' }; return true; }
    return false;
  };
  io.call = async (action, input) => {
    dev.counts.opens += 1;
    // The new document: a different timeOrigin and offset 0, same owner, a new tab.
    dev.metrics = metricsJson({ sy: 0, timeOrigin: 1700000999.25 });
    dev.producer.selectedTabId = 'tab-b';
    return { ok: true, data: { tabId: 'tab-b', url: input.url } };
  };
  const flow = await lifecycle.runDocumentChangeDiscards({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs, openNewDocument: () => adapters.openNewDocument() });
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 500));
  assert.equal(flow.restore.kind, 'Discarded');
  assert.equal(flow.changedDoc, true);
});

test('B5-3: an unknown identity is not a changed document and never passes', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 300 } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.tapLabelRaw = async (label) => {
    if (label === 'Enlarge') { dev.ui = uiJson({ enlarged: true }); return true; }
    if (label === 'Restore') { dev.ui = uiJson({ enlarged: false }); dev.producer.restore = { kind: 'Discarded', reason: 'PAGE_CHANGED' }; return true; }
    return false;
  };
  dev.metrics.timeOrigin = null;
  const flow = await lifecycle.runDocumentChangeDiscards({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs, openNewDocument: () => adapters.openNewDocument() });
  assert.equal(flow.ok, false, 'null timeOrigin is unknown, not a changed document');
});

test('B3 mode: mobile to desktop, A->B->A keeps UA, clientWidth, handle, timeOrigin, loads, posts; no modeSeq claim', async () => {
  const { dev, io } = deviceFrom({ producer: { desktopMode: false }, metrics: { ua: 'mobile', clientWidth: 390, media1000: false, timeOrigin: 1700000000.5 } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.openOtherTab = undefined;
  const deps = {
    ...adapters,
    readLifecycle: adapters.readLifecycle,
    now: io.now,
    sleep: io.sleepMs,
    direction: 'computer',
    switchMode: (t) => adapters.switchMode(t),
    openOtherTab: async () => ({ ok: true, tabId: 'tab-b' }),
    selectTab: (id) => adapters.selectTab(id),
  };
  const flow = await lifecycle.runTabRoundTripMode(deps);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 600));
  assert.equal(flow.modeSeq, 'unavailable', 'modeSeq is reported unavailable, not used');
  assert.equal(dev.counts.modeSwitch, 1, 'exactly one explicit mode switch');
  assert.equal(dev.counts.selects, 2, 'select B then select A');
});

test('B3 mode: desktop to mobile is run as its own direction', async () => {
  const { dev, io } = deviceFrom({ producer: { desktopMode: true }, metrics: { ua: 'desktop', clientWidth: 1024, media1000: true, timeOrigin: 1700000000.5 } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const deps = {
    ...adapters,
    readLifecycle: adapters.readLifecycle,
    now: io.now,
    sleep: io.sleepMs,
    direction: 'phone',
    switchMode: (t) => adapters.switchMode(t),
    openOtherTab: async () => ({ ok: true, tabId: 'tab-b' }),
    selectTab: (id) => adapters.selectTab(id),
  };
  const flow = await lifecycle.runTabRoundTripMode(deps);
  assert.equal(flow.direction, 'phone');
  assert.equal(dev.counts.modeSwitch, 1);
  assert.ok(dev.tapOrder.includes('mode:phone'));
});

test('B3 POST: the submit baseline is used; landing needs posts and arrivals to be baseline plus one', async () => {
  const { dev, io } = deviceFrom({ fixture: { page: { url: URL_MOUSE, ready: 'complete', posts: 1, loads: 3 }, postArrivals: 1 }, producer: { selectedTabId: 'tab-post' } });
  dev.producer.selectedTabId = 'tab-post';
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const deps = {
    ...adapters,
    readLifecycle: adapters.readLifecycle,
    now: io.now,
    sleep: io.sleepMs,
    baseline: { beforePosts: 0, beforeArrivals: 0, landing: { url: URL_MOUSE }, finalState: { page: { posts: 1 } } },
    openOtherTab: async () => ({ ok: true, tabId: 'tab-b' }),
    selectTab: (id) => adapters.selectTab(id),
  };
  const flow = await lifecycle.runPostRoundTripMode(deps);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 500));
  assert.equal(flow.baseline.beforePosts, 0);
});

test('B3 POST: a missing baseline stops before any select', async () => {
  const { dev, io } = deviceFrom();
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const flow = await lifecycle.runPostRoundTripMode({
    ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs, baseline: null,
    selectTab: async () => { throw new Error('must not select without a baseline'); },
  });
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'post-baseline-missing');
  assert.equal(dev.counts.selects, 0);
});

test('timeout fence still blocks a late flow: no tap or two-finger after the fence', async () => {
  const { dev, io } = deviceFrom({ metrics: { sy: 300 } });
  const fence = harness.createCaseFence();
  const adapters = harness.createLifecycleGateAdapters(io, fence);
  fence.stop();
  const enlarged = await adapters.tapEnlarge();
  const scroll = await adapters.twoFingerScroll({ dy: 100 });
  assert.equal(enlarged.code, 'FENCED');
  assert.equal(scroll.code, 'FENCED');
  assert.equal(dev.counts.taps, 0);
  assert.equal(dev.counts.twoFinger, 0);
});

test('lifecycle cases: five real cases, both B3 directions and the POST case', () => {
  const names = harness.LIFECYCLE_CASES.map((item) => item.name);
  assert.deepEqual(names, [
    'lifecycle-b5-enlarge-restore',
    'lifecycle-b5-user-scroll-wins',
    'lifecycle-b5-document-change',
    'lifecycle-b3-mode-mobile-to-desktop',
    'lifecycle-b3-mode-desktop-to-mobile',
    'lifecycle-b3-post-roundtrip',
  ]);
  for (const item of harness.LIFECYCLE_CASES) assert.ok(item.setup, `${item.name} has a real setup`);
});

test('result report for lifecycle-gate is focused: scope lifecycle-targeted and never an overall PASS', () => {
  const pass = harness.result([{ name: 'lifecycle-b5-enlarge-restore', result: 'PASS', detail: 'ok' }], 'lifecycle-gate');
  assert.equal(pass.scope, 'lifecycle-targeted');
  assert.equal(pass.phaseComplete, false);
  assert.equal(pass.result, 'FAIL');
});

test('focus values accept lifecycle-gate and reject unknown focus', () => {
  assert.ok(harness.FOCUS_VALUES.includes('lifecycle-gate'));
  assert.throws(() => harness.parseArgs(['--serial', 'emulator-5580', '--out', '/tmp/out', '--focus', 'other']), /lifecycle-gate/);
  const parsed = harness.parseArgs(['--serial', 'emulator-5580', '--out', '/tmp/out', '--focus', 'lifecycle-gate', '--source', '/tmp/no-cli']);
  assert.equal(parsed.focus, 'lifecycle-gate');
});
