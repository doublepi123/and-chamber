'use strict';

// Offline tests for the true-local guest matrix host lane. They exercise the real single-file command
// protocol, the real typed adapters and the existing pure probes against a simulated producer. No adb,
// no device and no provider token are involved. A missing producer field/reader is never turned into a
// passing value.

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const matrix = require('../lib/local_provider_matrix_io.cjs');
const runner = require('../verify_local_provider_matrix.cjs');
const harness = require('../verify_openchamber_provider_emulator.cjs');
const lifecycleProbe = require('../lib/provider_lifecycle_gate_probe.cjs');
const retirementProbe = require('../lib/provider_retirement_gate_probe.cjs');

const RUN_ID = 'matrix-run-0001';
const TOKEN = 'agent-token-never-on-disk';
const COOKIE_HEX = '0123456789abcdef0123456789abcdef';
const MOUSE_URL = matrix.FIXTURES.mouse;
const RETIRE_URL = matrix.FIXTURES.retire;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Real files and real atomic renames. Only the producer is simulated, not the protocol channel. */
function createProtocolDevice({ runId = RUN_ID, handler, resultDelayMs = 0, staleFirst = false } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'local-matrix-'));
  const commandFile = path.join(dir, 'command.json');
  const resultFile = path.join(dir, 'result.json');
  const statusFile = path.join(dir, 'status.json');
  const evidenceFile = path.join(dir, 'evidence.json');
  const seen = new Set();
  let commands = 0;
  const written = [];
  const transport = {
    async writeCommand(command) {
      commands += 1;
      const text = JSON.stringify(command);
      assert.equal(text.includes(TOKEN), false, 'the provider token must never reach the command file');
      assert.equal(command.runId, runId);
      assert.match(command.id, /^[A-Za-z0-9._:-]{1,80}$/);
      assert.equal(seen.has(command.id), false, 'command id must be unique');
      seen.add(command.id);
      const tmp = `${commandFile}.tmp`;
      fs.writeFileSync(tmp, `${text}\n`, { mode: 0o600 });
      fs.renameSync(tmp, commandFile);
      written.push(command);
      const produce = async () => {
        let result;
        try {
          result = await handler(command);
        } catch (error) {
          result = { ok: false, error: { code: 'simulated-producer-error', message: String(error.message || error) } };
        }
        if (result === undefined) return;
        if (staleFirst && commands === 1) {
          const stale = JSON.stringify({ runId: 'stale-run', id: 'stale-id', ok: true, data: { stale: true } });
          fs.writeFileSync(resultFile, `${stale}\n`, { mode: 0o600 });
        }
        await sleep(resultDelayMs);
        const body = `${JSON.stringify({ runId, id: command.id, ...result })}\n`;
        assert.equal(body.includes(TOKEN), false, 'the provider token must never reach the result file');
        fs.writeFileSync(resultFile, body, { mode: 0o600 });
      };
      setTimeout(() => { produce(); }, 0);
      return true;
    },
    async readResult() {
      if (!fs.existsSync(resultFile)) return null;
      try { return JSON.parse(fs.readFileSync(resultFile, 'utf8')); } catch (_error) { return null; }
    },
    async readJson(name) {
      const file = name === 'status.json' ? statusFile : name === 'evidence.json' ? evidenceFile : null;
      if (!file || !fs.existsSync(file)) return null;
      try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (_error) { return null; }
    },
  };
  return {
    dir,
    transport,
    written,
    readCommandFile: () => fs.existsSync(commandFile) ? fs.readFileSync(commandFile, 'utf8') : '',
    readResultFile: () => fs.existsSync(resultFile) ? fs.readFileSync(resultFile, 'utf8') : '',
    writeStatus(body) { fs.writeFileSync(statusFile, `${JSON.stringify(body)}\n`, { mode: 0o600 }); },
    writeEvidence(body) { fs.writeFileSync(evidenceFile, `${JSON.stringify(body)}\n`, { mode: 0o600 }); },
    cleanup: () => fs.rmSync(dir, { recursive: true, force: true }),
  };
}

function lifecycleProducer(overrides = {}) {
  return {
    phase: 'lifecycle-diag',
    selectedTabId: 'tab-a',
    tabIds: ['tab-a'],
    pageRetired: false,
    agentOn: true,
    grantForSelected: true,
    mousePressActive: false,
    mode: 'AGENT',
    desktopMode: true,
    mouseMode: true,
    pageReady: true,
    viewHandle: 7,
    currentIdentity: 7,
    documentEpoch: 3,
    restore: null,
    recorderReady: true,
    recorderViewId: 1,
    totalClicks: 0,
    recordedDocuments: { 1: [{ origin: '1700000000.5', installed: true, clicks: 0, current: true }] },
    lastRetired: null,
    ...overrides,
  };
}

/** Simulated guest for the lifecycle B5 flow, using the producer's documented observe/fixture shapes. */
function lifecycleDevice({ omitSy = false } = {}) {
  const dev = {
    diag: lifecycleProducer(),
    enlarged: false,
    restore: null,
    metrics: {
      ua: 'desktop',
      clientWidth: 1024,
      media1000: true,
      sy: 300,
      loads: 1,
      posts: 0,
      timeOrigin: 1700000000.5,
    },
    fixture: {
      page: { url: MOUSE_URL, loads: 1, posts: 0 },
      native: { physicalFrame: { left: 100, top: 200, width: 900, height: 600 } },
      postArrivals: 0,
    },
    uiSurface: { left: 100, top: 150, right: 1000, bottom: 700 },
    uiPending: null,
    frame: {
      physical: { left: 100, top: 200, width: 900, height: 600 },
      page: { visualViewport: { width: 1024, height: 683, offsetLeft: 0, offsetTop: 0 } },
      pointerUi: { geometryReady: true, viewHandle: 7, pointerViewX: 0, pointerViewY: 0 },
      viewHandle: 7,
      documentEpoch: 3,
    },
  };
  if (omitSy) delete dev.metrics.sy;
  const handler = async (command) => {
    if (command.kind === 'tool') {
      if (command.action === 'browser.open') return { ok: true, data: { tabId: 'tab-a', url: command.input.url || MOUSE_URL } };
      if (command.action === 'browser.snapshot') {
        return dev.diag.grantForSelected ? { ok: true, data: { elements: [] } } : { ok: false, error: { code: 'AUTHORIZATION_REQUIRED', message: 'no grant' } };
      }
      return { ok: true, data: {} };
    }
    if (command.kind === 'finish') return { ok: true, data: { toolCommands: 1, deviceCommands: 1 } };
    const text = command.text;
    if (text === 'lifecycle-diag') return { ok: true, data: { ...dev.diag, restore: dev.restore } };
    if (text === 'fixture-state') return { ok: true, data: dev.fixture };
    if (text === 'observe') {
      return {
        ok: true,
        data: { url: MOUSE_URL, metrics: { ...dev.metrics }, page: { clicks: 0, releases: 0, buttons: 0, wheel: 0, logTail: [], nextClicks: 0, boxClicks: 0 } },
      };
    }
    if (text === 'ui-state') {
      const labels = dev.enlarged ? { Restore: { bounds: [] }, BrowserFloatSurface: dev.uiSurface } : { Enlarge: { bounds: [] }, BrowserFloatSurface: dev.uiSurface };
      return { ok: true, data: { phase: 'ui-state', labels, surface: dev.uiSurface } };
    }
    if (text === 'page-rect') return { ok: true, data: dev.frame };
    if (text === 'two-finger-scroll:0,0,-240' || text.startsWith('two-finger-scroll:')) return { ok: true, data: { sent: true } };
    if (text.startsWith('ui-target:')) {
      const label = text.slice('ui-target:'.length);
      if (!matrix.UI_LABELS.has(label)) return { ok: false, error: { code: 'unknown-device-command', message: text } };
      dev.uiPending = label;
      return { ok: true, data: {
        label,
        ownerPackage: 'io.andchamber.localprovider',
        windowId: 1,
        enabled: true,
        observedAtMs: Date.now(),
        left: 100,
        top: 100,
        right: 200,
        bottom: 150,
        phase: 'ui-target',
        runId: RUN_ID,
      } };
    }
    if (text === 'tap-label:Enlarge') {
      dev.enlarged = true;
      dev.restore = null;
      return { ok: true, data: { sent: true } };
    }
    if (text === 'tap-label:Restore') {
      dev.enlarged = false;
      dev.restore = { kind: 'Restored', actualX: 0, actualY: 300 };
      return { ok: true, data: { sent: true } };
    }
    if (text.startsWith('tap-label:')) return { ok: true, data: { sent: true } };    if (text.startsWith('gate-finger:') || text.startsWith('gate-native:')) return { ok: true, data: { sent: true, buttons: 0 } };
    return { ok: false, error: { code: 'unknown-device-command', message: text } };
  };
  return { dev, handler };
}

function retirementProducer(overrides = {}) {
  return lifecycleProducer({
    grantForSelected: true,
    agentOn: true,
    desktopMode: true,
    mouseMode: true,
    ...overrides,
  });
}

/** Simulated guest for retirement A/B: native recorder shape, frame/pointer geometry, page counters. */
function retirementDevice() {
  const dev = {
    diag: retirementProducer(),
    page: { timeOrigin: 1700000000.5, clicks: 0, releases: 0, buttons: 0, wheel: 0, boxCaptured: false, pointerId: null, logTail: [], nextClicks: 0, boxClicks: 0 },
    pointer: { x: 0, y: 0 },
    frame: { left: 1300, top: 900, width: 820, height: 300 },
    box: { left: 400, top: 150, right: 520, bottom: 230 },
    next: { left: 8, top: 8, right: 56, bottom: 40 },
    finger: null,
    cookieValue: '',
    mouseTaps: 0,
    uiPending: null,
  };
  const scale = dev.frame.width / 1024;
  function manual() {
    dev.diag.mode = 'MANUAL';
    dev.diag.grantForSelected = false;
    dev.diag.agentOn = false;
  }
  function hit(id, pointer = dev.pointer) {
    const r = dev[id];
    return pointer.x / scale >= r.left && pointer.x / scale < r.right && pointer.y / scale >= r.top && pointer.y / scale < r.bottom;
  }
  function click() {
    dev.page.clicks += 1;
    if (hit('next')) dev.page.nextClicks += 1;
    if (hit('box')) dev.page.boxClicks += 1;
    const docs = dev.diag.recordedDocuments[dev.diag.recorderViewId];
    if (docs && docs[0]) docs[0].clicks += 1;
    dev.page.logTail.push({ t: 'pointerdown', p: 'mouse', bs: 1 }, { t: 'pointerup', p: 'mouse', bs: 0 }, { t: 'click', p: 'mouse', bs: 0 });
    dev.page.releases += 1;
  }
  function reopen() {
    const retired = dev.diag.lastRetired;
    dev.diag = retirementProducer({
      viewHandle: 9,
      currentIdentity: 9,
      recorderViewId: 2,
      recordedDocuments: { 2: [{ origin: '1700000111.5', installed: true, clicks: 0, current: true }] },
      grantForSelected: false,
      agentOn: false,
      mode: 'MANUAL',
      mouseMode: false,
      lastRetired: retired,
    });
    dev.page = { ...dev.page, clicks: 0, releases: 0, buttons: 0, wheel: 0, boxCaptured: false, pointerId: null, logTail: [], nextClicks: 0, boxClicks: 0, timeOrigin: 1700000111.5 };
    dev.pointer = { x: 0, y: 0 };
    dev.finger = null;
  }
  const handler = async (command) => {
    if (command.kind === 'tool') {
      if (command.action === 'browser.open') {
        dev.diag.selectedTabId = 'tab-retire';
        dev.diag.pageReady = true;
        return { ok: true, data: { tabId: 'tab-retire', url: command.input.url || RETIRE_URL } };
      }
      if (command.action === 'browser.snapshot') {
        if (dev.diag.pageRetired || !dev.diag.grantForSelected) {
          return { ok: false, error: { code: 'AUTHORIZATION_REQUIRED', message: 'no grant' } };
        }
        return { ok: true, data: { elements: [] } };
      }
      return { ok: true, data: {} };
    }
    if (command.kind === 'finish') return { ok: true, data: { toolCommands: 1, deviceCommands: 1 } };
    const text = command.text;
    if (text === 'lifecycle-diag') return { ok: true, data: JSON.parse(JSON.stringify(dev.diag)) };
    if (text === 'observe') {
      return { ok: true, data: { url: RETIRE_URL, metrics: { ua: 'desktop', clientWidth: 1024, media1000: true, sy: dev.page.wheel * 120, loads: 1, timeOrigin: dev.page.timeOrigin }, page: JSON.parse(JSON.stringify(dev.page)) } };
    }
    if (text === 'page-rect') {
      return { ok: true, data: {
        physical: dev.frame,
        page: { visualViewport: { width: 1024, height: dev.frame.height / scale, offsetLeft: 0, offsetTop: 0 } },
        pointerUi: { geometryReady: true, viewHandle: dev.diag.viewHandle, pointerViewX: dev.pointer.x, pointerViewY: dev.pointer.y },
        viewHandle: dev.diag.viewHandle,
        documentEpoch: 3,
      } };
    }
    if (text.startsWith('fixture-target:')) {
      const [id, xText, yText] = text.slice('fixture-target:'.length).split(',');
      const x = Number(xText);
      const y = Number(yText);
      if (!['box', 'next'].includes(id) || !Number.isFinite(x) || !Number.isFinite(y)) {
        return { ok: false, error: { code: 'unknown-device-command', message: text } };
      }
      const rect = dev[id];
      return { ok: true, data: {
        timeOrigin: dev.page.timeOrigin,
        box: { ...rect },
        hit: x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom,
        page: JSON.parse(JSON.stringify(dev.page)),
      } };
    }
    if (text === 'cookie-read') return { ok: true, data: { value: dev.cookieValue } };
    if (text.startsWith('cookie-write:')) {
      dev.cookieValue = `oc_fixture_retire=${text.slice('cookie-write:'.length)}`;
      return { ok: true, data: { set: true } };
    }
    if (text.startsWith('ui-target:')) {
      const label = text.slice('ui-target:'.length);
      if (!matrix.UI_LABELS.has(label)) return { ok: false, error: { code: 'unknown-device-command', message: text } };
      if (label === 'Allow agent' && (dev.diag.pageRetired || dev.diag.grantForSelected === false)) {
        return { ok: false, error: { code: 'missing-target', message: 'no visible Allow agent' } };
      }
      dev.uiPending = label;
      return { ok: true, data: {
        label,
        ownerPackage: 'io.andchamber.localprovider',
        windowId: 1,
        enabled: true,
        observedAtMs: Date.now(),
        left: 10,
        top: 20,
        right: 110,
        bottom: 120,
        phase: 'ui-target',
        runId: RUN_ID,
      } };
    }
    if (text.startsWith('tap-label:')) {
      // Fixture form taps only. UI controls are resolved through ui-target plus a real host ADB tap.
      const field = text.slice('tap-label:'.length);
      if (field !== 'login-user' && field !== 'Sign in') return { ok: false, error: { code: 'unknown-device-command', message: text } };
      return { ok: true, data: { sent: true } };
    }
    if (text.startsWith('gate-finger:')) {
      const [action, xText, yText] = text.slice('gate-finger:'.length).split(',');
      const x = Number(xText);
      const y = Number(yText);
      if (action === 'down') {
        dev.finger = { x, y };
        manual();
      } else if (action === 'move' && dev.finger) {
        if (!dev.diag.mousePressActive) {
          dev.diag.mousePressActive = true;
          dev.page.buttons = 1;
          dev.page.pointerId = 71;
          dev.page.boxCaptured = hit('box');
          dev.page.logTail.push({ t: 'pointerdown', p: 'mouse', bs: 1 });
        }
        dev.pointer.x += x - dev.finger.x;
        dev.pointer.y += y - dev.finger.y;
        dev.finger.x = x;
        dev.finger.y = y;
      } else if (action === 'secondary-down') {
        // A secondary contact never reinterprets the drag.
      } else if (action === 'primary-up') {
        dev.diag.mousePressActive = false;
        dev.page.releases += 1;
        dev.page.buttons = 0;
        dev.page.boxCaptured = false;
        dev.page.logTail.push({ t: 'pointerup', p: 'mouse', bs: 0 });
        dev.finger = null;
      } else if (action === 'up') {
        dev.finger = null;
      } else if (action === 'cancel') {
        dev.diag.mousePressActive = false;
        dev.diag.pageRetired = true;
        dev.diag.currentIdentity = null;
        dev.diag.grantForSelected = false;
        dev.diag.agentOn = false;
        dev.diag.recorderViewId = null;
        dev.diag.mode = 'MANUAL';
        dev.diag.mouseMode = false;
        dev.diag.lastRetired = { viewId: 1, readyAtRetire: true, documents: [{ origin: '1700000000.5', installed: true, clicks: dev.page.clicks, current: false }] };
      }
      return { ok: true, data: { sent: true } };
    }
    if (text.startsWith('gate-native:')) {
      const spec = text.slice('gate-native:'.length);
      if (spec.startsWith('wheel')) {
        dev.page.wheel += 1;
        dev.next = { left: 100, top: 8, right: 148, bottom: 40 };
      }
      return { ok: true, data: { sent: true, buttons: 0, dispatched: true } };
    }
    if (text.startsWith('finger:')) {
      const parts = text.slice('finger:'.length).split(',').map(Number);
      const [sx, sy, dx, dy, hold, steps] = parts;
      if (parts.length !== 6) return { ok: false, error: { code: 'bad-finger', message: text } };
      dev.pointer.x += dx;
      dev.pointer.y += dy;
      if (dx === 0 && dy === 0 && steps === 1) click();
      return { ok: true, data: { sent: true } };
    }
    return { ok: false, error: { code: 'unknown-device-command', message: text } };
  };
  dev.performUiTap = (label) => {
    if (label === 'Reopen') reopen();
    if (label === 'Mouse') {
      dev.mouseTaps += 1;
      dev.diag.mouseMode = true;
      manual();
    }
    return true;
  };
  return { dev, handler };
}


function loginFixtureState(state) {
  return {
    page: {
      url: matrix.FIXTURES.login,
      ready: 'complete',
      inputValue: state.inputValue,
      formPresent: true,
      posts: state.posts,
      loads: 1,
      inputFocused: state.focused,
      activeTag: state.focused ? 'INPUT' : 'BODY',
      selectionStart: state.inputValue.length,
      selectionEnd: state.inputValue.length,
      documentHasFocus: true,
      inputEnabled: true,
      inputReadOnly: false,
      inputTag: 'INPUT',
      timeOrigin: 1700,
    },
    native: {
      selectedTabId: 'tab-login',
      owner: 'ses-test',
      viewHandle: 7,
      viewGeneration: 1,
      documentEpoch: 3,
      shown: true,
      pageReady: true,
      mouseMode: false,
      agentOn: false,
      mode: 'MANUAL',
      hasFocus: true,
      hasWindowFocus: true,
      isShown: true,
      windowTokenPresent: true,
      physicalFrame: { left: 0, top: 0, width: 1080, height: 1920 },
      screenVisibleRect: { left: 0, top: 0, width: 1080, height: 1920 },
    },
    outerime: { shown: false, source: 'root-window-insets', bottomPx: 0 },
    postArrivals: state.arrivals,
  };
}

function clientFor(device, { timeoutMs = 200, pollMs = 1 } = {}) {
  let clock = 0;
  return matrix.createMatrixClient({
    transport: device.transport,
    runId: RUN_ID,
    now: () => clock,
    sleep: async (ms) => { clock += ms; await sleep(0); },
    commandTimeoutMs: timeoutMs,
    pollMs,
  });
}

test('CLI accepts only the dedicated package/serial and a fresh output outside the repository', () => {
  const out = path.join(os.tmpdir(), `matrix-cli-${process.pid}-${Date.now()}`, 'run');
  const parsed = matrix.parseArgs([
    '--serial', 'emulator-5582', '--package', 'io.andchamber.localprovider',
    '--out', out, '--scope', 'all', '--run-id', RUN_ID,
  ]);
  assert.equal(parsed.scope, 'all');
  assert.equal(matrix.casesForScope('all').length, 8);
  assert.deepEqual(matrix.casesForScope('lifecycle').map((item) => item.name), [...matrix.LIFECYCLE_CASE_NAMES]);
  assert.deepEqual(matrix.casesForScope('retirement').map((item) => item.name), [...matrix.RETIREMENT_CASE_NAMES]);
  assert.throws(() => matrix.parseArgs(['--serial', 'emulator-5580', '--out', out]), /emulator-5582/);
  assert.throws(() => matrix.parseArgs(['--serial', 'emulator-5582', '--package', 'io.andchamber.app', '--out', out]), /production app|exactly/);
  assert.throws(() => matrix.parseArgs(['--serial', 'emulator-5582', '--out', path.join(__dirname, '..', 'build', 'x')]), /outside the repository/);
  assert.throws(() => matrix.parseArgs(['--serial', 'emulator-5582', '--out', out, '--scope', 'geometry']), /scope/);
  assert.throws(() => matrix.parseArgs([
    '--serial', 'emulator-5582', '--out', out, '--scope', 'lifecycle', '--expected-cases', 'retirement-a-primary-drag-lock',
  ]), /outside scope/);
  assert.throws(() => matrix.parseArgs(['--serial', 'emulator-5582', '--out', out, '--expected-cases', ',']), /non-empty/);
});

test('command ids and run ids are bounded, unique and carry the current run only', async () => {
  const device = createProtocolDevice({
    handler: (command) => (command.kind === 'device' ? { ok: true, data: { value: 1 } } : { ok: true, data: {} }),
  });
  try {
    const client = clientFor(device);
    await client.sendDevice('lifecycle-diag');
    await client.sendDevice('observe');
    assert.equal(new Set(device.written.map((item) => item.id)).size, 2);
    assert.ok(device.written.every((item) => item.runId === RUN_ID));
    const file = device.readCommandFile();
    assert.equal(file.includes(TOKEN), false);
    assert.match(file, /"runId":"matrix-run-0001"/);
  } finally {
    device.cleanup();
  }
});

test('a stale result for another run/id is ignored until the matching result arrives', async () => {
  const device = createProtocolDevice({
    handler: () => ({ ok: true, data: { value: 'fresh' } }),
    resultDelayMs: 20,
    staleFirst: true,
  });
  try {
    const client = clientFor(device);
    const result = await client.sendDevice('lifecycle-diag');
    assert.equal(result.ok, true);
    assert.equal(result.data.value, 'fresh');
    assert.ok(client.stats.stale >= 1, 'the stale file was observed and not accepted');
  } finally {
    device.cleanup();
  }
});

test('only one command may be in flight; a timed-out command fences all later sends', async () => {
  const device = createProtocolDevice({
    handler: () => new Promise(() => {}),
    resultDelayMs: 500,
  });
  try {
    const client = clientFor(device, { timeoutMs: 30 });
    const first = client.sendDevice('lifecycle-diag');
    await assert.rejects(client.sendDevice('observe'), /in flight|COMMAND_INFLIGHT/);
    await assert.rejects(first, /timed out|COMMAND_TIMEOUT/);
    assert.equal(client.isFenced(), true);
    await assert.rejects(client.sendDevice('observe'), /fenced|FENCED/);
    assert.equal(device.written.length, 1, 'no second command after the timeout fence');
  } finally {
    device.cleanup();
  }
});

test('a failed tool envelope and NoProvider are failures, never a pass', async () => {
  const failed = matrix.normalizeResult({ runId: RUN_ID, id: 'a', ok: true, data: { ok: false, error: { code: 'NO_PROVIDER', message: 'No Android browser provider' } } }, { runId: RUN_ID, id: 'a' });
  assert.equal(failed.ok, false);
  assert.equal(failed.code, 'NO_PROVIDER');
  assert.equal(failed.providerMissing, true);
  const transport = matrix.normalizeResult({ runId: RUN_ID, id: 'b', ok: false, error: { code: 'NO_PROVIDER', message: 'No Android browser provider' } }, { runId: RUN_ID, id: 'b' });
  assert.equal(transport.ok, false);
  assert.equal(transport.providerMissing, true);
  assert.equal(matrix.normalizeResult({ runId: RUN_ID, id: 'a', ok: true, data: {} }, { runId: RUN_ID, id: 'other' }), null);
});

test('a tap on Allow agent is not a grant; only the registry read can pass', async () => {
  const dev = { grant: false, taps: 0 };
  const client = {
    async sendDevice(text) {
      if (text === 'ui-target:Allow agent') {
        return { ok: true, data: {
          label: 'Allow agent',
          ownerPackage: 'io.andchamber.localprovider',
          windowId: 1,
          enabled: true,
          observedAtMs: Date.now(),
          left: 10,
          top: 20,
          right: 110,
          bottom: 120,
          phase: 'ui-target',
          runId: RUN_ID,
        } };
      }
      if (text === 'lifecycle-diag') return { ok: true, data: { phase: 'lifecycle-diag', grantForSelected: dev.grant } };
      return { ok: false, data: null, code: 'unknown-device-command', detail: text };
    },
    async sendTool() { return { ok: false, data: null, code: 'not-used', detail: '' }; },
    async readStatus() { return null; },
  };
  const guest = matrix.createGuestGateIo({
    client,
    runId: RUN_ID,
    screen: { width: 1080, height: 1920 },
    tapScreen: async () => { dev.taps += 1; return true; },
  });
  const result = await guest.tapAllowAndVerify({ budgetMs: 100 });
  assert.equal(result.tapped, true);
  assert.equal(result.grantPresent, false);
  assert.equal(result.ok, false);
  assert.equal(dev.taps, 1);
});

test('B5 same-document flow runs the real typed adapter and pure probe over the protocol', async () => {
  const { dev, handler } = lifecycleDevice();
  const device = createProtocolDevice({ handler });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({
      client,
      runId: RUN_ID,
      screen: { width: 1080, height: 1920 },
      tapScreen: async () => {
        if (dev.uiPending === 'Enlarge') { dev.enlarged = true; dev.restore = null; }
        if (dev.uiPending === 'Restore') { dev.enlarged = false; dev.restore = { kind: 'Restored', actualX: 0, actualY: 300 }; }
        return true;
      },
    });
    const adapters = harness.createLifecycleGateAdapters(guest, harness.createCaseFence());
    const flow = await lifecycleProbe.runEnlargeRestoreSameDoc({ ...adapters, now: guest.now, sleep: guest.sleepMs });
    assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 800));
    assert.equal(flow.restore.kind, 'Restored');
    assert.equal(flow.after.sy, 300);
    assert.equal(dev.enlarged, false);
    assert.deepEqual(guest.missingReport().reader, null);
  } finally {
    device.cleanup();
  }
});

test('a missing observe metric refuses the B5 flow and is named as a missing producer field', async () => {
  const { handler } = lifecycleDevice({ omitSy: true });
  const device = createProtocolDevice({ handler });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const adapters = harness.createLifecycleGateAdapters(guest, harness.createCaseFence());
    const state = await adapters.readLifecycle();
    const missing = matrix.missingLifecycleFields(state);
    assert.ok(missing.includes('metrics.sy'), missing.join(','));
    const flow = await lifecycleProbe.runEnlargeRestoreSameDoc({ ...adapters, now: guest.now, sleep: guest.sleepMs });
    assert.equal(flow.ok, false);
    assert.equal(flow.reason, 'precondition-no-scroll');
  } finally {
    device.cleanup();
  }
});


test('hoverTarget diagnostics preserve literal pointer sources and never turn null into a pass', async () => {
  const setup = (wrap = (handler) => handler) => {
    const { dev, handler } = retirementDevice();
    const device = createProtocolDevice({ handler: wrap(handler, dev) });
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID, screen: { width: 1080, height: 1920 } });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const adapters = harness.createRetirementGateAdapters(retirement, harness.createCaseFence());
    return { dev, device, guest, retirement, adapters };
  };
  const deps = (guest, adapters) => ({ ...adapters, now: guest.now, sleep: guest.sleepMs });

  const valid = setup();
  try {
    const value = await retirementProbe.hoverTarget(deps(valid.guest, valid.adapters), 'box');
    assert.ok(value, 'default literal fixture should support a real hover');
    const diagnostic = valid.retirement.hoverDiagnostics();
    assert.ok(diagnostic.length > 0 && diagnostic.length <= 24);
    assert.ok(diagnostic.some((entry) => entry.op === 'atomicFinger' && entry.ok === true), JSON.stringify(diagnostic.map(e=>({op:e.op,ok:e.ok,result:e.result&&e.result.hit===undefined?undefined:{hit:e.result.hit},err:e.error}))).slice(0,2000));
    assert.ok(diagnostic.some((entry) => entry.op === 'readTarget' && entry.result && entry.result.hit === true));
    assert.equal(JSON.stringify(diagnostic).includes('oc_fixture_retire'), false);
  } finally { valid.device.cleanup(); }

  const tiny = setup();
  tiny.dev.box = { left: 455, right: 465, top: 150, bottom: 160 };
  tiny.dev.pointer = { x: 350, y: 124 };
  try {
    const value = await retirementProbe.hoverTarget(deps(tiny.guest, tiny.adapters), 'box');
    assert.equal(value, null);
    const diagnostic = tiny.retirement.hoverDiagnostics();
    const targets = diagnostic.filter((entry) => entry.op === 'readTarget' && entry.result);
    assert.ok(targets.length >= 1);
    const last = targets[targets.length - 1].result;
    assert.ok(Math.hypot(last.dx, last.dy) <= 32, `${last.dx},${last.dy}`);
    assert.equal(diagnostic.some((entry) => entry.op === 'atomicFinger'), false, 'tiny correction sends no atomic move');
    assert.equal(diagnostic.some((entry) => entry.op === 'atomic'), false);
  } finally { tiny.device.cleanup(); }

  const changed = setup();
  try {
    const original = changed.dev.handler;
    // Count literal finger commands and record a page counter change after the atomic move.
    const value = await retirementProbe.hoverTarget(deps(changed.guest, changed.adapters), 'box');
    assert.ok(value);
    changed.dev.page.clicks += 1;
    const after = await changed.adapters.readPage();
    assert.equal(after.clicks, 1);
    const diagnostic = changed.retirement.hoverDiagnostics();
    assert.ok(diagnostic.some((entry) => entry.op === 'atomic' && entry.result && entry.result.sent === true));
    assert.ok(diagnostic.some((entry) => entry.op === 'readTarget'));
  } finally { changed.device.cleanup(); }

  const afterClick = setup((handler, dev) => async (command) => {
    const result = await handler(command);
    if (command.kind === 'device' && String(command.text).startsWith('finger:') && result.ok) {
      dev.page.clicks += 1;
    }
    return result;
  });
  try {
    const value = await retirementProbe.hoverTarget(deps(afterClick.guest, afterClick.adapters), 'box');
    assert.equal(value, null, 'a page click after the hover move cannot pass');
    const diagnostic = afterClick.retirement.hoverDiagnostics();
    const targets = diagnostic.filter((entry) => entry.op === 'readTarget').map((entry) => entry.result);
    assert.ok(targets.some((item) => item && item.hit === true), 'before sample');
    assert.ok(diagnostic.some((entry) => entry.op === 'atomicFinger' && entry.ok === true));
    assert.ok(diagnostic.some((entry) => entry.op === 'readTarget' && entry.ok === true), 'after sample is preserved');
  } finally { afterClick.device.cleanup(); }

  const source = setup((handler) => async (command) => {
    if (command.kind === 'device' && command.text === 'lifecycle-diag') {
      const result = await handler(command);
      if (result.ok) result.data.viewHandle = result.data.viewHandle === 7 ? 99 : 7;
      return result;
    }
    return handler(command);
  });
  try {
    const value = await retirementProbe.hoverTarget(deps(source.guest, source.adapters), 'box');
    assert.equal(value, null);
    const diagnostic = source.retirement.hoverDiagnostics();
    assert.ok(diagnostic.some((entry) => entry.op === 'identity' && entry.ok === true));
    assert.equal(diagnostic.some((entry) => entry.op === 'atomicFinger'), false, JSON.stringify(diagnostic.map(e=>({op:e.op,ok:e.ok,err:e.error&&e.error.code}))));
    assert.ok(diagnostic.some((entry) => entry.op === 'dom'));
  } finally { source.device.cleanup(); }

  const nullTarget = setup((handler) => async (command) => {
    if (command.kind === 'device' && String(command.text).startsWith('fixture-target:box,')) {
      return { ok: false, error: { code: 'source-changed', message: 'source changed' } };
    }
    return handler(command);
  });
  try {
    const value = await retirementProbe.hoverTarget(deps(nullTarget.guest, nullTarget.adapters), 'box');
    assert.equal(value, null);
    const diagnostic = nullTarget.retirement.hoverDiagnostics();
    assert.ok(diagnostic.some((entry) => entry.op === 'dom' && entry.error && entry.error.code === 'source-changed'));
    assert.equal(diagnostic.some((entry) => entry.op === 'atomicFinger'), false, JSON.stringify(diagnostic.map(e=>({op:e.op,ok:e.ok,err:e.error&&e.error.code}))));
  } finally { nullTarget.device.cleanup(); }
});


test('scroll setup guard waits for two real geometry samples and only accepts observed same-source scroll', async () => {
  const make = (options = {}) => {
    let clock = 0;
    const state = {
      tab: 'tab-a', viewHandle: 7, timeOrigin: 1700, sy: options.initialSy == null ? 0 : options.initialSy, wheel: 0, geometry: false,
      scroll: options.scroll === true, sourceChange: options.sourceChange === true,
      fenced: false, scrollCount: 0, rectReads: 0,
    };
    const client = {
      async sendDevice(text) {
        if (state.fenced) return { ok: false, code: 'FENCED', detail: 'fenced' };
        if (text === 'lifecycle-diag') {
          if (state.sourceChange && state.scrollCount > 0) state.tab = 'tab-b';
          return { ok: true, data: { phase: 'lifecycle-diag', selectedTabId: state.tab, pageReady: true, mouseMode: true, viewHandle: state.viewHandle, currentIdentity: state.viewHandle, documentEpoch: 3, mode: 'MANUAL' } };
        }
        if (text === 'page-rect') {
          state.rectReads += 1;
          if (options.geometryAfter == null || state.rectReads >= options.geometryAfter) state.geometry = true;
          return { ok: true, data: { physical: { left: 100, top: 900, width: 800, height: 240 }, page: { visualViewport: { width: 1024, height: 307, offsetLeft: 0, offsetTop: 0 } }, pointerUi: { geometryReady: state.geometry, viewHandle: state.viewHandle, pointerViewX: 0, pointerViewY: 0 }, viewHandle: state.viewHandle, documentEpoch: 3 } };
        }
        if (text === 'observe') {
          return { ok: true, data: { url: matrix.FIXTURES.mouse, metrics: { ua: 'desktop', clientWidth: 1024, media1000: true, sy: state.sy, loads: 1, timeOrigin: state.timeOrigin }, maxScrollY: 900, wheel: state.wheel, log: [] } };
        }
        if (text.startsWith('two-finger-scroll:')) {
          state.scrollCount += 1;
          if (state.scroll) {
            state.sy = options.scrollSy == null ? 120 : options.scrollSy;
            state.wheel += 1;
          }
          return { ok: true, data: { sent: true } };
        }
        return { ok: false, code: 'unknown-device-command', detail: text };
      },
      async sendTool() { return { ok: false, data: null, code: 'not-used', detail: '' }; },
      async readStatus() { return null; },
    };
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID, now: () => clock, sleep: async (ms) => { clock += ms; } });
    return { guest, state };
  };

  const ok = make({ geometryAfter: 2, scroll: true });
  assert.equal(await ok.guest.twoFingerRaw(-240), true);
  assert.equal(ok.state.scrollCount, 1);
  const diag = ok.guest.scrollSetupDiagnostics();
  assert.equal(diag.ok, true);
  assert.ok(diag.samples.filter((sample) => sample.phase === 'before').length >= 2);
  assert.ok(diag.samples.some((sample) => sample.phase === 'after' && sample.observe.sy === 120));
  assert.equal(diag.before.observe.sy, 0);
  assert.equal(diag.after.observe.sy, 120);
  assert.equal(diag.command.dy, -240);
  assert.equal(diag.reply.sent, true);
  assert.ok(diag.samples.some((sample) => sample.projection && sample.projection.reason === 'pointer-not-ready'), 'first geometry rejection reason is preserved');
  assert.ok(diag.samples.every((sample) => sample.rawFrame !== undefined));
  assert.equal(ok.guest.frameReadDiagnostics().projection.reason, 'ok');

  const downward = make({ scroll: true, initialSy: 840, scrollSy: 480 });
  assert.equal(await downward.guest.twoFingerRaw(120), true);
  assert.equal(downward.state.scrollCount, 1);
  assert.equal(downward.guest.scrollSetupDiagnostics().before.observe.sy, 840);
  assert.equal(downward.guest.scrollSetupDiagnostics().after.observe.sy, 480);
  assert.equal(downward.guest.scrollSetupDiagnostics().expectedDirection, 'down');
  assert.equal(downward.guest.scrollSetupDiagnostics().delta, -360);

  const wrongDirection = make({ scroll: true, initialSy: 840, scrollSy: 850 });
  assert.equal(await wrongDirection.guest.twoFingerRaw(120), false);
  assert.equal(wrongDirection.state.scrollCount, 1);
  assert.equal(wrongDirection.guest.scrollSetupDiagnostics().reason, 'scroll-wrong-direction');
  assert.equal(wrongDirection.guest.scrollSetupDiagnostics().wrongDirection, true);
  assert.equal(wrongDirection.guest.scrollSetupDiagnostics().after.observe.sy, 850);

  const zero = make({ scroll: false });
  assert.equal(await zero.guest.twoFingerRaw(0), false);
  assert.equal(zero.state.scrollCount, 0, 'dy=0 sends no gesture');
  assert.equal(zero.guest.scrollSetupDiagnostics().reason, 'scroll-dy-zero');

  const boundary = make({ scroll: false, initialSy: 0 });
  assert.equal(await boundary.guest.twoFingerRaw(120), false);
  assert.equal(boundary.state.scrollCount, 1);
  assert.equal(boundary.guest.scrollSetupDiagnostics().reason, 'scroll-boundary-no-movement');

  const dead = make({ scroll: false });
  assert.equal(await dead.guest.twoFingerRaw(-240), false);
  assert.equal(dead.state.scrollCount, 1, 'no retry after zero scroll');
  assert.equal(dead.guest.scrollSetupDiagnostics().reason, 'no-scroll-observed');
  assert.equal(dead.guest.scrollSetupDiagnostics().after.observe.sy, 0);
  assert.equal(dead.guest.scrollSetupDiagnostics().maxScrollY, 900);

  const changed = make({ scroll: true, sourceChange: true });
  assert.equal(await changed.guest.twoFingerRaw(-240), false);
  assert.equal(changed.state.scrollCount, 1);
  assert.equal(changed.guest.scrollSetupDiagnostics().reason, 'scroll-source-changed');
  assert.equal(changed.guest.scrollSetupDiagnostics().after.lifecycle.selectedTabId, 'tab-b');

  const noRetry = make({ scroll: false });
  assert.equal(await noRetry.guest.twoFingerRaw(-240), false);
  assert.equal(noRetry.state.scrollCount, 1, 'the failed post-read never sends a second gesture');
});


test('prepareLifecycleCase uses the new scroll guard and saves scroll-setup evidence on failure', async () => {
  const { dev, handler } = lifecycleDevice();
  dev.metrics.sy = 0;
  const device = createProtocolDevice({ handler });
  let clock = 0;
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID, now: () => clock, sleep: async (ms) => { clock += ms; } });
    const saves = [];
    const item = harness.LIFECYCLE_CASES.find((entry) => entry.name === 'lifecycle-b5-document-change');
    const result = await runner.runLifecycleCase({
      item,
      guest,
      expectedSessionId: 'ses-test',
      save: (name, value) => saves.push({ name, value }),
      log: () => {},
    });
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'prepare:setup-scroll-failed');
    assert.equal(result.diagnostics.scrollSetup, 'lifecycle-b5-document-change-scroll-setup.json');
    const setupFile = saves.find((entry) => entry.name === result.diagnostics.scrollSetup);
    assert.ok(setupFile);
    assert.equal(setupFile.value.diagnostics.ok, false);
    assert.equal(setupFile.value.diagnostics.reason, 'no-scroll-observed');
    assert.ok(setupFile.value.diagnostics.samples.filter((sample) => sample.phase === 'before').length >= 2);
    assert.equal(setupFile.value.diagnostics.after.observe.sy, 0);
    assert.equal(setupFile.value.setup.reason, 'setup-scroll-failed');
  } finally {
    device.cleanup();
  }
});

test('retirement A: the real adapter and pure drag-lock flow pass over fixture-target and gate-finger', async () => {
  const { dev, handler } = retirementDevice();
  const device = createProtocolDevice({ handler });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const adapters = harness.createRetirementGateAdapters(retirement, harness.createCaseFence());
    const flow = await retirementProbe.runPrimaryDragLock({ ...adapters, now: guest.now, sleep: guest.sleepMs });
    assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 800));
    assert.equal(flow.page.releases, 1);
    assert.equal(dev.page.clicks, 0);
    assert.deepEqual(guest.missingReport().reader, null);
  } finally {
    device.cleanup();
  }
});

test('per-page counters can come from fixture-target when observe is metrics-only', async () => {
  const { handler } = retirementDevice();
  const device = createProtocolDevice({
    handler: async (command) => {
      if (command.kind === 'device' && command.text === 'observe') {
        return { ok: true, data: { url: RETIRE_URL, metrics: { ua: 'desktop', clientWidth: 1024, media1000: true, sy: 0, loads: 1, timeOrigin: 1700 } } };
      }
      return handler(command);
    },
  });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const adapters = harness.createRetirementGateAdapters(retirement, harness.createCaseFence());
    const flow = await retirementProbe.runPrimaryDragLock({ ...adapters, now: guest.now, sleep: guest.sleepMs });
    assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 800));
    assert.equal(flow.page.releases, 1);
  } finally {
    device.cleanup();
  }
});

test('fixture-target accepts the delivered boxCSS/visibleRect names', async () => {
  const { handler } = retirementDevice();
  const device = createProtocolDevice({
    handler: async (command) => {
      if (command.kind === 'device' && String(command.text).startsWith('fixture-target:')) {
        const result = await handler(command);
        if (result.ok && result.data && result.data.box) {
          result.data.boxCSS = result.data.box;
          result.data.visibleRect = { ...result.data.box };
          delete result.data.box;
        }
        return result;
      }
      return handler(command);
    },
  });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const adapters = harness.createRetirementGateAdapters(retirement, harness.createCaseFence());
    const flow = await retirementProbe.runPrimaryDragLock({ ...adapters, now: guest.now, sleep: guest.sleepMs });
    assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 800));
  } finally {
    device.cleanup();
  }
});

test('retirement B: cookie, CANCEL retirement, explicit Reopen and one fresh click pass', async () => {
  const { dev, handler } = retirementDevice();
  const device = createProtocolDevice({ handler });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({
      client,
      runId: RUN_ID,
      screen: { width: 1080, height: 1920 },
      tapScreen: async () => dev.performUiTap(dev.uiPending),
    });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const fence = harness.createCaseFence();
    const adapters = harness.createRetirementGateAdapters(retirement, fence);
    assert.equal((await adapters.writeTestCookie(COOKIE_HEX)).ok, true);
    const flow = await retirementProbe.runTrueCancelRetire({ ...adapters, now: guest.now, sleep: guest.sleepMs });
    assert.equal(flow.ok, true, JSON.stringify(flow).slice(0, 900));
    assert.equal(flow.clicksAdded, 0);
    assert.equal(flow.snapshot.code, 'AUTHORIZATION_REQUIRED');
    assert.equal(flow.reopen.cookieKept, true);
    assert.equal(flow.reopen.freshClicks, 0);
    assert.equal(dev.page.nextClicks, 1);
    assert.equal(dev.page.boxClicks, 0);
    assert.equal(dev.cookieValue.includes(COOKIE_HEX), true);
  } finally {
    device.cleanup();
  }
});


test('retirement orchestrator preflights writeCookieRaw and never calls a writeTestCookie stub', async () => {
  const make = ({ writeCookieResult = true, missingCookie = false } = {}) => {
    const calls = { open: 0, allow: 0, mouse: 0, frame: 0, target: 0, writes: 0 };
    const guest = {
      async call(action) { calls.open += 1; return { ok: true, data: { tabId: 'tab-retire', url: matrix.FIXTURES.retire } }; },
      async readLifecycleRaw() { return { selectedTabId: 'tab-retire', pageReady: true, mouseMode: true, viewHandle: 7, documentEpoch: 3 }; },
      async sleepMs() {},
      async tapAllowAndVerify() { calls.allow += 1; return { ok: true, tapped: true, grantPresent: true }; },
      async enableMouseRaw() { calls.mouse += 1; return true; },
      missingReport: () => ({}),
      readFailures: () => [],
    };
    const retirement = {
      call: guest.call,
      readLifecycleRaw: guest.readLifecycleRaw,
      readNativeViewRaw: async () => ({ currentViewPresent: true }),
      readPageRaw: async () => null,
      readFrameRaw: async () => { calls.frame += 1; return null; },
      readTargetRaw: async () => { calls.target += 1; return null; },
      fingerRaw: async () => false,
      atomicFingerRaw: async () => false,
      enableMouseRaw: guest.enableMouseRaw,
      nativeGateRaw: async () => null,
      agentSnapshotRaw: async () => ({ ok: false, code: 'AUTHORIZATION_REQUIRED' }),
      allowAttemptRaw: async () => ({ tapped: false, granted: false }),
      readCookieRaw: async () => null,
      writeCookieRaw: missingCookie ? undefined : async () => { calls.writes += 1; return writeCookieResult; },
      tapLabelRaw: async () => false,
      sleepMs: guest.sleepMs,
      now: () => 0,
      missingReport: () => ({}),
      readFailures: () => [],
    };
    return { guest, retirement, calls };
  };
  const saves = [];
  const save = (name, value) => saves.push({ name, value });
  const item = harness.RETIREMENT_CASES.find((entry) => entry.name === 'retirement-b-true-cancel-reopen');

  const missing = make({ missingCookie: true });
  const missingResult = await runner.runRetirementCase({ item, guest: missing.guest, retirement: missing.retirement, save, log: () => {} });
  assert.equal(missingResult.ok, false);
  assert.equal(missingResult.reason, 'prepare:retirement-adapter-method-missing');
  assert.ok(missingResult.setup.methods.includes('writeCookieRaw'));
  assert.deepEqual(missing.calls, { open: 0, allow: 0, mouse: 0, frame: 0, target: 0, writes: 0 }, 'preflight is zero-UI');

  const badCookie = make({ writeCookieResult: false });
  const badResult = await runner.runRetirementCase({ item, guest: badCookie.guest, retirement: badCookie.retirement, save, log: () => {} });
  assert.equal(badResult.ok, false);
  assert.equal(badResult.reason, 'prepare:test-cookie-not-written');
  assert.equal(badCookie.calls.writes, 1);
  assert.equal(badCookie.calls.frame, 0, 'bad/no readback cookie stops before geometry/flow');

  const goodCookie = make({ writeCookieResult: true });
  const goodResult = await runner.runRetirementCase({ item, guest: goodCookie.guest, retirement: goodCookie.retirement, save, log: () => {} });
  assert.equal(goodResult.ok, false);
  assert.equal(goodResult.reason, 'prepare:retirement-geometry-reader-missing');
  assert.equal(goodCookie.calls.writes, 1, 'literal writeCookieRaw boolean contract was called once');
  assert.equal(goodCookie.calls.frame, 1);

  const throwing = make({ writeCookieResult: true });
  throwing.retirement.writeCookieRaw = async () => { throwing.calls.writes += 1; throw new Error('cookie boom'); };
  const throwingResult = await runner.runRetirementCase({ item, guest: throwing.guest, retirement: throwing.retirement, save, log: () => {} });
  assert.equal(throwingResult.ok, false);
  assert.equal(throwingResult.reason, 'case-exception');
  assert.match(throwingResult.error, /cookie boom/);
  assert.equal(throwing.calls.writes, 1);
  assert.equal(throwing.calls.frame, 0, 'exception does not run geometry/flow');
});


test('createGuestRetirementIo exposes the audited literal surface and honors set:true/false', async () => {
  let set = false;
  const client = {
    async sendDevice(text) {
      if (text.startsWith('cookie-write:')) return { ok: true, data: { set } };
      if (text === 'cookie-read') return { ok: true, data: { value: set ? `oc_fixture_retire=${COOKIE_HEX}` : null } };
      return { ok: false, data: null, code: 'unknown-device-command', detail: text };
    },
    async sendTool() { return { ok: false, data: null, code: 'not-used', detail: '' }; },
    async readStatus() { return null; },
  };
  const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
  const retirement = matrix.createGuestRetirementIo(guest, { client });
  assert.deepEqual(runner.auditRetirementIo(retirement), []);
  assert.equal(await retirement.writeCookieRaw(COOKIE_HEX), false, 'set:false is not success');
  set = true;
  assert.equal(await retirement.writeCookieRaw(COOKIE_HEX), true);
  assert.equal(await retirement.readCookieRaw(), `oc_fixture_retire=${COOKIE_HEX}`);
});


test('runRetirementCase saves bounded hover diagnostics when B stops at box-hover-unproved', async () => {
  const { dev, handler } = retirementDevice();
  let targetReads = 0;
  const device = createProtocolDevice({
    handler: async (command) => {
      if (command.kind === 'device' && String(command.text).startsWith('fixture-target:box,')) {
        const pageCounterRead = command.text === 'fixture-target:box,1,1';
        if (!pageCounterRead) {
          targetReads += 1;
          if (targetReads > 1) return { ok: false, error: { code: 'source-changed', message: 'source changed' } };
        }
      }
      return handler(command);
    },
  });
  const saves = [];
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({
      client,
      runId: RUN_ID,
      screen: { width: 1080, height: 1920 },
      tapScreen: async () => dev.performUiTap(dev.uiPending),
    });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const item = harness.RETIREMENT_CASES.find((entry) => entry.name === 'retirement-b-true-cancel-reopen');
    const result = await runner.runRetirementCase({
      item,
      guest,
      retirement,
      save: (name, value) => saves.push({ name, value }),
      log: () => {},
    });
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'box-hover-unproved');
    assert.equal(result.diagnostics.hover, 'retirement-b-true-cancel-reopen-hover.json');
    const hover = saves.find((entry) => entry.name === result.diagnostics.hover);
    assert.ok(hover);
    assert.equal(hover.value.reason, 'box-hover-unproved');
    assert.ok(hover.value.diagnostics.some((entry) => entry.op === 'dom' && entry.error && entry.error.code === 'source-changed'));
    assert.equal(JSON.stringify(hover.value).includes('oc_fixture_retire'), false);
  } finally {
    device.cleanup();
  }
});

test('a missing fixture-target reader refuses retirement and names the exact command', async () => {
  const { handler } = retirementDevice();
  const device = createProtocolDevice({
    handler: async (command) => {
      if (command.kind === 'device' && String(command.text).startsWith('fixture-target:')) {
        return { ok: false, error: { code: 'unknown-device-command', message: command.text } };
      }
      return handler(command);
    },
  });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const frame = await retirement.readFrameRaw();
    assert.ok(frame);
    const aim = await retirement.readTargetRaw('box');
    assert.equal(aim, null);
    assert.equal(guest.missingReport().reader, 'fixture-target');
    assert.match(guest.missingReport().command, /^fixture-target:box/);
  } finally {
    device.cleanup();
  }
});

test('a missing page-rect pointerUi refuses retirement geometry without inventing a pointer', async () => {
  const { handler } = retirementDevice();
  const device = createProtocolDevice({
    handler: async (command) => {
      if (command.kind === 'device' && command.text === 'page-rect') {
        const result = await handler(command);
        delete result.data.pointerUi;
        return result;
      }
      return handler(command);
    },
  });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    assert.equal(await retirement.readFrameRaw(), null);
    assert.equal(guest.missingReport().reader, 'page-rect');
    assert.equal(guest.missingReport().field, 'pointerUi');
  } finally {
    device.cleanup();
  }
});

test('fixture-target is refused outside the RETIRE fixture', async () => {
  const { handler } = retirementDevice();
  const device = createProtocolDevice({
    handler: async (command) => {
      if (command.kind === 'device' && command.text === 'observe') {
        return { ok: true, data: { url: matrix.FIXTURES.mouse, metrics: { ua: 'desktop', clientWidth: 1024, media1000: true, sy: 0, timeOrigin: 1 }, page: { clicks: 0 } } };
      }
      return handler(command);
    },
  });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    assert.equal(await retirement.readTargetRaw('box'), null);
    assert.equal(guest.missingReport().reader, 'fixture-target');
    assert.equal(guest.missingReport().field, 'only-RETIRE_URL');
  } finally {
    device.cleanup();
  }
});

test('missing cookie reader refuses the retirement-cookie step and names the command', async () => {
  const { handler } = retirementDevice();
  const device = createProtocolDevice({
    handler: async (command) => {
      if (command.kind === 'device' && /^cookie-(read|write):/.test(String(command.text))) {
        return { ok: false, error: { code: 'unknown-device-command', message: command.text } };
      }
      if (command.kind === 'device' && command.text === 'cookie-read') {
        return { ok: false, error: { code: 'unknown-device-command', message: command.text } };
      }
      return handler(command);
    },
  });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    assert.equal(await retirement.writeCookieRaw(COOKIE_HEX), false);
    assert.equal(await retirement.readCookieRaw(), null);
    assert.equal(guest.missingReport().command, 'cookie-write');
  } finally {
    device.cleanup();
  }
});

test('a fenced case cannot send a later tool, device or cookie mutation', async () => {
  const { handler } = retirementDevice();
  const device = createProtocolDevice({ handler });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({ client, runId: RUN_ID });
    const retirement = matrix.createGuestRetirementIo(guest, { client });
    const fence = harness.createCaseFence();
    const adapters = harness.createRetirementGateAdapters(retirement, fence);
    fence.stop();
    const finger = await adapters.finger({ action: 'down', x: 1, y: 1 });
    const cookie = await adapters.writeTestCookie('late');
    const reopen = await adapters.tapReopen();
    assert.equal(finger.code, 'FENCED');
    assert.equal(cookie.code, 'FENCED');
    assert.equal(reopen.code, 'FENCED');
    assert.equal(device.written.length, 0, 'nothing reached the simulated producer after the fence');
  } finally {
    device.cleanup();
  }
});

test('finish summary carries the flat producer booleans, the cases array and the required whitelist', () => {
  const summary = runner.buildFinishSummary({
    runId: RUN_ID,
    scope: 'retirement',
    required: ['retirement-a-primary-drag-lock', 'retirement-b-true-cancel-reopen'],
    results: { 'retirement-a-primary-drag-lock': true, 'retirement-b-true-cancel-reopen': false },
  });
  assert.equal(summary['retirement-a-primary-drag-lock'], true);
  assert.equal(summary['retirement-b-true-cancel-reopen'], false);
  assert.equal(summary.ok, false);
  assert.deepEqual(summary.required, ['retirement-a-primary-drag-lock', 'retirement-b-true-cancel-reopen']);
  assert.deepEqual(summary.cases, [
    { name: 'retirement-a-primary-drag-lock', ok: true, reason: null },
    { name: 'retirement-b-true-cancel-reopen', ok: false, reason: null },
  ]);
});

test('host verdict requires actual producer casePartial/caseScope/expectedCases, not a fake partial flag', () => {
  const all = [...matrix.ALL_CASE_NAMES];
  const allResults = Object.fromEntries(all.map((name) => [name, true]));
  const base = {
    runId: RUN_ID,
    scope: 'all',
    required: all,
    results: allResults,
    counts: { pass: true, tests: 1, failures: 0, instrumentationCode: -1 },
    adbExit: 0,
    status: { runId: RUN_ID, phase: 'done', selectedSessionId: 'ses-9', casePartial: false, caseScope: 'all', expectedCases: all },
    evidence: { runId: RUN_ID, phase: 'done', casePartial: false, caseScope: 'all', expectedCases: all },
    finish: { ok: true, data: { toolCommands: 1, deviceCommands: 2 } },
    preconditions: {
      deviceSha256: 'a'.repeat(64),
      serial: 'emulator-5582',
      package: 'io.andchamber.localprovider',
      hostServerStarted: false,
    },
    boot: { status: { phase: 'ready' } },
  };
  assert.deepEqual(runner.hostVerdict(base), { ok: true, reasons: [] });

  const failedCase = runner.hostVerdict({ ...base, results: { ...allResults, 'lifecycle-b5-enlarge-restore': false } });
  assert.equal(failedCase.ok, false);
  assert.ok(failedCase.reasons.includes('case-not-pass:lifecycle-b5-enlarge-restore'));
  const failedOfficial = runner.hostVerdict({ ...base, counts: { pass: false, tests: 1, failures: 1, instrumentationCode: 0 } });
  assert.equal(failedOfficial.ok, false);
  assert.ok(failedOfficial.reasons.includes('instrumentation-not-pass'));
  const noFinish = runner.hostVerdict({ ...base, finish: { ok: false, code: 'finish-case-missing' } });
  assert.equal(noFinish.ok, false);
  const staleStatus = runner.hostVerdict({ ...base, status: { runId: 'other', phase: 'done' } });
  assert.equal(staleStatus.ok, false);

  // lifecycle 6 is scope-complete but partial of the full matrix: casePartial must be true.
  const lifecycle = [...matrix.LIFECYCLE_CASE_NAMES];
  const lifecycleResults = Object.fromEntries(lifecycle.map((name) => [name, true]));
  const lifecycleBase = {
    ...base,
    scope: 'lifecycle',
    required: lifecycle,
    results: lifecycleResults,
    partialOfFullMatrix: true,
    partialOfRequestedScope: false,
    status: { runId: RUN_ID, phase: 'done', selectedSessionId: 'ses-9', casePartial: true, caseScope: 'lifecycle', expectedCases: lifecycle },
    evidence: { runId: RUN_ID, phase: 'done', casePartial: true, caseScope: 'lifecycle', expectedCases: lifecycle },
  };
  assert.deepEqual(runner.hostVerdict(lifecycleBase), { ok: true, reasons: [] });
  assert.equal(runner.hostVerdict({ ...lifecycleBase, status: { ...lifecycleBase.status, casePartial: false } }).ok, false);
  assert.equal(runner.hostVerdict({ ...lifecycleBase, status: { ...lifecycleBase.status, caseScope: 'pending' } }).ok, false);
  assert.equal(runner.hostVerdict({ ...lifecycleBase, evidence: { ...lifecycleBase.evidence, expectedCases: [...lifecycle].reverse() } }).ok, true, 'set comparison, not order');
  assert.equal(runner.hostVerdict({ ...lifecycleBase, status: { ...lifecycleBase.status, expectedCases: lifecycle.slice(1) } }).ok, false);

  // A requested-scope subset is custom/partial but the flat booleans and actual flow results stay unchanged.
  const one = ['lifecycle-b5-enlarge-restore'];
  const subset = {
    ...base,
    scope: 'lifecycle',
    required: one,
    results: { 'lifecycle-b5-enlarge-restore': true },
    partialOfFullMatrix: true,
    partialOfRequestedScope: true,
    status: { runId: RUN_ID, phase: 'done', selectedSessionId: 'ses-9', casePartial: true, caseScope: 'custom', expectedCases: one },
    evidence: { runId: RUN_ID, phase: 'done', casePartial: true, caseScope: 'custom', expectedCases: one },
  };
  assert.deepEqual(runner.hostVerdict(subset), { ok: true, reasons: [] });
  assert.equal(runner.hostVerdict({ ...subset, status: { ...subset.status, casePartial: null } }).ok, false);
  assert.equal(runner.hostVerdict({ ...subset, status: { ...subset.status, caseScope: 'all' } }).ok, true, 'partial request accepts a finite producer scope');
});

test('the instrumentation wire carries only -e cases, runId and class', () => {
  const args = runner.buildInstrumentArgs({
    serial: 'emulator-5582',
    runId: RUN_ID,
    caseNames: ['lifecycle-b5-enlarge-restore', 'retirement-a-primary-drag-lock'],
  });
  assert.deepEqual(args.slice(0, 8), ['-s', 'emulator-5582', 'shell', 'am', 'instrument', '-r', '-w', '-e']);
  assert.equal(args.includes('expectedCases'), false);
  assert.equal(args[args.indexOf('class') + 1], 'io.andchamber.app.browser.LocalProviderRegressionTest#runGuestMatrix');
  assert.equal(args[args.indexOf('runId') + 1], RUN_ID);
  assert.equal(args[args.indexOf('cases') + 1], 'lifecycle-b5-enlarge-restore,retirement-a-primary-drag-lock');
  assert.equal(args.at(-1), 'io.andchamber.localprovider.test/androidx.test.runner.AndroidJUnitRunner');
});

test('ui-state accepts a Surface from labels, a top-level surface or a fixed Tight node', () => {
  const bounds = { left: 10, top: 20, right: 110, bottom: 220 };
  assert.deepEqual(matrix.normalizeUi({ labels: { Restore: {}, BrowserFloatSurface: bounds } }), { enlarged: true, surface: { left: 10, top: 20, width: 100, height: 200, right: 110, bottom: 220 } });
  assert.equal(matrix.normalizeUi({ labels: { Enlarge: {} }, surface: bounds }).enlarged, false);
  assert.equal(matrix.normalizeUi({ labels: {}, tightNodes: [{ desc: 'BrowserFloatSurface', ...bounds }] }).surface.width, 100);
  assert.equal(matrix.normalizeUi({ labels: {} }).enlarged, null);
});


test('frameProjection keeps literal rejection reason and normalizeFrame stays frame|null', () => {
  const base = {
    physical: { left: 10, top: 20, width: 300, height: 400 },
    page: { visualViewport: { width: 320, height: 420, offsetLeft: 0, offsetTop: 0 } },
    pointerUi: { geometryReady: true, viewHandle: 3, pointerViewX: 1, pointerViewY: 2 },
    viewHandle: 3,
    documentEpoch: 4,
  };
  const ok = matrix.frameProjection(base);
  assert.equal(ok.reason, 'ok');
  assert.equal(ok.frame.viewHandle, 3);
  assert.equal(matrix.normalizeFrame(base).viewHandle, 3);
  assert.equal(matrix.frameProjection({ ...base, physical: undefined }).reason, 'physical-missing');
  assert.deepEqual(matrix.frameProjection({ ...base, physical: undefined }).missingFields, ['physical']);
  assert.equal(matrix.frameProjection({ ...base, page: { visualViewport: { width: null, height: 420, offsetLeft: 0, offsetTop: 0 } } }).reason, 'viewport-nonfinite');
  assert.equal(matrix.frameProjection({ ...base, page: { visualViewport: { width: 0, height: 420, offsetLeft: 0, offsetTop: 0 } } }).reason, 'viewport-not-positive');
  assert.equal(matrix.frameProjection({ ...base, pointerUi: undefined }).reason, 'pointer-missing');
  assert.equal(matrix.frameProjection({ ...base, pointerUi: { ...base.pointerUi, geometryReady: false } }).reason, 'pointer-not-ready');
  assert.equal(matrix.frameProjection({ ...base, pointerUi: { ...base.pointerUi, viewHandle: 99 } }).reason, 'pointer-handle-mismatch');
  assert.equal(matrix.frameProjection({ ...base, viewHandle: undefined }).reason, 'view-handle-missing');
  assert.equal(matrix.normalizeFrame({ ...base, pointerUi: undefined }), null);
  assert.equal(matrix.frameProjection(null).reason, 'raw-missing');
});

test('typed source readers accept the old fixture shape and reject a non-fixture observe URL', () => {
  const fixture = matrix.normalizeFixture({
    page: { url: MOUSE_URL, loads: 2, posts: 1 },
    native: { physicalFrame: { left: 10, top: 20, width: 300, height: 400 } },
    postArrivals: 1,
  });
  assert.equal(fixture.page.loads, 2);
  assert.equal(fixture.native.physicalFrame.width, 300);
  assert.equal(fixture.postArrivals, 1);
  assert.equal(matrix.normalizeObserve({ url: 'http://127.0.0.1:3000/', metrics: { sy: 1 } }), null, 'the chat URL is not a fixture');
  const observe = matrix.normalizeObserve({ url: matrix.FIXTURES.second, metrics: { ua: 'mobile', clientWidth: 390, media1000: false, sy: 9, timeOrigin: 5 } });
  assert.equal(observe.ua, 'mobile');
  assert.equal(observe.sy, 9);
  const frame = matrix.normalizeFrame({
    physical: { left: 10, top: 20, width: 300, height: 400 },
    page: { visualViewport: { width: 320, height: 420, offsetLeft: 0, offsetTop: 0 } },
    pointerUi: { geometryReady: true, viewHandle: 3, pointerViewX: 1, pointerViewY: 2 },
    viewHandle: 3,
    documentEpoch: 4,
  });
  assert.equal(frame.viewHandle, 3);
  assert.equal(matrix.normalizeFrame({ physical: { left: 1, top: 2, width: 3, height: 4 }, page: { visualViewport: { width: 3, height: 4, offsetLeft: 0, offsetTop: 0 } }, viewHandle: 3 }), null, 'no pointerUi means no pointer fact');
});


test('normalizeFixture prefers top-level outerime and preserves null shown instead of false', () => {
  const base = {
    page: { url: MOUSE_URL, loads: 1, posts: 0, inputValue: '', formPresent: true, inputFocused: false, activeTag: 'BODY', documentHasFocus: true, inputEnabled: true, inputReadOnly: false, inputTag: 'INPUT', timeOrigin: 12 },
    native: { selectedTabId: 'tab-a', viewHandle: 7, shown: true, pageReady: true, mouseMode: false, hasFocus: true, hasWindowFocus: true, isShown: true, windowTokenPresent: true, physicalFrame: { left: 1, top: 2, width: 300, height: 400 }, screenVisibleRect: { left: 1, top: 2, width: 300, height: 400, visible: false } },
    outerime: { shown: null, source: 'unavailable', bottomPx: null },
    identityBefore: { viewHandle: 7, timeOrigin: 12 },
    identityAfter: { viewHandle: 7, timeOrigin: 12 },
    ime: { shown: true, source: 'old', bottomPx: 100 },
    postArrivals: 0,
  };
  const f = matrix.normalizeFixture(base);
  assert.equal(f.ime.shown, null, 'outerime wins and null is not false');
  assert.equal(f.ime.source, 'unavailable');
  assert.equal(f.native.hasWindowFocus, true);
  assert.equal(f.native.windowTokenPresent, true);
  assert.equal(f.page.documentHasFocus, true);
  assert.equal(f.native.screenVisibleRect.visible, false, 'screenVisibleRect.visible is preserved, not defaulted');
  assert.deepEqual(f.identityBefore, { viewHandle: 7, timeOrigin: 12 });
  assert.deepEqual(f.identityAfter, { viewHandle: 7, timeOrigin: 12 });
  assert.equal(f.raw.page.timeOrigin, 12, 'fixed-fixture raw object is preserved');
  const shown = matrix.normalizeFixture({ ...base, outerime: { shown: true, source: 'root-window-insets', bottomPx: 300 } });
  assert.equal(shown.ime.shown, true);
  const noVisible = matrix.normalizeFixture({ ...base, native: { ...base.native, screenVisibleRect: { left: 1, top: 2, width: 300, height: 400 } } });
  assert.equal(noVisible.native.screenVisibleRect.visible, null, 'missing visible stays null');
});


test('raw response archive stores only current-run matching replies, 0600, redacted and bounded', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'matrix-responses-'));
  const dir = path.join(root, 'responses');
  const archive = matrix.createResponseArchive({ dir, maxResponses: 2, maxBytes: 1024 * 1024, maxResponseBytes: 1024 * 1024 });
  const marker = 'oc_fixture_retire=0123456789abcdef0123456789abcdef';
  try {
    const saved = await archive.save({
      runId: RUN_ID,
      id: 'matrix-run-0001:device:0001',
      kind: 'device',
      text: 'lifecycle-diag',
      raw: { ok: true, data: { marker, base64: 'AAAA', imageBytes: 'secret-image', auth: 'Bearer top-secret-token', authCookie: 'secret-cookie', token: 'secret-token' } },
    });
    assert.match(saved.file, /matrix-run-0001:device:0001\.json$/);
    assert.equal((fs.statSync(saved.file).mode & 0o777), 0o600);
    const body = JSON.parse(fs.readFileSync(saved.file, 'utf8'));
    assert.equal(body.runId, RUN_ID);
    assert.equal(body.rawResponse.data.marker, marker);
    assert.equal(body.rawResponse.data.base64, undefined);
    assert.equal(body.rawResponse.data.imageBytes, undefined);
    assert.equal(body.rawResponse.data.auth.includes('top-secret-token'), false);
    assert.equal(body.rawResponse.data.authCookie, '[redacted]');
    assert.equal(body.rawResponse.data.token, '[redacted]');
    assert.equal(body.devicePrefix, 'lifecycle-diag');

    await archive.save({ runId: RUN_ID, id: 'a', kind: 'device', text: 'observe', raw: { ok: true, data: { n: 2 } } });
    await archive.save({ runId: RUN_ID, id: 'b', kind: 'device', text: 'observe', raw: { ok: true, data: { n: 3 } } });
    const manifest = archive.manifest();
    assert.equal(manifest.count, 2);
    assert.ok(manifest.evicted.length >= 1);
    assert.equal(fs.existsSync(path.join(dir, 'matrix-run-0001:device:0001.json')), false);

    await archive.save({ runId: RUN_ID, id: '../outside', kind: 'device', text: 'observe', raw: { ok: true, data: {} } });
    assert.equal(fs.existsSync(path.join(root, 'outside.json')), false);
    assert.ok(fs.readdirSync(dir).every((name) => name !== '..'));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }

  const over = fs.mkdtempSync(path.join(os.tmpdir(), 'matrix-responses-over-'));
  try {
    const small = matrix.createResponseArchive({ dir: path.join(over, 'responses'), maxResponseBytes: 32 });
    await assert.rejects(() => small.save({ runId: RUN_ID, id: 'big', kind: 'device', text: 'observe', raw: { data: 'x'.repeat(200) } }), /budget|RESPONSE_TOO_LARGE/);
  } finally {
    fs.rmSync(over, { recursive: true, force: true });
  }
});

test('MatrixClient onResponse is called for matching current reply before normalize, never stale', async () => {
  const seen = [];
  const device = createProtocolDevice({
    handler: (command) => ({ ok: true, data: { action: command.kind, value: 1 } }),
    resultDelayMs: 5,
    staleFirst: true,
  });
  try {
    const client = matrix.createMatrixClient({
      transport: device.transport,
      runId: RUN_ID,
      now: (() => { let clock = 0; return () => clock; })(),
      sleep: async (ms) => { await new Promise((resolve) => setTimeout(resolve, 0)); },
      commandTimeoutMs: 500,
      pollMs: 1,
      onResponse: async (context) => { seen.push(context); },
    });
    const result = await client.sendDevice('lifecycle-diag');
    assert.equal(result.ok, true);
    assert.equal(seen.length, 1);
    assert.equal(seen[0].runId, RUN_ID);
    assert.match(seen[0].id, /:device:0001$/);
    assert.equal(seen[0].kind, 'device');
    assert.equal(seen[0].text, 'lifecycle-diag');
    assert.equal(seen[0].raw.runId, RUN_ID);
    assert.equal(seen[0].raw.id, seen[0].id);
    assert.equal(seen[0].raw.data.value, 1);
  } finally {
    device.cleanup();
  }
});

test('status maps producer casePartial/caseScope/expectedCases and never defaults a missing boolean', () => {
  const cases = ['lifecycle-b5-enlarge-restore'];
  const status = matrix.validStatus({
    runId: RUN_ID,
    phase: 'ready',
    updatedAtMs: 1,
    casePartial: true,
    caseScope: 'custom',
    expectedCases: cases,
  }, { runId: RUN_ID });
  assert.equal(status.casePartial, true);
  assert.equal(status.caseScope, 'custom');
  assert.deepEqual(status.expectedCases, cases);
  const missing = matrix.validStatus({ runId: RUN_ID, phase: 'ready', updatedAtMs: 1, caseScope: 'custom', expectedCases: cases }, { runId: RUN_ID });
  assert.equal(missing.casePartial, null, 'missing producer field stays null, not false');
  assert.equal(matrix.validStatus({ runId: RUN_ID, phase: 'ready', updatedAtMs: 1, casePartial: 'true' }, { runId: RUN_ID }).casePartial, null);
  assert.equal(matrix.validStatus({ runId: RUN_ID, phase: 'ready', updatedAtMs: 1, expectedCases: 'a,,b' }, { runId: RUN_ID }).expectedCases, null);
});

test('status wait can stop on an already-closed instrumentation without inventing a phase', async () => {
  const client = { readStatus: async () => ({ runId: RUN_ID, phase: 'booting', updatedAtMs: 1 }) };
  const result = await matrix.waitForStatus({ client, phases: ['ready'], budgetMs: 1000, pollMs: 1, isClosed: () => true });
  assert.equal(result.closed, true);
  assert.equal(result.timedOut, false);
  assert.equal(result.status, null);
});

test('fixture-field validates the DOM precondition shape, not a fake UI Accessibility target', async () => {
  const make = (fieldData, tapData = { phase: 'tap-label', sent: true, hit: true, pointVisible: true, viewHandle: 7, documentEpoch: 3, sx: 100, sy: 200, frame: { x: 0, y: 0, width: 1080, height: 1920 } }) => {
    const client = {
      async sendDevice(text) {
        if (text.startsWith('fixture-field:')) return { ok: true, data: fieldData };
        if (text.startsWith('tap-label:')) return { ok: true, data: tapData };
        return { ok: false, data: null, code: 'unknown-device-command', detail: text };
      },
      async sendTool() { return { ok: false, data: null, code: 'not-used', detail: '' }; },
      async readStatus() { return null; },
    };
    return matrix.createGuestGateIo({ client, runId: RUN_ID });
  };
  const validInput = { phase: 'fixture-field', field: 'login-user', present: true, tag: 'INPUT', type: 'text', value: 'abc', valueLength: 3 };
  const guest = make(validInput);
  assert.deepEqual(await guest.fixtureFieldTarget('login-user'), validInput);
  assert.equal(await guest.tapFixtureLabelRaw('login-user'), true);

  const validButton = { phase: 'fixture-field', field: 'Sign in', present: true, tag: 'BUTTON', type: '', value: '', valueLength: 0 };
  assert.deepEqual(await make(validButton).fixtureFieldTarget('Sign in'), validButton);

  // An Accessibility target shape without the DOM field/present/value facts is not accepted.
  const uiShape = { label: 'login-user', phase: 'fixture-field', windowId: 1, ownerPackage: 'io.andchamber.localprovider', observedAtMs: Date.now(), left: 1, top: 2, right: 3, bottom: 4 };
  const uiGuest = make(uiShape);
  assert.equal(await uiGuest.fixtureFieldTarget('login-user'), null);
  assert.ok(['wrong-field', 'present-not-true'].includes(uiGuest.missingReport().field));

  const missingValueGuest = make({ ...validInput, value: undefined });
  assert.equal(await missingValueGuest.fixtureFieldTarget('login-user'), null);
  assert.equal(missingValueGuest.missingReport().field, 'value-length-mismatch');
  const shortLengthGuest = make({ ...validInput, valueLength: 2 });
  assert.equal(await shortLengthGuest.fixtureFieldTarget('login-user'), null);
  const passwordGuest = make({ ...validInput, type: 'password' });
  assert.equal(await passwordGuest.fixtureFieldTarget('login-user'), null);
  assert.equal(passwordGuest.missingReport().field, 'refused-input-type');

  const noVisibleGuest = make(validInput, { phase: 'tap-label', sent: true, hit: true, pointVisible: false, viewHandle: 7 });
  assert.equal(await noVisibleGuest.tapFixtureLabelRaw('login-user'), false);
  const hitFalseGuest = make(validInput, { phase: 'tap-label', sent: true, hit: false, pointVisible: true, viewHandle: 7 });
  assert.equal(await hitFalseGuest.tapFixtureLabelRaw('login-user'), false);
  const sentFalseGuest = make(validInput, { phase: 'tap-label', sent: false, hit: false, pointVisible: false, viewHandle: 7 });
  assert.equal(await sentFalseGuest.tapFixtureLabelRaw('login-user'), false);
});

test('POST setup resolves fixture targets and uses real host input, never a tool form submit', async () => {
  const state = {
    posts: 0,
    arrivals: 0,
    labels: [],
    typed: 0,
    focused: false,
    inputValue: '',
  };
  const handler = async (command) => {
    if (command.kind === 'tool') {
      if (command.action === 'browser.open') return { ok: true, data: { tabId: 'tab-login', url: matrix.FIXTURES.login } };
      return { ok: true, data: {} };
    }
    if (command.text === 'lifecycle-diag') {
      return { ok: true, data: { phase: 'lifecycle-diag', selectedTabId: 'tab-login', pageReady: true, grantForSelected: false, mouseMode: true, desktopMode: false, viewHandle: 7, documentEpoch: 3 } };
    }
    if (command.text === 'fixture-state') {
      return { ok: true, data: loginFixtureState(state) };
    }
    if (command.text.startsWith('fixture-field:')) {
      const field = command.text.slice('fixture-field:'.length);
      if (field !== 'login-user' && field !== 'Sign in') return { ok: false, error: { code: 'unknown-device-command', message: command.text } };
      return { ok: true, data: {
        phase: 'fixture-field',
        field,
        present: true,
        tag: field === 'login-user' ? 'INPUT' : 'BUTTON',
        type: field === 'login-user' ? 'text' : '',
        value: '',
        valueLength: 0,
      } };
    }
    if (command.text.startsWith('tap-label:')) {
      const field = command.text.slice('tap-label:'.length);
      if (field !== 'login-user' && field !== 'Sign in') return { ok: false, error: { code: 'unknown-device-command', message: command.text } };
      state.labels.push(field);
      if (field === 'login-user') state.focused = true;
      if (field === 'Sign in') { state.posts = 1; state.arrivals = 1; }
      return { ok: true, data: { phase: 'tap-label', label: field, sent: true, hit: true, pointVisible: true, viewHandle: 7, documentEpoch: 3, sx: 210, sy: 230, frame: { x: 0, y: 0, width: 1080, height: 1920 }, scale: 1 } };
    }
    if (command.text === 'observe') {
      return { ok: true, data: { url: matrix.FIXTURES.login, metrics: { ua: 'mobile', clientWidth: 390, media1000: false, sy: 0, loads: 1, timeOrigin: 1700 } } };
    }
    return { ok: false, error: { code: 'unknown-device-command', message: command.text } };
  };
  const device = createProtocolDevice({ handler });
  try {
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({
      client,
      runId: RUN_ID,
      screen: { width: 1080, height: 1920 },
      humanType: async (text) => { state.typed += 1; state.inputValue = text; return { exitCode: 0, startedAtMs: 1, finishedAtMs: 2 }; },
    });
    const result = await guest.submitLoginFresh();
    assert.equal(result.ok, true, JSON.stringify(result));
    assert.equal(result.beforePosts, 0);
    assert.equal(result.beforeArrivals, 0);
    assert.equal(state.typed, 1);
    assert.deepEqual(state.labels, ['login-user', 'Sign in']);
    const toolActions = device.written.filter((item) => item.kind === 'tool').map((item) => item.action);
    assert.deepEqual(toolActions, ['browser.open'], 'no browser.type or browser.click fake form POST');
  } finally {
    device.cleanup();
  }
});

test('literal producer tap-label JSON: sent/hit/pointVisible/source identity gate one human submit', async () => {
  const runPost = async ({
    tapData = null,
    tapError = null,
    humanTypeThrows = false,
    humanExit = 0,
    typedValue = 'local-provider-matrix',
    pageOverrides = null,
    nativeOverrides = null,
    afterTapOverrides = null,
    fixtureTransform = null,
    imeShown = false,
    focusFlip = false,
    fixtureErrorAfterTap = false,
  } = {}) => {
    const state = { posts: 0, arrivals: 0, labels: [], typed: 0, focused: false, inputValue: '', fixtureReads: 0, pressBack: 0, humanTypeAtReads: null, imeShown };
    const handler = async (command) => {
      if (command.kind === 'tool') {
        if (command.action === 'browser.open') return { ok: true, data: { tabId: 'tab-login', url: matrix.FIXTURES.login } };
        return { ok: true, data: {} };
      }
      if (command.text === 'lifecycle-diag') {
        return { ok: true, data: { phase: 'lifecycle-diag', selectedTabId: 'tab-login', pageReady: true, grantForSelected: false, mouseMode: true, desktopMode: false, viewHandle: 7, documentEpoch: 3 } };
      }
      if (command.text === 'fixture-state') {
        state.fixtureReads += 1;
        if (fixtureErrorAfterTap && state.fixtureReads > 1) {
          return { ok: false, error: { code: 'source-changed', message: 'source changed' } };
        }
        const fixture = loginFixtureState(state);
        if (pageOverrides) Object.assign(fixture.page, pageOverrides);
        if (nativeOverrides) Object.assign(fixture.native, nativeOverrides);
        if (afterTapOverrides && state.fixtureReads > 1) {
          if (afterTapOverrides.page) Object.assign(fixture.page, afterTapOverrides.page);
          if (afterTapOverrides.native) Object.assign(fixture.native, afterTapOverrides.native);
        }
        if (fixtureTransform) fixtureTransform(fixture, state.fixtureReads, state);
        if (focusFlip && state.focused) {
          if (state.fixtureReads === 2) fixture.page.inputFocused = false;
        }
        fixture.outerime = { shown: state.imeShown, source: 'root-window-insets', bottomPx: state.imeShown ? 300 : 0 };
        return { ok: true, data: fixture };
      }
      if (command.text.startsWith('fixture-field:')) {
        const field = command.text.slice('fixture-field:'.length);
        return { ok: true, data: {
          phase: 'fixture-field', field, present: true,
          tag: field === 'login-user' ? 'INPUT' : 'BUTTON',
          type: field === 'login-user' ? 'text' : 'submit',
          value: '', valueLength: 0,
        } };
      }
      if (command.text.startsWith('tap-label:')) {
        const field = command.text.slice('tap-label:'.length);
        state.labels.push(field);
        if (tapError) return { ok: false, error: tapError };
        if (field === 'login-user') state.focused = true;
        if (field === 'Sign in') { state.posts = 1; state.arrivals = 1; }
        return { ok: true, data: tapData || { phase: 'tap-label', label: field, sent: true, hit: true, pointVisible: true, viewHandle: 7, documentEpoch: 3, sx: 210, sy: 230, frame: { x: 0, y: 0, width: 1080, height: 1920 }, scale: 1 } };
      }
      if (command.text === 'observe') {
        return { ok: true, data: { url: matrix.FIXTURES.login, metrics: { ua: 'mobile', clientWidth: 390, media1000: false, sy: 0, loads: 1, timeOrigin: 1700 } } };
      }
      return { ok: false, error: { code: 'unknown-device-command', message: command.text } };
    };
    const device = createProtocolDevice({ handler });
    let clock = 0;
    const client = clientFor(device);
    const guest = matrix.createGuestGateIo({
      client,
      runId: RUN_ID,
      screen: { width: 1080, height: 1920 },
      now: () => clock,
      sleep: async (ms) => { clock += ms; },
      humanType: async (text) => {
        state.typed += 1;
        state.humanTypeAtReads = state.fixtureReads;
        if (humanTypeThrows) throw new Error('input text refused');
        state.inputValue = typedValue;
        return { exitCode: humanExit, startedAtMs: 1, finishedAtMs: 2 };
      },
      pressBack: async () => {
        state.pressBack += 1;
        state.imeShown = false;
        return { exitCode: 0, startedAtMs: 3, finishedAtMs: 4 };
      },
    });
    const result = await guest.submitLoginFresh();
    device.cleanup();
    return { result, state, device };
  };

  const success = await runPost();
  assert.equal(success.result.ok, true, JSON.stringify(success.result));
  assert.deepEqual(success.state.labels, ['login-user', 'Sign in']);
  assert.equal(success.state.typed, 1);
  assert.equal(success.result.taps.input.pointVisible, undefined, 'literal pointVisible is normalized out of the gate object');
  assert.equal(success.result.taps.input.visible, true);
  assert.equal(success.result.taps.input.data.pointVisible, true, 'literal producer data is retained');
  assert.equal(success.result.taps.submit.data.hit, true);
  assert.equal(success.result.taps.submit.viewHandle, 7);
  assert.equal(success.result.phases.beforeType2.page.inputFocused, true);
  assert.equal(success.result.phases.beforeTap.page.inputValue, '');
  assert.ok(success.state.humanTypeAtReads >= 3, 'typing starts only after two consecutive stable focus samples');
  assert.ok(success.result.samples.length <= 16, 'sample timeline stays bounded');
  assert.ok(success.result.phases.beforeType1 && success.result.phases.beforeType2, 'both stable focus samples are preserved');
  assert.ok(success.result.samples.filter((sample) => sample.phase === 'beforeType1').length === 1);
  assert.ok(success.result.samples.filter((sample) => sample.phase === 'beforeType2').length === 1);

  const stableFlip = await runPost({ focusFlip: true });
  assert.equal(stableFlip.result.ok, true, JSON.stringify(stableFlip.result).slice(0, 600));
  assert.equal(stableFlip.state.typed, 1);
  assert.ok(stableFlip.state.humanTypeAtReads >= 4);

  for (const [name, options, expectedFields] of [
    ['DOM focus false although native focus true', { pageOverrides: { inputFocused: false } }, 'page.inputFocused'],
    ['mouse mode true', { nativeOverrides: { mouseMode: true } }, 'native.mouseMode'],
    ['tab changed', { nativeOverrides: { selectedTabId: 'tab-other' } }, 'native.selectedTabId'],
    ['document timeOrigin changed', { afterTapOverrides: { page: { timeOrigin: 999 } } }, 'page.timeOrigin'],
  ]) {
    const blocked = await runPost(options);
    assert.equal(blocked.result.ok, false, name);
    assert.equal(blocked.state.typed, 0, `${name}: no typing`);
    const expectedLabels = name.includes('tab changed') ? [] : ['login-user'];
    assert.deepEqual(blocked.state.labels, expectedLabels, `${name}: no Sign in`);
    assert.ok(JSON.stringify(blocked.result.missing || {}).includes(expectedFields), `${name}: ${JSON.stringify(blocked.result.missing)}`);
  }

  for (const [name, tapData, expected] of [
    ['sent false', { phase: 'tap-label', sent: false, hit: false, pointVisible: false, viewHandle: 7 }, 'tap-label:login-user:sent-false'],
    ['hit false', { phase: 'tap-label', sent: true, hit: false, pointVisible: true, viewHandle: 7 }, 'tap-label:login-user:hit-false'],
    ['pointVisible false', { phase: 'tap-label', sent: true, hit: true, pointVisible: false, viewHandle: 7 }, 'tap-label:login-user:visible-false'],
    ['source mismatch', { phase: 'tap-label', sent: true, hit: true, pointVisible: true, viewHandle: 99 }, 'tap-label:login-user:source-identity-mismatch'],
  ]) {
    const failed = await runPost({ tapData });
    assert.equal(failed.result.ok, false, name);
    assert.equal(failed.result.reason, expected, name);
    assert.equal(failed.state.typed, 0, `${name}: no human typing`);
    assert.deepEqual(failed.state.labels, ['login-user'], `${name}: no Sign in`);
    assert.equal(failed.result.tap && failed.result.tap.sent, tapData.sent, `${name}: sent preserved`);
    assert.equal(failed.result.tap && failed.result.tap.hit, tapData.hit, `${name}: hit preserved`);
  }

  const error = await runPost({ tapError: { code: 'label-not-in-viewport', message: 'decoded hit rejected' } });
  assert.equal(error.result.ok, false);
  assert.equal(error.result.reason, 'tap-label:login-user:label-not-in-viewport');
  assert.equal(error.result.tap.code, 'label-not-in-viewport');
  assert.equal(error.result.missing, null, 'a real producer failure is not a missing reader');
  assert.equal(error.state.typed, 0);
  assert.deepEqual(error.state.labels, ['login-user']);

  const typeFailure = await runPost({ humanTypeThrows: true });
  assert.equal(typeFailure.result.ok, false);
  assert.equal(typeFailure.result.reason, 'human-type-failed');
  assert.deepEqual(typeFailure.state.labels, ['login-user'], 'failed typing must not trigger Sign in');

  const exitFailure = await runPost({ humanExit: 1 });
  assert.equal(exitFailure.result.ok, false);
  assert.equal(exitFailure.result.reason, 'human-type-exit:1');
  assert.equal(exitFailure.state.typed, 1, 'typing command runs at most once');
  assert.deepEqual(exitFailure.state.labels, ['login-user'], 'non-zero typing cannot trigger Sign in');

  const wrongValue = await runPost({ typedValue: 'partial' });
  assert.equal(wrongValue.result.ok, false);
  assert.equal(wrongValue.result.reason, 'input-value-not-applied');
  assert.deepEqual(wrongValue.state.labels, ['login-user'], 'unconfirmed input must not trigger Sign in');
  assert.equal(wrongValue.result.last.page.inputValue, 'partial');
  assert.equal(wrongValue.result.last.page.activeTag, 'INPUT');
  assert.equal(wrongValue.result.phases.afterTypeImmediate.page.inputValue, 'partial');

  const lateValue = await runPost({
    typedValue: 'first',
    fixtureTransform: (fixture, reads, state) => {
      if (state.humanTypeAtReads != null && reads > state.humanTypeAtReads + 1) {
        fixture.page.inputValue = 'last';
        fixture.page.activeTag = 'BODY';
        fixture.page.selectionStart = 4;
        fixture.page.selectionEnd = 4;
      }
    },
  });
  assert.equal(lateValue.result.ok, false);
  assert.equal(lateValue.result.reason, 'input-value-not-applied');
  assert.equal(lateValue.result.last.page.inputValue, 'last', 'fail.last is the last observed value, not first/immediate');
  assert.equal(lateValue.result.phases.afterTypeFinal.page.inputValue, 'last');
  assert.equal(lateValue.result.last.page.activeTag, 'BODY');
  assert.equal(lateValue.result.last.page.selectionStart, 4);
  assert.deepEqual(lateValue.state.labels, ['login-user'], 'late value failure cannot submit');

  const focusDrift = await runPost({
    fixtureTransform: (fixture, reads, state) => {
      if (state.focused && state.humanTypeAtReads == null) {
        fixture.page.inputFocused = false;
        fixture.page.selectionStart = reads;
        fixture.page.selectionEnd = reads;
      }
    },
  });
  assert.equal(focusDrift.result.ok, false);
  assert.equal(focusDrift.state.typed, 0);
  assert.ok(focusDrift.result.last.page.selectionStart >= 2, 'last focus sample is preserved');
  assert.equal(focusDrift.result.phases.lastFocus, focusDrift.result.last);
  assert.deepEqual(focusDrift.state.labels, ['login-user']);

  const sourceDrift = await runPost({
    fixtureTransform: (fixture, reads, state) => {
      if (state.focused && state.humanTypeAtReads == null && reads >= 3) {
        fixture.native.selectedTabId = 'tab-other';
      }
    },
  });
  assert.equal(sourceDrift.result.ok, false);
  assert.equal(sourceDrift.state.typed, 0);
  assert.equal(sourceDrift.result.last.native.selectedTabId, 'tab-other', 'source-change failure preserves the actual sample');
  assert.ok(sourceDrift.result.missing.fields.includes('native.selectedTabId:changed'));
  assert.deepEqual(sourceDrift.state.labels, ['login-user']);

  const readFailure = await runPost({ fixtureErrorAfterTap: true });
  assert.equal(readFailure.result.ok, false);
  assert.equal(readFailure.result.reason, 'fixture-state-unreadable');
  assert.equal(readFailure.state.typed, 0);
  assert.deepEqual(readFailure.state.labels, ['login-user']);
  const lastFailure = readFailure.result.readFailures[readFailure.result.readFailures.length - 1];
  assert.equal(lastFailure.code, 'source-changed');
  assert.ok(lastFailure.commandId, 'command id is preserved');
  assert.match(lastFailure.diagnostics, /files\/local-provider-regression\/current\/diagnostics\/.+\.json$/);
  assert.ok(readFailure.result.readFailures.length <= 8);

  const imeShown = await runPost({ imeShown: true });
  assert.equal(imeShown.result.ok, true, JSON.stringify(imeShown.result).slice(0, 600));
  assert.equal(imeShown.state.pressBack, 1, 'actual IME shown -> exactly one Back');
  const imeUnknown = await runPost({ imeShown: null });
  assert.equal(imeUnknown.result.ok, true, JSON.stringify(imeUnknown.result).slice(0, 600));
  assert.equal(imeUnknown.state.pressBack, 0, 'IME shown=null never guesses Back');
});


test('a command timeout/fence after the input tap never reaches humanType or Sign in', async () => {
  const state = { posts: 0, arrivals: 0, labels: [], typed: 0, focused: false, inputValue: '' };
  let fenced = false;
  const client = {
    async sendTool(action) {
      if (action === 'browser.open') return { ok: true, data: { tabId: 'tab-login', url: matrix.FIXTURES.login } };
      return { ok: true, data: {} };
    },
    async sendDevice(text) {
      if (fenced) return { ok: false, data: null, code: 'FENCED', detail: 'case fenced after timeout' };
      if (text === 'lifecycle-diag') return { ok: true, data: { phase: 'lifecycle-diag', selectedTabId: 'tab-login', pageReady: true, grantForSelected: false, mouseMode: true, desktopMode: false, viewHandle: 7, documentEpoch: 3 } };
      if (text === 'fixture-state') return { ok: true, data: loginFixtureState(state) };
      if (text.startsWith('fixture-field:')) {
        const field = text.slice('fixture-field:'.length);
        return { ok: true, data: { phase: 'fixture-field', field, present: true, tag: field === 'login-user' ? 'INPUT' : 'BUTTON', type: field === 'login-user' ? 'text' : 'submit', value: '', valueLength: 0 } };
      }
      if (text.startsWith('tap-label:')) {
        const field = text.slice('tap-label:'.length);
        state.labels.push(field);
        if (field === 'login-user') { state.focused = true; fenced = true; }
        return { ok: true, data: { phase: 'tap-label', label: field, sent: true, hit: true, pointVisible: true, viewHandle: 7, documentEpoch: 3, sx: 1, sy: 2, frame: { x: 0, y: 0, width: 1080, height: 1920 } } };
      }
      return { ok: false, data: null, code: 'unknown-device-command', detail: text };
    },
    async readStatus() { return null; },
  };
  const guest = matrix.createGuestGateIo({
    client,
    runId: RUN_ID,
    screen: { width: 1080, height: 1920 },
    humanType: async (text) => { state.typed += 1; state.inputValue = text; return { exitCode: 0, startedAtMs: 1, finishedAtMs: 2 }; },
    pressBack: async () => true,
  });
  const result = await guest.submitLoginFresh();
  assert.equal(result.ok, false);
  assert.equal(result.stage, 'focus');
  assert.equal(state.typed, 0, 'no typing after the fence');
  assert.deepEqual(state.labels, ['login-user'], 'no Sign in after the fence');
  assert.equal(result.reason, 'fixture-state-unreadable');
  assert.ok(result.readFailures.some((item) => item.code === 'FENCED'), JSON.stringify(result.readFailures));
});

test('boot await-ui performs exactly one validated ADB tap and never a guessed coordinate', async () => {
  const pkg = 'io.andchamber.localprovider';
  const target = {
    label: 'Open',
    ownerPackage: pkg,
    windowId: 3,
    enabled: true,
    observedAtMs: Date.now(),
    left: 200,
    top: 400,
    right: 600,
    bottom: 500,
    phase: 'await-ui',
    runId: RUN_ID,
  };
  const statuses = [
    { runId: RUN_ID, phase: 'await-ui', updatedAtMs: 1, selectedSessionId: null, tapTarget: target },
    { runId: RUN_ID, phase: 'ready', updatedAtMs: 2, selectedSessionId: 'ses-1', tapTarget: null },
  ];
  let index = 0;
  const client = {
    runId: RUN_ID,
    async readStatus() { return statuses[Math.min(index++, statuses.length - 1)]; },
  };
  const taps = [];
  const adbFn = async (command) => {
    if (command[0] === 'shell' && command[1] === 'dumpsys') {
      return { code: 0, stdout: `mCurrentFocus=Window{1 u0 ${pkg}/.MainActivity}`, stderr: '' };
    }
    if (command[0] === 'shell' && command[1] === 'input' && command[2] === 'tap') {
      taps.push([Number(command[3]), Number(command[4])]);
      return { code: 0, stdout: '', stderr: '' };
    }
    return { code: 1, stdout: '', stderr: `unexpected ${command.join(' ')}` };
  };
  const boot = await runner.waitBoot({
    client,
    adbFn,
    pkg,
    screen: { width: 1080, height: 1920 },
    log: () => {},
    budgetMs: 5000,
  });
  assert.equal(boot.status.phase, 'ready');
  assert.deepEqual(taps, [[400, 450]]);
  assert.equal(boot.taps.length, 1);
  assert.deepEqual(boot.missingTargets, []);
});
