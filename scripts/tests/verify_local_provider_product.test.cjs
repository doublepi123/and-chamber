'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const product = require('../lib/local_provider_product_cases.cjs');
const driver = require('../verify_local_provider_product.cjs');

test('product catalog requires schema 1, suite, and the exact finite ten names', () => {
  const catalog = product.parseProductCatalog({ schemaVersion: 1, suite: 'local-provider-product', cases: [...product.PRODUCT_CASE_NAMES] });
  assert.deepEqual(catalog.cases, [...product.PRODUCT_CASE_NAMES]);
  assert.throws(() => product.parseProductCatalog({ schemaVersion: 2, suite: 'local-provider-product', cases: [...product.PRODUCT_CASE_NAMES] }), /schema/);
  assert.throws(() => product.parseProductCatalog({ schemaVersion: 1, suite: 'other', cases: [...product.PRODUCT_CASE_NAMES] }), /suite/);
  assert.throws(() => product.parseProductCatalog({ schemaVersion: 1, suite: 'local-provider-product', cases: product.PRODUCT_CASE_NAMES.slice(1) }), /exactly|missing/);
  assert.throws(() => product.parseProductCatalog({ schemaVersion: 1, suite: 'local-provider-product', cases: [...product.PRODUCT_CASE_NAMES, 'product-nope'] }), /exactly|unknown/);
});

test('product driver wire is ProductTest with -e cases and no Matrix class', () => {
  const args = driver.buildProductArgs({ serial: 'emulator-5582', runId: 'product-run-0001', cases: ['product-desktop-1024'] });
  assert.equal(args[args.indexOf('class') + 1], 'io.andchamber.app.browser.LocalProviderProductTest#runGuestProduct');
  assert.equal(args[args.indexOf('runId') + 1], 'product-run-0001');
  assert.equal(args[args.indexOf('cases') + 1], 'product-desktop-1024');
  assert.equal(args.includes('LocalProviderRegressionTest#runGuestMatrix'), false);
  assert.equal(args.at(-1), 'io.andchamber.localprovider.test/androidx.test.runner.AndroidJUnitRunner');
});

test('product CLI refuses non-dedicated serial/package, fresh outside out, and unknown cases', () => {
  const out = `/tmp/opencode/product-cli-${process.pid}-${Date.now()}`;
  const parsed = driver.parseArgs(['--serial', 'emulator-5582', '--package', 'io.andchamber.localprovider', '--out', out, '--cases', 'product-desktop-1024']);
  assert.deepEqual(parsed.cases, ['product-desktop-1024']);
  assert.throws(() => driver.parseArgs(['--serial', 'emulator-5580', '--package', 'io.andchamber.localprovider', '--out', out]), /emulator-5582/);
  assert.throws(() => driver.parseArgs(['--serial', 'emulator-5582', '--package', 'io.andchamber.app', '--out', out]), /exactly|production/);
  assert.throws(() => driver.parseArgs(['--serial', 'emulator-5582', '--package', 'io.andchamber.localprovider', '--out', pathRepo(),]), /outside|absolute|exists/);
});

function pathRepo() {
  return require('node:path').resolve(__dirname, '..', 'build', 'product-out');
}

const READY_ALLOW = Object.freeze({ enabled: true, visible: true, clickable: true, left: 100, top: 100, right: 300, bottom: 200, windowId: 1 });
function readyUi(labels = {}) {
  // An explicit open reaches the default grant on readiness: Take over is the enabled control.
  // The floating window's full-screen control is "Full screen" unless the enlarged label is given.
  const base = { 'Take over': { ...READY_ALLOW }, ...labels };
  if (!('Small window' in labels)) base['Full screen'] = { ...READY_ALLOW };
  return { labels: base, surface: {} };
}
function readyNative(tabId, viewHandle = 7, extra = {}) {
  return { selectedTabId: tabId, pageReady: true, viewHandle, grantForSelected: false, agentOn: false, presentationBlocked: false, ...extra };
}

test('blocked-tight case uses compact WM, exact snapshot code, native input and restores WM', async () => {
  const state = {
    blocked: false,
    grant: true,
    viewHandle: 7,
    timeOrigin: 100,
    loads: 1,
    inputValue: '',
    autoFull: false,
    wmSet: 0,
    wmRestore: 0,
  };
  const presentation = () => ({
    fixture: {
      page: { timeOrigin: state.timeOrigin, loads: state.loads, noteValue: state.inputValue, noteFocused: true },
      native: { ...readyNative('tab-focus', state.viewHandle), presentationBlocked: state.blocked, grantForSelected: state.grant, agentOn: !state.blocked && state.grant, hasFocus: false },
    },
    observe: { metrics: { sy: state.blocked ? 0 : 10 } },
    ui: state.autoFull || state.blocked
      ? { labels: { 'Take over': { ...READY_ALLOW } }, surface: {} }
      : readyUi(),
    composer: { valid: true },
  });
  const io = {
    async open() { return { ok: true, tabId: 'tab-focus' }; },
    async readDiag() { return { ...readyNative('tab-focus', state.viewHandle), grantForSelected: state.grant, agentOn: !state.blocked && state.grant }; },
    async pageRect() { return { viewHandle: state.viewHandle, pointerUi: { viewHandle: state.viewHandle, geometryReady: true } }; },
    async presentation() { return presentation(); },
    async snapshot() { return { ok: false, code: 'BROWSER_PAGE_UNAVAILABLE' }; },
    async capture() { return { ok: false, code: 'BROWSER_PAGE_UNAVAILABLE' }; },
    async nativeBlockedInput() { return { handled: { touch: [true, true], generic: [true, true], key: [true, true] }, before: { keys: 1, clicks: 0, applies: 0, inputHash: 'a'.repeat(64) }, after: { keys: 1, clicks: 0, applies: 0, inputHash: 'a'.repeat(64) }, delta: { keys: 0, clicks: 0, applies: 0 }, inputChanged: false }; },
  };
  const ctx = {
    async readWmSize() { return { raw: 'Physical size: 1080x2280' }; },
    async setCompactSize(spec) {
      state.wmSet += 1;
      state.autoFull = spec === '1080x1000';
      state.blocked = spec === '1080x300';
      return { raw: `Override size: ${spec}` };
    },
    async restoreWmSize() { state.wmRestore += 1; state.blocked = false; state.autoFull = false; return { raw: 'Physical size: 1080x2280' }; },
  };
  const result = await product.PRODUCT_CASE_FUNCTIONS['product-blocked-tight'](io, ctx);
  assert.equal(result.ok, true, JSON.stringify(result));
  assert.equal(result.autoFull.agentOn, true, '1080x1000 is auto-full and keeps the grant');
  assert.equal(state.wmSet, 2, '1080x1000 positive then 1080x300 blocked');
  assert.equal(state.wmRestore, 2, 'case finally and recovery path restore');
});

test('blocked-tight refuses a wrong capture code, non-nested handled, changed hashes or deltas', async () => {
  const make = (overrides = {}) => {
    const state = { blocked: false, autoFull: false, grant: true, viewHandle: 7, timeOrigin: 100, loads: 1, inputValue: '', wmSet: 0, wmRestore: 0 };
    const io = {
      async open() { return { ok: true, tabId: 'tab-focus' }; },
      async readDiag() { return { ...readyNative('tab-focus', state.viewHandle), grantForSelected: state.grant, agentOn: !state.blocked && state.grant }; },
      async pageRect() { return { viewHandle: state.viewHandle, pointerUi: { viewHandle: state.viewHandle, geometryReady: true } }; },
      async presentation() { return { fixture: { page: { timeOrigin: state.timeOrigin, loads: state.loads, noteValue: state.inputValue, noteFocused: true }, native: { ...readyNative('tab-focus', state.viewHandle), presentationBlocked: state.blocked, grantForSelected: state.grant, agentOn: !state.blocked && state.grant, hasFocus: false } }, observe: { metrics: { sy: state.blocked ? 0 : 10 } }, ui: state.autoFull || state.blocked ? { labels: { 'Take over': { ...READY_ALLOW } }, surface: {} } : readyUi(), composer: { valid: true } }; },
      async snapshot() { return { ok: false, code: 'BROWSER_PAGE_UNAVAILABLE' }; },
      async capture() { return { ok: false, code: overrides.captureCode || 'BROWSER_PAGE_UNAVAILABLE' }; },
      async nativeBlockedInput() { return { handled: overrides.handled !== undefined ? overrides.handled : { touch: [true, true], generic: [true, true], key: [true, true] }, before: { keys: 1, clicks: 0, applies: 0, inputHash: overrides.beforeHash || 'a'.repeat(64) }, after: { keys: 1, clicks: 0, applies: 0, inputHash: overrides.afterHash || 'a'.repeat(64) }, delta: overrides.delta || { keys: 0, clicks: 0, applies: 0 }, inputChanged: overrides.inputChanged === true }; },
    };
    const ctx = {
      async readWmSize() { return { raw: 'Physical size: 1080x2280' }; },
      async setCompactSize(spec) {
        state.wmSet += 1;
        state.autoFull = spec === '1080x1000';
        state.blocked = spec === '1080x300';
      },
      async restoreWmSize() { state.wmRestore += 1; state.blocked = false; state.autoFull = false; },
    };
    return { io, ctx };
  };
  const wrongCaptureIo = make({ captureCode: 'NO_PROVIDER' });
  const wrongCapture = await product.PRODUCT_CASE_FUNCTIONS['product-blocked-tight'](wrongCaptureIo.io, wrongCaptureIo.ctx);
  assert.equal(wrongCapture.ok, false);
  assert.equal(wrongCapture.reason, 'blocked-capture-code');
  const flatHandled = make({ handled: false });
  const flat = await product.PRODUCT_CASE_FUNCTIONS['product-blocked-tight'](flatHandled.io, flatHandled.ctx);
  assert.equal(flat.ok, false);
  assert.equal(flat.reason, 'native-blocked-input-failed');
  const badHash = make({ beforeHash: 'h' });
  const badHashResult = await product.PRODUCT_CASE_FUNCTIONS['product-blocked-tight'](badHash.io, badHash.ctx);
  assert.equal(badHashResult.ok, false);
  assert.equal(badHashResult.reason, 'native-blocked-input-failed');
  const changed = make({ afterHash: 'b'.repeat(64) });
  const changedResult = await product.PRODUCT_CASE_FUNCTIONS['product-blocked-tight'](changed.io, changed.ctx);
  assert.equal(changedResult.ok, false);
  assert.equal(changedResult.reason, 'native-blocked-input-failed');
  const delta = make({ delta: { keys: 1, clicks: 0, applies: 0 } });
  const deltaResult = await product.PRODUCT_CASE_FUNCTIONS['product-blocked-tight'](delta.io, delta.ctx);
  assert.equal(deltaResult.ok, false);
  assert.equal(deltaResult.reason, 'native-blocked-input-failed');
});

test('blocked-tight refuses NoProvider-style/wrong snapshot code and native input nonzero', async () => {
  const state = { blocked: false, autoFull: false };
  const io = {
    async open() { return { ok: true, tabId: 'tab' }; },
    async readDiag() { return { ...readyNative('tab', 7), grantForSelected: true, agentOn: !state.blocked }; },
    async pageRect() { return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true } }; },
    async presentation() {
      return {
        fixture: { page: {}, native: { ...readyNative('tab', 7), presentationBlocked: state.blocked, grantForSelected: true, agentOn: !state.blocked } },
        observe: {},
        ui: state.autoFull || state.blocked ? { labels: { 'Take over': { ...READY_ALLOW } }, surface: {} } : readyUi(),
        composer: {},
      };
    },
    async snapshot() { return { ok: false, code: 'NO_PROVIDER' }; },
    async capture() { return { ok: false, code: 'NO_PROVIDER' }; },
    async nativeBlockedInput() { return { handled: { touch: [true, true], generic: [true, true], key: [true, true] }, before: { keys: 1, clicks: 0, applies: 0, inputHash: 'a'.repeat(64) }, after: { keys: 1, clicks: 0, applies: 0, inputHash: 'a'.repeat(64) }, delta: { keys: 0, clicks: 0, applies: 0 }, inputChanged: false }; },
  };
  const ctx = {
    async readWmSize() { return {}; },
    async setCompactSize(spec) { state.autoFull = spec === '1080x1000'; state.blocked = spec === '1080x300'; return {}; },
    async restoreWmSize() { state.blocked = false; state.autoFull = false; },
  };
  const result = await product.PRODUCT_CASE_FUNCTIONS['product-blocked-tight'](io, ctx);
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'blocked-snapshot-code');
});

test('desktop-1024 keeps its historical id but asserts real 1920x1080 JS layout', async () => {
  const page = { innerWidth: 1920, innerHeight: 1080, media1000: true, devicePixelRatio: 1 };
  const io = {
    async open() { return { ok: true, tabId: 'tab' }; },
    async tapUi() { return { ok: true }; },
    async readDiag() { return { ...readyNative('tab', 7), grantForSelected: true }; },
    async pageRect() { return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true }, page }; },
    async presentation() { return { fixture: { native: { ...readyNative('tab', 7), physicalFrame: { left: 0, top: 0, width: 900, height: 600 } } }, observe: { metrics: { ua: 'desktop' } }, ui: readyUi(), composer: {} }; },
  };
  const result = await product.PRODUCT_CASE_FUNCTIONS['product-desktop-1024'](io, {});
  assert.equal(result.ok, true, JSON.stringify(result));
  assert.equal(result.page.innerWidth, 1920);
  assert.equal(result.page.innerHeight, 1080);
  io.pageRect = async () => ({ viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true }, page: { ...page, innerWidth: 1024, innerHeight: 768 } });
  const failed = await product.PRODUCT_CASE_FUNCTIONS['product-desktop-1024'](io, {});
  assert.equal(failed.ok, false);
  assert.deepEqual(failed.failures.sort(), ['innerHeight', 'innerWidth']);
  io.pageRect = async () => ({ viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true }, page });
  io.presentation = async () => ({ fixture: { native: { ...readyNative('tab', 7), physicalFrame: { left: 0, top: 0, width: 900, height: 600 } } }, observe: { metrics: { ua: 'desktopish' } }, ui: readyUi(), composer: {} });
  const wrongUa = await product.PRODUCT_CASE_FUNCTIONS['product-desktop-1024'](io, {});
  assert.equal(wrongUa.ok, false);
  assert.deepEqual(wrongUa.failures, ['ua']);
});

test('console profile isolation requires supported/distinct/checks/cleaned true', async () => {
  const make = (overrides = {}) => ({
    now: () => 0,
    sleep: async () => {},
    async open() { return { ok: true, tabId: 'tab' }; },
    async readDiag() { return { ...readyNative('tab', 7), grantForSelected: true }; },
    async pageRect() { return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true } }; },
    async presentation() { return { fixture: { native: { ...readyNative('tab', 7), grantForSelected: true } }, observe: {}, ui: readyUi(), composer: {} }; },
    async profileIsolation() { return { supported: true, profiles: { browser: 'profile-browser', console: 'profile-console', other: 'profile-other' }, cookieStoresDistinct: true, checks: [{ writer: 'browser', ownPresent: true, otherWriters: ['console', 'other'], otherPresent: [false, false] }, { writer: 'console', ownPresent: true, otherWriters: ['browser', 'other'], otherPresent: [false, false] }, { writer: 'other', ownPresent: true, otherWriters: ['browser', 'console'], otherPresent: [false, false] }], cleaned: true, ...overrides }; },
  });
  assert.equal((await product.PRODUCT_CASE_FUNCTIONS['product-console-profile-isolation'](make(), {})).ok, true);
  assert.equal((await product.PRODUCT_CASE_FUNCTIONS['product-console-profile-isolation'](make({ cleaned: false }), {})).ok, false);
  assert.equal((await product.PRODUCT_CASE_FUNCTIONS['product-console-profile-isolation'](make({ checks: [{ writer: 'other', ownPresent: false, otherPresent: [false, false] }] }), {})).ok, false);
  const empty = await product.PRODUCT_CASE_FUNCTIONS['product-console-profile-isolation'](make({ checks: [] }), {});
  assert.equal(empty.ok, false);
  assert.equal(empty.reason, 'profile-checks-count');
  const duplicate = await product.PRODUCT_CASE_FUNCTIONS['product-console-profile-isolation'](make({ checks: [
    { writer: 'browser', ownPresent: true, otherWriters: ['console', 'other'], otherPresent: [false, false] },
    { writer: 'browser', ownPresent: true, otherWriters: ['console', 'other'], otherPresent: [false, false] },
    { writer: 'other', ownPresent: true, otherWriters: ['browser', 'console'], otherPresent: [false, false] },
  ] }), {});
  assert.equal(duplicate.ok, false);
  assert.equal(duplicate.reason, 'profile-writers-invalid');
});

test('all ten product cases are implemented; no pending stub remains', () => {
  assert.deepEqual([...product.PENDING_PRODUCT_CASES], []);
  for (const name of product.PRODUCT_CASE_NAMES) {
    assert.equal(typeof product.PRODUCT_CASE_FUNCTIONS[name], 'function');
    assert.equal(product.PRODUCT_CASE_FUNCTIONS[name].name.endsWith('Case') || product.PRODUCT_CASE_FUNCTIONS[name].name === 'blockedCase', true);
  }
});

test('the packaged catalog asset lists ten cases with the hover exception after move-click', () => {
  const catalog = product.readProductCatalog();
  assert.equal(catalog.cases.length, 10);
  assert.deepEqual(catalog.cases, [...product.PRODUCT_CASE_NAMES]);
  assert.equal(catalog.cases.indexOf('product-mouse-hover-a11y-off'), catalog.cases.indexOf('product-mouse-move-click') + 1);
});

function clocked() {
  let clock = 0;
  return { now: () => clock, sleep: async (ms) => { clock += ms; } };
}

function focusIo({ focusable = true, ime = true, overlap = false, unsafe = false, imeOverlap = false, imeLayout = 'float', blocked = false } = {}) {
  const clock = clocked();
  const state = { mouse: false, grant: true, note: '', noteFocused: false, docFocus: false, nativeFocus: false, ime: false, sy: 0, enlarged: false };
  return {
    now: clock.now,
    sleep: clock.sleep,
    async open() { return { ok: true, tabId: 'tab-focus' }; },
    async tapUi(label) {
      if (label === 'Resume agent') state.grant = true;
      if (label === 'Mouse') state.mouse = true;
      if (label === 'Touch') state.mouse = false;
      if (label === 'Full screen') state.enlarged = true;
      if (label === 'Small window') state.enlarged = false;
      return { ok: true };
    },
    async imeHide() {
      if (!state.ime) return { ok: false, reason: 'ime-not-shown' };
      state.ime = false;
      return { ok: true, data: { phase: 'ime-hide', shownBefore: true, shownAfter: false } };
    },
    async readDiag() { return { ...readyNative('tab-focus', 7), grantForSelected: state.grant, mouseMode: state.mouse, presentationBlocked: blocked }; },
    async pageRect() { return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true } }; },
    async presentation() {
      const editorTop = imeOverlap ? 350 : overlap ? 400 : 550;
      const editorBottom = imeOverlap ? 450 : overlap ? 500 : 600;
      const imePage = { left: 0, top: 0, width: imeLayout === 'float' ? 600 : 1000, height: unsafe ? 500 : 400 };
      const frame = state.ime
        ? imePage
        : state.enlarged
          ? { left: 0, top: 0, width: 1000, height: 600 }
          : { left: 0, top: 0, width: 600, height: imeOverlap ? 300 : 500 };
      const surface = state.ime
        ? imeLayout === 'full' || imeLayout === 'autoFull'
          ? { left: 0, top: 0, right: 1000, bottom: 400 }
          : { left: 0, top: 0, right: 700, bottom: 420 }
        : state.enlarged
          ? { left: 0, top: 0, right: 1000, bottom: 600 }
          : { left: 0, top: 0, right: 700, bottom: imeOverlap ? 350 : 550 };
      let ui;
      if (state.ime) {
        if (imeLayout === 'float') ui = readyUi();
        else if (imeLayout === 'full') ui = readyUi({ 'Small window': {} });
        else ui = { labels: { 'Take over': { ...READY_ALLOW } }, surface: {} };
      } else {
        ui = state.enlarged ? readyUi({ 'Small window': {} }) : readyUi();
      }
      ui.surface = surface;
      return {
        fixture: {
          native: { ...readyNative('tab-focus', 7), grantForSelected: state.grant, presentationBlocked: blocked, hasFocus: state.nativeFocus, physicalFrame: frame },
          page: { noteValue: state.note, noteFocused: state.noteFocused, noteEnabled: true, noteReadOnly: false, noteTag: 'INPUT', noteSelectionStart: state.noteFocused ? state.note.length : null, noteSelectionEnd: state.noteFocused ? state.note.length : null, documentHasFocus: state.docFocus, timeOrigin: 100, loads: 1 },
          outerime: { shown: state.ime, windowLeftPx: 0, windowTopPx: 0, windowRightPx: 1000, safeBottomPx: 400 },
        },
        observe: { sy: state.sy },
        ui,
        composer: {
          mainWebView: { x: 0, y: 0, width: 1000, height: 600 },
          measurement: { consoleGeneration: 1, nativeGeneration: 1, nativeWidth: 1000, nativeHeight: 600, top: editorTop, bottom: editorBottom, viewportWidth: 1000, viewportHeight: 600, visible: true },
          consoleGeneration: 1, nativeGeneration: 1, contentWidth: 1000, contentHeight: 600,
          selectedClearancePx: imeOverlap ? 150 : overlap ? 100 : 50, fallback: false, validityReason: 'accepted',
        },
      };
    },
    async snapshot() { return { ok: true, data: { elements: [{ name: 'note', selector: '#note' }] } }; },
    async type(_selector, value) { state.note = value; return { ok: true }; },
    async twoFinger() { state.sy = 120; return { ok: true, command: 'test', center: { x: 0, y: 0 }, frame: {}, reply: {} }; },
    async tapFixtureField() {
      state.noteFocused = focusable;
      state.docFocus = focusable;
      state.nativeFocus = focusable;
      state.ime = focusable && ime;
      return { ok: true };
    },
  };
}

test('focused product flow: IME accepts real Full/Float, Back restores Float, then full/small cycle', async () => {
  const ok = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo(), {});
  assert.equal(ok.ok, true, JSON.stringify(ok));
  assert.equal(ok.imeLayout, 'float');
  const full = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ imeLayout: 'full' }), {});
  assert.equal(full.ok, true, JSON.stringify(full));
  assert.equal(full.imeLayout, 'full');
  const autoFull = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ imeLayout: 'autoFull' }), {});
  assert.equal(autoFull.ok, true, JSON.stringify(autoFull));
  assert.equal(autoFull.imeLayout, 'full-auto');
  const fullOverlap = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ imeLayout: 'full', imeOverlap: true }), {});
  assert.equal(fullOverlap.ok, true, JSON.stringify(fullOverlap));
  assert.equal(fullOverlap.imeLayout, 'full');
  const noFocus = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ focusable: false }), {});
  assert.equal(noFocus.ok, false);
  assert.equal(noFocus.reason, 'note-focus-timeout');
  const noFocusDoc = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ ime: false }), {});
  assert.equal(noFocusDoc.ok, false);
  const imeOverlap = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ imeOverlap: true }), {});
  assert.equal(imeOverlap.ok, false);
  assert.equal(imeOverlap.reason, 'ime-shown-float-overlaps-composer');
  const unknown = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ imeLayout: 'unknown' }), {});
  assert.equal(unknown.ok, false);
  assert.equal(unknown.reason, 'ime-shown-window-mode');
  const blocked = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ blocked: true }), {});
  assert.equal(blocked.ok, false);
  assert.equal(blocked.reason, 'ime-shown-blocked');
  const overlap = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ overlap: true }), {});
  assert.equal(overlap.ok, false);
  assert.equal(overlap.reason, 'ime-gone-autfloat-float-overlaps-composer');
  const unsafe = await product.PRODUCT_CASE_FUNCTIONS['product-focused-restore'](focusIo({ unsafe: true }), {});
  assert.equal(unsafe.ok, false);
  assert.equal(unsafe.reason, 'ime-shown-page-outside-ime-safe');
});

function mouseIo({ logClick = true, logMove = false, clickOffset = 0 } = {}) {
  const clock = clocked();
  const state = { mouse: false, grant: true, logs: [], pointer: { x: 0, y: 0 }, clicks: 0 };
  const box = { left: 400, top: 150, right: 520, bottom: 230 };
  return {
    now: clock.now,
    sleep: clock.sleep,
    async open() { return { ok: true, tabId: 'tab-mouse' }; },
    async tapUi(label) {
      if (label === 'Resume agent') state.grant = true;
      if (label === 'Mouse') state.mouse = true;
      if (label === 'Touch') state.mouse = false;
      return { ok: true };
    },
    async readDiag() { return { ...readyNative('tab-mouse', 7), grantForSelected: state.grant, mouseMode: state.mouse }; },
    async pageRect() {
      return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true, pointerViewX: state.pointer.x, pointerViewY: state.pointer.y }, physical: { left: 0, top: 0, width: 1024, height: 600 }, page: { visualViewport: { width: 1024, height: 600, offsetLeft: 0, offsetTop: 0 } } };
    },
    async fixtureTarget(_id, x, y) {
      return { box, hit: x >= box.left && x <= box.right && y >= box.top && y <= box.bottom };
    },
    async finger(text) {
      const values = text.replace('finger:', '').split(',').map(Number);
      const [sx, sy, dx, dy] = values;
      if (dx !== 0 || dy !== 0) {
        // Touchpad semantics: the finger start is irrelevant; the pointer moves by the delta.
        state.pointer = { x: state.pointer.x + dx, y: state.pointer.y + dy };
        if (logMove) state.logs.push({ t: 'pointermove', p: 'mouse', b: -1, bs: 0, x: state.pointer.x, y: state.pointer.y });
      } else if (logClick) {
        state.clicks += 1;
        state.logs.push({ t: 'pointerdown', p: 'mouse', b: 0, bs: 1, x: state.pointer.x, y: state.pointer.y });
        state.logs.push({ t: 'click', p: 'mouse', b: 0, bs: 0, x: state.pointer.x + clickOffset, y: state.pointer.y + clickOffset });
      }
      return true;
    },
    async presentation() {
      return {
        fixture: { native: { ...readyNative('tab-mouse', 7), physicalFrame: { left: 0, top: 0, width: 1024, height: 600 } } },
        observe: { log: state.logs.map((entry) => ({ ...entry })) },
        ui: readyUi(),
        composer: {},
      };
    },
  };
}

test('mouse move-click enables Mouse, uses measured pointer hit and counts the real report log', async () => {
  // Hover alone produces no DOM pointermove; the click landing at the moved pointer is the proof.
  const ok = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-move-click'](mouseIo(), {});
  assert.equal(ok.ok, true, JSON.stringify(ok));
  assert.equal(ok.moved, true);
  assert.equal(ok.pointermoveObserved, false);
  // The click case still verifies landing; the missing hover is disclosed, never a pass claim.
  assert.equal(ok.hover, 'unsupported-a11y-on');
  assert.ok(Math.hypot(ok.dx, ok.dy) > 32);
  const withMove = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-move-click'](mouseIo({ logMove: true }), {});
  assert.equal(withMove.ok, true, JSON.stringify(withMove));
  assert.equal(withMove.pointermoveObserved, true, 'an emitted pointermove is recorded but not required');
  assert.equal(withMove.hover, 'observed');
  const noLog = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-move-click'](mouseIo({ logClick: false }), {});
  assert.equal(noLog.ok, false);
  assert.equal(noLog.reason, 'mouse-click-count');
  const offBy = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-move-click'](mouseIo({ clickOffset: 3 }), {});
  assert.equal(offBy.ok, false);
  assert.equal(offBy.reason, 'mouse-click-position');
  assert.equal(offBy.moved, true);
});

function hoverA11yIo({
  move = true, moveOffset = 0, extraClick = false, a11yDuringGesture = false,
  beginCode = null, endOk = true,
  pageWidth = 1024, physicalWidth = 1024,
  box = { left: 400, top: 150, right: 520, bottom: 230 },
  pointerCss = { x: 0, y: 0 },
} = {}) {
  const clock = clocked();
  const scale = physicalWidth / pageWidth;
  const state = { mouse: false, grant: true, logs: [], pointer: { x: pointerCss.x, y: pointerCss.y }, began: 0, ended: 0 };
  return {
    now: clock.now,
    sleep: clock.sleep,
    began: () => state.began,
    ended: () => state.ended,
    async open() { return { ok: true, tabId: 'tab-hover' }; },
    async tapUi(label) {
      if (label === 'Resume agent') state.grant = true;
      if (label === 'Mouse') state.mouse = true;
      if (label === 'Touch') state.mouse = false;
      return { ok: true };
    },
    async readDiag() { return { ...readyNative('tab-hover', 7), grantForSelected: state.grant, mouseMode: state.mouse }; },
    async pageRect() {
      return { viewHandle: 7, viewWidth: pageWidth, viewHeight: 600, pointerUi: { viewHandle: 7, geometryReady: true, pointerViewX: state.pointer.x, pointerViewY: state.pointer.y }, physical: { left: 0, top: 0, width: physicalWidth, height: 600 }, page: { visualViewport: { width: pageWidth, height: 600, offsetLeft: 0, offsetTop: 0 } } };
    },
    async fixtureTarget(_id, x, y) {
      return { box, hit: x >= box.left && x <= box.right && y >= box.top && y <= box.bottom };
    },
    async presentation() {
      return {
        fixture: {
          native: { ...readyNative('tab-hover', 7), physicalFrame: { left: 0, top: 0, width: physicalWidth, height: 600 } },
          page: { timeOrigin: 100, loads: 1 },
        },
        observe: { log: state.logs.map((entry) => ({ ...entry })) },
        ui: readyUi(),
        composer: {},
      };
    },
    async a11yOffBegin() {
      state.began += 1;
      if (beginCode) return { ok: false, data: null, code: beginCode, detail: beginCode, commandId: 'cmd-begin' };
      return { ok: true, data: { phase: 'a11y-off', state: 'off', a11yEnabledBefore: true, a11yEnabledAfter: false, elapsedMs: 120 }, code: '', detail: '', commandId: 'cmd-begin' };
    },
    async a11yOffEnd() {
      state.ended += 1;
      if (!endOk) return { ok: false, data: null, code: 'a11y-restore-failed', detail: 'a11y-restore-failed', commandId: 'cmd-end' };
      return { ok: true, data: { phase: 'a11y-off', state: 'on', a11yEnabled: true, elapsedMs: 130 }, code: '', detail: '', commandId: 'cmd-end' };
    },
    async finger(text) {
      const values = text.replace('finger:', '').split(',').map(Number);
      const [, , dx, dy, hold, steps] = values;
      if (hold !== 0 || steps < 1) return false;
      state.pointer = { x: state.pointer.x + dx / scale, y: state.pointer.y + dy / scale };
      return true;
    },
    async hoverFinger(text) {
      const values = text.replace('finger:', '').split(',').map(Number);
      const [sx, sy, dx, dy, hold, steps] = values;
      if (hold !== 0 || steps < 1) return { ok: false, data: null, code: 'bad-finger', detail: 'bad-finger', commandId: 'cmd-finger' };
      state.pointer = { x: state.pointer.x + dx / scale, y: state.pointer.y + dy / scale };
      if (move) {
        state.logs.push({ t: 'pointermove', p: 'mouse', b: -1, bs: 0, x: Math.round(state.pointer.x + moveOffset), y: Math.round(state.pointer.y + moveOffset) });
      }
      if (extraClick) {
        state.logs.push({ t: 'pointerdown', p: 'mouse', b: 0, bs: 1, x: state.pointer.x, y: state.pointer.y });
        state.logs.push({ t: 'click', p: 'mouse', b: 0, bs: 0, x: state.pointer.x, y: state.pointer.y });
      }
      return { ok: true, data: { phase: 'finger', sent: true, a11yEnabledDuringGesture: a11yDuringGesture }, code: '', detail: '', commandId: 'cmd-finger' };
    },
  };
}

test('mouse hover a11y-off passes with one button-free pointermove at the measured point', async () => {
  const io = hoverA11yIo();
  const result = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](io, {});
  assert.equal(result.ok, true, JSON.stringify(result));
  assert.equal(result.hover, 'observed');
  assert.equal(result.pointermoveObserved, true);
  assert.equal(result.moves, 1);
  assert.ok(Math.hypot(result.dx, result.dy) > 32);
  assert.equal(io.began(), 1);
  assert.equal(io.ended(), 1, 'the accessibility-off window is closed exactly once');
});

test('mouse hover at the desktop display scale moves away before a large real move back to the box', async () => {
  // Prior move-click leaves the pointer at the box centre. On the 1920 CSS desktop viewport the real
  // box is only ~40 display px wide, so a centre-to-inset-corner move is naturally below 32 px.
  const io = hoverA11yIo({
    pageWidth: 1920,
    physicalWidth: 512,
    box: { left: 400, top: 150, right: 550, bottom: 230 },
    pointerCss: { x: 475, y: 190 },
  });
  const result = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](io, {});
  assert.equal(result.ok, true, JSON.stringify(result));
  assert.equal(result.hover, 'observed');
  assert.equal(result.pointermoveObserved, true);
  assert.equal(result.moves, 1, 'only the move back to the target is new hover evidence');
  assert.ok(result.away, 'the small natural move first performed a real away move');
  assert.equal(result.away.hit, false);
  assert.ok(Math.hypot(result.away.displayDx, result.away.displayDy) >= 64);
  assert.ok(Math.hypot(result.dx, result.dy) > 32);
  assert.equal(io.began(), 1);
  assert.equal(io.ended(), 1);
});

test('mouse hover a11y-off fails typed on a missing move, an extra click, an off-point move or a11y still on', async () => {
  const missing = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](hoverA11yIo({ move: false }), {});
  assert.equal(missing.ok, false);
  assert.equal(missing.reason, 'hover-pointermove-missing');
  assert.equal(missing.restore.ok, true, 'a failed hover still restores accessibility');

  const clicked = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](hoverA11yIo({ extraClick: true }), {});
  assert.equal(clicked.ok, false);
  assert.equal(clicked.reason, 'hover-extra-pointer-events');
  assert.ok(clicked.forbidden.length >= 1);

  const offPoint = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](hoverA11yIo({ moveOffset: 3 }), {});
  assert.equal(offPoint.ok, false);
  assert.equal(offPoint.reason, 'hover-pointermove-position');

  const enabled = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](hoverA11yIo({ a11yDuringGesture: true }), {});
  assert.equal(enabled.ok, false);
  assert.equal(enabled.reason, 'hover-a11y-enabled-during-gesture');

  const stillEnabled = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](hoverA11yIo({ beginCode: 'a11y-still-enabled' }), {});
  assert.equal(stillEnabled.ok, false);
  assert.equal(stillEnabled.reason, 'a11y-off-begin-failed');
  assert.equal(stillEnabled.begin.code, 'a11y-still-enabled');
  assert.equal(stillEnabled.restore.ok, true, 'restore is always attempted, even after a refused begin');

  const noRestore = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-hover-a11y-off'](hoverA11yIo({ endOk: false }), {});
  assert.equal(noRestore.ok, false);
  assert.equal(noRestore.reason, 'a11y-restore-failed');
});

function dragIo({ moveBox = true } = {}) {
  const clock = clocked();
  const state = { mouse: false, grant: true, logs: [], pointer: { x: 0, y: 0 }, box: { left: 400, top: 150, right: 520, bottom: 230 }, sy: 0 };
  const presentation = () => ({
    observe: {
      sy: state.sy,
      report: { box: [state.box.left, state.box.top] },
      log: state.logs.slice(-40),
    },
  });
  return {
    now: clock.now,
    sleep: clock.sleep,
    async open() { return { ok: true, tabId: 'tab-drag' }; },
    async tapUi(label) {
      if (label === 'Resume agent') state.grant = true;
      if (label === 'Mouse') state.mouse = true;
      if (label === 'Touch') state.mouse = false;
      return { ok: true };
    },
    async readDiag() { return { ...readyNative('tab-drag', 7), grantForSelected: state.grant, mouseMode: state.mouse }; },
    async pageRect() {
      return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true, pointerViewX: state.pointer.x, pointerViewY: state.pointer.y }, physical: { left: 0, top: 0, width: 1024, height: 600 }, page: { visualViewport: { width: 1024, height: 600, offsetLeft: 0, offsetTop: 0 } } };
    },
    async fixtureTarget(_id, x, y) {
      return { box: { ...state.box }, hit: x >= state.box.left && x <= state.box.right && y >= state.box.top && y <= state.box.bottom };
    },
    async finger(text) {
      const values = text.replace('finger:', '').split(',').map(Number);
      const [sx, sy, dx, dy, hold] = values;
      if (hold > 0) {
        state.logs.push({ t: 'pointerdown', p: 'mouse', b: 0, bs: 1 });
        state.logs.push({ t: 'pointermove', p: 'mouse', b: -1, bs: 1 });
        state.logs.push({ t: 'pointerup', p: 'mouse', b: 0, bs: 0 });
        state.pointer = { x: state.pointer.x + dx, y: state.pointer.y + dy };
        if (moveBox) {
          state.box.left += dx;
          state.box.top += dy;
        }
      } else if (dx !== 0 || dy !== 0) {
        state.pointer = { x: state.pointer.x + dx, y: state.pointer.y + dy };
        state.logs.push({ t: 'pointermove', p: 'mouse', b: -1, bs: 0 });
      }
      return true;
    },
    async twoFinger() {
      state.sy = 200;
      state.logs.push({ t: 'wheel', dy: -240 });
      return { ok: true, command: 'test', center: { x: 0, y: 0 }, frame: {}, reply: {} };
    },
    async presentation() {
      return {
        ...presentation(),
        fixture: { native: { ...readyNative('tab-drag', 7), physicalFrame: { left: 0, top: 0, width: 1024, height: 600 } } },
        ui: readyUi(),
        composer: {},
      };
    },
  };
}

test('mouse drag uses one held press and a measured DOM drop, then a real wheel', async () => {
  const ok = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-drag-wheel'](dragIo(), {});
  assert.equal(ok.ok, true, JSON.stringify(ok));
  assert.equal(ok.afterBox[0] - ok.beforeBox[0], 40);
  assert.equal(ok.afterBox[1] - ok.beforeBox[1], 20);
  const noMove = await product.PRODUCT_CASE_FUNCTIONS['product-mouse-drag-wheel'](dragIo({ moveBox: false }), {});
  assert.equal(noMove.ok, false);
  assert.equal(noMove.reason, 'drag-drop-mismatch');
});

function pinchIo({ changes = true } = {}) {
  const clock = clocked();
  const state = { mouse: false, grant: true, scale: 1, width: 800 };
  return {
    now: clock.now,
    sleep: clock.sleep,
    async open() { return { ok: true, tabId: 'tab-pinch' }; },
    async tapUi(label) { if (label === 'Resume agent') state.grant = true; return { ok: true }; },
    async readDiag() { return { ...readyNative('tab-pinch', 7), grantForSelected: state.grant, mouseMode: state.mouse }; },
    async presentation() {
      return { fixture: { native: { ...readyNative('tab-pinch', 7) } }, observe: { timeOrigin: 100, loads: 1, visualViewport: { scale: state.scale, width: state.width } }, ui: readyUi(), composer: {} };
    },
    async pinch() { if (changes) { state.scale = 1.5; state.width = 600; } return { ok: true, command: 'test', center: { x: 0, y: 0 }, frame: {}, reply: {} }; },
    async pageRect() { return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true } }; },
  };
}

test('pinch requires an actual scale or width change, not a window resize', async () => {
  const ok = await product.PRODUCT_CASE_FUNCTIONS['product-pinch'](pinchIo(), {});
  assert.equal(ok.ok, true, JSON.stringify(ok));
  const unchanged = await product.PRODUCT_CASE_FUNCTIONS['product-pinch'](pinchIo({ changes: false }), {});
  assert.equal(unchanged.ok, false);
  assert.equal(unchanged.reason, 'pinch-scale-not-changed');
});

function sessionIo({ corrupt = false } = {}) {
  const clock = clocked();
  const state = { grant: true, selected: false, clearCalls: 0, opens: 0, tabId: 'tab-1' };
  const hash = () => (corrupt && state.selected ? 'other' : 'same-hash');
  return {
    now: clock.now,
    sleep: clock.sleep,
    async open() { state.opens += 1; state.tabId = `tab-${state.opens}`; state.grant = true; return { ok: true, tabId: state.tabId }; },
    async tapUi(label) { if (label === 'Resume agent') state.grant = true; return { ok: true }; },
    async readDiag() { return { ...readyNative(state.tabId, 7), grantForSelected: state.grant }; },
    async pageRect() { return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true } }; },
    async presentation() { return { fixture: { native: { ...readyNative(state.tabId, 7) } }, observe: {}, ui: readyUi(), composer: {} }; },
    async siteState(action) {
      if (action === 'write') return { set: true };
      if (action === 'read') return { cookiePresent: true, storagePresent: true, cookieHash: 'same-hash', storageHash: hash(), profileName: 'profile-a' };
      if (action === 'clear') { state.clearCalls += 1; return { cleared: true }; }
      return null;
    },
    async snapshot() { return { ok: true, data: { elements: [{ selector: '#note' }] } }; },
    async sessionNew() { state.selected = true; state.grant = false; return { ok: true, data: { sessionId: 'ses-product-b' } }; },
    async click() { return { ok: false, code: 'AUTHORIZATION_REQUIRED' }; },
    get clearCalls() { return state.clearCalls; },
  };
}

test('session storage keeps the same profile/hash only and clears its fixed marker in finally', async () => {
  const io = sessionIo();
  const ok = await product.PRODUCT_CASE_FUNCTIONS['product-session-storage'](io, {});
  assert.equal(ok.ok, true, JSON.stringify(ok));
  assert.equal(ok.sessionId, 'ses-product-b');
  assert.equal(io.clearCalls, 1);
  const corrupt = sessionIo({ corrupt: true });
  const failed = await product.PRODUCT_CASE_FUNCTIONS['product-session-storage'](corrupt, {});
  assert.equal(failed.ok, false);
  assert.equal(failed.reason, 'site-state-not-preserved');
  assert.equal(corrupt.clearCalls, 1);
});

function nativeEntryIo({ skipOpen = false, typeOk = true, wrongValue = false, drawerOk = true, menuMissing = false, handleMissing = false } = {}) {
  const clock = clocked();
  const state = {
    grant: true, selectedTab: 'tab-old', selectedOwner: 'ses-a', viewHandle: 7, tabIds: ['tab-old'],
    drawer: false, menu: false, dialog: false, addressFocused: false, addressLength: 0,
    openTapped: 0, typeCalls: 0, sessionNewCalls: 0,
    order: [],
  };
  const target = (label) => {
    if (label === 'Open sessions and projects') return { ok: state.drawer === false, target: { label } };
    if (label === 'New chat') return { ok: state.drawer, target: { label } };
    if (label === 'AndChamber menu') return { ok: !handleMissing && state.sessionNewCalls === 1, target: { label } };
    if (label === 'Open web page') return { ok: !menuMissing && state.menu, target: { label } };
    if (label === 'Address') return { ok: state.dialog, target: { label } };
    if (label === 'Open') return { ok: state.dialog && state.addressLength > 0, target: { label } };
    if (label === 'Cancel') return { ok: state.dialog, target: { label } };
    return { ok: false };
  };
  return {
    now: clock.now,
    sleep: clock.sleep,
    async open() { return { ok: true, tabId: 'tab-old' }; },
    async tapUi(label) { if (label === 'Resume agent') state.grant = true; return { ok: true, commandId: `ui-${label}` }; },
    async presentation() {
      return {
        fixture: { native: { ...readyNative(state.selectedTab, state.viewHandle), owner: 'ses-a', grantForSelected: state.grant }, outer: { shown: false } },
        observe: {}, ui: readyUi(), composer: {},
      };
    },
    async pageRect() { return { viewHandle: state.viewHandle, pointerUi: { viewHandle: state.viewHandle, geometryReady: true } }; },
    async readDiag() {
      return {
        ...readyNative(state.selectedTab, state.viewHandle),
        tabIds: [...state.tabIds], grantForSelected: state.grant,
        selectedTabOwner: state.selectedOwner,
      };
    },
    async chatTarget(label) { return target(label); },
    async tapChat(label) {
      const allowed = target(label);
      if (!allowed.ok) return { ok: false, reason: 'target-missing' };
      state.order.push(label);
      if (label === 'Open sessions and projects') {
        if (!drawerOk) return { ok: false, reason: 'drawer-failed' };
        state.drawer = true;
      } else if (label === 'New chat') {
        state.selectedTab = null;
        state.selectedOwner = null;
        state.grant = false;
      } else if (label === 'AndChamber menu') {
        state.menu = true;
      } else if (label === 'Open web page') {
        state.menu = false;
        state.dialog = true;
      } else if (label === 'Address') {
        state.addressFocused = true;
      } else if (label === 'Open') {
        state.openTapped += 1;
        if (skipOpen) return { ok: false, reason: 'open-tap-failed' };
        state.selectedTab = 'tab-new';
        state.selectedOwner = 'ses-product-b';
        state.viewHandle = 8;
        state.tabIds.push('tab-new');
        // The person's explicit Open reaches the default ready grant.
        state.grant = true;
      }
      return { ok: true, commandId: `chat-${label}` };
    },
    async nativeEntryState() {
      return {
        ok: true,
        dialog: state.dialog,
        titlePresent: state.dialog,
        address: {
          present: state.dialog, focused: state.addressFocused, editable: true, enabled: true,
          valueLength: state.addressLength, valueExact: state.addressLength > 0 && !wrongValue,
        },
        open: { present: state.dialog, enabled: state.addressLength > 0 && !wrongValue },
        selectedTabId: state.selectedTab,
      };
    },
    async typeAddress(url) {
      state.typeCalls += 1;
      if (!typeOk) return { ok: false, reason: 'type-failed' };
      if (url !== 'https://example.com/openchamber-provider-focused') return { ok: false, reason: 'wrong-url' };
      state.addressLength = url.length;
      return { ok: true, typed: true, text: { ok: true, command: 'shell input text <fixed-focus-url>', elapsedMs: 1 } };
    },
    async sessionNew() {
      state.sessionNewCalls += 1;
      state.grant = false;
      return { ok: true, data: { sessionId: 'ses-product-b' } };
    },
    get state() { return state; },
  };
}

test('native entry follows the edge handle, dialog Address and Open, and never uses tool.open', async () => {
  const io = nativeEntryIo();
  const ok = await product.PRODUCT_CASE_FUNCTIONS['product-native-entry'](io, {});
  assert.equal(ok.ok, true, JSON.stringify(ok));
  assert.equal(ok.sessionA, 'ses-a');
  assert.equal(ok.sessionB, 'ses-product-b');
  assert.equal(ok.oldTab, 'tab-old');
  assert.equal(ok.afterTab, 'tab-new');
  assert.notEqual(ok.afterView, ok.oldView);
  assert.equal(ok.addressValueExact, true);
  assert.deepEqual(io.state.order, ['Open sessions and projects', 'New chat', 'AndChamber menu', 'Open web page', 'Address', 'Open']);
  assert.equal(io.state.sessionNewCalls, 1);
  assert.equal(io.state.typeCalls, 1);
  assert.equal(io.state.openTapped, 1);

  const noHandle = await product.PRODUCT_CASE_FUNCTIONS['product-native-entry'](nativeEntryIo({ handleMissing: true }), {});
  assert.equal(noHandle.ok, false);
  assert.equal(noHandle.reason, 'edge-handle-target-missing');

  const noMenu = await product.PRODUCT_CASE_FUNCTIONS['product-native-entry'](nativeEntryIo({ menuMissing: true }), {});
  assert.equal(noMenu.ok, false);
  assert.equal(noMenu.reason, 'open-web-page-target-missing');
  assert.equal(noMenu.commands, undefined);

  const noEntry = await product.PRODUCT_CASE_FUNCTIONS['product-native-entry'](nativeEntryIo({ skipOpen: true }), {});
  assert.equal(noEntry.ok, false);
  assert.equal(noEntry.reason, 'dialog-open-tap-failed');

  const drawer = nativeEntryIo({ drawerOk: false });
  const drawerResult = await product.PRODUCT_CASE_FUNCTIONS['product-native-entry'](drawer, {});
  assert.equal(drawerResult.ok, false);
  assert.equal(drawerResult.reason, 'sessions-drawer-missing');
  assert.equal(drawer.state.sessionNewCalls, 0, 'failed drawer never continues into a new session');

  const typeFail = nativeEntryIo({ typeOk: false });
  const typeResult = await product.PRODUCT_CASE_FUNCTIONS['product-native-entry'](typeFail, {});
  assert.equal(typeResult.ok, false);
  assert.equal(typeResult.reason, 'type-failed');
  assert.equal(typeFail.state.openTapped, 0, 'type failure never taps Open');

  const wrong = nativeEntryIo({ wrongValue: true });
  const wrongResult = await product.PRODUCT_CASE_FUNCTIONS['product-native-entry'](wrong, {});
  assert.equal(wrongResult.ok, false);
  assert.equal(wrongResult.reason, 'dialog-address-value-not-exact');
  assert.equal(wrong.state.openTapped, 0);
});

function wmAdb({ override = null, density = 'Physical density: 420', parseFail = false, restoreFail = false } = {}) {
  const physical = { width: 1080, height: 2400 };
  let currentOverride = override ? { ...override } : null;
  const commands = [];
  const adb = async (command) => {
    const line = command.join(' ');
    if (line === 'shell wm size') {
      if (parseFail) return { code: 0, stdout: 'garbage', stderr: '' };
      let stdout = `Physical size: ${physical.width}x${physical.height}`;
      if (currentOverride) stdout += `\nOverride size: ${currentOverride.width}x${currentOverride.height}`;
      return { code: 0, stdout, stderr: '' };
    }
    if (line === 'shell wm density') return { code: 0, stdout: density, stderr: '' };
    if (line.startsWith('shell wm size ')) {
      commands.push(command);
      const spec = command[3];
      if (spec === 'reset') {
        if (restoreFail) return { code: 1, stdout: '', stderr: 'restore failed' };
        currentOverride = null;
        return { code: 0, stdout: '', stderr: '' };
      }
      const [width, height] = spec.split('x').map(Number);
      currentOverride = { width, height };
      return { code: 0, stdout: '', stderr: '' };
    }
    return { code: 1, stdout: '', stderr: `unexpected ${line}` };
  };
  return { adb, commands, physical, currentOverride: () => currentOverride };
}

test('WM guard uses real page frame, rejects bad reads/specs, and restores the exact override', async () => {
  const fake = wmAdb({ override: { width: 640, height: 1000 } });
  const guard = driver.createWmGuard(fake.adb, () => {});
  const original = await guard.remember();
  assert.equal(original.physical.width, 1080);
  assert.equal(original.override.width, 640);
  const set = await guard.setCompactSize('1080x1000');
  assert.equal(set.ok, true, JSON.stringify(set));
  assert.equal(guard.changed(), true);
  assert.equal(fake.commands[0][3], '1080x1000');
  const restored = await guard.restore();
  assert.equal(restored.ok, true, JSON.stringify(restored));
  assert.equal(guard.changed(), false);
  assert.deepEqual(fake.commands[1], ['shell', 'wm', 'size', '640x1000']);
  assert.equal(fake.currentOverride().width, 640);
  assert.equal(fake.currentOverride().height, 1000);
  assert.equal(restored.readback.physical.height, 2400);
  assert.equal(restored.readback.density, 'Physical density: 420');
});

test('WM guard keeps changed on restore failure and marks the final host verdict false', async () => {
  const fake = wmAdb({ restoreFail: true });
  const guard = driver.createWmGuard(fake.adb, () => {});
  assert.equal((await guard.setCompactSize('1080x1000')).ok, true);
  const restored = await guard.restore();
  assert.equal(restored.ok, false);
  assert.equal(restored.code, 'wm-restore-exit');
  assert.equal(guard.changed(), true);
  const evidence = driver.markWmRestoreFailure({ verdict: { ok: true, reasons: [] } }, restored);
  assert.equal(evidence.verdict.ok, false);
  assert.ok(evidence.verdict.reasons.includes('wm-restore-failed'));
});

test('WM guard rejects invalid spec and unparsable read without sending a resize', async () => {
  const fake = wmAdb();
  const guard = driver.createWmGuard(fake.adb, () => {});
  const invalid = await guard.setCompactSize('bad-size');
  assert.equal(invalid.ok, false);
  assert.equal(invalid.code, 'wm-size-invalid');
  assert.equal(fake.commands.length, 0);
  const unparsable = wmAdb({ parseFail: true });
  const badGuard = driver.createWmGuard(unparsable.adb, () => {});
  const read = await badGuard.setCompactSize('1080x1000');
  assert.equal(read.ok, false);
  assert.equal(read.code, 'wm-read-failed');
  assert.equal(unparsable.commands.length, 0);
  assert.equal(badGuard.changed(), false);
});

test('gesture commands use the actual page-frame center and one post-read mutation', async () => {
  const frame = { left: 198, top: 1574, width: 819, height: 252 };
  const sent = [];
  const client = {
    async sendDevice(text) {
      if (text === 'page-rect') {
        return { ok: true, data: { viewHandle: 7, physical: frame, pointerUi: { viewHandle: 7, geometryReady: true }, page: {} } };
      }
      if (text === 'lifecycle-diag') return { ok: true, data: { viewHandle: 7, pageReady: true, selectedTabId: 'tab-gesture' } };
      sent.push(text);
      return { ok: true, data: { sent: true } };
    },
  };
  const io = product.createProductIo({ client, runId: 'product-gesture', screen: { width: 1080, height: 2400 } });
  const two = await io.twoFinger(-240);
  assert.equal(two.ok, true, JSON.stringify(two));
  assert.equal(two.command, 'two-finger-scroll:607.5,1700,-240');
  assert.deepEqual(two.center, { x: 607.5, y: 1700 });
  const pinch = await io.pinch();
  assert.equal(pinch.ok, true, JSON.stringify(pinch));
  assert.equal(pinch.command, 'pinch:607.5,1700');
  assert.deepEqual(sent, ['two-finger-scroll:607.5,1700,-240', 'pinch:607.5,1700']);

  const fence = product.createCaseFence();
  let fencedSent = 0;
  const fencedClient = {
    async sendDevice(text) {
      if (text === 'page-rect') {
        fence.stop();
        return { ok: true, data: { viewHandle: 7, physical: frame, pointerUi: { viewHandle: 7, geometryReady: true }, page: {} } };
      }
      if (text === 'lifecycle-diag') return { ok: true, data: { viewHandle: 7, pageReady: true, selectedTabId: 'tab-gesture' } };
      fencedSent += 1;
      return { ok: true, data: { sent: true } };
    },
  };
  const fencedIo = product.createProductIo({ client: fencedClient, runId: 'product-gesture-fenced', screen: { width: 1080, height: 2400 }, fence });
  const fenced = await fencedIo.twoFinger(-240);
  assert.equal(fenced.ok, false);
  assert.equal(fenced.code, 'FENCED');
  assert.equal(fencedSent, 0);
});

test('delayed UI target read is fenced before any ADB tap', async () => {
  const fence = product.createCaseFence();
  let taps = 0;
  const client = {
    async sendDevice() {
      fence.stop();
      return { ok: true, data: { runId: 'product-target', phase: 'ui-target', label: 'Full screen' } };
    },
  };
  const io = product.createProductIo({
    client,
    runId: 'product-target',
    screen: { width: 1080, height: 2400 },
    tapScreen: async () => { taps += 1; },
    fence,
  });
  const result = await io.tapUi('Full screen');
  assert.equal(result.ok, false);
  assert.equal(result.code, 'FENCED');
  assert.equal(taps, 0);
});

test('native entry targets use ui-target-chat only and stay fenced before the tap', async () => {
  const sent = [];
  let taps = 0;
  const targetJson = (label) => ({
    runId: 'product-native-target', phase: 'ui-target-chat', label,
    ownerPackage: 'io.andchamber.localprovider', windowId: 1, enabled: true,
    editable: label === 'Address', observedAtMs: 1000, ageMs: 0, fresh: true,
    left: 10, top: 100, right: 110, bottom: 160, source: 'native-ui',
  });
  const client = {
    async sendDevice(text) {
      sent.push(text);
      return { ok: true, data: targetJson(text.slice(text.indexOf(':') + 1)) };
    },
  };
  const io = product.createProductIo({
    client,
    runId: 'product-native-target',
    screen: { width: 1080, height: 2400 },
    tapScreen: async () => { taps += 1; },
    now: () => 1000,
  });
  for (const label of ['AndChamber menu', 'Open web page', 'Address', 'Open']) {
    assert.equal((await io.chatTarget(label)).ok, true, label);
  }
  assert.equal((await io.tapChat('AndChamber menu')).ok, true);
  assert.equal((await io.tapChat('Open web page')).ok, true);
  assert.deepEqual(sent, [
    'ui-target-chat:AndChamber menu',
    'ui-target-chat:Open web page',
    'ui-target-chat:Address',
    'ui-target-chat:Open',
    'ui-target-chat:AndChamber menu',
    'ui-target-chat:Open web page',
  ]);
  assert.equal(taps, 2, 'one real ADB tap per validated native target (reads do not tap)');
  const bad = product.createProductIo({
    client: { async sendDevice() { return { ok: true, data: { ...targetJson('Open'), windowId: -1 } }; } },
    runId: 'product-native-target',
    screen: { width: 1080, height: 2400 },
    tapScreen: async () => { taps += 1; },
    now: () => 1000,
  });
  const rejected = await bad.tapChat('Open');
  assert.equal(rejected.ok, false);
  assert.equal(rejected.reason, 'bad-window-id');
  const fence = product.createCaseFence();
  const fencedIo = product.createProductIo({
    client: { async sendDevice() { fence.stop(); return { ok: true, data: targetJson('Open') }; } },
    runId: 'product-native-target',
    screen: { width: 1080, height: 2400 },
    tapScreen: async () => { taps += 1; },
    fence,
    now: () => 1000,
  });
  const fenced = await fencedIo.tapChat('Open');
  assert.equal(fenced.ok, false);
  assert.equal(fenced.code, 'FENCED');
  assert.equal(taps, 2, 'a fence closed during the target read sends no ADB touch');
});

test('WM restore runs even when the fence closed, while a new resize is refused', async () => {
  const fake = wmAdb();
  const guard = driver.createWmGuard(fake.adb, () => {});
  assert.equal((await guard.setCompactSize('1080x1000', () => true)).ok, true);
  const fenced = await guard.setCompactSize('1080x1000', () => false);
  assert.equal(fenced.ok, false);
  assert.equal(fenced.code, 'FENCED');
  assert.equal(fake.commands.length, 1);
  const restored = await guard.restore();
  assert.equal(restored.ok, true, JSON.stringify(restored));
  assert.equal(guard.changed(), false);
});

function composerSnapshot({ top = 2200, bottom = 2400, stale = false, fallback = false, visible = true } = {}) {
  return {
    mainWebView: { x: 0, y: 0, width: 1080, height: 2400 },
    measurement: {
      consoleGeneration: 4, nativeGeneration: 9, nativeWidth: 1080, nativeHeight: 2400,
      top, bottom, viewportWidth: 1080, viewportHeight: 2400, visible,
    },
    contentWidth: 1080, contentHeight: 2400,
    consoleGeneration: stale ? 3 : 4, nativeGeneration: 9,
    selectedClearancePx: 136, fallback, validityReason: fallback ? 'measurement-missing' : 'accepted',
  };
}

test('composer overlap uses the accepted measurement sub-rect, not the whole chat WebView', () => {
  const page = { left: 198, top: 1574, right: 1017, bottom: 1826 };
  const accepted = product.acceptedComposerRect(composerSnapshot());
  assert.ok(accepted);
  assert.equal(accepted.top, 2200);
  assert.equal(accepted.bottom, 2400);
  assert.equal(product.acceptedComposerRect(composerSnapshot({ top: 1600, bottom: 1800 })).top, 1600);
  assert.equal(product.acceptedComposerRect({ ...composerSnapshot(), measurement: null }), null);
  assert.equal(product.acceptedComposerRect(composerSnapshot({ stale: true })), null);
  assert.equal(product.acceptedComposerRect(composerSnapshot({ fallback: true })), null);
  assert.equal(product.acceptedComposerRect(composerSnapshot({ visible: false })), null);
  // Device-observed rounding: fractional CSS viewport bottom projects 0.44px past the int WebView.
  const rounded = product.acceptedComposerRect({
    validityReason: 'accepted', fallback: false, consoleGeneration: 7, nativeGeneration: 0,
    mainWebView: { x: 0, y: 136, width: 1080, height: 1381 },
    measurement: { consoleGeneration: 7, nativeGeneration: 0, nativeWidth: 1080, nativeHeight: 1381,
      top: 413.23809814453125, bottom: 527.2380981445312, viewportWidth: 412.19049072265625,
      viewportHeight: 527.2380981445312, visible: true },
  });
  assert.ok(rounded, 'sub-pixel overshoot is clamped');
  assert.equal(rounded.bottom, 1517);
  const beyond = product.acceptedComposerRect({
    validityReason: 'accepted', fallback: false, consoleGeneration: 7, nativeGeneration: 0,
    mainWebView: { x: 0, y: 136, width: 1080, height: 1381 },
    measurement: { consoleGeneration: 7, nativeGeneration: 0, nativeWidth: 1080, nativeHeight: 1381,
      top: 400, bottom: 528, viewportWidth: 412.19049072265625, viewportHeight: 528, visible: true },
  });
  assert.equal(beyond, null, 'more than one physical px past the WebView is rejected');
  const wholeOverlaps = !(page.left >= 0 + 1080 || page.right <= 0 || page.top >= 2400 || page.bottom <= 0);
  assert.equal(wholeOverlaps, true, 'the whole chat WebView necessarily overlaps the page');
  const intersects = !(accepted.left >= page.right || page.left >= accepted.right || accepted.top >= page.bottom || page.bottom >= accepted.bottom);
  assert.equal(intersects, false, 'accepted composer editor does not overlap');
});

test('case preparation reads actual serial state and performs one Small window or Close page only', async () => {
  const make = ({ enlarged = false, ime = false, restoreOk = true, closeOk = true } = {}) => {
    const clock = clocked();
    const state = { enlarged, ime, tapCount: 0, selectedTab: 'tab-focus' };
    return {
      now: clock.now,
      sleep: clock.sleep,
      async presentation() {
        return {
          fixture: { native: { selectedTabId: state.selectedTab, viewHandle: 7, pageReady: true, presentationBlocked: false }, outerime: { shown: state.ime } },
          observe: {}, composer: {},
          ui: { labels: state.enlarged ? { 'Small window': {} } : { 'Full screen': {} } },
        };
      },
      async tapUi(label) {
        state.tapCount += 1;
        if (label === 'Small window') { if (!restoreOk) return { ok: false, reason: 'restore-failed' }; state.enlarged = false; }
        if (label === 'Close page') { if (!closeOk) return { ok: false, reason: 'close-failed' }; state.ime = false; state.selectedTab = 'chat'; }
        return { ok: true };
      },
      async readDiag() { return { selectedTabId: state.selectedTab, pageReady: state.selectedTab !== 'chat' }; },
      get state() { return state; },
    };
  };
  const enlarged = make({ enlarged: true });
  const restored = await product.prepareProductCase(enlarged);
  assert.equal(restored.ok, true, JSON.stringify(restored));
  assert.deepEqual(restored.actions.map((action) => action.action), ['Small window']);
  assert.equal(enlarged.state.tapCount, 1);
  const ime = make({ ime: true });
  const closed = await product.prepareProductCase(ime);
  assert.equal(closed.ok, true, JSON.stringify(closed));
  assert.deepEqual(closed.actions.map((action) => action.action), ['More', 'Close page']);
  assert.equal(ime.state.tapCount, 2);
  const failed = make({ enlarged: true, restoreOk: false });
  const failedPrep = await product.prepareProductCase(failed);
  assert.equal(failedPrep.ok, false);
  assert.equal(failedPrep.reason, 'case-prepare-restore-failed');
  assert.equal(failed.state.tapCount, 1, 'no retry of a failed prepare action');
});

test('baseOpen reads ready/source/geometry/UI and the default grant without tapping', async () => {
  const clock = clocked();
  const state = { ready: false, grant: false, taps: 0 };
  const io = {
    now: clock.now,
    sleep: clock.sleep,
    async open() { return { ok: true, tabId: 'tab-ready' }; },
    async readDiag() {
      return { selectedTabId: 'tab-ready', pageReady: state.ready, pageRetired: false, viewHandle: 7, grantForSelected: state.grant, agentOn: false };
    },
    async pageRect() { return { viewHandle: 7, pointerUi: { viewHandle: 7, geometryReady: true } }; },
    async presentation() {
      return {
        fixture: { native: { selectedTabId: 'tab-ready', pageReady: state.ready, viewHandle: 7, presentationBlocked: false }, outerime: { shown: false } },
        observe: {},
        ui: state.ready ? readyUi() : { labels: { 'Resume agent': { ...READY_ALLOW, enabled: false } }, surface: {} },
        composer: {},
      };
    },
    async tapUi(label) { state.taps += 1; if (label === 'Resume agent') state.grant = true; return { ok: true }; },
  };
  const notReady = await product.baseOpen(io, 'https://example.com/open-provider-focused', 'mobile');
  assert.equal(notReady.ok, false);
  assert.equal(notReady.reason, 'product-ready-diag-timeout');
  assert.equal(state.taps, 0, 'no UI tap before actual readiness');
  state.ready = true;
  state.grant = true;
  const ready = await product.baseOpen(io, 'https://example.com/open-provider-focused', 'mobile');
  assert.equal(ready.ok, true, JSON.stringify(ready));
  assert.equal(state.taps, 0, 'the default grant is read from diag, never from a Resume tap');
});

function nativeEntryStateData(overrides = {}) {
  return {
    phase: 'native-entry-state', dialog: true, titlePresent: true,
    address: { present: true, focused: true, editable: true, enabled: true, valueLength: 0, valueExact: false },
    open: { present: true, enabled: false },
    selectedTabId: 'tab-ses-b',
    ...overrides,
  };
}

test('native dialog reader stays value-blind and typeAddress sends exactly one fixed URL', async () => {
  let humanCalls = 0;
  const humanText = async (text) => {
    humanCalls += 1;
    assert.equal(text, 'https://example.com/openchamber-provider-focused');
    return { ok: true, command: 'shell input text <fixed-focus-url>', code: 0, elapsedMs: 1 };
  };
  const make = (data, fence = null) => {
    const client = {
      async sendDevice(text) {
        if (text === 'native-entry-state') {
          if (fence) fence.stop();
          return { ok: true, data };
        }
        return { ok: false, code: 'UNEXPECTED', detail: text };
      },
    };
    return product.createProductIo({ client, runId: 'product-dialog', screen: { width: 1080, height: 2400 }, humanText, fence });
  };
  const io = make(nativeEntryStateData());
  const before = await io.nativeEntryState();
  assert.equal(before.ok, true, JSON.stringify(before));
  assert.equal(before.address.valueLength, 0);
  assert.equal(before.address.valueExact, false);
  assert.equal('value' in before.address, false, 'the typed text is never exported');
  const typed = await io.typeAddress('https://example.com/openchamber-provider-focused');
  assert.equal(typed.ok, true, JSON.stringify(typed));
  assert.equal(typed.typed, true);
  assert.equal(humanCalls, 1);
  const exactIo = make(nativeEntryStateData({
    address: { present: true, focused: true, editable: true, enabled: true, valueLength: 49, valueExact: true },
    open: { present: true, enabled: true },
  }));
  const exact = await exactIo.typeAddress('https://example.com/openchamber-provider-focused');
  assert.equal(exact.ok, true);
  assert.equal(exact.alreadyExact, true);
  assert.equal(humanCalls, 1, 'already exact URL is a recorded no-type continuation');
  const wrong = await make(nativeEntryStateData({
    address: { present: true, focused: true, editable: true, enabled: true, valueLength: 20, valueExact: false },
  })).typeAddress('https://example.com/openchamber-provider-focused');
  assert.equal(wrong.ok, false);
  assert.equal(wrong.reason, 'dialog-address-not-empty');
  const closed = await make(nativeEntryStateData({ dialog: false, address: { present: false, focused: false, editable: false, enabled: false, valueLength: 0, valueExact: false } }))
    .typeAddress('https://example.com/openchamber-provider-focused');
  assert.equal(closed.ok, false);
  assert.equal(closed.reason, 'dialog-address-missing');
  const mismatch = await make({ ...nativeEntryStateData(), address: { present: true } }).nativeEntryState();
  assert.equal(mismatch.ok, false);
  assert.equal(mismatch.reason, 'native-entry-state-schema');
  const fence = product.createCaseFence();
  const fencedIo = make(nativeEntryStateData(), fence);
  const fenced = await fencedIo.typeAddress('https://example.com/openchamber-provider-focused');
  assert.equal(fenced.ok, false);
  assert.equal(fenced.code, 'FENCED');
  assert.equal(humanCalls, 1, 'fenced type sends no ADB text');
  const wrongUrl = await make(nativeEntryStateData()).typeAddress('https://example.com/openchamber-provider-second');
  assert.equal(wrongUrl.ok, false);
  assert.equal(wrongUrl.reason, 'dialog-url-not-allowed');
});

test('human text helper accepts only the one fixed focus URL and records command/timing', async () => {
  const seen = [];
  const human = driver.createHumanText(async (command) => { seen.push(command); return { code: 0, stderr: '' }; });
  const ok = await human('https://example.com/openchamber-provider-focused');
  assert.equal(ok.ok, true);
  assert.equal(ok.command, 'shell input text <fixed-focus-url>');
  assert.equal(typeof ok.startedAt, 'number');
  assert.equal(typeof ok.endedAt, 'number');
  assert.deepEqual(seen, [['shell', 'input', 'text', 'https://example.com/openchamber-provider-focused']]);
  const refused = await human('https://example.com/other');
  assert.equal(refused.ok, false);
  assert.equal(refused.code, 'HUMAN_TEXT_NOT_ALLOWED');
  assert.equal(seen.length, 1);
});
