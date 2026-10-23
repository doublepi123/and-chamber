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
 * several round trips. Reads are counted per stage. The Float window reports a real small/full UI surface and
 * the page keeps its own metrics; the window cycle no longer produces a restore result.
 */
function timedDevice({ readMs = 900, tapMs = 300, sy = 893, surfaceChanges = true } = {}) {
  const clock = { t: 0 };
  const smallSurface = { left: 0, top: 0, right: 390, bottom: 800 };
  const fullSurface = { left: 0, top: 0, right: 1024, bottom: 800 };
  const dev = {
    producer: { phase: 'lifecycle-diag', selectedTabId: 'tab-a', tabIds: ['tab-a'], pageRetired: false, agentOn: true, grantForSelected: true, mousePressActive: false, mode: 'MANUAL', desktopMode: false, mouseMode: true, pageReady: true, viewHandle: 7, documentEpoch: 1, restore: null, recorderReady: true, totalClicks: 0, recordedDocuments: {}, lastRetired: null },
    metrics: { ua: 'mobile', clientWidth: 390, media1000: false, sy, loads: 1, timeOrigin: 1700000000.5 },
    fixture: { page: { url: 'https://example.com/openchamber-provider-mouse', loads: 1, posts: 0 }, postArrivals: 0, native: null },
    enlarged: false,
    surfaceChanges,
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
    readUiRaw: () => read('ui', {
      enlarged: dev.enlarged,
      surface: dev.enlarged && dev.surfaceChanges ? fullSurface : smallSurface,
    }),
    tapLabelRaw: async (label) => {
      clock.t += tapMs;
      dev.counts.taps += 1;
      dev.counts.mutations += 1;
      dev.order.push(`tap:${label}`);
      if (label === 'Full screen') { dev.enlarged = true; return true; }
      if (label === 'Small window') { dev.enlarged = false; return true; }
      return false;
    },
    call: async () => { dev.counts.mutations += 1; return { ok: false, error: { code: 'UNSUPPORTED' } }; },
  };
  return { dev, io, clock };
}

test('scene budget: 90s covers serial reads that together exceed 20s, and a correct window cycle still passes', async () => {
  // Four serial reads of ~1.6 s each make one lifecycle read ~6.4 s, the real order of magnitude on the device.
  const { dev, io, clock } = timedDevice({ readMs: 1600, tapMs: 300 });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const started = clock.t;
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  const sceneMs = clock.t - started;
  assert.ok(sceneMs > 20000, `the scene really spent more than the old 20s limit: ${sceneMs}ms`);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 600));
  assert.equal(flow.expanded.enlarged, true);
  assert.equal(flow.after.enlarged, false);
  assert.equal(flow.checks.surfaceChangedFromFull, true);
  // Every stage stays inside its own per-stage budget; the scene total is what exceeded 20s.
  for (const stage of flow.stages.filter((item) => item.elapsedMs != null && item.outcome !== 'read')) {
    assert.ok(stage.elapsedMs <= 20000, `${stage.label} ${stage.elapsedMs}ms is inside its own 20s stage budget`);
  }
  assert.deepEqual(dev.order.filter((name) => name.startsWith('tap:')), ['tap:Full screen', 'tap:Small window'], 'one Full screen and one Small window only');
});

test('a Full screen with no real surface change stays FAIL on real serial reads', async () => {
  const { io, clock } = timedDevice({ readMs: 1600, tapMs: 300, surfaceChanges: false });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  assert.ok(clock.t > 6000, 'the case ran real serial reads, not a constant');
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'full-screen-contract-failed');
  assert.equal(flow.checks.surfaceChanged, false, 'the real UI facts decide, not the budget');
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
    readUiRaw: async () => { await slow(40); return { enlarged: dev.enlarged, surface: dev.enlarged ? { left: 0, top: 0, right: 1024, bottom: 800 } : { left: 0, top: 0, right: 390, bottom: 800 } }; },
    tapLabelRaw: async (label) => { dev.mutations += 1; await slow(5); if (label === 'Full screen') { dev.enlarged = true; dev.restore = null; } return true; },
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
 * Float/Full device shared by the three B5 window-cycle cases. It reports a real small and full UI
 * surface, the page's own metrics, and the producer identity. The window cycle produces no restore
 * result; only the page/UI facts are used. Each knob models one contract violation.
 */
function b5Device({
  sy = 893,
  userY = 400,
  surfaceChanges = true,
  fullScrollDelta = 0,
  dropGrantOnFull = false,
  smallToOld = false,
  timeOriginOnScroll = false,
  unknownIdentityOnOpen = false,
  newSy = 0,
  newTimeOrigin = 1700000999.25,
  openReturnsSmall = false,
  noSurface = false,
  noClientWidth = false,
} = {}) {
  const smallSurface = { left: 0, top: 0, right: 390, bottom: 800 };
  const fullSurface = { left: 0, top: 0, right: 1024, bottom: 800 };
  const dev = {
    enlarged: false, sy, initialSy: sy, tabId: 'tab-a', tabs: ['tab-a'], timeOrigin: 1700000000.5,
    handle: 7, loads: 2, grant: true, agentOn: true, mode: 'AGENT', clock: 0,
    taps: [], wheels: 0, opens: 0,
  };
  const surfaceFor = (enlarged) => {
    if (noSurface) return null;
    return enlarged && surfaceChanges ? fullSurface : smallSurface;
  };
  const io = {
    now: () => dev.clock,
    sleepMs: async (ms) => { dev.clock += ms; await new Promise((r) => setImmediate(r)); },
    readLifecycleRaw: async () => ({
      phase: 'lifecycle-diag', selectedTabId: dev.tabId, tabIds: dev.tabs, pageRetired: false,
      agentOn: dev.agentOn, grantForSelected: dev.grant, mousePressActive: false, mode: dev.mode,
      desktopMode: true, mouseMode: true, pageReady: true, viewHandle: dev.handle, documentEpoch: 1,
      restore: null, recorderReady: true, totalClicks: 0, recordedDocuments: {}, lastRetired: null,
    }),
    readFixtureRaw: async () => ({
      page: { url: URL_MOUSE, loads: dev.loads, posts: 0 },
      postArrivals: 0,
      native: { physicalFrame: { left: 0, top: 0, width: 390, height: 800 } },
    }),
    readMetricsRaw: async () => ({
      ua: 'desktop', clientWidth: noClientWidth ? null : 1024, media1000: true, sy: dev.sy,
      loads: dev.loads, timeOrigin: dev.timeOrigin,
    }),
    readUiRaw: async () => ({ enlarged: dev.enlarged, surface: surfaceFor(dev.enlarged) }),
    tapLabelRaw: async (label) => {
      dev.taps.push(label);
      if (label === 'Full screen') {
        dev.enlarged = true;
        if (dropGrantOnFull) { dev.grant = false; dev.agentOn = false; dev.mode = 'MANUAL'; }
        dev.sy += fullScrollDelta;
      }
      if (label === 'Small window') {
        dev.enlarged = false;
        if (smallToOld) dev.sy = dev.initialSy;
      }
      return true;
    },
    twoFingerRaw: async () => {
      dev.wheels += 1;
      dev.sy = userY;
      if (timeOriginOnScroll) dev.timeOrigin += 1;
      return true;
    },
    newDocumentUrl: URL_SECOND,
    call: async (action, input = {}) => {
      if (action !== 'browser.open') return { ok: false, error: { code: 'UNSUPPORTED' } };
      dev.opens += 1;
      dev.sy = newSy;
      if (unknownIdentityOnOpen) {
        // Same tab/handle but an unreadable timeOrigin: identity is unknown, not a changed document.
        dev.timeOrigin = null;
      } else {
        dev.tabId = 'tab-b';
        dev.tabs = ['tab-a', 'tab-b'];
        dev.handle = 9;
        dev.timeOrigin = newTimeOrigin;
      }
      if (openReturnsSmall) dev.enlarged = false;
      return { ok: true, data: { tabId: dev.tabId, url: input.url } };
    },
  };
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  const deps = {
    ...adapters,
    readLifecycle: adapters.readLifecycle,
    now: io.now,
    sleep: io.sleepMs,
    openNewDocument: () => adapters.openNewDocument(),
  };
  return { dev, io, adapters, deps };
}

test('B5-1: Full screen changes the real surface and Small window returns it without reflow or scroll change', async () => {
  const { deps } = b5Device();
  const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 700));
  assert.equal(flow.reason, 'completed');
  assert.deepEqual(flow.saved.surface, { left: 0, top: 0, right: 390, bottom: 800 });
  assert.deepEqual(flow.expanded.surface, { left: 0, top: 0, right: 1024, bottom: 800 });
  assert.deepEqual(flow.after.surface, { left: 0, top: 0, right: 390, bottom: 800 });
  assert.equal(flow.saved.sy, flow.expanded.sy);
  assert.equal(flow.saved.sy, flow.after.sy, 'no user scroll means the offset stays put');
  assert.equal(flow.saved.grant, true);
  assert.equal(flow.after.grant, true);
  assert.equal(flow.checks.surfaceChangedFromFull, true);
});

test('B5-1: a Full screen with the same surface refuses as a UI contract failure', async () => {
  const { deps } = b5Device({ surfaceChanges: false });
  const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'full-screen-contract-failed');
  assert.equal(flow.checks.surfaceChanged, false);
});

test('B5-1: the page scrolling during Full screen without a user scroll refuses', async () => {
  const { deps } = b5Device({ fullScrollDelta: 12 });
  const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'full-screen-contract-failed');
  assert.equal(flow.checks.scrollHeld, false);
});

test('B5-1: losing the grant on Full screen refuses instead of pretending control', async () => {
  const { deps } = b5Device({ dropGrantOnFull: true });
  const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'full-screen-contract-failed');
  assert.equal(flow.checks.grantKept, false);
});

test('B5-1: a missing surface or fixed viewport refuses before any tap', async () => {
  for (const [options, reason] of [
    [{ noSurface: true }, 'precondition-surface-missing'],
    [{ noClientWidth: true }, 'precondition-viewport-unreadable'],
  ]) {
    const { deps, dev } = b5Device(options);
    const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
    assert.equal(flow.ok, false, reason);
    assert.equal(flow.reason, reason);
    assert.deepEqual(dev.taps, [], 'missing producer fields refuse before UI actions');
  }
});

test('B5-2: a user scroll in Full screen is kept after Small window; no old offset is applied', async () => {
  const { deps } = b5Device({ userY: 400 });
  const flow = await lifecycle.runUserScrollWins(deps);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 700));
  assert.equal(flow.userMoved.sy, 400);
  assert.equal(flow.after.sy, 400, 'the user position is kept');
  assert.equal(flow.checks.userScrollKept, true);
  assert.equal(flow.checks.userDiffersFromOld, true);
  assert.equal(flow.saved.sy, 893, 'the old offset is unchanged in evidence');
});

test('B5-2: a two-finger move that does not change the page refuses', async () => {
  const { deps } = b5Device({ userY: 893 });
  const flow = await lifecycle.runUserScrollWins(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'user-scroll-contract-failed');
  assert.equal(flow.checks.scrollMoved, false);
  assert.ok(Array.isArray(flow.stages) && flow.stages.length > 0, 'stages are kept on a normal failure');
});

test('B5-2: Small window jumping back to the old offset fails even after a real user scroll', async () => {
  const { deps } = b5Device({ userY: 400, smallToOld: true });
  const flow = await lifecycle.runUserScrollWins(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'user-scroll-wins-failed');
  assert.equal(flow.checks.userScrollKept, false);
  assert.equal(flow.after.sy, 893);
});

test('B5-2: the document changing during the user scroll refuses the case', async () => {
  const { deps } = b5Device({ userY: 400, timeOriginOnScroll: true });
  const flow = await lifecycle.runUserScrollWins(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'user-scroll-contract-failed');
  assert.equal(flow.checks.samePage, false);
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
    if (label === 'Full screen') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true, surface: { left: 0, top: 0, right: 1024, bottom: 800 } }); return true; }
    if (label === 'Small window') { dev.ui = uiJson({ enlarged: false }); dev.producer.restore = { kind: 'Clamped', actualX: 0, actualY: 488 }; return true; }
    return false;
  };
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  const withTarget = flow.stages.filter((stage) => stage.last && stage.last.restoreTarget);
  assert.ok(withTarget.length > 0, 'at least one stage snapshot keeps the target metadata');
  assert.equal(withTarget[0].last.restoreTarget.targetHeightPx, 252);
});

test('the target metadata never replaces the real UI contract, even when it says accepted', async () => {
  // The producer's observation says the target matched, but Full screen reports the same surface size.
  const { dev, io } = deviceFrom({ metrics: { sy: 893 }, producer: { restore: null, restoreTarget: { ...sampleTarget, sampleAccepted: true, nativeHeight: 252, targetHeightPx: 252 } } });
  const adapters = harness.createLifecycleGateAdapters(io, harness.createCaseFence());
  io.tapLabelRaw = async (label) => {
    dev.tapOrder.push(label);
    if (label === 'Full screen') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true }); return true; }
    if (label === 'Small window') { dev.ui = uiJson({ enlarged: false }); return true; }
    return false;
  };
  const flow = await lifecycle.runEnlargeRestoreSameDoc({ ...adapters, readLifecycle: adapters.readLifecycle, now: io.now, sleep: io.sleepMs });
  assert.equal(flow.ok, false, 'the real surface facts decide, not the observation');
  assert.equal(flow.reason, 'full-screen-contract-failed');
  assert.equal(flow.checks.surfaceChanged, false);
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
      if (label === 'Full screen') { dev.producer.restore = null; dev.ui = uiJson({ enlarged: true }); }
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

test('B5-1 real flow over the real adapter: real surfaces, same document, fixed viewport and grant', async () => {
  const { deps, dev } = b5Device();
  const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 500));
  assert.deepEqual(dev.taps, ['Full screen', 'Small window'], 'Full screen once, then Small window once');
  assert.equal(flow.saved.sy, 893, 'saved offset is the real one at Full screen');
  assert.equal(flow.after.sy, 893, 'the page is not reflowed or scrolled by the window cycle');
});

test('B5-1: a stale restore object is not required and cannot influence the UI contract', async () => {
  const { deps } = b5Device({ surfaceChanges: false });
  const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
  assert.equal(flow.ok, false, 'no real surface change fails, regardless of any restore object');
  assert.equal(flow.reason, 'full-screen-contract-failed');
});

test('B5-1: the window cycle is judged only by UI/page facts, never a restore kind', async () => {
  const { deps } = b5Device({ fullScrollDelta: 5 });
  const flow = await lifecycle.runEnlargeRestoreSameDoc(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'full-screen-contract-failed');
  assert.equal(flow.checks.scrollHeld, false);
  assert.equal('restore' in flow, false, 'the flow does not depend on a RestoreResult event');
});

test('B5-3 document change: explicit open during Full screen keeps its own scroll and identity', async () => {
  const { deps, dev } = b5Device({ newSy: 0 });
  const flow = await lifecycle.runDocumentChangeDiscards(deps);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 700));
  assert.equal(flow.reason, 'completed');
  assert.equal(flow.newDoc.timeOrigin, 1700000999.25);
  assert.equal(flow.after.timeOrigin, 1700000999.25);
  assert.equal(flow.after.sy, 0);
  assert.equal(flow.checks.oldScrollNotApplied, true);
  assert.equal(dev.opens, 1, 'the document change is one explicit open');
});

test('B5-3: an unreadable identity is not a changed document and refuses the case', async () => {
  const { deps } = b5Device({ unknownIdentityOnOpen: true });
  const flow = await lifecycle.runDocumentChangeDiscards(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'new-document-not-observed');
});

test('B5-3: applying the old scroll to the new document fails', async () => {
  const { deps } = b5Device({ newSy: 893 });
  const flow = await lifecycle.runDocumentChangeDiscards(deps);
  assert.equal(flow.ok, false);
  assert.equal(flow.reason, 'document-change-failed');
  assert.equal(flow.checks.oldScrollNotApplied, false);
});

test('B5-3: an explicit open that already returns the small window needs no extra tap', async () => {
  const { deps, dev } = b5Device({ openReturnsSmall: true });
  const flow = await lifecycle.runDocumentChangeDiscards(deps);
  assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 700));
  assert.deepEqual(dev.taps, ['Full screen'], 'Small window is not tapped when the open already returned it');
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

test('lifecycle allow reads the default grant; a hand-back needs a proven takeover', async () => {
  const state = { grant: true, mode: 'AGENT', agentOn: true, taps: 0, grantAfterTap: false };
  let clock = 0;
  const io = {
    readLifecycleRaw: async () => ({ phase: 'lifecycle-diag', selectedTabId: 'tab-a', grantForSelected: state.grant, mode: state.mode, agentOn: state.agentOn }),
    tapLabelRaw: async (label) => {
      assert.equal(label, 'Resume agent');
      state.taps += 1;
      if (state.grantAfterTap) state.grant = true;
      return true;
    },
    now: () => clock,
    sleepMs: async (ms) => { clock += ms; },
  };
  const adapters = harness.createLifecycleGateAdapters(io);

  const granted = await adapters.allow();
  assert.equal(granted.ok, true);
  assert.equal(granted.source, 'default-grant');
  assert.equal(granted.tapped, false);
  assert.equal(state.taps, 0, 'default control needs no tap');

  // A manual page with no grant is not a proven takeover for a hand-back until the diag says so.
  state.grant = false; state.mode = 'MANUAL'; state.agentOn = false; state.grantAfterTap = false; state.taps = 0;
  const refused = await adapters.allow({ handback: true });
  assert.equal(refused.ok, false);
  assert.equal(refused.detail, 'tapped-without-grant');
  assert.equal(state.taps, 1, 'one hand-back tap, never retried');

  state.grantAfterTap = true; state.taps = 0;
  const handed = await adapters.allow({ handback: true });
  assert.equal(handed.ok, true);
  assert.equal(handed.source, 'manual-handback');
  assert.equal(state.taps, 1, 'the tap alone never passes; the diag read after it does');

  // Without the explicit takeover hand-back option a missing default grant is reported, not tapped.
  state.grant = false; state.grantAfterTap = false; state.taps = 0;
  const missing = await adapters.allow();
  assert.equal(missing.ok, false);
  assert.equal(missing.source, 'no-default-grant');
  assert.equal(state.taps, 0);
});
