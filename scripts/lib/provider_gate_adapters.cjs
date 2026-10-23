'use strict';

// Shared, pure gate adapters for the kept local-provider verification lanes (matrix, product) and their
// offline tests. Every device call is injected through `io`; nothing here opens a socket, spawns a
// process or reads a device. The retired OpenChamber patch CLI that used to live around these helpers
// is gone; the live transport is the in-app browser-tool plugin (LocalBrowserToolClient).

const fs = require('node:fs');
const path = require('node:path');

const FOCUS_VALUES = ['geometry-gate', 'lifecycle-gate', 'retirement-gate'];
// Fixed whitelist of lifecycle case names, used only to validate --lifecycle-case. Not a plugin system.
const LIFECYCLE_CASE_NAMES = Object.freeze([
  'lifecycle-b5-enlarge-restore',
  'lifecycle-b5-user-scroll-wins',
  'lifecycle-b5-document-change',
  'lifecycle-b3-mode-mobile-to-desktop',
  'lifecycle-b3-mode-desktop-to-mobile',
  'lifecycle-b3-post-roundtrip',
]);

function parseArgs(argv) {
  const args = { serial: '', out: '', adb: path.join(process.env.HOME || '', 'Android/Sdk/platform-tools/adb') };
  for (let index = 0; index < argv.length; index += 1) {
    if (!argv[index].startsWith('--')) continue;
    args[argv[index].slice(2)] = argv[index + 1];
    index += 1;
  }
  if (!/^emulator-\d+$/.test(args.serial || '')) throw new Error('serial must match emulator-N');
  if (!args.out || !path.isAbsolute(args.out)) throw new Error('absolute out required');
  if (args.focus && !FOCUS_VALUES.includes(args.focus)) throw new Error('focus must be geometry-gate, lifecycle-gate, or retirement-gate');
  // Optional single-case filter. Only names already in the fixed lifecycle whitelist are accepted.
  if (args['lifecycle-case'] !== undefined && !LIFECYCLE_CASE_NAMES.includes(args['lifecycle-case'])) {
    throw new Error(`lifecycle-case must be one of: ${LIFECYCLE_CASE_NAMES.join(', ')}`);
  }
  return args;
}

const MENU_LABELS = {
  More: ['More', '更多'],
  'Computer layout': ['Computer layout', '电脑版'],
  Confirm: ['Confirm', '确认'],
  Mouse: ['Mouse', '鼠标'],
};

function decodeEval(status) {
  if (!status || status.phase !== 'eval') return null;
  try {
    const first = JSON.parse(status.value);
    return typeof first === 'string' ? JSON.parse(first) : first;
  } catch (_error) {
    return null;
  }
}

/** Physical CSS pixel scale, or null when either width is not a usable positive number. */
function visualScale(physicalWidth, visualWidth) {
  const width = Number(physicalWidth);
  const visual = Number(visualWidth);
  if (!(width > 0) || !(visual > 0)) return null;
  return width / visual;
}

/** Maps a page client point to the screen point the device injects at. The only screen conversion. */
function cssClientToScreen(screenLeft, screenTop, viewWidth, visualWidth, clientX, clientY, offsetLeft, offsetTop) {
  const scale = visualScale(viewWidth, visualWidth);
  if (scale == null) return null;
  return { x: screenLeft + (clientX - offsetLeft) * scale, y: screenTop + (clientY - offsetTop) * scale, scale };
}

/**
 * Device adapter for the geometry gate. Every device IO call is injected through `io`, so the Node tests
 * run this same code against a simulated device. Tool replies are awaited and normalised here: `ok` is a
 * real boolean, `code` is the real error code (or the code found in the reply text), and raw text is kept.
 */
const geometryProbe = require('./provider_geometry_gate_probe.cjs');

const GEOMETRY_CAPTURE_FILE = 'geometry-gate-capture.png';
const GEOMETRY_CASES = [
  { name: 'geometry-b1-dispatch', flow: 'runDispatchGeometry' },
  { name: 'geometry-b2a-field', flow: 'runFieldGeometry' },
  { name: 'geometry-b2b-snapshot', flow: 'runSnapshotGeometry' },
  { name: 'geometry-b2c-capture', flow: 'runCaptureGeometry', mode: 'frame' },
];
const CODE_IN_TEXT = /AUTHORIZATION_REQUIRED|REF_EXPIRED|OUTCOME_UNKNOWN|NO_PROVIDER|No Android browser provider/;

function gateResult(raw) {
  const error = raw && raw.error && typeof raw.error === 'object' ? raw.error : {};
  const redacted = (raw && raw.redacted) || '';
  const text = `${error.code || ''} ${error.message || ''} ${(raw && raw.detail) || ''} ${redacted}`;
  const found = text.match(CODE_IN_TEXT);
  return {
    ok: Boolean(raw && raw.ok === true),
    data: (raw && raw.data) || null,
    code: error.code || (raw && raw.code) || (found ? found[0] : ''),
    detail: error.message || (raw && raw.detail) || redacted,
    redacted,
  };
}

/** A refusal returned by a fenced adapter. It never reached the device, so it is not an authorization result. */
const FENCED = Object.freeze({ ok: false, code: 'FENCED', detail: 'case fenced after timeout; no new mutation' });

/**
 * Per-case fence. Once stopped, every new mutating action is refused before it reaches the device.
 * Read-only observations stay allowed so an already-sent request can be observed for its real result.
 */
function createCaseFence() {
  const state = { stopped: false, mutations: 0, blocked: 0 };
  return {
    state,
    stop() { state.stopped = true; },
    /** Runs `action` only while not stopped. A blocked call returns `fallback` and is counted, never sent. */
    guard(action, fallback = FENCED) {
      if (state.stopped) { state.blocked += 1; return Promise.resolve(fallback); }
      state.mutations += 1;
      return Promise.resolve().then(action);
    },
  };
}

/**
 * Waits read-only for the default agent control an explicit foreground open produces once its page
 * is ready. The producer's registry read is the only evidence; a tap is not, and a missing read is
 * never a pass. `diag` returns the producer grant fields, or null when unreadable.
 */
async function awaitDefaultGrant(io, diag, { budgetMs = 3000, pollMs = 200 } = {}) {
  const started = io.now();
  let state = null;
  while (io.now() - started < budgetMs) {
    state = await diag();
    if (state && state.registryGrantPresent === true) {
      return { ok: true, source: 'default-grant', tapped: false, grantPresent: true, detail: 'default-grant', state };
    }
    await io.sleepMs(pollMs);
  }
  if (!state || typeof state.registryGrantPresent !== 'boolean') {
    return { ok: false, source: 'grant-unreadable', tapped: false, grantPresent: null, detail: 'grant-unreadable', state };
  }
  return { ok: false, source: 'no-default-grant', tapped: false, grantPresent: false, detail: 'no-default-grant', state };
}

/**
 * The person's explicit hand-back, only after a real manual takeover. The tap is sent at most once
 * and the registry read afterwards is still the only proof. A page that never got default control
 * is not a takeover, so this refuses instead of papering over that defect.
 */
async function handBackAfterTakeover(io, diag, state, tap, { budgetMs = 3000, pollMs = 200 } = {}) {
  if (!state || state.mode !== 'MANUAL' || state.agentOn === true) {
    return { ok: false, source: 'manual-takeover-not-proven', tapped: false, grantPresent: false, detail: 'manual-takeover-not-proven', state };
  }
  let tapped = false;
  try {
    tapped = (await tap()) === true;
  } catch (_error) {
    tapped = false;
  }
  const deadline = io.now() + budgetMs;
  let after = null;
  while (io.now() < deadline) {
    after = await diag();
    if (after && after.registryGrantPresent === true) break;
    await io.sleepMs(pollMs);
  }
  const granted = Boolean(after && after.registryGrantPresent === true);
  return {
    ok: granted,
    source: tapped ? 'manual-handback' : 'manual-handback-missing',
    tapped,
    grantPresent: granted,
    detail: granted ? 'manual-handback-granted' : tapped ? 'tapped-without-grant' : 'handback-control-missing',
    state: after || state,
  };
}

function createGeometryGateAdapters(io, fence = null) {
  const last = { capture: null };
  // Mutating actions go through the fence when one is present. Reads never do.
  const mut = (action, fallback) => (fence ? fence.guard(action, fallback) : Promise.resolve().then(action));
  const toolCall = async (action, input) => gateResult(await io.call(action, input));
  const toolMut = (action, input) => mut(() => toolCall(action, input));
  const diag = async () => {
    const value = await io.readDiagRaw();
    return value && typeof value === 'object' ? value : null;
  };
  const adapters = {
    setMarker: (name) => mut(async () => (await io.deviceCmd(`marker:${name}`, 'marker-set')) === true, false),
    // Release is the cleanup path: it must run even after a fence stop, so it bypasses the guard.
    releaseMarker: async (name) => (await io.deviceCmd(`marker-clear:${name}`, 'marker-cleared')) === true,
    readStatus: (phase) => io.readStatus(phase),
    readDiag: diag,
    dragTitle: () => mut(async () => ({ moved: (await io.dragTitleRaw()) === true }), { moved: false }),
    type: (selector, value) => toolMut('browser.type', { selector, value }),
    snapshotOnce: () => toolCall('browser.snapshot', {}),
    snapshotFresh: () => toolCall('browser.snapshot', {}),
    useOldRef: (selector) => toolMut('browser.click', { selector }),
    // Default control is read from the registry; a tap is never the proof. The explicit hand-back
    // (person's own control action) is opt-in and only after a real manual takeover.
    allow: (options = {}) => mut(async () => {
      const granted = await awaitDefaultGrant(io, diag, options);
      if (granted.ok || options.handback !== true) return granted;
      return handBackAfterTakeover(io, diag, granted.state, () => io.tapAllow(), options);
    }, { ok: false, source: 'fenced', tapped: false, grantPresent: false, detail: FENCED.code }),
    openFresh: (url) => toolMut('browser.open', { url }),
    readInput: async () => {
      const value = await io.pageEvalRaw('JSON.stringify(document.querySelector("input,textarea")&&document.querySelector("input,textarea").value)');
      return typeof value === 'string' ? value : null;
    },
    capture: () => mut(async () => {
      await io.removeFile(GEOMETRY_CAPTURE_FILE);
      const captured = await toolCall('browser.capture', { label: 'geometry-gate' });
      last.capture = captured;
      const relative = captured.ok && captured.data && captured.data.path ? captured.data.path : null;
      // Only an accepted capture writes a PNG. A refused late capture must leave no file behind.
      if (relative) await io.saveCapture(relative, GEOMETRY_CAPTURE_FILE);
      return captured;
    }, FENCED),
    readCapture: () => io.fileExists(GEOMETRY_CAPTURE_FILE),
    offsetFrame: () => mut(async () => {
      const phased = (await io.deviceCmd('frame-offset', 'frame-offset')) === true;
      const status = phased ? await io.readStatus('frame-offset') : null;
      return { moved: Boolean(status && status.applied === true) };
    }, { moved: false }),
    // Frame restore is cleanup for an offset that already happened: it runs even after a fence stop.
    restoreFrame: async () => (await io.deviceCmd('frame-restore', 'frame-restored')) === true,
    now: io.now,
    sleep: io.sleepMs,
  };
  return { adapters, gateResult, lastCapture: () => last.capture };
}

const GEOMETRY_READY_MS = 15000;
const GEOMETRY_PROBE_MS = 20000;

/**
 * Read-only ready check for the selected page: no tap, no mutation. Requires the fresh tab to be the selected
 * one, owned by the session, the host connected and eligible, pageReady, and the fixture loaded.
 * Returns the last read state (with a timeout reason) when the deadline passes.
 */
function geometryPageReady(diag, expectedTabId, expectedSessionId) {
  if (!diag || typeof diag !== 'object') return { ready: false, reason: 'diag-missing' };
  if (diag.selectedTabId == null || diag.selectedTabId !== expectedTabId) return { ready: false, reason: 'not-selected-tab' };
  if (diag.currentOwner !== expectedSessionId) return { ready: false, reason: 'owner-mismatch' };
  if (diag.connected !== true || diag.eligible !== true || diag.hostAttached !== true) return { ready: false, reason: 'host-not-ready' };
  // pageReady is the presenter's own ready flag for the selected page (the fixture document has loaded).
  if (diag.pageReady !== true) return { ready: false, reason: 'page-not-ready' };
  return { ready: true, reason: 'ready' };
}

/** Each case starts from a fresh open, a bounded read-only ready wait, one real Allow and a real snapshot. */
async function prepareGeometryCase(adapters, url, { expectedSessionId, now = Date.now, sleepMs = sleep } = {}) {
  const opened = await adapters.openFresh(url);
  if (!opened.ok) return { ok: false, reason: 'open-failed', opened };
  const tabId = opened.data && opened.data.tabId;
  if (!tabId) return { ok: false, reason: 'open-no-tab', opened };
  // Bounded wait: nothing is tapped and nothing is mutated while waiting.
  const deadline = now() + GEOMETRY_READY_MS;
  let state = null;
  let readyNow = false;
  while (now() < deadline) {
    state = await adapters.readDiag();
    const verdict = geometryPageReady(state, tabId, expectedSessionId);
    if (verdict.ready) { readyNow = true; break; }
    await sleepMs(250);
  }
  if (!readyNow) return { ok: false, reason: 'page-ready-timeout', tabId, state: state && { pageReady: state.pageReady, selectedTabId: state.selectedTabId, connected: state.connected, eligible: state.eligible } };
  // Exactly one Allow after readiness. A failed tap is reported, never retried in a loop.
  const allowed = await adapters.allow();
  if (!allowed.ok) return { ok: false, reason: 'allow-not-granted', allowed };
  const snap = await adapters.snapshotOnce();
  if (!snap.ok) return { ok: false, reason: 'snapshot-failed', snap };
  const elements = snap.data && Array.isArray(snap.data.elements) ? snap.data.elements : [];
  const note = elements.find((item) => item.name === 'note' && item.selector);
  if (!note) return { ok: false, reason: 'note-ref-missing', snap };
  return { ok: true, selector: note.selector, fingerprint: geometryProbe.refFingerprint(snap), allowed };
}

/** Thrown when a case cannot be settled after its fence. The whole focus stops here: no next case runs. */
class GeometryGateAbort extends Error {
  constructor(name, detail) {
    super(`geometry-gate aborted at ${name}: ${detail}`);
    this.name = 'GeometryGateAbort';
  }
}

const SETTLE_GRACE_MS = 5000;

/**
 * Lifecycle adapter. Every device call is injected via `io`, so Node tests run this same code against a
 * simulated device. Mutating actions go through the case fence; reads and cleanup do not.
 * lifecycle-diag is produced by another lane. When it is absent the reply is UNSUPPORTED and the case
 * refuses as a precondition failure, never as a pass.
 */
const LIFECYCLE_UNSUPPORTED = Object.freeze({ ok: false, code: 'UNSUPPORTED', detail: 'lifecycle-diag not produced' });

/** True only for a finite number. Used to refuse missing or non-numeric coordinates and counts. */
function isNum(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

/** Only a real value passes through. Anything absent or of the wrong type becomes null, never a default. */
function realOr(value, type) {
  if (value === undefined || value === null) return null;
  if (type === 'number') return typeof value === 'number' && Number.isFinite(value) ? value : null;
  if (type === 'string') return typeof value === 'string' && value.length > 0 ? value : null;
  if (type === 'boolean') return typeof value === 'boolean' ? value : null;
  return value;
}

/**
 * Merges the real sources into one lifecycle view. The producer (lifecycle-diag) supplies state, restore and
 * events. fixture-state supplies the page's own posts/loads/url and the native frame. window.__report() supplies
 * the JS metrics. Enlarged comes from the live UI (the Small window button is shown only while enlarged).
 * No source is invented: a missing source leaves its fields null, and a null field can never pass a case.
 */
function lifecycleNormalise(raw, { fixture = null, metrics = null, uiEnlarged = null, surface = null } = {}) {
  if (!raw || typeof raw !== 'object' || raw.phase !== 'lifecycle-diag') return null;
  const restore = raw.restore && typeof raw.restore === 'object' && typeof raw.restore.kind === 'string'
    ? { kind: raw.restore.kind, reason: realOr(raw.restore.reason, 'string'), actual: raw.restore.actualX != null ? { x: realOr(raw.restore.actualX, 'number'), y: realOr(raw.restore.actualY, 'number') } : null }
    : null;
  const page = fixture && fixture.page && typeof fixture.page === 'object' ? fixture.page : null;
  const native = fixture && fixture.native && typeof fixture.native === 'object' ? fixture.native : null;
  const m = metrics && typeof metrics === 'object' ? metrics : null;
  return {
    selectedTabId: realOr(raw.selectedTabId, 'string'),
    tabIds: Array.isArray(raw.tabIds) ? raw.tabIds : null,
    pageRetired: realOr(raw.pageRetired, 'boolean'),
    agentOn: realOr(raw.agentOn, 'boolean'),
    grantForSelected: realOr(raw.grantForSelected, 'boolean'),
    mousePressActive: realOr(raw.mousePressActive, 'boolean'),
    mode: realOr(raw.mode, 'string'),
    desktopMode: realOr(raw.desktopMode, 'boolean'),
    mouseMode: realOr(raw.mouseMode, 'boolean'),
    pageReady: realOr(raw.pageReady, 'boolean'),
    viewHandle: realOr(raw.viewHandle, 'number'),
    documentEpoch: realOr(raw.documentEpoch, 'number'),
    // Per-document identity from the page itself. Global documentEpoch is only diagnostic.
    timeOrigin: m ? realOr(m.timeOrigin, 'number') : null,
    witness: m ? realOr(m.witness, 'string') : null,
    // Page-level counters: fixture-state (real page globals and arrivals). Not a default.
    url: page ? realOr(page.url, 'string') : null,
    loads: page ? realOr(page.loads, 'number') : null,
    posts: page ? realOr(page.posts, 'number') : null,
    postArrivals: fixture ? realOr(fixture.postArrivals, 'number') : null,
    // Enlarged is read from the live UI: the Small window button is visible only while the window is enlarged.
    enlarged: uiEnlarged,
    surface: surface && typeof surface === 'object' ? surface : null,
    frame: native && native.physicalFrame ? native.physicalFrame : null,
    metrics: m,
    restore,
    // Real producer recorder fields (LifecycleGateCommands.diag). Kept as typed values, never defaulted.
    recorderReady: realOr(raw.recorderReady, 'boolean'),
    totalClicks: realOr(raw.totalClicks, 'number'),
    // modeSeq is not part of the producer contract. It is kept null on purpose and never used as proof.
    modeSeq: null,
    // B5 target observation (test-only, read-only, explains a failure). Never read by any pass predicate.
    restoreTarget: restoreTargetMetadata(raw.restoreTarget),
  };
}

/**
 * Passes the producer's read-only B5 observation through unchanged in shape. Numbers and booleans keep their value,
 * the fixed unknown code stays a string, and any missing or non-finite field becomes null. Nothing is defaulted to 0
 * or false here. This object is diagnosis only; no pass predicate reads it.
 */
function restoreTargetMetadata(value) {
  if (!value || typeof value !== 'object') return null;
  const out = {};
  for (const [key, field] of Object.entries(value)) {
    if (typeof field === 'boolean') out[key] = field;
    else if (typeof field === 'number') out[key] = Number.isFinite(field) ? field : null;
    // Strings are only the fixed unknown code. Any other text is dropped, never passed through.
    else if (typeof field === 'string') out[key] = field === 'diagnosticUnknown' ? field : null;
    else out[key] = null;
  }
  return out;
}

function createLifecycleGateAdapters(io, fence = null) {
  const mut = (action, fallback) => (fence ? fence.guard(action, fallback) : Promise.resolve().then(action));
  // The last complete read, copied, with its time. A timeout reports it instead of guessing the page state.
  let lastComplete = null;
  const lastRead = () => (lastComplete ? { at: lastComplete.at, state: JSON.parse(JSON.stringify(lastComplete.state)) } : null);
  // One read merges the real sources. Each source is read once per call and a missing one stays null.
  const readLifecycle = async () => {
    const raw = await io.readLifecycleRaw();
    if (!raw) return null;
    // The device commands share one command/status file and must not overwrite each other.
    const fixture = await io.readFixtureRaw();
    const metrics = await io.readMetricsRaw();
    const ui = await io.readUiRaw();
    const state = lifecycleNormalise(raw, { fixture, metrics, uiEnlarged: ui ? ui.enlarged : null, surface: ui ? ui.surface : null });
    if (state) lastComplete = { at: io.now ? io.now() : null, state };
    return state;
  };
  const toolCall = async (action, input) => gateResult(await io.call(action, input));
  const toolMut = (action, input) => mut(() => toolCall(action, input));
  return {
    readLifecycle,
    lastRead,

    scrollWheel: ({ dy }) => mut(async () => ({ ok: (await io.wheelRaw(dy)) === true }), { ok: false, code: FENCED.code }),
    twoFingerScroll: ({ dy }) => mut(async () => ({ ok: (await io.twoFingerRaw(dy)) === true }), { ok: false, code: FENCED.code }),
    tapEnlarge: () => mut(async () => ({ ok: (await io.tapLabelRaw('Full screen')) === true }), { ok: false, code: FENCED.code }),
    tapRestore: () => mut(async () => ({ ok: (await io.tapLabelRaw('Small window')) === true }), { ok: false, code: FENCED.code }),
    switchMode: (target) => mut(async () => ({ ok: (await io.switchModeRaw(target)) === true }), { ok: false, code: FENCED.code }),
    // The reply's tabId lives under data; the probe reads a top-level tabId, so it is lifted here once.
    // A fresh open of a fixture (setup). It is a real browser.open, fenced like any other mutation.
    // The initial viewport is part of the open request itself (not a menu click after the fact). Without a declared
    // viewport the request carries none, so no state is inherited silently.
    openFresh: async (url, viewport) => {
      const input = viewport ? { url, viewport } : { url };
      const opened = await toolMut('browser.open', input);
      return { ...opened, tabId: opened.data && opened.data.tabId ? opened.data.tabId : null };
    },
    openOtherTab: async () => {
      const opened = await toolMut('browser.open', { url: io.otherUrl });
      return { ...opened, tabId: opened.data && opened.data.tabId ? opened.data.tabId : null };
    },
    openNewDocument: async () => {
      const opened = await toolMut('browser.open', { url: io.newDocumentUrl });
      return { ...opened, tabId: opened.data && opened.data.tabId ? opened.data.tabId : null };
    },
    // Lifecycle grant read: the same registry fact, taken from the lifecycle diag field. A UI tap is
    // never the proof; the explicit hand-back is opt-in and only after a proven manual takeover.
    allow: (options = {}) => mut(async () => {
      const readGrant = async () => {
        const raw = await io.readLifecycleRaw();
        if (!raw || typeof raw !== 'object') return null;
        return {
          phase: raw.phase,
          mode: realOr(raw.mode, 'string'),
          agentOn: realOr(raw.agentOn, 'boolean'),
          registryGrantPresent: realOr(raw.grantForSelected, 'boolean'),
        };
      };
      const granted = await awaitDefaultGrant(io, readGrant, options);
      if (granted.ok || options.handback !== true) return granted;
      return handBackAfterTakeover(io, readGrant, granted.state, () => io.tapLabelRaw('Resume agent'), options);
    }, { ok: false, source: 'fenced', tapped: false, grantPresent: false, detail: FENCED.code }),
    selectTab: (tabId) => mut(async () => ({ ok: (await io.selectRaw(tabId)) === true }), { ok: false, code: FENCED.code }),
    submitLoginPost: () => mut(async () => ({ ok: (await io.submitLoginRaw()) === true }), { ok: false, code: FENCED.code }),
    // Cleanup release and status reads are never fenced.
    releaseOwned: async () => io.releaseOwnedRaw(),
  };
}

/**
 * B4 retirement adapter. Each call reads a real source or sends one real command. Mutating calls go through the
 * case fence; reads do not. Missing values are null, never 0 or false, so they cannot pass a step.
 */
function createRetirementGateAdapters(io, fence = null) {
  const mut = (action, fallback) => (fence ? fence.guard(action, fallback) : Promise.resolve().then(action));
  const canSend = () => !fence || !fence.state.stopped;
  let measuredSource = null;
  let originalRecord = null;
  const sourceStillCurrent = async () => {
    if (!measuredSource) return false;
    const raw = await io.readLifecycleRaw();
    const record = raw && currentRecordOf(raw);
    const frame = await io.readFrameRaw();
    return raw?.viewHandle === measuredSource.viewHandle && record &&
      frame?.viewHandle === measuredSource.viewHandle && JSON.stringify(frame.physical) === JSON.stringify(measuredSource.frame.display || measuredSource.frame) &&
      record.viewId === measuredSource.record.viewId && String(record.origin) === String(measuredSource.origin);
  };
  // Reads are sequential: the device commands share one command/status file, so parallel reads overwrite each other.
  const readState = async () => {
    const raw = await io.readLifecycleRaw();
    if (!raw || raw.phase !== 'lifecycle-diag') return null;
    const native = await io.readNativeViewRaw();
    const page = await io.readPageRaw();
    if (!originalRecord) originalRecord = currentRecordOf(raw);
    return {
      selectedTabId: realOr(raw.selectedTabId, 'string'),
      pageRetired: realOr(raw.pageRetired, 'boolean'),
      // Native truth: null identity means no current WebView. A surface placeholder does not count.
      currentViewPresent: native ? native.currentViewPresent : null,
      pageReady: realOr(raw.pageReady, 'boolean'),
      agentOn: realOr(raw.agentOn, 'boolean'),
      grantForSelected: realOr(raw.grantForSelected, 'boolean'),
      mousePressActive: realOr(raw.mousePressActive, 'boolean'),
      mode: realOr(raw.mode, 'string'),
      mouseMode: realOr(raw.mouseMode, 'boolean'),
      viewHandle: realOr(raw.viewHandle, 'number'),
      recorderReady: realOr(raw.recorderReady, 'boolean'),
      recorder: {
        current: currentRecordOf(raw),
        lastRetired: lastRetiredRecordOf(raw, originalRecord),
      },
      page,
    };
  };
  return {
    readState,
    readPage: async () => {
      const page = await io.readPageRaw();
      return page && typeof page === 'object' ? page : null;
    },
    // Streaming producer accepts WebView-local physical coordinates and adds screen origin itself.
    finger: (spec) => mut(async () => ({ ok: await sourceStillCurrent() && canSend() && (await io.fingerRaw(spec)) === true }), { ok: false, code: FENCED.code }),
    readTarget: async (id) => { const aim = await io.readTargetRaw(id); if (aim) measuredSource = aim; return aim; },
    atomicFinger: (spec) => mut(async () => ({ ok: (await io.atomicFingerRaw(spec, canSend)) === true }), { ok: false, code: FENCED.code }),
    enableMouse: () => mut(async () => ({ ok: (await io.enableMouseRaw(canSend)) === true }), { ok: false, code: FENCED.code }),
    // Fenced: a button-free native event after a timeout never reaches the device.
    nativeGate: (spec) => mut(() => io.nativeGateRaw(spec, canSend), { sent: false, code: FENCED.code }),
    agentSnapshot: () => mut(() => io.agentSnapshotRaw(), { ok: false, code: FENCED.code }),
    allowAttempt: () => mut(() => io.allowAttemptRaw(), { granted: null, code: FENCED.code }),
    readOldCookie: async () => io.readCookieRaw(),
    // One unique test cookie, written only on the old page before any press. The new page never writes it.
    writeTestCookie: (value) => mut(async () => ({ ok: (await io.writeCookieRaw(value)) === true }), { ok: false, code: FENCED.code }),
    tapReopen: () => mut(async () => ({ ok: (await io.tapLabelRaw('Reopen')) === true }), { ok: false, code: FENCED.code }),
    sleep: io.sleepMs,
    now: io.now,
    releaseOwned: async () => true,
  };
}

/**
 * Producer shape (LifecycleGateCommands.diag): recordedDocuments = { "<viewId>": [{origin, installed, clicks,
 * current}] }. The current record is the entry marked current in the view that is live now. It is read
 * from the producer, never estimated. A missing record is null, never 0.
 */
function currentRecordOf(raw) {
  const docs = raw.recordedDocuments && typeof raw.recordedDocuments === 'object' ? raw.recordedDocuments : null;
  if (!docs || raw.recorderReady !== true) return null;
  if (raw.recorderViewId == null) return null;
  const list = docs[String(raw.recorderViewId)];
  if (!Array.isArray(list)) return null;
  const current = list.filter((item) => item && item.current === true && item.installed === true);
  if (current.length !== 1 || !isNum(current[0].clicks) || current[0].origin == null) return null;
  return { clicks: current[0].clicks, origin: current[0].origin, viewId: raw.recorderViewId };
}

/**
 * The retired view keeps its record: lastRetired.documents lists every installed document of that view. The
 * retired document must match the captured old view+origin. Without an expected identity, only a single
 * installed document is unambiguous; missing or ambiguous records are null, not invented zeroes.
 */
function lastRetiredRecordOf(raw, expected = null) {
  const last = raw.lastRetired && typeof raw.lastRetired === 'object' ? raw.lastRetired : null;
  const list = last && Array.isArray(last.documents) ? last.documents : null;
  if (expected && last?.viewId !== expected.viewId) return null;
  const installed = list?.filter(item => item?.installed === true &&
    (!expected || String(item.origin) === String(expected.origin))) || [];
  const doc = installed.length === 1 ? installed[0] : null;
  if (!doc || doc.installed !== true || typeof doc.clicks !== 'number') return null;
  return { clicks: doc.clicks, origin: doc.origin, viewId: last.viewId, readyAtRetire: last.readyAtRetire === true };
}

/** Click count of the retired view's last installed document, or null. Kept for the exported contract. */
function lastRetiredClicksOf(raw) {
  const record = lastRetiredRecordOf(raw);
  return record ? record.clicks : null;
}

const RETIREMENT_CASES = [
  { name: 'retirement-a-primary-drag-lock', flow: 'runPrimaryDragLock' },
  { name: 'retirement-b-true-cancel-reopen', flow: 'runTrueCancelRetire' },
];

/** Retirement-only UI caller. All plans are local physical px; only atomic finger uses screen px. */
function createRetirementPointerIO(transport) {
  const inside = (p, f) => p && f && isNum(p.x) && isNum(p.y) && p.x >= 0 && p.y >= 0 && p.x < f.width && p.y < f.height;
  const sameFrame = (a, b) => a && b && a.viewHandle === b.viewHandle && a.documentEpoch === b.documentEpoch &&
    JSON.stringify(a.physical) === JSON.stringify(b.physical) && JSON.stringify(a.page?.visualViewport) === JSON.stringify(b.page?.visualViewport);
  const sameIdentity = (a, b) => a && b && a.currentIdentity != null && a.currentIdentity === b.currentIdentity &&
    a.selectedTabId === b.selectedTabId && a.recorderViewId === b.recorderViewId && a.viewHandle === b.viewHandle;
  return {
    readFrameRaw: () => transport.frame(),
    readTargetRaw: async (id) => {
      if (!['box', 'next'].includes(id)) return null;
      const first = await transport.identity();
      const rect = await transport.frame();
      const f = rect?.physical, v = rect?.page?.visualViewport, p = rect?.pointerUi;
      if (!f || ![f.left, f.top, f.width, f.height].every(isNum) || f.width <= 1 || f.height <= 1 ||
          !v || ![v.width, v.height, v.offsetLeft, v.offsetTop].every(isNum) || v.width <= 0 || v.height <= 0 ||
          !p || p.geometryReady !== true || p.viewHandle !== rect.viewHandle) return null;
      // physical is the scaled display rectangle. Agent pointer coordinates stay in raw WebView px.
      const rawWidth = isNum(rect.viewWidth) && rect.viewWidth > 0 ? rect.viewWidth : f.width;
      const rawHeight = isNum(rect.viewHeight) && rect.viewHeight > 0 ? rect.viewHeight : f.height;
      if (!inside({ x:p.pointerViewX, y:p.pointerViewY }, { left:0, top:0, width:rawWidth, height:rawHeight })) return null;
      const rawToCss = rawWidth / v.width;
      const dom = await transport.dom(id, { x:v.offsetLeft + p.pointerViewX / rawToCss, y:v.offsetTop + p.pointerViewY / rawToCss });
      const afterFrame = await transport.frame();
      const after = await transport.identity();
      const record = currentRecordOf(after);
      if (!sameIdentity(first, after) || !sameFrame(rect, afterFrame) || !record || !dom ||
          after.currentIdentity !== rect.viewHandle || after.viewHandle !== rect.viewHandle ||
          String(record.origin) !== String(dom.timeOrigin) || !dom.box ||
          ![dom.box.left,dom.box.top,dom.box.right,dom.box.bottom].every(isNum)) return null;
      const left = Math.max(v.offsetLeft, dom.box.left), right = Math.min(v.offsetLeft + v.width, dom.box.right);
      const top = Math.max(v.offsetTop, dom.box.top), bottom = Math.min(v.offsetTop + v.height, dom.box.bottom);
      if (right <= left || bottom <= top) return null;
      const target = cssClientToScreen(0, 0, rawWidth, v.width, (left+right)/2, (top+bottom)/2, v.offsetLeft, v.offsetTop);
      const frame = rawWidth === f.width && rawHeight === f.height ? f
        : { left:0, top:0, width:rawWidth, height:rawHeight, display:f };
      if (!inside(target, frame)) return null;
      return { target, frame, pointer:{x:p.pointerViewX,y:p.pointerViewY}, hit:dom.hit,
        viewHandle:rect.viewHandle, origin:dom.timeOrigin, record, page:dom.page };
    },
    atomicFingerRaw: async (spec, canSend = () => true) => {
      const identity = await transport.identity();
      const record = identity && currentRecordOf(identity);
      const rect = await transport.frame(), f = rect?.physical;
      if (!f || rect.viewHandle !== spec.viewHandle || !isNum(spec.dx) || !isNum(spec.dy) ||
          identity?.currentIdentity !== spec.viewHandle || !record || String(record.origin) !== String(spec.origin) ||
          JSON.stringify(f) !== JSON.stringify(spec.frame?.display || spec.frame) || !Number.isInteger(spec.steps) || spec.steps < 1 || spec.steps > 4) return false;
      // spec.x/spec.y are raw WebView-local pixels; the frame is the displayed, scaled rectangle.
      const rawWidth = isNum(rect.viewWidth) && rect.viewWidth > 0 ? rect.viewWidth : f.width;
      const rawHeight = isNum(rect.viewHeight) && rect.viewHeight > 0 ? rect.viewHeight : f.height;
      const raw = { left:0, top:0, width:rawWidth, height:rawHeight };
      if (!inside(spec, raw) || !inside({x:spec.x+spec.dx,y:spec.y+spec.dy}, raw)) return false;
      const displayX = f.width / rawWidth;
      const displayY = f.height / rawHeight;
      // The ONLY screen conversion. gate-finger/native use local coordinates unchanged.
      if (!canSend()) return false;
      return transport.atomic({ ...spec, frame:f,
        x:Math.round(f.left+spec.x*displayX), y:Math.round(f.top+spec.y*displayY),
        dx:Math.round(spec.dx*displayX), dy:Math.round(spec.dy*displayY) });
    },
  };
}

/**
 * The one shared primitive that makes native Mouse mode on. Idempotent:
 *  - reads the real native mouseMode first. When it is already true, it returns true and taps nothing. The Window
 *    menu then offers only "Touch" (the "Mouse" item does not exist), so a tap here would fail.
 *  - when it is false, it taps More -> Mouse exactly once, then reads native mouseMode back with a bounded wait.
 *  - an unreadable native state returns false. Nothing is assumed, and nothing is tapped blind.
 * A tap is never the proof: only the native read afterwards counts.
 * Device primitives are injected through `io`; a caller without a device must not use this.
 */
async function switchMouseOn(_args, io = null) {
  if (!io || typeof io.readMouse !== 'function' || typeof io.tap !== 'function') {
    throw new Error('switchMouseOn requires an injected device io');
  }
  const read = io.readMouse;
  const tap = io.tap;
  const wait = io.wait;
  const now = io.now;
  const before = await read();
  if (before === null) return false;
  if (before === true) return true;
  const more = await tap('More');
  if (!more) return false;
  await wait(800);
  const mouse = await tap('Mouse');
  if (mouse !== true) return false;
  const deadline = now() + 8000;
  while (now() < deadline) {
    if ((await read()) === true) return true;
    await wait(300);
  }
  return false;
}



/** Normalises a snapshot reply to the code the probe checks. A transport error is not an authorization result. */
function gateSnapshotResult(raw) {
  return gateResult(raw);
}

/**
 * Runs one geometry case under a per-case fence.
 *  - Normal completion: the fence is never stopped; the case result is taken as-is.
 *  - Deadline or throw: stop the fence first (no new mutation can reach the device), release only this
 *    case's own hold, then wait a bounded grace for the probe to settle. A settled late result is recorded
 *    read-only once. Nothing is retried, nothing is undone, and a late result is never a success.
 *  - Still unsettled after the grace: throw GeometryGateAbort so the caller stops the whole focus.
 */
async function runOneGeometryCase(item, { deps, fence, adapters, probeDeadlineMs, save, now }) {
  const owned = { runDispatchGeometry: 'dhold', runFieldGeometry: 'fhold', runSnapshotGeometry: 'shold', runCaptureGeometry: 'chold' }[item.flow];
  const probeRun = Promise.resolve().then(() => geometryProbe[item.flow](deps));
  let timer;
  const deadline = new Promise((resolve) => { timer = setTimeout(() => resolve(TIMED_OUT), probeDeadlineMs); });
  let outcome;
  try {
    outcome = await Promise.race([probeRun.then((flow) => ({ flow })), deadline]);
  } catch (error) {
    outcome = { flow: { ok: false, reason: 'probe-threw', error: String(error && error.message || error).slice(0, 200) } };
  } finally {
    clearTimeout(timer);
  }
  if (outcome !== TIMED_OUT) {
    // Normal path: the probe settled inside its deadline. No fence stop, no grace.
    await adapters.releaseMarker(owned);
    return outcome.flow;
  }
  // Timed out or thrown mid-run: fence first, so no later step of the old probe mutates anything.
  fence.stop();
  await adapters.releaseMarker(owned);
  const settled = await Promise.race([
    probeRun.then((late) => ({ late }), (error) => ({ late: { ok: false, reason: 'probe-threw-late', error: String(error && error.message || error).slice(0, 200) } })),
    new Promise((resolve) => setTimeout(() => resolve(TIMED_OUT), SETTLE_GRACE_MS)),
  ]);
  if (settled === TIMED_OUT) {
    save(`${item.name}-pending-failed.json`, { ok: false, reason: 'probe-unsettled-after-fence', terminal: 'pending-failed', deadlineMs: probeDeadlineMs, graceMs: SETTLE_GRACE_MS, blockedAfterFence: fence.state.blocked, mutationsSent: fence.state.mutations, now: now() });
    throw new GeometryGateAbort(item.name, 'probe did not settle after fence');
  }
  // Late result: read-only, saved once, never promoted to a case success.
  return { ok: false, reason: 'probe-deadline', terminal: 'client-timeout-no-retry', deadlineMs: probeDeadlineMs, late: geometryProbe.brief(settled.late), mutationsSent: fence.state.mutations, blockedAfterFence: fence.state.blocked };
}

const TIMED_OUT = Symbol('timed-out');

/** Runs the four geometry cases. Each case has its own fence; a timed-out case stops the focus. */
async function runGeometryGateCases(io, { url, save, check, cases = GEOMETRY_CASES, expectedSessionId = null, probeDeadlineMs = GEOMETRY_PROBE_MS }) {
  const flows = {};
  for (const item of cases) {
    const fence = createCaseFence();
    const { adapters, lastCapture } = createGeometryGateAdapters(io, fence);
    const setup = await prepareGeometryCase(adapters, url, { expectedSessionId, now: io.now, sleepMs: io.sleepMs });
    if (!setup.ok) {
      save(`${item.name}-prepare.json`, setup);
      check(`${item.name}-prepare`, setup);
      flows[item.name] = setup;
      continue;
    }
    const deps = {
      ...adapters,
      type: (value) => adapters.type(setup.selector, value),
      useOldRef: () => adapters.useOldRef(setup.selector),
      openFresh: () => adapters.openFresh(url),
      allow: () => adapters.allow(),
      mode: item.mode,
    };
    const flow = await runOneGeometryCase(item, { deps, fence, adapters, probeDeadlineMs, save, now: io.now });
    save(`${item.name}.json`, flow);
    check(item.name, flow);
    flows[item.name] = flow;
    if (item.flow === 'runCaptureGeometry') save(`${item.name}-response.json`, lastCapture());
    // A timed-out case has already fenced itself. The next case may start only after a settled, non-timeout result.
    if (flow.terminal === 'client-timeout-no-retry') break;
  }
  return flows;
}

const LIFECYCLE_CASES = [
  { name: 'lifecycle-b5-enlarge-restore', flow: 'runEnlargeRestoreSameDoc', setup: 'longPageScrolled', viewport: 'desktop' },
  { name: 'lifecycle-b5-user-scroll-wins', flow: 'runUserScrollWins', setup: 'longPageScrolled', viewport: 'desktop' },
  { name: 'lifecycle-b5-document-change', flow: 'runDocumentChangeDiscards', setup: 'longPageScrolled', viewport: 'desktop' },
  { name: 'lifecycle-b3-mode-mobile-to-desktop', flow: 'runTabRoundTripMode', setup: 'mobileFixture', viewport: 'mobile', direction: 'computer' },
  { name: 'lifecycle-b3-mode-desktop-to-mobile', flow: 'runTabRoundTripMode', setup: 'desktopFixture', viewport: 'desktop', direction: 'phone' },
  { name: 'lifecycle-b3-post-roundtrip', flow: 'runPostRoundTripMode', setup: 'postFixture', viewport: 'mobile' },
];


/** Read-only wait until the fresh page is the selected tab, owned by the session, ready, and frame-stable. */
async function waitLifecycleReady(adapters, { expectedTabId, expectedSessionId, now, sleepMs, budgetMs = 15000 }) {
  const deadline = now() + budgetMs;
  let last = null;
  let previous = null;
  let stableCount = 0;
  while (now() < deadline) {
    last = await adapters.readLifecycle();
    const frame = last && last.frame ? JSON.stringify(last.frame) : null;
    const ok = last && last.selectedTabId === expectedTabId && last.pageReady === true &&
      last.grantForSelected !== null && last.url && last.url.length > 0 && frame !== null;
    stableCount = ok && frame === previous ? stableCount + 1 : 0;
    previous = frame;
    if (ok && stableCount >= 2) return { ready: true, state: last, owner: expectedSessionId };
    await sleepMs(300);
  }
  return { ready: false, state: last };
}

/**
 * Makes native Mouse mode on for a case, without repeating a tap that is already in effect.
 *  - native mouseMode true: no tap, success (the state the case needs is already there).
 *  - native mouseMode false: one real More -> Mouse, then a read-only wait for native mouseMode true.
 *  - anything else (null, unreadable): refused. Nothing is assumed.
 * A tap alone is never the proof: only the native state read afterwards counts.
 */
async function ensureNativeMouseOn(io, state) {
  if (!state || typeof state.mouseMode !== 'boolean') return { ok: false, reason: 'mouse-state-unreadable', mouseMode: state ? state.mouseMode ?? null : null };
  if (state.mouseMode === true) return { ok: true, tapped: false, mouseMode: true };
  const tapped = await io.enableMouseRaw();
  if (!tapped) return { ok: false, reason: 'mouse-tap-failed', tapped: false };
  const deadline = io.now() + 8000;
  while (io.now() < deadline) {
    const raw = await io.readLifecycleRaw();
    if (raw && raw.mouseMode === true) return { ok: true, tapped: true, mouseMode: true };
    await io.sleepMs(300);
  }
  return { ok: false, reason: 'mouse-not-observed-native', tapped: true };
}

/**
 * Real per-case setup. Opens a fresh fixture, waits read-only for readiness, and only then acts:
 *  - desktop cases: one real More -> Computer layout / Phone layout -> Confirm (explicit user action, once).
 *  - long-page cases: one real two-finger scroll to a non-zero offset, read back from the device. A zero
 *    start is not a failure here; the scroll must produce the non-zero value.
 *  - POST case: a fresh humanSubmit that returns its own baseline (before posts/arrivals) and landing.
 * Returns the baseline the flow relies on. Any failed step refuses the case before the flow runs.
 */
async function prepareLifecycleCase(item, { adapters, io, expectedSessionId }) {
  const url = item.setup === 'postFixture' ? io.postLoginUrl : item.setup === 'desktopFixture' || item.setup === 'mobileFixture' ? io.fixtureUrl : io.longUrl;
  // The declared viewport goes in the open request itself. Nothing here inherits a previous case's mode.
  if (item.viewport !== 'mobile' && item.viewport !== 'desktop') return { ok: false, reason: 'setup-viewport-undeclared', viewport: item.viewport ?? null };
  const opened = await adapters.openFresh(url, item.viewport);
  if (!opened.ok) return { ok: false, reason: 'open-failed', opened };
  const tabId = opened.data && opened.data.tabId ? opened.data.tabId : null;
  if (!tabId) return { ok: false, reason: 'open-no-tab', opened };
  const ready = await waitLifecycleReady(adapters, { expectedTabId: tabId, expectedSessionId, now: io.now, sleepMs: io.sleepMs });
  if (!ready.ready) return { ok: false, reason: 'page-not-ready', tabId, state: ready.state };
  // Read-only check of the mode the request asked for. A mismatch fails the case: it is never accepted or repaired.
  const wantDesktop = item.viewport === 'desktop';
  if (ready.state.desktopMode !== wantDesktop) {
    return { ok: false, reason: 'setup-declared-viewport-not-applied', tabId, declared: item.viewport, desktopMode: ready.state.desktopMode ?? null, state: ready.state };
  }
  if (item.setup === 'longPageScrolled') {
    // Two-finger touch is a pinch in direct-touch mode. A wheel needs Mouse on. Mouse is a mode switch from the
    // Touch menu: it is tapped only when the native state says it is not already on, and then read back.
    const mouse = await ensureNativeMouseOn(io, ready.state);
    if (!mouse.ok) return { ok: false, reason: 'setup-mouse-enable-failed', mouse };
    const before = ready.state.metrics && ready.state.metrics.sy;
    if (typeof before !== 'number') return { ok: false, reason: 'setup-metric-missing', field: 'metrics.sy' };
    const scrolled = await adapters.twoFingerScroll({ dy: -240 });
    if (!scrolled.ok) return { ok: false, reason: 'setup-scroll-failed', scrolled };
    const after = await waitLifecycleReady(adapters, { expectedTabId: tabId, expectedSessionId, now: io.now, sleepMs: io.sleepMs, budgetMs: 4000 });
    const sy = after.state && after.state.metrics ? after.state.metrics.sy : null;
    if (typeof sy !== 'number' || sy <= 0) return { ok: false, reason: 'setup-scroll-not-applied', sy, before };
    return { ok: true, tabId, selectedTabId: tabId, scrollBefore: before, scrollAfter: sy };
  }
  if (item.setup === 'postFixture') {
    const submit = await io.submitLoginFresh();
    if (!submit || !submit.ok) return { ok: false, reason: 'post-submit-failed', submit };
    return { ok: true, tabId: submit.tabId || tabId, selectedTabId: tabId, baseline: { beforePosts: submit.beforePosts, beforeArrivals: submit.beforeArrivals, landing: submit.landing, finalState: submit.finalState } };
  }
  return { ok: true, tabId, selectedTabId: tabId };
}

const LIFECYCLE_SCENE_MS = 90000;

async function runOneLifecycleCase(item, { deps, fence, adapters, probeDeadlineMs, save, now, sceneLog }) {
  const lifecycleProbe = require('./provider_lifecycle_gate_probe.cjs');
  const started = now();
  const flowStages = [];
  const runDeps = { ...deps, stages: flowStages };
  const probeRun = Promise.resolve().then(() => lifecycleProbe[item.flow](runDeps));
  let timer;
  const deadline = new Promise((resolve) => { timer = setTimeout(() => resolve(TIMED_OUT), probeDeadlineMs); });
  let outcome;
  try {
    outcome = await Promise.race([probeRun.then((flow) => ({ flow })), deadline]);
  } catch (error) {
    outcome = { flow: { ok: false, reason: 'probe-threw', error: String(error && error.message || error).slice(0, 200), stages: flowStages } };
  } finally {
    clearTimeout(timer);
  }
  if (outcome !== TIMED_OUT) {
    const sceneMs = now() - started;
    if (sceneLog) sceneLog.push({ case: item.name, sceneMs, budgetMs: probeDeadlineMs, outcome: outcome.flow.ok ? 'completed' : outcome.flow.reason });
    // Observation only: a failed normal return also keeps the last complete read, so the evidence is not lost.
    // The pass/reason of the flow are returned unchanged.
    return { ...outcome.flow, sceneMs, sceneBudgetMs: probeDeadlineMs, lastRead: deps.lastRead ? deps.lastRead() : null };
  }
  // Scene budget exhausted: no more mutation may reach the device, and the case keeps its full evidence.
  const stoppedAt = now();
  fence.stop();
  const lastRead = deps.lastRead ? deps.lastRead() : null;
  const settled = await Promise.race([
    probeRun.then((late) => ({ late }), (error) => ({ late: { ok: false, reason: 'probe-threw-late', error: String(error && error.message || error).slice(0, 200) } })),
    new Promise((resolve) => setTimeout(() => resolve(TIMED_OUT), SETTLE_GRACE_MS)),
  ]);
  if (settled === TIMED_OUT) {
    save(`${item.name}-pending-failed.json`, { ok: false, reason: 'probe-unsettled-after-fence', terminal: 'pending-failed', deadlineMs: probeDeadlineMs, graceMs: SETTLE_GRACE_MS, blockedAfterFence: fence.state.blocked, mutationsSent: fence.state.mutations, now: now(), stages: flowStages, lastRead });
    throw new GeometryGateAbort(item.name, 'lifecycle probe did not settle after fence');
  }
  const late = settled.late || {};
  const sceneMs = now() - started;
  if (sceneLog) sceneLog.push({ case: item.name, sceneMs, budgetMs: probeDeadlineMs, outcome: 'scene-budget-exhausted' });
  // The full late flow stays in the record (reason, trace, stages, reads), not a brief that drops the stage.
  return {
    ok: false,
    reason: 'scene-budget-exhausted',
    terminal: 'client-timeout-no-retry',
    deadlineMs: probeDeadlineMs,
    sceneBudgetMs: probeDeadlineMs,
    sceneMs,
    stoppedAtMs: stoppedAt - started,
    stages: flowStages,
    lastRead,
    late: { ok: late.ok === true, reason: late.reason || null, trace: late.trace || [], stages: late.stages || flowStages, saved: late.saved || null, expanded: late.expanded || null, restore: late.restore || null, after: late.after || null, error: late.error || null },
    mutationsSent: fence.state.mutations,
    blockedAfterFence: fence.state.blocked,
  };
}

function result(checks, focus = '') {
  const failed = checks.filter((item) => item.result !== 'PASS').map((item) => item.name);
  const focused = FOCUS_VALUES.includes(focus);
  // A focused probe never reports an overall PASS, even when every requested case passes.
  return {
    result: !focused && failed.length === 0 ? 'PASS' : 'FAIL',
    checks,
    failed,
    scope: focus === 'geometry-gate' ? 'geometry-race-targeted' : focus === 'lifecycle-gate' ? 'lifecycle-targeted' : focus === 'retirement-gate' ? 'retirement-targeted' : focus === 'manual' ? 'manual-focused-probe' : focused ? 'phase3-focused-probe' : 'full',
    phaseComplete: false,
    report: focused ? 'focused probe only; not a Phase 3 or full pass' : 'real broker and emulator WebView intended; no LLM and no Android PRoot',
  };
}

module.exports = {
  MENU_LABELS,
  FOCUS_VALUES,
  parseArgs,
  decodeEval,
  createCaseFence,
  createGeometryGateAdapters,
  prepareGeometryCaseForTest: prepareGeometryCase,
  runGeometryGateCases,
  GEOMETRY_CASES,
  geometryPageReady,
  lifecycleNormalise,
  createLifecycleGateAdapters,
  createRetirementGateAdapters,
  createRetirementPointerIO,
  currentRecordOf,
  lastRetiredClicksOf,
  RETIREMENT_CASES,
  LIFECYCLE_CASES,
  prepareLifecycleCase,
  runOneLifecycleCase,
  ensureNativeMouseOn,
  switchMouseOn,
  gateSnapshotResult,
  result,
};
