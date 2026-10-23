'use strict';

// Host-side protocol and typed-read adapter for the true-local guest matrix.
//
// The device side is LocalProviderRegressionTest (AndroidJUnitRunner). The host writes one fixed
// command at a time into the app-private test directory (command.json, atomic rename through stdin),
// and reads exactly that command's result (result.json). There is no host Node server, no relay and no
// provider token in this process: browser tool actions are executed inside the app by
// LocalBrowserToolClient (plugin transport, direct /v1/tool).
// Anything the producer does not expose is null and is recorded as a missing reader/field; it is never
// defaulted to a passing value.
//
// Only fixed commands are emitted here. Arbitrary JavaScript, arbitrary URLs, arbitrary shell and
// "click at these coordinates" are not part of this protocol.

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const {
  createCaseFence,
  createRetirementPointerIO,
  currentRecordOf,
} = require('./provider_gate_adapters.cjs');
const { validateExportedTarget, redact } = require('../verify_local_provider_emulator.cjs');

const MATRIX = Object.freeze({
  dir: 'files/local-provider-regression/current',
  statusFile: 'status.json',
  commandFile: 'command.json',
  resultFile: 'result.json',
  evidenceFile: 'evidence.json',
  serial: 'emulator-5582',
  package: 'io.andchamber.localprovider',
  testPackage: 'io.andchamber.localprovider.test',
  runner: 'androidx.test.runner.AndroidJUnitRunner',
  testClass: 'io.andchamber.app.browser.LocalProviderRegressionTest#runGuestMatrix',
  commandTimeoutMs: 20000,
  bootBudgetMs: 300000,
  sceneBudgetMs: 90000,
  settleGraceMs: 5000,
  stageBudgetMs: 20000,
  evidenceDirMode: 0o700,
  evidenceMode: 0o600,
  maxResponseBytes: 2 * 1024 * 1024,
});

const LIFECYCLE_CASE_NAMES = Object.freeze([
  'lifecycle-b5-enlarge-restore',
  'lifecycle-b5-user-scroll-wins',
  'lifecycle-b5-document-change',
  'lifecycle-b3-mode-mobile-to-desktop',
  'lifecycle-b3-mode-desktop-to-mobile',
  'lifecycle-b3-post-roundtrip',
]);

const RETIREMENT_CASE_NAMES = Object.freeze([
  'retirement-a-primary-drag-lock',
  'retirement-b-true-cancel-reopen',
]);

const ALL_CASE_NAMES = Object.freeze([...LIFECYCLE_CASE_NAMES, ...RETIREMENT_CASE_NAMES]);

const SCOPES = Object.freeze({
  lifecycle: LIFECYCLE_CASE_NAMES,
  retirement: RETIREMENT_CASE_NAMES,
  all: ALL_CASE_NAMES,
});

// Producer case metadata vocabulary. `custom` is the finite value for an explicit subset.
const CASE_SCOPES = Object.freeze(['lifecycle', 'retirement', 'all', 'custom']);

const FIXTURES = Object.freeze({
  origin: 'https://example.com/openchamber-provider',
  second: 'https://example.com/openchamber-provider-second',
  login: 'https://example.com/openchamber-provider-login',
  welcome: 'https://example.com/openchamber-provider-welcome',
  mouse: 'https://example.com/openchamber-provider-mouse',
  retire: 'https://example.com/openchamber-provider-retire',
});

const FIXTURE_URLS = Object.freeze(Object.values(FIXTURES));

// The producer parser's fixed UI labels. tap-label:<label> is a real accessibility hit test plus
// sendPointerSync inside the app; it is never a JavaScript click or a presenter grant.
const UI_LABELS = Object.freeze(new Set([
  'More', 'Computer layout', 'Phone layout', 'Confirm', 'Mouse', 'Touch',
  'Full screen', 'Small window', 'Resume agent', 'Take over', 'Reopen', 'Open', 'Close page', 'Turn off', 'Close',
]));

/**
 * Readers the host lane needs but the current producer draft does not expose yet. They are listed so
 * the parent can coordinate them instead of the host inventing a stub. When one is absent, the case
 * refuses and the evidence carries `missing.reader`/`missing.command`/`missingFields`.
 */
const REQUIRED_PRODUCER_READERS = Object.freeze({
  boot: [
    'status.json phase=await-ui with tapTarget(label=Open, runId=current, measured bounds) in files/local-provider-regression/current',
  ],
  lifecycle: [
    'lifecycle-diag',
    'fixture-state old shape: page.url/page.loads/page.posts, native.physicalFrame, postArrivals',
    'observe: exact fixture URL + generic metrics (ua/clientWidth/media1000/sy/timeOrigin/loads)',
    'page-rect: physical + page.visualViewport + pointerUi',
    'ui-state: fixed controls (Small window/Full screen/...) + Surface/Tight nodes',
    'ui-target:<UI label> -> full LocalTapTarget for host ADB tap (tap-label UI controls are refused by design)',
  ],
  post: [
    'fixture-field:login-user -> DOM precondition {phase,field,present,tag,type,value,valueLength}, no screen rect/windowId',
    'fixture-field:Sign in -> DOM precondition (BUTTON, value may be empty)',
    'tap-label:login-user and tap-label:Sign in -> sent/hit/visible true from producer real hit test + touch',
  ],
  retirement: [
    'fixture-target:<box|next>,<cssX>,<cssY> -> {timeOrigin, boxCSS/visibleRect, hit, page}',
    'observe.page counters: clicks/releases/buttons/wheel/boxCaptured/pointerId/logTail/nextClicks/boxClicks',
    'cookie-read -> {value}',
    'cookie-write:<value> -> {set}',
  ],
});

const TOOL_ACTIONS = Object.freeze(new Set([
  'browser.open', 'browser.snapshot', 'browser.click', 'browser.type',
  'browser.scroll', 'browser.capture', 'browser.resize',
]));

// The retirement cookie is test-only and fixed to the RETIRE fixture: 16..64 lowercase hex.
const RETIRE_COOKIE_VALUE = /^(?:[0-9a-f]{16,64}|m[A-Za-z0-9._:-]{1,63})$/;
const RETIRE_COOKIE_HEADER = /(?:^|;\s*)oc_fixture_retire=((?:[0-9a-f]{16,64}|m[A-Za-z0-9._:-]{1,63}))(?:;|$)/;

function isObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function isNum(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function isText(value) {
  return typeof value === 'string' && value.length > 0;
}

function isBool(value) {
  return typeof value === 'boolean';
}

function realOr(value, type) {
  if (value === undefined || value === null) return null;
  if (type === 'number') return isNum(value) ? value : null;
  if (type === 'string') return isText(value) ? value : null;
  if (type === 'boolean') return isBool(value) ? value : null;
  return value;
}

function validRunId(id) {
  return isText(id) && /^[A-Za-z0-9._:-]{8,80}$/.test(id);
}

function createRunId(now = Date.now, random = crypto.randomBytes) {
  const id = `m-${now().toString(36)}-${random(4).toString('hex')}`;
  if (!validRunId(id)) throw new Error('invalid generated matrix run id');
  return id;
}

function requireDedicatedSerial(serial) {
  if (serial !== MATRIX.serial) {
    throw new Error(`serial must be exactly ${MATRIX.serial}; no other emulator or physical device`);
  }
  return serial;
}

function requireDedicatedPackage(pkg) {
  if (pkg !== MATRIX.package) {
    throw new Error(`package must be exactly ${MATRIX.package}; the production app is refused`);
  }
  return pkg;
}

function requireOutsideFreshOut(out) {
  if (!isText(out) || !path.isAbsolute(out)) throw new Error('absolute --out required');
  const repository = path.resolve(__dirname, '..', '..') + path.sep;
  if (path.resolve(out).startsWith(repository)) {
    throw new Error('--out must be outside the repository (raw device files and logs)');
  }
  if (fs.existsSync(out)) throw new Error('output root already exists; refusing to mix runs');
  return out;
}

function parseArgs(argv) {
  const args = {
    serial: '',
    package: MATRIX.package,
    adb: path.join(process.env.HOME || '', 'Android/Sdk/platform-tools/adb'),
    out: '',
    scope: 'all',
    runId: '',
    expectedCases: [],
  };
  let expectedSpecified = false;
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) throw new Error(`unexpected argument: ${key}`);
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new Error(`missing value for ${key}`);
    index += 1;
    if (key === '--serial') args.serial = value;
    else if (key === '--package') args.package = value;
    else if (key === '--adb') args.adb = value;
    else if (key === '--out') args.out = value;
    else if (key === '--scope') args.scope = value;
    else if (key === '--run-id') args.runId = value;
    else if (key === '--expected-cases') {
      expectedSpecified = true;
      args.expectedCases = String(value).split(',').map((item) => item.trim()).filter(Boolean);
    } else throw new Error(`unknown option: ${key}`);
  }
  requireDedicatedSerial(args.serial);
  requireDedicatedPackage(args.package);
  requireOutsideFreshOut(args.out);
  if (!Object.prototype.hasOwnProperty.call(SCOPES, args.scope)) {
    throw new Error(`scope must be one of ${Object.keys(SCOPES).join('|')}`);
  }
  if (args.runId && !validRunId(args.runId)) throw new Error('invalid --run-id');
  const allowed = SCOPES[args.scope];
  if (expectedSpecified && args.expectedCases.length === 0) {
    throw new Error('--expected-cases requires a non-empty finite comma list');
  }
  if (args.expectedCases.length) {
    const unknown = args.expectedCases.filter((name) => !ALL_CASE_NAMES.includes(name));
    if (unknown.length) throw new Error(`unknown expected case: ${unknown.join(',')}`);
    const outside = args.expectedCases.filter((name) => !allowed.includes(name));
    if (outside.length) throw new Error(`expected case outside scope ${args.scope}: ${outside.join(',')}`);
    if (new Set(args.expectedCases).size !== args.expectedCases.length) throw new Error('duplicate expected case');
  }
  return args;
}

function casesForScope(scope, expectedCases = []) {
  const allowed = SCOPES[scope];
  if (!allowed) throw new Error(`unknown scope ${scope}`);
  const names = expectedCases.length ? expectedCases : [...allowed];
  return names.map((name) => ({ name, required: true }));
}

/**
 * One command's result. Only the current run id and the current command id are accepted; a stale file
 * (previous run or previous command) is null and the caller keeps polling for the matching result.
 * A transport/tool error is never promoted to a pass, and a missing provider is called out explicitly.
 */
function normalizeResult(raw, { runId, id } = {}) {
  if (!isObject(raw)) return null;
  if (raw.runId !== runId || raw.id !== id) return null;
  const error = isObject(raw.error) ? raw.error : {};
  if (raw.ok !== true) {
    const code = isText(error.code) ? error.code : 'failed';
    const detail = isText(error.message) ? error.message : '';
    return { id, ok: false, code, detail, data: null, providerMissing: providerMissingText(`${code} ${detail}`) };
  }
  const data = isObject(raw.data) ? raw.data : null;
  // The producer may return a failed agent envelope as data with result.ok=true. It stays a failure.
  if (data && (data.ok === false || data.error !== undefined)) {
    const inner = isObject(data.error) ? data.error : {};
    const code = isText(inner.code) ? inner.code : isText(data.code) ? data.code : 'agent-error';
    const detail = isText(inner.message) ? inner.message : isText(data.error) ? data.error : '';
    return { id, ok: false, code, detail, data, providerMissing: providerMissingText(`${code} ${detail}`) };
  }
  return { id, ok: true, code: '', detail: '', data, providerMissing: false };
}

function providerMissingText(text) {
  return /NO_PROVIDER|No Android browser provider|no-provider|transport|ECONNRESET|ECONNREFUSED/i.test(String(text || ''));
}

function expectedCaseNames(raw) {
  let values;
  if (Array.isArray(raw)) values = raw;
  else if (typeof raw === 'string') values = raw.split(',');
  else return null;
  if (!values.every((item) => typeof item === 'string' && item.trim().length > 0)) return null;
  const names = [...new Set(values.map((item) => item.trim()))];
  return names.length ? names : null;
}

function validStatus(raw, { runId } = {}) {
  if (!isObject(raw) || raw.runId !== runId) return null;
  const phase = realOr(raw.phase, 'string');
  const updatedAtMs = realOr(raw.updatedAtMs, 'number');
  if (!phase || updatedAtMs == null) return null;
  return {
    runId,
    phase,
    updatedAtMs,
    selectedSessionId: realOr(raw.selectedSessionId, 'string'),
    failure: realOr(raw.failure, 'string'),
    tapTarget: isObject(raw.tapTarget) ? raw.tapTarget : null,
    label: realOr(raw.label, 'string'),
    bounds: isObject(raw.bounds) ? raw.bounds : null,
    windowId: realOr(raw.windowId, 'number'),
    ownerPackage: realOr(raw.ownerPackage, 'string'),
    // Producer metadata is read only from the actual fields. A missing/invalid boolean stays null,
    // which the host refuses; it is never coerced to false.
    casePartial: realOr(raw.casePartial, 'boolean'),
    caseScope: realOr(raw.caseScope, 'string'),
    expectedCases: expectedCaseNames(raw.expectedCases),
  };
}

function commandId(runId, sequence, kind) {
  const prefix = String(runId).slice(0, 60);
  const id = `${prefix}:${kind}:${String(sequence).padStart(4, '0')}`;
  if (!/^[A-Za-z0-9._:-]{1,80}$/.test(id)) throw new Error('generated command id is invalid');
  return id;
}

/**
 * Serial command client. Exactly one command may be in flight; a timeout fences the client, so no new
 * mutation is sent after an unsettled command. Reads are ordinary protocol commands and share that
 * single-file channel, so they are serialised too.
 */
function createMatrixClient({
  transport,
  runId,
  now = Date.now,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  commandTimeoutMs = MATRIX.commandTimeoutMs,
  pollMs = 100,
  onResponse = null,
} = {}) {
  if (!transport || typeof transport.writeCommand !== 'function' || typeof transport.readResult !== 'function') {
    throw new Error('transport with writeCommand/readResult required');
  }
  if (!validRunId(runId)) throw new Error('invalid run id');
  let sequence = 0;
  let inflight = false;
  let stopped = false;
  const stats = { sent: 0, stale: 0, timeouts: 0, fences: 0, byKind: { tool: 0, device: 0, finish: 0 } };

  function fence() {
    if (!stopped) stats.fences += 1;
    stopped = true;
  }

  async function request(partial) {
    if (stopped) {
      const error = new Error('matrix transport fenced; no new command');
      error.code = 'FENCED';
      throw error;
    }
    if (inflight) {
      const error = new Error('another matrix command is in flight');
      error.code = 'COMMAND_INFLIGHT';
      throw error;
    }
    if (partial.kind === 'tool' && !TOOL_ACTIONS.has(partial.action)) {
      throw new Error(`unsupported tool action ${partial.action}`);
    }
    if (partial.kind === 'device' && !isText(partial.text)) throw new Error('device command text required');
    if (partial.kind === 'tool' && isObject(partial.input) && partial.input.sessionId !== undefined) {
      throw new Error('sessionId belongs to the command, not input');
    }
    inflight = true;
    sequence += 1;
    const command = {
      runId,
      id: commandId(runId, sequence, partial.kind),
      kind: partial.kind,
    };
    if (partial.kind === 'tool') {
      command.action = partial.action;
      command.input = isObject(partial.input) ? partial.input : {};
      if (partial.sessionId) command.sessionId = partial.sessionId;
    } else if (partial.kind === 'device') {
      command.text = partial.text;
      if (partial.sessionId) command.sessionId = partial.sessionId;
    } else if (partial.kind === 'finish') {
      command.summary = isObject(partial.summary) ? partial.summary : {};
    } else {
      inflight = false;
      throw new Error(`unsupported command kind ${partial.kind}`);
    }
    stats.byKind[command.kind] += 1;
    try {
      await transport.writeCommand(command);
      stats.sent += 1;
      const deadline = now() + commandTimeoutMs;
      while (now() < deadline) {
        const raw = await transport.readResult();
        if (isObject(raw) && raw.runId === runId && raw.id === command.id) {
          if (typeof onResponse === 'function') {
            try {
              await onResponse({
                runId,
                id: command.id,
                kind: command.kind,
                action: command.action || null,
                text: command.text || null,
                raw,
              });
            } catch (error) {
              if (!error.code) error.code = 'RESPONSE_ARCHIVE_FAILED';
              throw error;
            }
          }
          const result = normalizeResult(raw, { runId, id: command.id });
          if (result) return result;
        } else if (raw) {
          stats.stale += 1;
        }
        await sleep(pollMs);
      }
      stats.timeouts += 1;
      fence();
      const error = new Error(`command ${command.id} timed out after ${commandTimeoutMs}ms`);
      error.code = 'COMMAND_TIMEOUT';
      error.command = command.id;
      throw error;
    } finally {
      inflight = false;
    }
  }

  return {
    runId,
    stats,
    request,
    fence,
    isFenced: () => stopped,
    sendTool: (action, input = {}, sessionId = undefined) => request({ kind: 'tool', action, input, sessionId }),
    sendDevice: (text, sessionId = undefined) => request({ kind: 'device', text, sessionId }),
    finish: (summary) => request({ kind: 'finish', summary }),
    readJson: (name) => transport.readJson(name),
    readStatus: async () => validStatus(await transport.readJson(MATRIX.statusFile), { runId }),
  };
}

/**
 * Read-only status wait. It repeats only reads; a failed or done status ends the wait, and unknown
 * phases/timeouts are returned as-is so the host can name the phase that stopped.
 */
async function waitForStatus({
  client,
  phases,
  budgetMs,
  now = Date.now,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  pollMs = 250,
  onStatus = null,
  isClosed = null,
}) {
  const wanted = new Set(phases);
  const deadline = now() + budgetMs;
  let last = null;
  while (now() < deadline) {
    if (typeof isClosed === 'function' && isClosed()) {
      return { status: last, lastPhase: last ? last.phase : null, timedOut: false, closed: true };
    }
    const status = await client.readStatus();
    if (status) {
      last = status;
      if (onStatus) await onStatus(status);
      if (wanted.has(status.phase)) return { status, lastPhase: status.phase, timedOut: false, closed: false };
      if (status.phase === 'failed' || status.phase === 'done') return { status, lastPhase: status.phase, timedOut: false, closed: false };
    }
    await sleep(pollMs);
  }
  return { status: last, lastPhase: last ? last.phase : null, timedOut: true, closed: false };
}

/* ------------------------------------------------------------------------------------------------
 * Typed producer sources.
 * ---------------------------------------------------------------------------------------------- */

function isFixtureUrl(url) {
  return isText(url) && FIXTURE_URLS.some((item) => url === item || url.startsWith(`${item}/`) || url.startsWith(`${item}?`));
}

/** observe: exact fixture generic metrics, timeOrigin, url and scroll. Missing values stay null. */
function normalizeObserve(raw) {
  if (!isObject(raw) || raw.error) return null;
  const url = realOr(raw.url, 'string');
  if (!url || !isFixtureUrl(url)) return null;
  const source = isObject(raw.metrics) ? raw.metrics : raw;
  const timeOrigin = realOr(source.timeOrigin, 'number') ?? realOr(raw.timeOrigin, 'number');
  return {
    url,
    timeOrigin,
    ua: realOr(source.ua, 'string'),
    clientWidth: realOr(source.clientWidth, 'number'),
    media1000: realOr(source.media1000, 'boolean'),
    sy: realOr(source.sy, 'number') ?? realOr(source.scrollY, 'number'),
    loads: realOr(source.loads, 'number'),
    posts: realOr(source.posts, 'number'),
  };
}

/** fixture-state: literal producer page/native/top-level ime fields. Missing stays null, never 0/false. */
function normalizeFixture(raw) {
  if (!isObject(raw) || raw.error) return null;
  const source = isObject(raw.page) ? raw.page : raw;
  const page = {
    url: realOr(source.url, 'string') ?? realOr(raw.url, 'string'),
    ready: realOr(source.ready, 'string'),
    inputValue: typeof source.inputValue === 'string' ? source.inputValue
      : typeof raw.inputValue === 'string' ? raw.inputValue : null,
    formPresent: realOr(source.formPresent, 'boolean') ?? realOr(raw.formPresent, 'boolean'),
    posts: realOr(source.posts, 'number') ?? realOr(raw.posts, 'number'),
    loads: realOr(source.loads, 'number') ?? realOr(raw.loads, 'number'),
    clicks: realOr(source.clicks, 'number'),
    submits: realOr(source.submits, 'number'),
    inputFocused: realOr(source.inputFocused, 'boolean') ?? realOr(raw.inputFocused, 'boolean'),
    activeTag: realOr(source.activeTag, 'string') ?? realOr(raw.activeTag, 'string'),
    selectionStart: realOr(source.selectionStart, 'number'),
    selectionEnd: realOr(source.selectionEnd, 'number'),
    documentHasFocus: realOr(source.documentHasFocus, 'boolean') ?? realOr(raw.documentHasFocus, 'boolean'),
    inputEnabled: realOr(source.inputEnabled, 'boolean') ?? realOr(raw.inputEnabled, 'boolean'),
    inputReadOnly: realOr(source.inputReadOnly, 'boolean') ?? realOr(raw.inputReadOnly, 'boolean'),
    inputTag: realOr(source.inputTag, 'string') ?? realOr(raw.inputTag, 'string'),
    timeOrigin: realOr(source.timeOrigin, 'number') ?? realOr(raw.timeOrigin, 'number'),
    form: source.form !== undefined ? source.form : raw.form === undefined ? null : raw.form,
    input: source.input !== undefined ? source.input : raw.input === undefined ? null : raw.input,
  };
  const nativeSource = isObject(raw.native) ? raw.native : null;
  const native = nativeSource ? {
    selectedTabId: realOr(nativeSource.selectedTabId, 'string'),
    owner: realOr(nativeSource.owner, 'string'),
    viewHandle: realOr(nativeSource.viewHandle, 'number'),
    viewGeneration: realOr(nativeSource.viewGeneration, 'number'),
    documentEpoch: realOr(nativeSource.documentEpoch, 'number'),
    shown: realOr(nativeSource.shown, 'boolean'),
    pageReady: realOr(nativeSource.pageReady, 'boolean'),
    mouseMode: realOr(nativeSource.mouseMode, 'boolean'),
    agentOn: realOr(nativeSource.agentOn, 'boolean'),
    mode: realOr(nativeSource.mode, 'string'),
    hasFocus: realOr(nativeSource.hasFocus, 'boolean'),
    hasWindowFocus: realOr(nativeSource.hasWindowFocus, 'boolean'),
    isShown: realOr(nativeSource.isShown, 'boolean'),
    windowTokenPresent: realOr(nativeSource.windowTokenPresent, 'boolean'),
    physicalFrame: normalizeBounds(nativeSource.physicalFrame),
    screenVisibleRect: isObject(nativeSource.screenVisibleRect) ? {
      ...(normalizeBounds(nativeSource.screenVisibleRect) || {}),
      visible: realOr(nativeSource.screenVisibleRect.visible, 'boolean'),
    } : null,
  } : null;
  const imeSource = isObject(raw.outerime) ? raw.outerime
    : isObject(raw.ime) ? raw.ime
      : isObject(raw.page) && isObject(raw.page.ime) ? raw.page.ime : null;
  const ime = {
    shown: imeSource ? realOr(imeSource.shown, 'boolean') : null,
    source: imeSource ? realOr(imeSource.source, 'string') : null,
    bottomPx: imeSource ? realOr(imeSource.bottomPx, 'number') : null,
  };
  const copy = (value) => {
    if (!isObject(value)) return null;
    try { return JSON.parse(JSON.stringify(value)); } catch (_error) { return null; }
  };
  return {
    page,
    native,
    ime,
    // Literal fixed-fixture identity pair and raw fixture object (login fixture only; no cookie/token).
    identityBefore: copy(raw.identityBefore),
    identityAfter: copy(raw.identityAfter),
    raw: copy(raw),
    postArrivals: realOr(raw.postArrivals, 'number'),
  };
}

function normalizeBounds(value) {
  if (!isObject(value)) return null;
  if (isObject(value.bounds)) return normalizeBounds(value.bounds);
  if (typeof value.bounds === 'string') {
    const match = /^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/.exec(value.bounds);
    if (match) return normalizeBounds({ left: Number(match[1]), top: Number(match[2]), right: Number(match[3]), bottom: Number(match[4]) });
  }
  const left = realOr(value.left !== undefined ? value.left : value.x, 'number');
  const top = realOr(value.top !== undefined ? value.top : value.y, 'number');
  const explicitWidth = value.width !== undefined ? value.width : value.w;
  const explicitHeight = value.height !== undefined ? value.height : value.h;
  const width = realOr(explicitWidth !== undefined ? explicitWidth
    : isNum(value.right) && left != null ? value.right - left : null, 'number');
  const height = realOr(explicitHeight !== undefined ? explicitHeight
    : isNum(value.bottom) && top != null ? value.bottom - top : null, 'number');
  if (left == null || top == null || width == null || height == null || width <= 1 || height <= 1) return null;
  return { left, top, width, height, right: left + width, bottom: top + height };
}

/** page-rect: native physical frame, page visualViewport and the pointer UI geometry. */
function frameProjection(raw) {
  const rawSafe = (() => {
    if (raw === undefined || raw === null) return null;
    try { return JSON.parse(JSON.stringify(raw)); } catch (_error) { return null; }
  })();
  const reject = (reason, missingFields = []) => ({ frame: null, reason, missingFields, rawSafe });
  if (!isObject(raw)) return reject('raw-missing', ['raw']);
  if (raw.error) return reject('raw-error', ['raw.error']);
  const physical = normalizeBounds(isObject(raw.physical) ? raw.physical
    : isObject(raw.physicalFrame) ? raw.physicalFrame
      : isObject(raw.frame) && raw.frame.left !== undefined ? raw.frame
        : raw);
  if (!physical) return reject('physical-missing', ['physical']);
  const viewWidth = isNum(raw.viewWidth) ? raw.viewWidth : physical.width;
  const viewHeight = isNum(raw.viewHeight) ? raw.viewHeight : physical.height;
  if (!(viewWidth > 0) || !(viewHeight > 0)) return reject('view-size-missing', ['viewWidth', 'viewHeight']);
  const visual = isObject(raw.page) && isObject(raw.page.visualViewport) ? raw.page.visualViewport
    : isObject(raw.visualViewport) ? raw.visualViewport : null;
  if (!isObject(visual)) return reject('viewport-missing', ['page.visualViewport']);
  if (![visual.width, visual.height, visual.offsetLeft, visual.offsetTop].every(isNum)) {
    return reject('viewport-nonfinite', ['page.visualViewport.width', 'page.visualViewport.height', 'page.visualViewport.offsetLeft', 'page.visualViewport.offsetTop']);
  }
  if (visual.width <= 0 || visual.height <= 0) return reject('viewport-not-positive', ['page.visualViewport.width', 'page.visualViewport.height']);
  const pointer = isObject(raw.pointerUi) ? raw.pointerUi : null;
  if (!pointer) return reject('pointer-missing', ['pointerUi']);
  if (pointer.geometryReady !== true) return reject('pointer-not-ready', ['pointerUi.geometryReady']);
  if (!isNum(pointer.pointerViewX) || !isNum(pointer.pointerViewY)) return reject('pointer-not-finite', ['pointerUi.pointerViewX', 'pointerUi.pointerViewY']);
  const viewHandle = realOr(raw.viewHandle, 'number');
  if (viewHandle == null) return reject('view-handle-missing', ['viewHandle']);
  if (pointer.viewHandle !== viewHandle) return reject('pointer-handle-mismatch', ['pointerUi.viewHandle', 'viewHandle']);
  return {
    frame: {
      physical,
      // Raw WebView size; agent-local pointer pixels keep this space. physical is the scaled
      // displayed screen rectangle.
      viewWidth,
      viewHeight,
      page: { visualViewport: {
        width: visual.width,
        height: visual.height,
        offsetLeft: visual.offsetLeft,
        offsetTop: visual.offsetTop,
      } },
      pointerUi: {
        geometryReady: true,
        viewHandle,
        pointerViewX: pointer.pointerViewX,
        pointerViewY: pointer.pointerViewY,
      },
      viewHandle,
      documentEpoch: realOr(raw.documentEpoch, 'number'),
    },
    reason: 'ok',
    missingFields: [],
    rawSafe,
  };
}

function normalizeFrame(raw) {
  return frameProjection(raw).frame;
}

function physicalCenter(raw) {
  const physical = normalizeBounds(isObject(raw && raw.physical) ? raw.physical : raw);
  if (!physical) return null;
  return {
    x: Math.round(physical.left + physical.width / 2),
    y: Math.round(physical.top + physical.height / 2),
    physical,
  };
}

/** ui-state: fixed controls and the floating Surface bounds. Enlarged is presence of Small window/Full screen. */
function normalizeUi(raw) {
  if (!isObject(raw) || raw.error) return null;
  const labels = isObject(raw.labels) ? raw.labels : {};
  const nodes = Array.isArray(raw.tightNodes) ? raw.tightNodes : Array.isArray(raw.nodes) ? raw.nodes : [];
  const has = (label) => isObject(labels[label]) ||
    nodes.some((node) => isObject(node) && (node.text === label || node.desc === label || node.label === label));
  const enlarged = has('Small window') || has('Restore') ? true
    : has('Full screen') || has('Enlarge') ? false : null;
  const nodeSurface = nodes.find((node) => isObject(node) &&
    (node.desc === 'BrowserFloatSurface' || node.label === 'BrowserFloatSurface' || node.id === 'BrowserFloatSurface'));
  const surfaceCandidate = isObject(raw.surface) ? raw.surface
    : isObject(labels.BrowserFloatSurface) ? labels.BrowserFloatSurface
      : isObject(nodeSurface) ? nodeSurface
        : isObject(raw.tightSurface) ? raw.tightSurface : null;
  const surface = normalizeBounds(surfaceCandidate);
  return { enlarged, surface };
}

function normalizePage(raw) {
  if (!isObject(raw) || raw.error) return null;
  return {
    timeOrigin: realOr(raw.timeOrigin, 'number'),
    clicks: realOr(raw.clicks, 'number'),
    releases: realOr(raw.releases, 'number'),
    buttons: realOr(raw.buttons, 'number'),
    wheel: realOr(raw.wheel, 'number'),
    boxCaptured: raw.boxCaptured === undefined ? null : raw.boxCaptured,
    pointerId: realOr(raw.pointerId, 'number'),
    logTail: Array.isArray(raw.logTail) ? raw.logTail : null,
    nextClicks: realOr(raw.nextClicks, 'number'),
    boxClicks: realOr(raw.boxClicks, 'number'),
    cookie: realOr(raw.cookie, 'string'),
  };
}

function missingLifecycleFields(state) {
  const missing = [];
  if (!state) return ['lifecycle-diag'];
  if (state.phase !== undefined && state.pageReady === undefined) missing.push('pageReady');
  if (!isText(state.selectedTabId)) missing.push('selectedTabId');
  if (state.pageReady !== true) missing.push('pageReady(true)');
  if (!isNum(state.viewHandle)) missing.push('viewHandle');
  if (!isBool(state.desktopMode)) missing.push('desktopMode');
  if (!isBool(state.mouseMode)) missing.push('mouseMode');
  if (!isBool(state.grantForSelected)) missing.push('grantForSelected');
  const metrics = state.metrics;
  if (!isObject(metrics)) missing.push('observe.metrics');
  else {
    if (!isText(metrics.ua)) missing.push('metrics.ua');
    if (!isNum(metrics.clientWidth)) missing.push('metrics.clientWidth');
    if (!isBool(metrics.media1000)) missing.push('metrics.media1000');
    if (!isNum(metrics.timeOrigin)) missing.push('metrics.timeOrigin');
    if (!isNum(metrics.sy)) missing.push('metrics.sy');
  }
  if (!isText(state.url)) missing.push('fixture.page.url');
  if (!isNum(state.loads)) missing.push('fixture.page.loads');
  if (!isNum(state.posts)) missing.push('fixture.page.posts');
  if (!isNum(state.postArrivals)) missing.push('fixture.postArrivals');
  if (!isObject(state.frame)) missing.push('fixture.native.physicalFrame');
  if (!isBool(state.enlarged)) missing.push('ui-state.enlarged');
  return missing;
}

function missingRetirementFields(state, frame) {
  const missing = [];
  if (!state) return ['lifecycle-diag'];
  if (state.pageReady !== true) missing.push('pageReady(true)');
  if (!isBool(state.pageRetired)) missing.push('pageRetired');
  if (!isBool(state.grantForSelected)) missing.push('grantForSelected');
  if (!isBool(state.mousePressActive)) missing.push('mousePressActive');
  if (!isText(state.mode)) missing.push('mode');
  if (!isNum(state.viewHandle)) missing.push('viewHandle');
  if (state.recorderReady !== true) missing.push('recorderReady(true)');
  if (!state.recorder || !state.recorder.current) missing.push('recorder.current(recorderViewId)');
  if (state.currentViewPresent !== true) missing.push('currentIdentity');
  if (!frame) missing.push('page-rect(physical+visualViewport+pointerUi)');
  const page = state.page;
  if (!isObject(page)) missing.push('observe.page');
  else {
    for (const key of ['clicks', 'releases', 'buttons', 'wheel', 'nextClicks', 'boxClicks']) {
      if (!isNum(page[key])) missing.push(`observe.page.${key}`);
    }
    if (!Array.isArray(page.logTail)) missing.push('observe.page.logTail');
  }
  return missing;
}

/* ------------------------------------------------------------------------------------------------
 * Protocol file transport (adb run-as). Values travel on stdin; no shell interpolation.
 * ---------------------------------------------------------------------------------------------- */

function runAsShellPath(dir, name) {
  return `${dir}/${name}`;
}

function writePrivateAtomic({ adbPath, serial, pkg, dir, name, text, timeoutMs = 20000 }) {
  const target = runAsShellPath(dir, name);
  const temporary = `${target}.tmp`;
  const script = `cat > ${temporary} && chmod 600 ${temporary} && mv -f ${temporary} ${target}`;
  return new Promise((resolve, reject) => {
    const child = spawn(adbPath, ['-s', serial, 'shell', `run-as ${pkg} sh -c ${JSON.stringify(script)}`], {
      stdio: ['pipe', 'ignore', 'pipe'],
    });
    const errors = [];
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    child.stderr.on('data', (chunk) => errors.push(chunk));
    child.on('error', (error) => { clearTimeout(timer); reject(error); });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve(true);
      else reject(new Error(`private write ${name} exit ${code}: ${String(Buffer.concat(errors)).slice(0, 200)}`));
    });
    child.stdin.end(text);
  });
}

function readPrivateText({ adbPath, serial, pkg, dir, name, timeoutMs = 15000, maxBytes = 0 }) {
  return new Promise((resolve, reject) => {
    const child = spawn(adbPath, ['-s', serial, 'exec-out', 'run-as', pkg, 'cat', runAsShellPath(dir, name)], {
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    child.on('close', (code) => {
      clearTimeout(timer);
      if (tooLarge) {
        const error = new Error(`response exceeds ${maxBytes} bytes`);
        error.code = 'RESPONSE_TOO_LARGE';
        reject(error);
        return;
      }
      resolve(code === 0 ? Buffer.concat(chunks).toString('utf8') : null);
    });
    child.stdout.on('data', (chunk) => {
      size += chunk.length;
      if (maxBytes > 0 && size > maxBytes) {
        tooLarge = true;
        child.kill('SIGKILL');
        return;
      }
      chunks.push(chunk);
    });
  });
}

function createAdbProtocolTransport({ adbPath, serial, pkg, dir = MATRIX.dir }) {
  requireDedicatedSerial(serial);
  requireDedicatedPackage(pkg);
  return {
    async writeCommand(command) {
      return writePrivateAtomic({
        adbPath,
        serial,
        pkg,
        dir,
        name: MATRIX.commandFile,
        text: `${JSON.stringify(command)}\n`,
      });
    },
    async readResult() {
      const text = await readPrivateText({ adbPath, serial, pkg, dir, name: MATRIX.resultFile, maxBytes: MATRIX.maxResponseBytes });
      if (!text) return null;
      try { return JSON.parse(text); } catch (_error) { return null; }
    },
    async readJson(name) {
      if (!['status.json', 'result.json', 'evidence.json'].includes(name)) return null;
      const text = await readPrivateText({ adbPath, serial, pkg, dir, name });
      if (!text) return null;
      try { return JSON.parse(text); } catch (_error) { return null; }
    },
  };
}

/**
 * Bounded raw-response archive for the host protocol. It only receives a reply already matched to the
 * current run id and command id in MatrixClient; stale/foreign replies never reach it. Fixed-fixture
 * payloads are filtered for bearer/token/password/auth-cookie text and image bytes; the test cookie
 * marker remains available for the cookie readback test.
 */
function sanitizeResponse(value, depth = 0, seen = new WeakSet()) {
  if (depth > 8) return '[depth-limited]';
  if (value === null || value === undefined) return null;
  if (typeof value === 'string') {
    if (/^data:image\//i.test(value) || value.length > 262144) return '[binary-or-oversize]';
    return redact(value).replace(/((?:auth)?cookie|token|password)=([^&\s;]+)/gi, '$1=[redacted]');
  }
  if (typeof value === 'number' || typeof value === 'boolean') return value;
  if (Array.isArray(value)) return value.slice(0, 256).map((item) => sanitizeResponse(item, depth + 1, seen));
  if (typeof value !== 'object') return String(value).slice(0, 300);
  if (seen.has(value)) return '[circular]';
  seen.add(value);
  const out = {};
  for (const [key, item] of Object.entries(value)) {
    if (/^(base64|base64data|imagedata|pngbase64|screenshotbase64)$/i.test(key)) continue;
    if (/^(bytesbase64|image|imagebytes)$/i.test(key)) continue;
    if (/^(authorization|cookie|setcookie|authcookie|token|accesstoken|refreshtoken|password)$/i.test(key)) {
      out[key] = '[redacted]';
      continue;
    }
    out[key] = sanitizeResponse(item, depth + 1, seen);
  }
  return out;
}

function createResponseArchive({
  dir,
  now = Date.now,
  maxResponses = 256,
  maxBytes = 16 * 1024 * 1024,
  maxResponseBytes = 2 * 1024 * 1024,
} = {}) {
  if (!isText(dir) || !path.isAbsolute(dir)) throw new Error('response archive needs absolute dir');
  fs.mkdirSync(dir, { recursive: true, mode: MATRIX.evidenceDirMode });
  fs.chmodSync(dir, MATRIX.evidenceDirMode);
  const manifestPath = path.join(dir, 'manifest.json');
  const entries = [];
  const evicted = [];
  let totalBytes = 0;
  const safeId = (id) => String(id || 'unknown').replace(/[^A-Za-z0-9._:-]/g, '_').replace(/^\.+$/, '_').slice(0, 120) || 'unknown';
  const fileFor = (id) => {
    const file = path.join(dir, `${safeId(id)}.json`);
    const root = path.resolve(dir) + path.sep;
    if (!path.resolve(file).startsWith(root)) throw new Error('response archive path escaped');
    return file;
  };
  const writeManifest = () => {
    const body = {
      version: 1,
      updatedAtMs: now(),
      totalBytes,
      count: entries.length,
      entries: entries.map((entry) => ({ ...entry })),
      evicted: evicted.slice(-64),
    };
    const text = `${JSON.stringify(body, null, 2)}\n`;
    const tmp = `${manifestPath}.tmp`;
    fs.writeFileSync(tmp, text, { mode: MATRIX.evidenceMode });
    fs.chmodSync(tmp, MATRIX.evidenceMode);
    fs.renameSync(tmp, manifestPath);
  };
  const evictOne = () => {
    const oldest = entries.shift();
    if (!oldest) return;
    totalBytes -= oldest.bytes;
    try { fs.rmSync(oldest.file, { force: true }); } catch (_error) { /* manifest still records eviction */ }
    evicted.push({ id: oldest.id, file: path.basename(oldest.file), at: oldest.at });
    while (evicted.length > 64) evicted.shift();
  };
  return {
    dir,
    manifestPath,
    async save({ runId, id, kind, action = null, text = null, raw }) {
      if (!isText(runId) || !isText(id)) throw new Error('response archive requires runId/id');
      const devicePrefix = kind === 'device' && isText(text) ? String(text).split(':')[0].slice(0, 40) : null;
      const context = {
        runId,
        id,
        kind: String(kind || ''),
        action: kind === 'tool' ? (isText(action) ? action.slice(0, 80) : null) : null,
        devicePrefix,
        rawResponse: sanitizeResponse(raw),
        timestamp: now(),
      };
      const body = `${JSON.stringify(context)}\n`;
      const bytes = Buffer.byteLength(body);
      if (bytes > maxResponseBytes) {
        const error = new Error(`response exceeds per-entry budget (${bytes} bytes)`);
        error.code = 'RESPONSE_TOO_LARGE';
        throw error;
      }
      const file = fileFor(id);
      const tmp = `${file}.tmp`;
      fs.writeFileSync(tmp, body, { mode: MATRIX.evidenceMode });
      fs.chmodSync(tmp, MATRIX.evidenceMode);
      fs.renameSync(tmp, file);
      const existing = entries.findIndex((entry) => entry.id === id);
      if (existing >= 0) {
        totalBytes -= entries[existing].bytes;
        entries.splice(existing, 1);
      }
      entries.push({ id, file, bytes, at: context.timestamp, kind: context.kind, action: context.action || devicePrefix });
      totalBytes += bytes;
      while (entries.length > maxResponses || totalBytes > maxBytes) evictOne();
      writeManifest();
      return { file, bytes, context };
    },
    manifest() {
      return { version: 1, totalBytes, count: entries.length, entries: entries.map((entry) => ({ ...entry })), evicted: evicted.slice(-64) };
    },
  };
}

/* ------------------------------------------------------------------------------------------------
 * Guest gate IO: typed reads + fixed device commands behind the real protocol client.
 * ---------------------------------------------------------------------------------------------- */

function createGuestGateIo({
  client,
  runId,
  serial = MATRIX.serial,
  package: pkg = MATRIX.package,
  screen = null,
  humanType = null,
  tapScreen = null,
  pressBack = null,
  now = Date.now,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
} = {}) {
  if (!client) throw new Error('matrix client required');
  const currentRunId = runId || client.runId;
  const missing = { reader: null, field: null, command: null, code: null };
  const readFailures = [];
  let lastDeviceCommandId = null;
  let lastDeviceRaw = null;
  let lastDeviceError = null;
  let lastFrameRead = null;
  const diagnosticsPath = (commandId) => commandId
    ? `${MATRIX.dir}/diagnostics/${commandId}.json`
    : null;
  const remember = (reader, field = null, command = null, code = null) => {
    if (!missing.reader) missing.reader = reader;
    if (!missing.field && field) missing.field = field;
    if (!missing.command && command) missing.command = command;
    if (!missing.code && code) missing.code = code;
  };
  const missingReport = () => ({ ...missing });
  const recordReadFailure = (reader, code, detail, command, commandId) => {
    readFailures.push({
      reader,
      command,
      commandId: commandId || null,
      diagnostics: diagnosticsPath(commandId),
      code: code || 'read-failed',
      detail: String(detail || '').slice(0, 300),
    });
    while (readFailures.length > 8) readFailures.shift();
  };
  const readFailureReport = () => readFailures.map((item) => ({ ...item }));

  async function deviceData(text, reader) {
    let result;
    try {
      result = await client.sendDevice(text);
    } catch (error) {
      const code = error.code || 'transport-error';
      if (code === 'FENCED' || code === 'COMMAND_TIMEOUT' || code === 'COMMAND_INFLIGHT') remember(reader, null, text, code);
      recordReadFailure(reader, code, error.message || '', text, error.command && /^[A-Za-z0-9._:-]+$/.test(error.command) ? error.command : null);
      return null;
    }
    if (result.id) lastDeviceCommandId = result.id;
    lastDeviceRaw = result.data === undefined ? null : result.data;
    if (!result.ok) {
      const code = result.code || 'device-error';
      lastDeviceError = { code, detail: result.detail || '', id: result.id || null };
      const unknown = /unknown-device-command|unhandled-device-command|unsupported-device-command/.test(code);
      if (unknown) remember(reader, null, text, code);
      recordReadFailure(reader, code, result.detail || '', text, result.id || null);
      return null;
    }
    lastDeviceError = null;
    if (!isObject(result.data)) {
      remember(reader, 'data', text);
      recordReadFailure(reader, 'empty-data', 'producer data was not an object', text, result.id || null);
      return null;
    }
    return result.data;
  }

  async function rawLifecycle() {
    const data = await deviceData('lifecycle-diag', 'lifecycle-diag');
    if (!data || data.phase !== 'lifecycle-diag') {
      remember('lifecycle-diag', 'phase');
      return null;
    }
    return data;
  }

  /**
   * The registry grant for the selected page, exactly as the producer reports it. An explicit
   * foreground open grants default control after the page is ready; this read is the only evidence
   * of it. A UI control tap is never evidence. Missing fields stay null.
   */
  async function readGrantRaw() {
    const state = await rawLifecycle();
    if (!state) return null;
    return {
      grantForSelected: isBool(state.grantForSelected) ? state.grantForSelected : null,
      mode: isText(state.mode) ? state.mode : null,
      agentOn: isBool(state.agentOn) ? state.agentOn : null,
      pageRetired: isBool(state.pageRetired) ? state.pageRetired : null,
      pageReady: isBool(state.pageReady) ? state.pageReady : null,
      selectedTabId: isText(state.selectedTabId) ? state.selectedTabId : null,
    };
  }

  /**
   * Waits for the default grant an explicit open produces once its page is ready. This is the only
   * accepted evidence for a default open: the diag read. A UI hand-back is opt-in (`allowManualHandback`)
   * and only for a real manual takeover test; it can never stand in for a missing default grant.
   */
  async function ensureAgentControl({ budgetMs = 5000, pollMs = 200, allowManualHandback = false } = {}) {
    const started = now();
    let last = null;
    // Keep polling after a transient false: the ready probe and the grant it triggers are async,
    // so one early false read is not proof that the open never grants.
    while (now() - started < budgetMs) {
      last = await readGrantRaw();
      if (last && last.grantForSelected === true) {
        return {
          ok: true,
          source: 'default-grant',
          tapped: false,
          grantPresent: true,
          detail: 'default-grant',
          state: last,
        };
      }
      await sleep(pollMs);
    }
    if (!last || !isBool(last.grantForSelected)) {
      remember('lifecycle-diag', 'grantForSelected');
      return {
        ok: false,
        source: 'grant-unreadable',
        tapped: false,
        grantPresent: null,
        detail: 'grant-unreadable',
        state: last,
      };
    }
    // Default control was not granted (or was revoked). Only an explicit manual-takeover test may
    // ask for a hand-back; otherwise this is a default-open defect and must be reported as such.
    if (allowManualHandback !== true) {
      return {
        ok: false,
        source: 'no-default-grant',
        tapped: false,
        grantPresent: false,
        detail: 'no-default-grant',
        state: last,
      };
    }
    if (last.pageRetired === true || last.mode !== 'MANUAL' || last.agentOn === true) {
      return {
        ok: false,
        source: 'manual-takeover-not-proven',
        tapped: false,
        grantPresent: false,
        detail: 'manual-takeover-not-proven',
        state: last,
      };
    }
    let tapped = false;
    try {
      tapped = await io.tapLabelRaw('Resume agent');
    } catch (_error) {
      tapped = false;
    }
    const handbackDeadline = now() + Math.min(budgetMs, 3000);
    let after = null;
    while (now() < handbackDeadline) {
      after = await readGrantRaw();
      if (after && after.grantForSelected === true) break;
      await sleep(pollMs);
    }
    const granted = Boolean(after && after.grantForSelected === true);
    return {
      ok: granted,
      source: tapped ? 'manual-handback' : 'manual-handback-missing',
      tapped,
      grantPresent: granted,
      detail: granted
        ? 'manual-handback-granted'
        : tapped ? 'tapped-without-grant' : 'handback-control-missing',
      state: after || last,
    };
  }

  async function rawObserve() {
    const data = await deviceData('observe', 'observe');
    const observe = normalizeObserve(data);
    if (!observe) {
      remember('observe', data && data.error ? String(data.error) : 'url/metrics');
      return null;
    }
    return observe;
  }

  async function rawFrame() {
    const data = await deviceData('page-rect', 'page-rect');
    const projection = lastDeviceError
      ? { frame: null, reason: 'command-error', missingFields: [], rawSafe: lastDeviceRaw }
      : frameProjection(data);
    lastFrameRead = {
      commandId: lastDeviceCommandId,
      observedAt: now(),
      raw: lastDeviceRaw,
      error: lastDeviceError ? { ...lastDeviceError } : null,
      reason: projection.reason,
      missingFields: projection.missingFields.slice(),
      projection: {
        reason: projection.reason,
        missingFields: projection.missingFields.slice(),
        rawSafe: projection.rawSafe,
      },
    };
    const frame = projection.frame;
    if (!frame) {
      remember('page-rect', projection.missingFields[0] || projection.reason);
      recordReadFailure('page-rect', `frame-projection:${projection.reason}`, projection.missingFields.join(','), 'page-rect', lastDeviceCommandId);
      return null;
    }
    return frame;
  }

  let scrollSamples = [];
  let scrollSetup = null;
  const scrollSource = (sample) => sample && sample.lifecycle && sample.frame && sample.observe ? {
    selectedTabId: sample.lifecycle.selectedTabId == null ? null : sample.lifecycle.selectedTabId,
    viewHandle: sample.frame.viewHandle == null ? null : sample.frame.viewHandle,
    timeOrigin: sample.observe.timeOrigin == null ? null : sample.observe.timeOrigin,
    url: sample.observe.url == null ? null : sample.observe.url,
  } : null;
  const sameScrollSource = (a, b) => {
    const x = scrollSource(a), y = scrollSource(b);
    return Boolean(x && y && x.selectedTabId != null && x.selectedTabId === y.selectedTabId &&
      x.viewHandle != null && x.viewHandle === y.viewHandle &&
      x.timeOrigin != null && x.timeOrigin === y.timeOrigin &&
      x.url != null && x.url === y.url && isFixtureUrl(y.url));
  };
  const scrollReady = (sample) => Boolean(sample && sample.lifecycle && sample.frame && sample.observe &&
    sample.lifecycle.pageReady === true && sample.lifecycle.mouseMode === true &&
    sample.lifecycle.selectedTabId != null && sample.lifecycle.viewHandle != null &&
    sample.lifecycle.currentIdentity != null && sample.lifecycle.currentIdentity === sample.lifecycle.viewHandle &&
    sample.frame.viewHandle === sample.lifecycle.viewHandle &&
    sample.frame.pointerUi && sample.frame.pointerUi.geometryReady === true &&
    sample.frame.pointerUi.viewHandle === sample.frame.viewHandle &&
    isFixtureUrl(sample.observe.url) && isNum(sample.observe.timeOrigin) &&
    isNum(sample.observe.sy) && isObject(sample.rawObserve));
  const captureScrollSample = async (phase) => {
    const lifecycle = await rawLifecycle();
    const frame = await rawFrame();
    const frameRead = lastFrameRead ? JSON.parse(JSON.stringify(lastFrameRead)) : null;
    const rawObserve = await deviceData('observe', 'observe');
    const observe = normalizeObserve(rawObserve);
    const sample = { phase, observedAt: now(), lifecycle: lifecycle ? {
      selectedTabId: lifecycle.selectedTabId == null ? null : lifecycle.selectedTabId,
      viewHandle: lifecycle.viewHandle == null ? null : lifecycle.viewHandle,
      currentIdentity: lifecycle.currentIdentity == null ? null : lifecycle.currentIdentity,
      documentEpoch: lifecycle.documentEpoch == null ? null : lifecycle.documentEpoch,
      mouseMode: lifecycle.mouseMode == null ? null : lifecycle.mouseMode,
      pageReady: lifecycle.pageReady == null ? null : lifecycle.pageReady,
      mode: lifecycle.mode == null ? null : lifecycle.mode,
    } : null,
      frame: frame ? {
        physical: frame.physical,
        page: frame.page,
        pointerUi: frame.pointerUi,
        viewHandle: frame.viewHandle,
        documentEpoch: frame.documentEpoch,
      } : null,
      rawFrame: frameRead,
      projection: frameRead ? frameRead.projection : null,
      observe: observe ? {
        url: observe.url,
        timeOrigin: observe.timeOrigin,
        sy: observe.sy,
        loads: observe.loads,
        posts: observe.posts,
      } : null,
      rawObserve: isObject(rawObserve) ? {
        url: rawObserve.url == null ? null : rawObserve.url,
        timeOrigin: rawObserve.timeOrigin == null ? null : rawObserve.timeOrigin,
        sy: rawObserve.sy == null ? null : rawObserve.sy,
        scrollY: rawObserve.scrollY == null ? null : rawObserve.scrollY,
        maxScrollY: rawObserve.maxScrollY == null ? null : rawObserve.maxScrollY,
        wheel: isNum(rawObserve.wheel) ? rawObserve.wheel
          : isObject(rawObserve.report) && isNum(rawObserve.report.wheel) ? rawObserve.report.wheel : null,
        loads: rawObserve.loads == null ? null : rawObserve.loads,
        log: Array.isArray(rawObserve.log) ? rawObserve.log.slice(-20) : null,
        logTail: Array.isArray(rawObserve.logTail) ? rawObserve.logTail.slice(-20) : null,
      } : null,
    };
    scrollSamples.push(sample);
    while (scrollSamples.length > 24) scrollSamples.shift();
    return sample;
  };
  const scrollGesture = async (dy) => {
    scrollSamples = [];
    scrollSetup = {
      ok: false,
      reason: 'scroll-preflight',
      before: null,
      after: null,
      expected: null,
      expectedDirection: null,
      command: null,
      reply: null,
      samples: [],
    };
    if (!isNum(dy) || dy === 0) {
      scrollSetup.reason = dy === 0 ? 'scroll-dy-zero' : 'scroll-dy-missing';
      return false;
    }
    const expectedDirection = dy < 0 ? 'up' : 'down';
    const deadline = now() + 4000;
    let previous = null;
    let stable = null;
    let last = null;
    while (now() < deadline) {
      const sample = await captureScrollSample('before');
      last = sample;
      if (!scrollReady(sample)) {
        previous = null;
        await sleep(200);
        continue;
      }
      if (previous && sameScrollSource(previous, sample) &&
          JSON.stringify(previous.frame.physical) === JSON.stringify(sample.frame.physical) &&
          JSON.stringify(previous.frame.pointerUi) === JSON.stringify(sample.frame.pointerUi)) {
        stable = sample;
        break;
      }
      previous = sample;
      await sleep(200);
    }
    scrollSetup.samples = scrollSamples.slice();
    if (!stable) {
      scrollSetup.reason = 'scroll-preflight-not-ready';
      scrollSetup.before = last;
      return false;
    }
    const beforeSy = stable.observe.sy;
    const beforeWheel = stable.rawObserve && isNum(stable.rawObserve.wheel) ? stable.rawObserve.wheel : null;
    scrollSetup.expectedDirection = expectedDirection;
    const center = stable.frame.physical;
    if (!center) {
      scrollSetup.reason = 'scroll-physical-missing';
      scrollSetup.before = stable;
      return false;
    }
    const screenX = Math.round(center.left + center.width / 2);
    const screenY = Math.round(center.top + center.height / 2);
    const command = `two-finger-scroll:${screenX},${screenY},${Math.round(dy)}`;
    scrollSetup.expected = scrollSource(stable);
    scrollSetup.command = { text: command, screenX, screenY, dy: Math.round(dy), viewport: stable.frame.page ? stable.frame.page.visualViewport : null, pointerUi: stable.frame.pointerUi, frame: stable.frame.physical, beforeSy, beforeWheel };
    let reply;
    try {
      reply = await client.sendDevice(command);
    } catch (error) {
      scrollSetup.reason = 'scroll-send-error';
      scrollSetup.reply = { ok: false, code: error.code || 'transport-error', detail: String(error.message || error).slice(0, 300), commandId: error.command || null };
      scrollSetup.before = stable;
      return false;
    }
    scrollSetup.reply = {
      id: reply.id || null,
      ok: reply.ok === true,
      code: reply.code || '',
      detail: reply.detail || '',
      sent: Boolean(isObject(reply.data) && reply.data.sent === true),
      data: reply.data || null,
      observedAt: now(),
    };
    scrollSetup.before = stable;
    if (!reply.ok || !isObject(reply.data) || reply.data.sent !== true) {
      scrollSetup.reason = reply.ok ? 'scroll-sent-not-true' : 'scroll-command-failed';
      return false;
    }
    const afterDeadline = now() + 4000;
    let observed = null;
    let wrongDirection = null;
    let lastAfter = stable;
    while (now() < afterDeadline) {
      const sample = await captureScrollSample('after');
      lastAfter = sample;
      if (!sameScrollSource(stable, sample)) {
        scrollSetup.reason = 'scroll-source-changed';
        scrollSetup.after = sample;
        scrollSetup.samples = scrollSamples.slice();
        return false;
      }
      if (!isNum(sample.observe.sy)) {
        await sleep(200);
        continue;
      }
      if (sample.observe.sy === beforeSy) {
        await sleep(200);
        continue;
      }
      const expectedDelta = expectedDirection === 'up' ? sample.observe.sy > beforeSy : sample.observe.sy < beforeSy;
      if (!expectedDelta) {
        wrongDirection = sample;
        break;
      }
      observed = sample;
      break;
    }
    const afterWheel = lastAfter.rawObserve && isNum(lastAfter.rawObserve.wheel) ? lastAfter.rawObserve.wheel : null;
    const maxScrollY = lastAfter.rawObserve && isNum(lastAfter.rawObserve.maxScrollY) ? lastAfter.rawObserve.maxScrollY : null;
    const chosenAfter = observed || wrongDirection || lastAfter;
    scrollSetup.after = chosenAfter;
    scrollSetup.maxScrollY = maxScrollY;
    scrollSetup.afterWheel = afterWheel;
    scrollSetup.expectedDirection = expectedDirection;
    scrollSetup.delta = chosenAfter && isNum(chosenAfter.observe && chosenAfter.observe.sy) ? chosenAfter.observe.sy - beforeSy : null;
    scrollSetup.samples = scrollSamples.slice();
    if (wrongDirection) {
      scrollSetup.reason = 'scroll-wrong-direction';
      scrollSetup.wrongDirection = true;
      return false;
    }
    if (!observed) {
      const atBoundary = (expectedDirection === 'up' && maxScrollY != null && beforeSy >= maxScrollY) ||
        (expectedDirection === 'down' && beforeSy <= 0);
      scrollSetup.reason = atBoundary
        ? 'scroll-boundary-no-movement'
        : afterWheel != null && beforeWheel != null && afterWheel > beforeWheel
          ? 'wheel-event-no-scroll'
          : 'no-scroll-observed';
      return false;
    }
    scrollSetup.delta = observed.observe.sy - beforeSy;
    scrollSetup.ok = true;
    scrollSetup.reason = 'scroll-observed';
    return true;
  };

  const io = {
    now,
    sleepMs: sleep,
    runId: currentRunId,
    serial,
    package: pkg,
    // Fixed fixture URLs used by the existing pure lifecycle setup. The decorator only ever attaches
    // to these example.com fixtures; the chat client is never decorated.
    fixtureUrl: FIXTURES.mouse,
    longUrl: FIXTURES.mouse,
    otherUrl: FIXTURES.second,
    newDocumentUrl: FIXTURES.second,
    postLoginUrl: FIXTURES.login,
    call: (action, input = {}) => client.sendTool(action, input),
    missing,
    missingReport,
    readFailures: readFailureReport,
    frameReadDiagnostics: () => lastFrameRead ? JSON.parse(JSON.stringify(lastFrameRead)) : null,

    readLifecycleRaw: rawLifecycle,

    readFixtureRaw: async () => {
      const data = await deviceData('fixture-state', 'fixture-state');
      const fixture = normalizeFixture(data);
      if (!fixture) {
        remember('fixture-state', 'data');
        if (data) recordReadFailure('fixture-state', 'fixture-state-schema', 'page/native shape not accepted', 'fixture-state', lastDeviceCommandId);
        return null;
      }
      // The lifecycle probe needs loads/posts from the page. fixture-state carries posts; observe carries
      // generic metrics including loads. Merge only exact typed values and never default a missing one.
      const observe = await rawObserve();
      if (fixture.page && fixture.page.loads == null && observe && isNum(observe.loads)) fixture.page.loads = observe.loads;
      if (fixture.page && fixture.page.posts == null && observe && isNum(observe.posts)) fixture.page.posts = observe.posts;
      if (fixture.page && fixture.page.url == null && observe && isText(observe.url)) fixture.page.url = observe.url;
      return fixture;
    },

    readMetricsRaw: async () => {
      const observe = await rawObserve();
      if (!observe) return null;
      return {
        ua: observe.ua,
        clientWidth: observe.clientWidth,
        media1000: observe.media1000,
        sy: observe.sy,
        timeOrigin: observe.timeOrigin,
        loads: observe.loads,
        posts: observe.posts,
      };
    },

    readUiRaw: async () => {
      const data = await deviceData('ui-state', 'ui-state');
      const ui = normalizeUi(data);
      if (!ui) {
        remember('ui-state', 'labels/surface');
        return null;
      }
      return ui;
    },

    readPageRaw: async () => {
      const data = await deviceData('observe', 'observe.page');
      if (!isObject(data)) return null;
      const observe = normalizeObserve(data);
      if (!observe) {
        remember('observe.page', 'url/not-fixture');
        return null;
      }
      const page = normalizePage(isObject(data.page) ? data.page : data);
      if (!page) remember('observe.page', 'data');
      return page;
    },

    readNativeViewRaw: async () => {
      const diag = await rawLifecycle();
      if (!diag) return null;
      return { currentViewPresent: diag.currentIdentity != null };
    },

    readFrameRaw: rawFrame,

    readObserveRaw: async () => {
      const data = await deviceData('observe', 'observe');
      return isObject(data) ? data : null;
    },

    tapLabelRaw: async (label) => {
      if (!UI_LABELS.has(label)) {
        remember(`ui-target:${label}`, 'label-not-allowed');
        return false;
      }
      // A UI control tap is a read-only ui-target lookup followed by one real ADB touch. The producer
      // deliberately refuses tap-label for UI controls, so no presenter grant or JS click is involved.
      if (!isObject(screen) || !isNum(screen.width) || !isNum(screen.height)) {
        remember(`ui-target:${label}`, 'screen-bounds-unavailable');
        return false;
      }
      if (typeof tapScreen !== 'function') {
        remember(`ui-target:${label}`, 'host-tap-unavailable');
        return false;
      }
      const data = await deviceData(`ui-target:${label}`, `ui-target:${label}`);
      if (!data) return false;
      const target = isObject(data.target) ? data.target : data;
      const rejection = validateExportedTarget(target, {
        runId: currentRunId,
        phase: 'ui-target',
        label,
        package: pkg,
        screenWidth: screen.width,
        screenHeight: screen.height,
        now: now(),
      });
      if (rejection) {
        remember(`ui-target:${label}`, rejection);
        return false;
      }
      const bounds = normalizeBounds(target);
      if (!bounds) {
        remember(`ui-target:${label}`, 'bounds');
        return false;
      }
      try {
        await tapScreen(bounds);
        return true;
      } catch (error) {
        remember(`ui-target:${label}`, String(error && error.message || error));
        return false;
      }
    },

    // Fixture form controls are not UI labels or screen points. fixture-field is a DOM precondition
    // reader: field/present/tag/type/value/valueLength only, no Accessibility windowId or bounds. The
    // later tap-label performs the producer's own visible-intersection/hit test and real touch.
    fixtureFieldTarget: async (field) => {
      if (field !== 'login-user' && field !== 'Sign in') {
        remember(`fixture-field:${field}`, 'field-not-allowed');
        return null;
      }
      const data = await deviceData(`fixture-field:${field}`, `fixture-field:${field}`);
      if (!data) return null;
      if (data.phase !== 'fixture-field') {
        remember(`fixture-field:${field}`, 'missing-DOM-phase');
        return null;
      }
      if (data.field !== field) {
        remember(`fixture-field:${field}`, 'wrong-field');
        return null;
      }
      if (data.present !== true) {
        remember(`fixture-field:${field}`, 'present-not-true');
        return null;
      }
      if (data.tag !== 'INPUT' && data.tag !== 'BUTTON') {
        remember(`fixture-field:${field}`, 'tag-not-INPUT-or-BUTTON');
        return null;
      }
      if (typeof data.type !== 'string' || /password|file/i.test(data.type)) {
        remember(`fixture-field:${field}`, 'refused-input-type');
        return null;
      }
      if (typeof data.value !== 'string' || !isNum(data.valueLength) || data.value.length !== data.valueLength) {
        remember(`fixture-field:${field}`, 'value-length-mismatch');
        return null;
      }
      // Keep the producer's DOM shape. It is a precondition, not a click point.
      return data;
    },

    tapFixtureLabelTyped: async (field, expected = {}) => {
      if (field !== 'login-user' && field !== 'Sign in') {
        remember(`tap-label:${field}`, 'field-not-allowed');
        return { ok: false, field, command: `tap-label:${field}`, reason: `tap-label:${field}:field-not-allowed`, sent: false, hit: false, visible: false, code: 'field-not-allowed', detail: '', data: null };
      }
      const command = `tap-label:${field}`;
      let result;
      try {
        result = await client.sendDevice(command);
      } catch (error) {
        return { ok: false, field, command, reason: `tap-label:${field}:transport-error`, sent: false, hit: false, visible: false, code: error.code || 'transport-error', detail: String(error.message || error), data: null };
      }
      if (!result.ok) {
        const code = result.code || 'device-error';
        const detail = result.detail || '';
        // Only a truly unknown/unsupported command is a missing reader. A real hit-test/touch failure
        // keeps its actual code and never claims the reader was not implemented.
        const missingReader = /unknown-device-command|unsupported-device-command|unsupported-fixture-label/i.test(`${code} ${detail}`);
        if (missingReader) remember(`tap-label:${field}`, null, command, code);
        return { ok: false, field, command, reason: `tap-label:${field}:${code}`, sent: false, hit: false, visible: false, code, detail, data: null, missingReader };
      }
      const data = isObject(result.data) ? result.data : null;
      const sent = Boolean(data && data.sent === true);
      const hit = Boolean(data && data.hit === true);
      // The producer's literal success field is pointVisible. Accept visible for older readbacks, but
      // never invent either field when both are absent.
      const visible = Boolean(data && (data.pointVisible === true || data.visible === true));
      const viewHandle = realOr(data && data.viewHandle, 'number');
      const documentEpoch = realOr(data && data.documentEpoch, 'number');
      const expectedHandle = realOr(expected.viewHandle, 'number');
      const expectedEpoch = realOr(expected.documentEpoch, 'number');
      const identityOk = (expectedHandle == null || viewHandle === expectedHandle) &&
        (expectedEpoch == null || documentEpoch === expectedEpoch);
      let reason = 'ok';
      if (!sent) reason = 'sent-false';
      else if (!hit) reason = 'hit-false';
      else if (!visible) reason = 'visible-false';
      else if (!identityOk) reason = 'source-identity-mismatch';
      const ok = sent && hit && visible && identityOk;
      return {
        ok,
        field,
        command,
        reason: `tap-label:${field}:${reason}`,
        sent,
        hit,
        visible,
        viewHandle,
        documentEpoch,
        code: '',
        detail: '',
        data,
        missingReader: false,
      };
    },

    tapFixtureLabelRaw: async (field) => (await io.tapFixtureLabelTyped(field)).ok,

    wheelRaw: async (dy) => scrollGesture(dy),
    twoFingerRaw: async (dy) => scrollGesture(dy),
    scrollSetupDiagnostics: () => scrollSetup ? JSON.parse(JSON.stringify(scrollSetup)) : null,

    switchModeRaw: async (target) => {
      const label = target === 'computer' ? 'Computer layout' : target === 'phone' ? 'Phone layout' : null;
      if (!label) return false;
      if (!(await io.tapLabelRaw('More'))) return false;
      await sleep(800);
      if (!(await io.tapLabelRaw(label))) return false;
      await sleep(800);
      return io.tapLabelRaw('Confirm');
    },

    selectRaw: async (tabId) => {
      if (!isText(tabId)) return false;
      const data = await deviceData(`select:${tabId}`, 'select');
      return Boolean(data && data.selected === true && data.tabId === tabId);
    },

    enableMouseRaw: async () => {
      const before = await rawLifecycle();
      if (!before) return false;
      if (!isBool(before.mouseMode)) {
        remember('lifecycle-diag', 'mouseMode');
        return false;
      }
      if (before.mouseMode === true) return true;
      // A fast fixture can be ready before Compose exposes its chrome to accessibility.
      const uiDeadline = now() + 5000;
      let menuReady = false;
      while (now() < uiDeadline) {
        const ui = await deviceData('ui-state', 'mouse-ui-ready');
        if (isObject(ui?.labels?.More)) { menuReady = true; break; }
        await sleep(100);
      }
      if (!menuReady) return false;
      if (!(await io.tapLabelRaw('More'))) return false;
      await sleep(800);
      if (!(await io.tapLabelRaw('Mouse'))) return false;
      const deadline = now() + 8000;
      while (now() < deadline) {
        const state = await rawLifecycle();
        if (state && state.mouseMode === true) return true;
        await sleep(300);
      }
      return false;
    },

    releaseOwnedRaw: async () => true,

    // Explicit hand-back after a real manual takeover; the diag grant read is the only evidence.
    ensureAgentControl,
    readGrantRaw,

    submitLoginFresh: async () => {
      if (typeof humanType !== 'function') {
        return {
          ok: false,
          reason: 'human-type-unavailable',
          detail: 'host adb input text callback missing (no JS form submit is allowed)',
        };
      }
      const phases = {
        beforeTap: null,
        afterTap: null,
        beforeType1: null,
        beforeType2: null,
        lastFocus: null,
        afterTypeImmediate: null,
        afterTypeFinal: null,
        lastIme: null,
        beforeSubmit: null,
        afterSubmit: null,
        imeBack: null,
      };
      let latest = null;
      let humanReceipt = null;
      let inputTap = null;
      let submitTap = null;
      const samples = [];
      const record = (phase, fixture) => {
        if (!fixture) return fixture;
        latest = fixture;
        samples.push({ phase, observedAt: now(), fixture });
        while (samples.length > 16) samples.shift();
        return fixture;
      };
      const capture = (phase, fixture) => {
        if (!fixture) return fixture;
        phases[phase] = fixture;
        return record(phase, fixture);
      };
      const fail = (stage, reason, fields = [], extra = {}) => ({
        ok: false,
        reason,
        stage,
        missing: fields.length ? { stage, fields: [...new Set(fields)] } : null,
        last: latest,
        phases: { ...phases },
        samples: samples.map((sample) => ({ phase: sample.phase, observedAt: sample.observedAt, fixture: sample.fixture })),
        humanType: humanReceipt,
        taps: { input: inputTap, submit: submitTap },
        readFailures: readFailureReport(),
        ...extra,
      });
      const sourceFields = (fixture, lifecycle, expected) => {
        const fields = [];
        if (!fixture || !fixture.page) return ['fixture-state.page'];
        const page = fixture.page;
        const native = fixture.native;
        if (page.url !== expected.url) fields.push(page.url == null ? 'page.url:missing' : 'page.url:changed');
        if (page.timeOrigin == null) fields.push('page.timeOrigin:missing');
        else if (page.timeOrigin !== expected.timeOrigin) fields.push('page.timeOrigin:changed');
        if (!native) fields.push('native:missing');
        else {
          if (native.selectedTabId == null) fields.push('native.selectedTabId:missing');
          else if (native.selectedTabId !== expected.tabId) fields.push('native.selectedTabId:changed');
          if (native.viewHandle == null) fields.push('native.viewHandle:missing');
          else if (native.viewHandle !== expected.viewHandle) fields.push('native.viewHandle:changed');
        }
        if (!lifecycle || lifecycle.selectedTabId !== expected.tabId) fields.push('lifecycle.selectedTabId:changed');
        return fields;
      };
      const focusFields = (fixture) => {
        const fields = [];
        const check = (path, value, expected) => {
          if (value == null) fields.push(`${path}:missing`);
          else if (value !== expected) fields.push(`${path}:${String(value)}`);
        };
        if (!fixture || !fixture.page) return ['fixture-state.page'];
        check('page.inputFocused', fixture.page.inputFocused, true);
        check('page.activeTag', fixture.page.activeTag, 'INPUT');
        check('page.documentHasFocus', fixture.page.documentHasFocus, true);
        check('page.inputEnabled', fixture.page.inputEnabled, true);
        check('page.inputReadOnly', fixture.page.inputReadOnly, false);
        check('page.formPresent', fixture.page.formPresent, true);
        check('page.inputValue', fixture.page.inputValue, '');
        if (!fixture.native) return [...fields, 'native:missing'];
        check('native.mouseMode', fixture.native.mouseMode, false);
        check('native.hasFocus', fixture.native.hasFocus, true);
        check('native.hasWindowFocus', fixture.native.hasWindowFocus, true);
        check('native.shown', fixture.native.shown, true);
        check('native.pageReady', fixture.native.pageReady, true);
        return fields;
      };
      const fatalFields = (fields) => fields.some((field) =>
        field.endsWith(':missing') ||
        field.includes(':changed') ||
        field === 'native:missing' ||
        field === 'fixture-state.page' ||
        field === 'page.inputValue:nonempty' ||
        field === 'native.mouseMode:true' ||
        field === 'page.inputReadOnly:true');
      const opened = await io.call('browser.open', { url: FIXTURES.login });
      if (!opened.ok) return { ok: false, reason: 'login-open-failed', opened };
      const tabId = opened.data && opened.data.tabId ? opened.data.tabId : null;
      if (!isText(tabId)) return { ok: false, reason: 'login-open-no-tab', opened };
      const readyDeadline = now() + 15000;
      let ready = null;
      while (now() < readyDeadline) {
        const state = await rawLifecycle();
        if (state && state.selectedTabId === tabId && state.pageReady === true) { ready = state; break; }
        await sleep(250);
      }
      if (!ready) return { ok: false, reason: 'login-page-not-ready', tabId };
      // An explicit controlled open grants default agent control once the page is ready. POST needs
      // the person's MANUAL mode, and page touch no longer revokes, so this flow must first confirm
      // the real default grant and then use the real Take over control. The diag read after it is the
      // only proof; no grant is written by the harness.
      const grantDeadline = now() + 5000;
      let defaultGrant = null;
      let lastGrantState = ready;
      while (now() < grantDeadline) {
        const state = await rawLifecycle();
        if (state) {
          lastGrantState = state;
          if (state.selectedTabId === tabId && state.pageReady === true &&
              state.grantForSelected === true && state.mode === 'AGENT' && state.agentOn === true) {
            defaultGrant = state;
            break;
          }
        }
        await sleep(200);
      }
      if (!defaultGrant) {
        phases.defaultGrant = lastGrantState && isObject(lastGrantState) ? {
          selectedTabId: lastGrantState.selectedTabId == null ? null : lastGrantState.selectedTabId,
          pageReady: lastGrantState.pageReady == null ? null : lastGrantState.pageReady,
          grantForSelected: isBool(lastGrantState.grantForSelected) ? lastGrantState.grantForSelected : null,
          mode: isText(lastGrantState.mode) ? lastGrantState.mode : null,
          agentOn: isBool(lastGrantState.agentOn) ? lastGrantState.agentOn : null,
        } : null;
        return fail('takeover', 'login-default-grant-not-confirmed', ['grantForSelected', 'mode', 'agentOn'], { takeover: { tapped: false, defaultGrant: false, state: phases.defaultGrant } });
      }
      phases.defaultGrant = {
        grantForSelected: defaultGrant.grantForSelected,
        mode: defaultGrant.mode,
        agentOn: defaultGrant.agentOn,
        viewHandle: isNum(defaultGrant.viewHandle) ? defaultGrant.viewHandle : null,
        documentEpoch: isNum(defaultGrant.documentEpoch) ? defaultGrant.documentEpoch : null,
      };
      let takeoverTapped = false;
      try {
        takeoverTapped = await io.tapLabelRaw('Take over');
      } catch (_error) {
        takeoverTapped = false;
      }
      const takeoverDeadline = now() + 5000;
      let manual = null;
      while (takeoverTapped && now() < takeoverDeadline) {
        const state = await rawLifecycle();
        if (state && state.selectedTabId === tabId && state.pageReady === true &&
            state.grantForSelected === false && state.mode === 'MANUAL' && state.agentOn === false) {
          manual = state;
          break;
        }
        await sleep(200);
      }
      if (!manual) {
        return fail('takeover', takeoverTapped ? 'login-takeover-not-observed' : 'login-takeover-tap-failed',
          ['tap-label:Take over', 'grantForSelected=false', 'mode=MANUAL', 'agentOn=false'], {
            takeover: {
              tapped: takeoverTapped,
              defaultGrant: phases.defaultGrant,
              state: lastGrantState && isObject(lastGrantState) ? {
                selectedTabId: lastGrantState.selectedTabId == null ? null : lastGrantState.selectedTabId,
                grantForSelected: isBool(lastGrantState.grantForSelected) ? lastGrantState.grantForSelected : null,
                mode: isText(lastGrantState.mode) ? lastGrantState.mode : null,
                agentOn: isBool(lastGrantState.agentOn) ? lastGrantState.agentOn : null,
              } : null,
            },
          });
      }
      // A takeover is UI only: the same view must still be behind the form.
      if (isNum(defaultGrant.viewHandle) && isNum(manual.viewHandle) && manual.viewHandle !== defaultGrant.viewHandle) {
        return fail('takeover', 'login-takeover-changed-view', ['viewHandle'], {
          takeover: { tapped: true, viewHandleBefore: defaultGrant.viewHandle, viewHandleAfter: manual.viewHandle },
        });
      }
      phases.takeover = {
        tapped: true,
        grantForSelected: manual.grantForSelected,
        mode: manual.mode,
        agentOn: manual.agentOn,
        viewHandle: isNum(manual.viewHandle) ? manual.viewHandle : null,
        documentEpoch: isNum(manual.documentEpoch) ? manual.documentEpoch : null,
      };
      const before = await io.readFixtureRaw();
      if (!capture('beforeTap', before)) return fail('beforeTap', 'fixture-state-missing', ['fixture-state']);
      const beforePosts = before.page ? before.page.posts : null;
      const beforeArrivals = before.postArrivals;
      if (!isNum(beforePosts) || !isNum(beforeArrivals)) {
        return fail('beforeTap', 'fixture-post-baseline-missing', ['fixture-state.page.posts', 'fixture-state.postArrivals'], { beforePosts, beforeArrivals });
      }
      const expected = {
        url: FIXTURES.login,
        tabId,
        viewHandle: before.native ? before.native.viewHandle : ready.viewHandle,
        timeOrigin: before.page.timeOrigin,
      };
      const beforeFields = sourceFields(before, ready, expected);
      if (before.page.url !== FIXTURES.login) beforeFields.push('page.url:not-login');
      if (!isNum(expected.viewHandle)) beforeFields.push('native.viewHandle:missing');
      if (!isNum(expected.timeOrigin)) beforeFields.push('page.timeOrigin:missing');
      if (before.page.inputValue == null) beforeFields.push('page.inputValue:missing');
      else if (before.page.inputValue !== '') beforeFields.push('page.inputValue:nonempty');
      if (before.page.formPresent !== true) beforeFields.push(`page.formPresent:${String(before.page.formPresent)}`);
      if (beforeFields.length) return fail('beforeTap', 'login-before-tap-precondition', beforeFields, { expected });

      // DOM precondition; the producer owns the real hit test and touch.
      const inputTarget = await io.fixtureFieldTarget('login-user');
      if (!inputTarget) return fail('fixture-field:login-user', 'missing-producer-reader:fixture-field:login-user', ['fixture-field:login-user']);
      inputTap = await io.tapFixtureLabelTyped('login-user', { viewHandle: expected.viewHandle });
      if (!inputTap.ok) {
        return fail('tap-label:login-user', inputTap.reason, inputTap.missingReader ? ['tap-label:login-user'] : [], { tap: inputTap });
      }

      const focusDeadline = now() + 8000;
      let stable = 0;
      let lastFocusFields = [];
      let focused = null;
      while (now() < focusDeadline) {
        const fixture = await io.readFixtureRaw();
        const state = await rawLifecycle();
        if (!fixture || !fixture.page) {
          return fail('focus', 'fixture-state-unreadable', ['fixture-state.page']);
        }
        if (!phases.afterTap) capture('afterTap', fixture);
        phases.lastFocus = fixture;
        record('lastFocus', fixture);
        const source = sourceFields(fixture, state, expected);
        const guard = focusFields(fixture);
        const fields = [...source, ...guard];
        lastFocusFields = fields;
        if (fields.length) {
          if (fatalFields(fields)) return fail('focus', 'login-focus-precondition', fields);
          stable = 0;
          await sleep(200);
          continue;
        }
        stable += 1;
        if (stable === 1) capture('beforeType1', fixture);
        if (stable >= 2) { focused = fixture; capture('beforeType2', fixture); break; }
        await sleep(200);
      }
      if (!focused) return fail('focus', 'login-focus-not-stable', lastFocusFields, { expected });

      const typedText = 'local-provider-matrix';
      const humanStarted = now();
      let rawReceipt = null;
      try {
        rawReceipt = await humanType(typedText);
      } catch (error) {
        humanReceipt = { tryCount: 1, exitCode: null, startedAtMs: humanStarted, finishedAtMs: now(), error: String(error && error.message || error) };
        return fail('humanType', 'human-type-failed', ['humanType.exitCode']);
      }
      const receipt = isObject(rawReceipt) ? rawReceipt : {};
      humanReceipt = {
        tryCount: 1,
        exitCode: realOr(receipt.exitCode, 'number'),
        startedAtMs: realOr(receipt.startedAtMs, 'number'),
        finishedAtMs: realOr(receipt.finishedAtMs, 'number'),
      };
      if (humanReceipt.exitCode == null) return fail('humanType', 'human-type-exit-missing', ['humanType.exitCode']);
      if (humanReceipt.exitCode !== 0) return fail('humanType', `human-type-exit:${humanReceipt.exitCode}`, ['humanType.exitCode']);

      const sourceOrInput = (fixture, state) => {
        const fields = sourceFields(fixture, state, expected);
        if (!fixture || !fixture.page) return [...fields, 'fixture-state.page'];
        if (fixture.page.activeTag !== 'INPUT') fields.push(`page.activeTag:${String(fixture.page.activeTag)}`);
        return fields;
      };
      const immediate = await io.readFixtureRaw();
      const immediateState = await rawLifecycle();
      capture('afterTypeImmediate', immediate);
      const immediateFields = sourceOrInput(immediate, immediateState);
      if (immediateFields.length) return fail('afterTypeImmediate', 'input-source-changed', immediateFields, { expected });

      const valueDeadline = now() + 5000;
      let typed = null;
      let lastValueFields = [];
      while (now() < valueDeadline) {
        const fixture = await io.readFixtureRaw();
        const state = await rawLifecycle();
        if (fixture) { phases.afterTypeFinal = fixture; record('afterTypeFinal', fixture); }
        const fields = sourceOrInput(fixture, state);
        if (fields.length) {
          if (fatalFields(fields)) return fail('afterTypeFinal', 'input-source-changed', fields);
          lastValueFields = fields;
          await sleep(200);
          continue;
        }
        const value = fixture.page.inputValue;
        if (value == null) return fail('afterTypeFinal', 'input-value-missing', ['page.inputValue:missing']);
        if (value === typedText) { typed = fixture; capture('afterTypeFinal', fixture); break; }
        lastValueFields = [`page.inputValue:actual-length:${value.length}`, `page.activeTag:${String(fixture.page.activeTag)}`];
        await sleep(200);
      }
      if (!typed) return fail('afterTypeFinal', 'input-value-not-applied', lastValueFields, { expected });

      let beforeSubmitState = typed;
      if (typed.ime && typed.ime.shown === true) {
        if (typeof pressBack !== 'function') return fail('ime', 'ime-shown-but-no-host-back', ['ime.shown:true']);
        phases.imeBack = { tryCount: 1 };
        try { await pressBack(); } catch (error) {
          return fail('ime', 'ime-back-failed', ['ime.back'], { detail: String(error && error.message || error) });
        }
        const hideDeadline = now() + 3000;
        let hidden = null;
        while (now() < hideDeadline) {
          const fixture = await io.readFixtureRaw();
          const state = await rawLifecycle();
          if (fixture) { phases.lastIme = fixture; record('lastIme', fixture); }
          const fields = sourceFields(fixture, state, expected);
          if (fields.length) return fail('ime', 'ime-source-changed', fields);
          if (fixture.page.inputValue !== typedText) return fail('ime', 'ime-input-value-changed', [`page.inputValue:actual-length:${String(fixture.page.inputValue == null ? 'null' : fixture.page.inputValue.length)}`]);
          if (!fixture.ime || fixture.ime.shown == null) return fail('ime', 'ime-state-missing', ['ime.shown:missing']);
          if (fixture.ime.shown === false) { hidden = fixture; break; }
          await sleep(100);
        }
        if (!hidden) return fail('ime', 'ime-not-hidden', ['ime.shown:true']);
        beforeSubmitState = hidden;
      } else if (typed.ime && typed.ime.shown == null) {
        // Unknown IME state: do not press Back and do not relax the submit hit test.
      }

      const preSubmit = await io.readFixtureRaw();
      const preSubmitLifecycle = await rawLifecycle();
      capture('beforeSubmit', preSubmit);
      // After a real IME Back the input may legitimately lose DOM focus; same tab/view/document and the
      // exact typed value still gate the single Sign in, and the producer hit test decides the target.
      const preFields = sourceFields(preSubmit, preSubmitLifecycle, expected);
      if (preSubmit.page.formPresent !== true) preFields.push(`page.formPresent:${String(preSubmit.page.formPresent)}`);
      if (preSubmit.page.inputValue !== typedText) preFields.push(`page.inputValue:actual-length:${String(preSubmit.page.inputValue == null ? 'null' : preSubmit.page.inputValue.length)}`);
      if (preFields.length) return fail('beforeSubmit', 'submit-precondition', preFields, { expected });
      const submitTarget = await io.fixtureFieldTarget('Sign in');
      if (!submitTarget) return fail('fixture-field:Sign in', 'missing-producer-reader:fixture-field:Sign in', ['fixture-field:Sign in'], { inputTap });
      submitTap = await io.tapFixtureLabelTyped('Sign in', { viewHandle: expected.viewHandle });
      if (!submitTap.ok) return fail('tap-label:Sign in', submitTap.reason, submitTap.missingReader ? ['tap-label:Sign in'] : [], { tap: submitTap, inputTap });

      capture('afterSubmit', await io.readFixtureRaw());
      const deadline = now() + 10000;
      let after = null;
      while (now() < deadline) {
        const fixture = await io.readFixtureRaw();
        capture('afterSubmit', fixture);
        if (fixture && fixture.page && fixture.page.posts === beforePosts + 1 &&
            fixture.postArrivals === beforeArrivals + 1) { after = fixture; break; }
        await sleep(250);
      }
      if (!after) return fail('afterSubmit', 'login-post-not-observed', ['page.posts', 'postArrivals'], { beforePosts, beforeArrivals });
      return {
        ok: true,
        tabId,
        beforePosts,
        beforeArrivals,
        phases: { ...phases },
        samples: samples.map((sample) => ({ phase: sample.phase, observedAt: sample.observedAt, fixture: sample.fixture })),
        humanType: humanReceipt,
        taps: { input: inputTap, submit: submitTap },
        readFailures: readFailureReport(),
        landing: after.page ? { url: after.page.url, posts: after.page.posts } : null,
        finalState: { page: after.page },
      };
    },
  };
  return io;
}

/**
 * Retirement IO. Geometry uses the existing pure createRetirementPointerIO: it locates the virtual
 * pointer from page-rect and resolves the DOM box through a fixed producer reader. Raw 7/11 events use
 * gate-finger; finger: is never treated as an absolute mouse point.
 */
function createGuestRetirementIo(guest, { client, now, sleep } = {}) {
  const missing = guest.missing;
  const remember = (reader, field = null, command = null) => {
    if (!missing.reader) missing.reader = reader;
    if (!missing.field && field) missing.field = field;
    if (!missing.command && command) missing.command = command;
  };
  const diagnosticRing = [];
  const MAX_DIAGNOSTICS = 24;
  let diagnosticSeq = 0;
  let lastFrame = null;
  let lastTargetFailure = null;
  const diagnosticNow = () => (typeof now === 'function' ? now() : Date.now());
  const readError = (error) => ({
    code: error && error.code ? String(error.code).slice(0, 80) : 'error',
    detail: String(error && error.message ? error.message : error).slice(0, 300),
  });
  const recordDiagnostic = (op, entry) => {
    diagnosticRing.push({ seq: ++diagnosticSeq, op, observedAt: diagnosticNow(), ...entry });
    while (diagnosticRing.length > MAX_DIAGNOSTICS) diagnosticRing.shift();
  };
  const recordFor = (raw) => {
    if (!raw || typeof raw !== 'object') return null;
    const current = currentRecordOf(raw);
    return {
      recorderViewId: raw.recorderViewId == null ? null : raw.recorderViewId,
      current: current ? { viewId: current.viewId, origin: current.origin, clicks: current.clicks } : null,
      lastRetired: raw.lastRetired && typeof raw.lastRetired === 'object' ? {
        viewId: raw.lastRetired.viewId == null ? null : raw.lastRetired.viewId,
        readyAtRetire: raw.lastRetired.readyAtRetire === true,
        documents: Array.isArray(raw.lastRetired.documents)
          ? raw.lastRetired.documents.slice(0, 8).map((doc) => ({
            origin: doc && doc.origin != null ? String(doc.origin) : null,
            installed: doc && doc.installed === true,
            clicks: doc && isNum(doc.clicks) ? doc.clicks : null,
          }))
          : null,
      } : null,
    };
  };
  const readLifecycle = async () => {
    try {
      const value = await guest.readLifecycleRaw();
      recordDiagnostic('identity', {
        ok: Boolean(value),
        result: value ? {
          selectedTabId: value.selectedTabId == null ? null : value.selectedTabId,
          viewHandle: value.viewHandle == null ? null : value.viewHandle,
          documentEpoch: value.documentEpoch == null ? null : value.documentEpoch,
          currentIdentity: value.currentIdentity == null ? null : value.currentIdentity,
          record: recordFor(value),
        } : null,
      });
      return value;
    } catch (error) {
      recordDiagnostic('identity', { ok: false, error: readError(error) });
      throw error;
    }
  };
  const readFrame = async () => {
    try {
      const value = await guest.readFrameRaw();
      lastFrame = value || null;
      recordDiagnostic('frame', {
        ok: Boolean(value),
        result: value ? {
          physical: value.physical,
          page: value.page,
          pointerUi: value.pointerUi,
          viewHandle: value.viewHandle,
          documentEpoch: value.documentEpoch,
        } : null,
      });
      return value;
    } catch (error) {
      recordDiagnostic('frame', { ok: false, error: readError(error) });
      throw error;
    }
  };

  // fixture-target:<box|next>,<cssX>,<cssY> is a fixed typed DOM read (rect + hit + page). It is not
  // arbitrary JS. If the producer does not implement it, the case refuses and names this command.
  const readFixtureTarget = async (id, pointer) => {
    lastTargetFailure = null;
    if (!['box', 'next'].includes(id) || !isObject(pointer) || !isNum(pointer.x) || !isNum(pointer.y)) {
      lastTargetFailure = { code: 'bad-fixture-target-input', detail: String(id) };
      return null;
    }
    // fixture-target is only valid on the RETIRE fixture. Check the current exact fixture URL first;
    // a chat/localhost page or a different fixture refuses instead of reading another document.
    const currentObserve = normalizeObserve(await guest.readObserveRaw());
    if (!currentObserve || currentObserve.url !== FIXTURES.retire) {
      remember('fixture-target', 'only-RETIRE_URL');
      lastTargetFailure = { code: 'only-RETIRE_URL', detail: currentObserve ? currentObserve.url : 'observe-unreadable' };
      return null;
    }
    const command = `fixture-target:${id},${Math.round(pointer.x * 100) / 100},${Math.round(pointer.y * 100) / 100}`;
    let result;
    try {
      result = await client.sendDevice(command);
    } catch (error) {
      remember('fixture-target', null, `fixture-target:${id}`);
      lastTargetFailure = { ...readError(error), command };
      return null;
    }
    if (!result.ok) {
      remember('fixture-target', null, `fixture-target:${id}`);
      lastTargetFailure = { code: result.code || 'fixture-target-failed', detail: result.detail || '', id: result.id || null, command };
      return null;
    }
    const data = result.data;
    const boxSource = isObject(data) ? (data.box || data.boxCSS || data.visibleRect || data.boxCss) : null;
    if (!isObject(data) || !isObject(boxSource) || !isBool(data.hit) || !isNum(data.timeOrigin)) {
      lastTargetFailure = { code: 'fixture-target-schema', detail: 'box/hit/timeOrigin missing', id: result.id || null, command, data };
      return null;
    }
    const box = normalizeBounds(boxSource);
    if (!box) {
      lastTargetFailure = { code: 'fixture-target-bounds', detail: 'box not finite', id: result.id || null, command, data };
      return null;
    }
    return {
      timeOrigin: data.timeOrigin,
      box,
      hit: data.hit,
      page: normalizePage(isObject(data.page) ? data.page : data),
      raw: data,
      css: { x: pointer.x, y: pointer.y },
      command,
      commandId: result.id || null,
    };
  };

  const domWrapped = async (id, pointer) => {
    const value = await readFixtureTarget(id, pointer);
    recordDiagnostic('dom', {
      ok: Boolean(value),
      input: { id, css: pointer, command: value ? value.command : null, commandId: value ? value.commandId : null },
      result: value ? {
        timeOrigin: value.timeOrigin,
        box: value.box,
        hit: value.hit,
        raw: value.raw || null,
        page: value.page,
      } : null,
      error: value ? null : lastTargetFailure,
    });
    return value;
  };
  const atomicWrapped = async (point) => {
    const frame = point && point.frame ? point.frame : null;
    const local = frame && isNum(point.x) && isNum(point.y)
      ? { x: point.x - frame.left, y: point.y - frame.top, dx: point.dx, dy: point.dy }
      : { x: null, y: null, dx: point ? point.dx : null, dy: point ? point.dy : null };
    const command = point
      ? `finger:${Math.round(point.x)},${Math.round(point.y)},${Math.round(point.dx)},${Math.round(point.dy)},0,${point.steps}`
      : 'finger:<missing>';
    let result;
    try {
      result = await client.sendDevice(command);
    } catch (error) {
      recordDiagnostic('atomic', {
        ok: false,
        input: { screen: point, local, frame, viewport: lastFrame && lastFrame.page ? lastFrame.page.visualViewport : null, pointerUi: lastFrame ? lastFrame.pointerUi : null, command },
        error: readError(error),
      });
      return false;
    }
    const sent = Boolean(result.ok && isObject(result.data) && result.data.sent === true);
    recordDiagnostic('atomic', {
      ok: sent,
      input: { screen: point, local, frame, viewport: lastFrame && lastFrame.page ? lastFrame.page.visualViewport : null, pointerUi: lastFrame ? lastFrame.pointerUi : null, command },
      result: {
        id: result.id || null,
        ok: result.ok === true,
        code: result.code || '',
        detail: result.detail || '',
        sent,
        data: result.data || null,
      },
    });
    return sent;
  };
  const pointerIO = createRetirementPointerIO({
    identity: readLifecycle,
    frame: readFrame,
    dom: domWrapped,
    atomic: atomicWrapped,
  });
  const readTargetRaw = async (id) => {
    const value = await pointerIO.readTargetRaw(id);
    recordDiagnostic('readTarget', {
      ok: Boolean(value),
      input: { id },
      result: value ? {
        target: value.target,
        pointer: value.pointer,
        hit: value.hit,
        frame: value.frame,
        viewHandle: value.viewHandle,
        origin: value.origin,
        record: value.record,
        page: value.page,
        dx: isNum(value.target && value.target.x) && isNum(value.pointer && value.pointer.x) ? Math.round(value.target.x - value.pointer.x) : null,
        dy: isNum(value.target && value.target.y) && isNum(value.pointer && value.pointer.y) ? Math.round(value.target.y - value.pointer.y) : null,
        distance: isNum(value.target && value.target.x) && isNum(value.pointer && value.pointer.x) &&
          isNum(value.target && value.target.y) && isNum(value.pointer && value.pointer.y)
          ? Math.round(Math.hypot(value.target.x - value.pointer.x, value.target.y - value.pointer.y) * 100) / 100
          : null,
      } : null,
    });
    return value;
  };
  const atomicFingerRaw = async (spec, canSend = () => true) => {
    const value = await pointerIO.atomicFingerRaw(spec, canSend);
    recordDiagnostic('atomicFinger', { ok: value === true, input: spec, result: value === true });
    return value;
  };

  return {
    ...guest,
    ...pointerIO,
    readTargetRaw,
    atomicFingerRaw,
    hoverDiagnostics: () => diagnosticRing.map((entry) => JSON.parse(JSON.stringify(entry))),
    readPageRaw: async () => {
      // The producer's fixture-target page payload is the explicit per-page counter source. It is
      // read on the RETIRE fixture only; a missing reader stays a named missing reader, never zeros.
      let result;
      try {
        result = await client.sendDevice('fixture-target:box,1,1');
      } catch (_error) {
        remember('fixture-target', 'page-counters');
        return guest.readPageRaw();
      }
      if (result.ok && isObject(result.data)) {
        const page = normalizePage(isObject(result.data.page) ? result.data.page : result.data);
        if (page) {
          recordDiagnostic('page', { ok: true, result: page });
          return page;
        }
      }
      remember('fixture-target', 'page-counters');
      recordDiagnostic('page', { ok: false, error: { code: 'page-counters-failed', detail: 'fixture-target page unavailable' } });
      return guest.readPageRaw();
    },
    readCookieRaw: async () => {
      let result;
      try {
        result = await client.sendDevice('cookie-read');
      } catch (_error) {
        remember('cookie-read', null, 'cookie-read');
        return null;
      }
      if (!result.ok || !isObject(result.data)) {
        remember('cookie-read', null, 'cookie-read');
        return null;
      }
      const value = realOr(result.data.value, 'string');
      if (!value || !RETIRE_COOKIE_HEADER.test(value)) {
        remember('cookie-read', 'oc_fixture_retire-hex');
        return null;
      }
      return value;
    },
    writeCookieRaw: async (value) => {
      if (!isText(value) || !RETIRE_COOKIE_VALUE.test(value)) {
        remember('cookie-write', 'oc_fixture_retire-requires-16..64-hex-or-m-marker');
        return false;
      }
      let result;
      try {
        result = await client.sendDevice(`cookie-write:${value}`);
      } catch (_error) {
        remember('cookie-write', null, 'cookie-write');
        return false;
      }
      if (!result.ok || !isObject(result.data)) {
        remember('cookie-write', null, 'cookie-write');
        return false;
      }
      return result.data.set === true;
    },
    agentSnapshotRaw: async () => {
      const result = await client.sendTool('browser.snapshot');
      return { ok: result.ok, code: result.code || '', detail: result.detail || '', data: result.data, providerMissing: result.providerMissing };
    },
    // Grant-absence read for the retirement probe. It is a diag read, never a UI tap: after a
    // retirement there is no hand-back control, and a tap could not prove anything anyway.
    allowAttemptRaw: async () => {
      const grant = await guest.readGrantRaw();
      return {
        tapped: false,
        granted: grant && isBool(grant.grantForSelected) ? grant.grantForSelected === true : null,
      };
    },
    fingerRaw: async (spec) => {
      if (!isObject(spec) || !isNum(spec.x) || !isNum(spec.y)) return false;
      let result;
      try {
        result = await client.sendDevice(
          `gate-finger:${spec.action},${Math.round(spec.x)},${Math.round(spec.y)}`,
        );
      } catch (_error) {
        return false;
      }
      return result.ok && isObject(result.data) && result.data.sent === true;
    },
    nativeGateRaw: async (spec, canSend = () => true) => {
      if (!isText(spec) || typeof canSend !== 'function' || !canSend()) return null;
      const before = await readLifecycle();
      const parts = spec.split(',');
      if (!before || !['enter', 'hover', 'exit', 'wheel'].includes(parts[0])) return null;
      let result;
      try {
        result = await client.sendDevice(`gate-native:${spec}`);
      } catch (_error) {
        return null;
      }
      if (!result.ok || !isObject(result.data) || result.data.sent !== true || result.data.buttons !== 0) return null;
      const after = await readLifecycle();
      if (!after || after.currentIdentity == null || after.currentIdentity !== before.currentIdentity) return null;
      return { sent: true, dispatched: result.data.dispatched };
    },
    tapReopen: async () => guest.tapLabelRaw('Reopen'),
    sleep: sleep || guest.sleepMs,
    now: now || guest.now,
  };
}

/* ------------------------------------------------------------------------------------------------
 * Case-level runner for the retirement probes (same fence/settle contract as the lifecycle runner).
 * The probe itself is the existing pure flow; nothing about its pass rules is copied here.
 * ---------------------------------------------------------------------------------------------- */

const TIMED_OUT = Symbol('timed-out');

async function runFencedProbe({
  name,
  flow,
  deps,
  fence,
  budgetMs = MATRIX.sceneBudgetMs,
  settleGraceMs = MATRIX.settleGraceMs,
  now = Date.now,
  save = null,
}) {
  const started = now();
  const run = Promise.resolve().then(() => flow(deps));
  let timer;
  const deadline = new Promise((resolve) => { timer = setTimeout(() => resolve(TIMED_OUT), budgetMs); });
  let outcome;
  try {
    outcome = await Promise.race([run.then((value) => ({ value })), deadline]);
  } catch (error) {
    outcome = { value: { ok: false, reason: 'probe-threw', error: String(error && error.message || error).slice(0, 200) } };
  } finally {
    clearTimeout(timer);
  }
  if (outcome !== TIMED_OUT) {
    return { ...outcome.value, sceneMs: now() - started, sceneBudgetMs: budgetMs };
  }
  fence.stop();
  const settled = await Promise.race([
    run.then((value) => ({ value }), () => ({ value: { ok: false, reason: 'probe-threw-late' } })),
    new Promise((resolve) => setTimeout(() => resolve(TIMED_OUT), settleGraceMs)),
  ]);
  if (settled === TIMED_OUT) {
    const pending = {
      ok: false,
      reason: 'probe-unsettled-after-fence',
      terminal: 'pending-failed',
      deadlineMs: budgetMs,
      graceMs: settleGraceMs,
      blockedAfterFence: fence.state.blocked,
      mutationsSent: fence.state.mutations,
    };
    if (save) save(`${name}-pending-failed.json`, pending);
    const error = new Error(`probe ${name} did not settle after fence`);
    error.code = 'PROBE_PENDING';
    throw error;
  }
  const late = settled.value || {};
  return {
    ok: false,
    reason: 'probe-deadline',
    terminal: 'client-timeout-no-retry',
    deadlineMs: budgetMs,
    sceneMs: now() - started,
    blockedAfterFence: fence.state.blocked,
    mutationsSent: fence.state.mutations,
    late: { ok: late.ok === true, reason: late.reason || null, trace: late.trace || [] },
  };
}

module.exports = {
  MATRIX,
  SCOPES,
  CASE_SCOPES,
  LIFECYCLE_CASE_NAMES,
  RETIREMENT_CASE_NAMES,
  ALL_CASE_NAMES,
  FIXTURES,
  FIXTURE_URLS,
  UI_LABELS,
  REQUIRED_PRODUCER_READERS,
  TOOL_ACTIONS,
  createRunId,
  validRunId,
  parseArgs,
  casesForScope,
  requireDedicatedSerial,
  requireDedicatedPackage,
  requireOutsideFreshOut,
  normalizeResult,
  validStatus,
  expectedCaseNames,
  commandId,
  createMatrixClient,
  waitForStatus,
  createAdbProtocolTransport,
  createGuestGateIo,
  createGuestRetirementIo,
  normalizeObserve,
  normalizeFixture,
  frameProjection,
  normalizeFrame,
  createResponseArchive,
  sanitizeResponse,
  normalizeUi,
  normalizePage,
  missingLifecycleFields,
  missingRetirementFields,
  isFixtureUrl,
  runFencedProbe,
  createCaseFence,
  TIMED_OUT,
};
