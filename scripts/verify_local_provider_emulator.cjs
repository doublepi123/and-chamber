#!/usr/bin/env node
'use strict';

// Host driver for the true-local (PRoot/guest) provider entry. It never starts a host node or
// OpenChrome server: it forwards the app's guest port, reads the app's UI password, and drives only
// the installed debug package on the dedicated emulator. The OpenCode/agent-tool calls happen inside
// the app process (LocalProviderRuntimeE2ETest); this driver supplies the real user tap, the real
// HTTP/PTY checks over adb forward, and the official instrumentation counts.
//
// Required preconditions:
//  - emulator-5582 (x86_64 Android 15), package installed from a debug build with the stock runtime
//    and the browser-tool plugin assets.
//  - port 23000 is not forwarded by anyone else.
//
// This file is deliberately device-free for its tests: every helper that touches a device takes an
// injected adb/callable, so scripts/tests/verify_local_provider_emulator.test.cjs runs offline.

const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const DEDICATED_SERIAL = 'emulator-5582';
const DEDICATED_PACKAGE = 'io.andchamber.localprovider';
const PRODUCTION_PACKAGE = 'io.andchamber.app';
const HOST_PORT = 23000;
const GUEST_PORT = 3000;
const STATUS_DIR = 'files/local-provider-e2e/current';
const HOST_RESPONSE_LIMIT = 1024 * 1024;

const ENDPOINTS = Object.freeze({
  health: '/health',
  systemInfo: '/api/system/info',
  openCodeVersion: '/api/opencode/version',
  info: '/api/info',
  authSession: '/auth/session',
});

function endpoints(port = HOST_PORT) {
  const base = `http://127.0.0.1:${port}`;
  return Object.freeze(Object.fromEntries(
    Object.entries(ENDPOINTS).map(([name, suffix]) => [name, `${base}${suffix}`]),
  ));
}

function parseJson(text) {
  try {
    const value = JSON.parse(String(text || ''));
    return value && typeof value === 'object' ? value : null;
  } catch (_error) {
    return null;
  }
}

function findEnvelope(node, depth = 0) {
  if (node == null || depth > 5) return null;
  if (typeof node === 'string') {
    const trimmed = node.trimStart();
    return trimmed.startsWith('{') ? findEnvelope(parseJson(node), depth + 1) : null;
  }
  if (typeof node !== 'object' || Array.isArray(node)) return null;
  if (typeof node.ok === 'boolean') return node;
  for (const key of ['data', 'content', 'output']) {
    const found = findEnvelope(node[key], depth + 1);
    if (found) return found;
  }
  return null;
}

function payloadOf(node, depth = 0) {
  if (node == null || depth > 5) return null;
  if (typeof node === 'string') {
    const trimmed = node.trimStart();
    return trimmed.startsWith('{') ? payloadOf(parseJson(node), depth + 1) : null;
  }
  if (typeof node !== 'object' || Array.isArray(node)) return null;
  if (typeof node.ok === 'boolean') return payloadOf(node.data, depth + 1);
  if (typeof node.content === 'string') {
    const parsed = parseJson(node.content);
    if (parsed) return payloadOf(parsed, depth + 1);
  }
  if (node.output !== undefined && node.output !== null) return payloadOf(node.output, depth + 1);
  return node;
}

/**
 * Equivalent to the Kotlin AgentToolResponse decoder: final envelope is
 * `{schemaVersion, ok, action, data}` or `{ok:false, error:{message,kind,code}}`.
 * The OpenCode tool layer may carry it under `content` (JSON text) or `output`.
 * A missing envelope or a nested failing envelope is never a success.
 */
function decodeAgentToolResponse(raw) {
  const root = parseJson(raw);
  if (!root) return { ok: false, data: null, error: 'invalid-agent-tool-json', errorKind: '', errorCode: '', action: null };
  const envelope = findEnvelope(root);
  if (!envelope) return { ok: false, data: null, error: 'invalid-agent-tool-envelope', errorKind: '', errorCode: '', action: null };
  const ok = typeof envelope.ok === 'boolean' ? envelope.ok : false;
  const data = payloadOf(envelope.data);
  const error = envelope.error && typeof envelope.error === 'object' ? envelope.error
    : typeof envelope.error === 'string' ? { message: envelope.error } : {};
  const message = typeof error.message === 'string' ? error.message : '';
  const errorKind = typeof error.kind === 'string' ? error.kind : '';
  const errorCode = typeof error.code === 'string' ? error.code
    : data && typeof data.code === 'string' ? data.code : '';
  const status = error.status == null ? '' : String(error.status);
  return {
    ok,
    data,
    error: [message, errorKind, errorCode, status].filter(Boolean).join(' | '),
    errorKind,
    errorCode,
    action: typeof envelope.action === 'string' ? envelope.action : null,
  };
}

function healthReady(body) {
  const parsed = parseJson(body);
  return Boolean(parsed && parsed.status === 'ok' && parsed.isOpenCodeReady === true);
}

function openCodeVersionMatches(body, expected) {
  const parsed = parseJson(body);
  return Boolean(parsed && typeof parsed.version === 'string' && parsed.version === expected);
}

function resolveCapturePath(raw, home) {
  if (typeof raw !== 'string' || !raw || typeof home !== 'string') return null;
  let relative;
  if (raw.startsWith('/root/')) relative = raw.slice('/root/'.length);
  else if (raw.startsWith('/')) return null;
  else relative = raw;
  if (!relative.startsWith('.openchamber/screenshots/')) return null;
  try {
    const root = fs.realpathSync(home);
    const candidate = fs.realpathSync(path.join(root, relative));
    if (candidate !== root && !candidate.startsWith(`${root}${path.sep}`)) return null;
    return candidate;
  } catch (_error) {
    return null;
  }
}

function requireFreshOutputRoot(out) {
  if (!out || !path.isAbsolute(out)) throw new Error('absolute --out required');
  if (fs.existsSync(out)) throw new Error('output root already exists; refusing to append or mix runs');
  return out;
}

function createRunId(now = Date.now, random = crypto.randomBytes) {
  const id = `tl-${now().toString(36)}-${random(4).toString('hex')}`;
  if (!validRunId(id)) throw new Error('invalid generated run id');
  return id;
}

function validRunId(id) {
  return typeof id === 'string' && /^[A-Za-z0-9._:-]{8,80}$/.test(id);
}

function parseArgs(argv) {
  const args = {
    serial: '',
    package: DEDICATED_PACKAGE,
    adb: path.join(process.env.HOME || '', 'Android/Sdk/platform-tools/adb'),
    out: '',
    runId: '',
    allowProductionPackage: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) throw new Error(`unexpected argument: ${key}`);
    if (key === '--allow-production-package') {
      args.allowProductionPackage = true;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new Error(`missing value for ${key}`);
    index += 1;
    if (key === '--serial') args.serial = value;
    else if (key === '--package') args.package = value;
    else if (key === '--adb') args.adb = value;
    else if (key === '--out') args.out = value;
    else if (key === '--run-id') args.runId = value;
    else throw new Error(`unknown option: ${key}`);
  }
  requireSerial(args.serial);
  requirePackage(args.package, { allowProductionPackage: args.allowProductionPackage });
  if (!args.out || !path.isAbsolute(args.out)) throw new Error('absolute --out required');
  if (args.runId && !validRunId(args.runId)) throw new Error('invalid --run-id');
  const repository = path.resolve(__dirname, '..') + path.sep;
  if (path.resolve(args.out).startsWith(repository)) {
    throw new Error('--out must be outside the repository (cookie jar and raw logs)');
  }
  return args;
}

function requireSerial(serial) {
  if (serial !== DEDICATED_SERIAL) {
    throw new Error(`serial must be exactly ${DEDICATED_SERIAL}; refusing physical/shared devices`);
  }
}

function requirePackage(pkg, { allowProductionPackage = false } = {}) {
  if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(pkg || '')) throw new Error('invalid package id');
  if (pkg === PRODUCTION_PACKAGE && !allowProductionPackage) {
    throw new Error(`${PRODUCTION_PACKAGE} is the production package; use the dedicated testApplicationId or pass --allow-production-package on the dedicated device`);
  }
  return pkg;
}

function parseProps(raw) {
  const values = {};
  for (const line of String(raw || '').split('\n')) {
    const match = line.match(/^\[([^\]]+)\]:\s*\[(.*)\]$/);
    if (match) values[match[1]] = match[2];
  }
  return values;
}

function emulatorProps(props) {
  if (props['ro.kernel.qemu'] !== '1') throw new Error('not an emulator (ro.kernel.qemu != 1)');
  const abi = props['ro.product.cpu.abi'];
  const abis = String(props['ro.product.cpu.abilist'] || abi || '');
  if (abi !== 'x86_64' || !abis.split(',').includes('x86_64')) {
    throw new Error(`emulator is not x86_64 (abi=${abi})`);
  }
  return { abi, sdk: Number(props['ro.build.version.sdk'] || 0) };
}

function redact(value, secrets = []) {
  let text = String(value == null ? '' : value);
  for (const secret of secrets) {
    if (!secret) continue;
    text = text.split(String(secret)).join('[redacted]');
  }
  text = text
    .replace(/Bearer\s+\S+/gi, 'Bearer [redacted]')
    .replace(/((?:set-)?cookie\s*:\s*)[^\n]*/gi, '$1[redacted]')
    .replace(/("password"\s*:\s*)"[^"]*"/gi, '$1"[redacted]"')
    .replace(/(ui-password[^\n]*?)(\S{8,})/gi, '$1[redacted]');
  return text;
}

function onceAction(label) {
  let used = false;
  return function run(fn) {
    if (used) throw new Error(`${label} may run only once`);
    used = true;
    return typeof fn === 'function' ? fn() : fn;
  };
}

function withTimeout(promise, timeoutMs, onTimeout = null) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      try { if (onTimeout) onTimeout(); } catch (_error) { /* the timeout still wins */ }
      reject(new Error(`timeout after ${timeoutMs}ms`));
    }, timeoutMs);
    Promise.resolve(promise).then((value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    }, (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error);
    });
  });
}

function attributesOf(tag) {
  const values = {};
  const source = tag.replace(/^<node\b/, '').replace(/\/?>$/, '');
  for (const match of source.matchAll(/([\w:-]+)="([^"]*)"/g)) values[match[1]] = match[2];
  return values;
}

function selectAllowNode(xml, label) {
  for (const match of String(xml || '').matchAll(/<node\b[^>]*\/?>/g)) {
    const attributes = attributesOf(match[0]);
    if (attributes.text !== label) continue;
    const bounds = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(attributes.bounds || '');
    if (!bounds) continue;
    const left = Number(bounds[1]);
    const top = Number(bounds[2]);
    const right = Number(bounds[3]);
    const bottom = Number(bounds[4]);
    if (right <= left || bottom <= top) continue;
    return {
      label,
      left,
      top,
      right,
      bottom,
      x: Math.round((left + right) / 2),
      y: Math.round((top + bottom) / 2),
    };
  }
  return null;
}

function parseWmSize(raw) {
  const text = String(raw || '');
  const override = /Override size:\s*(\d+)x(\d+)/.exec(text);
  const physical = /Physical size:\s*(\d+)x(\d+)/.exec(text);
  const match = override || physical;
  if (!match) return null;
  return { width: Number(match[1]), height: Number(match[2]) };
}

function foregroundPackage(raw) {
  const text = String(raw || '');
  for (const line of text.split('\n')) {
    if (!/mCurrentFocus|mFocusedApp|mResumedActivity/.test(line)) continue;
    const match = /(?:u\d+\s+)?([a-zA-Z][a-zA-Z0-9_.]*)\//.exec(line);
    if (match) return match[1];
  }
  return null;
}

function validateExportedTarget(target, expected) {
  if (!target || typeof target !== 'object') return 'missing-target';
  if (!expected || typeof expected !== 'object') return 'missing-expectation';
  if (target.runId !== expected.runId) return 'wrong-run';
  if (target.phase !== expected.phase) return 'wrong-phase';
  if (target.label !== expected.label) return 'wrong-label';
  if (target.ownerPackage !== expected.package) return 'wrong-package';
  if (!Number.isInteger(Number(target.windowId)) || Number(target.windowId) < 0) return 'bad-window-id';
  if (target.enabled === false) return 'disabled-target';
  const now = expected.now == null ? Date.now() : expected.now;
  const observed = Number(target.observedAtMs);
  if (!Number.isFinite(observed)) return 'missing-observed-time';
  const age = now - observed;
  if (age < -1000 || age > 3000) return 'stale-target';
  const values = ['left', 'top', 'right', 'bottom'].map((key) => Number(target[key]));
  if (values.some((value) => !Number.isFinite(value) || !Number.isInteger(value))) return 'bad-bounds';
  const [left, top, right, bottom] = values;
  if (left < 0 || top < 0 || right <= left || bottom <= top) return 'no-area';
  const width = Number(expected.screenWidth);
  const height = Number(expected.screenHeight);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) return 'missing-screen';
  if (right > width || bottom > height) return 'outside-screen';
  return null;
}

/**
 * One-shot real tap on the target exported by the instrumentation. Validation is pure and happens
 * before any adb input; the active foreground package is checked too. `state.tapped` makes every
 * later host poll return without another click, so retries are status reads only.
 */
async function tapExportedTarget(adbFn, target, expected, state = {}) {
  if (state.tapped) return { tapped: false, reason: 'already-tapped', exitCode: null };
  const rejection = validateExportedTarget(target, expected);
  if (rejection) {
    state.reason = rejection;
    return { tapped: false, reason: rejection, exitCode: null };
  }
  const foreground = await adbFn(['shell', 'dumpsys', 'window'], 15000);
  const current = foregroundPackage(foreground.stdout);
  if (current !== expected.package) {
    state.reason = `foreground-package:${current || 'unknown'}`;
    return { tapped: false, reason: state.reason, exitCode: null, foregroundPackage: current };
  }
  const stillFresh = validateExportedTarget(target, {
    ...expected,
    now: expected.nowProvider ? expected.nowProvider() : Date.now(),
  });
  if (stillFresh) {
    state.reason = stillFresh;
    return { tapped: false, reason: stillFresh, exitCode: null, foregroundPackage: current };
  }
  state.tapped = true;
  const x = Math.round((target.left + target.right) / 2);
  const y = Math.round((target.top + target.bottom) / 2);
  const tap = await adbFn(['shell', 'input', 'tap', String(x), String(y)], 15000);
  return {
    tapped: tap.code === 0,
    exitCode: tap.code,
    x,
    y,
    target,
    foregroundPackage: current,
    reason: tap.code === 0 ? null : `input-tap-exit:${tap.code}`,
  };
}
function ptyReportOk(exitCode, report) {
  return Number(exitCode) === 0 && Boolean(report) &&
    report.ok === true &&
    report.standaloneMarkerLine === true &&
    report.markersComplete === true &&
    report.doneLine === true &&
    typeof report.gitVersion === 'string' && report.gitVersion.length > 0 &&
    typeof report.nodeVersion === 'string' && report.nodeVersion.length > 0 &&
    typeof report.opencodeVersion === 'string' && report.opencodeVersion.length > 0;
}

function ptyHandshake(runId, exitCode, report) {
  const whitelist = {};
  if (report && typeof report === 'object') {
    for (const key of ['tag', 'ok', 'standaloneMarkerLine', 'markersComplete', 'doneLine', 'gitVersion', 'nodeVersion', 'opencodeVersion']) {
      if (report[key] !== undefined) whitelist[key] = report[key];
    }
  }
  return {
    runId,
    cold: true,
    ok: ptyReportOk(exitCode, report),
    exitCode: Number(exitCode),
    report: whitelist,
  };
}

/**
 * Coordinates the cold instrumentation while it is alive: wait for the current cold status
 * `await-host-pty`, perform exactly one login and one own PTY run, then write the test-private
 * handshake. It never logs in after the instrumentation has finished and never retries a PTY.
 */
async function coordinateColdPty({
  runId,
  coldChild,
  coldDone,
  readStatus,
  login,
  runPty,
  writeHandshake,
  timeoutMs = 180000,
  pollMs = 1000,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
}) {
  let closed = false;
  Promise.resolve(coldDone).then(() => { closed = true; }, () => { closed = true; });
  const deadline = Date.now() + timeoutMs;
  let status = null;
  while (Date.now() < deadline) {
    status = await Promise.resolve(readStatus()).catch(() => null);
    if (status && status.phase === 'failed') {
      throw new Error(`cold-test-failed-before-pty:${status.detail || 'failed'}`);
    }
    if (status && status.phase === 'await-host-pty') break;
    if (closed || (coldChild && coldChild.exitCode !== null)) {
      throw new Error('cold-exited-before-pty-phase');
    }
    await sleep(pollMs);
  }
  if (!status || status.phase !== 'await-host-pty') throw new Error('cold-pty-phase-timeout');
  if (closed || coldChild?.exitCode !== null) throw new Error('cold-exited-before-pty-phase');

  let cookie;
  try {
    cookie = await login();
  } catch (error) {
    await writeHandshake({ runId, cold: true, ok: false, exitCode: null, error: 'login-failed' }).catch(() => {});
    throw error;
  }
  let pty;
  try {
    pty = await runPty(cookie);
  } catch (error) {
    await writeHandshake({ runId, cold: true, ok: false, exitCode: null, error: 'pty-failed' }).catch(() => {});
    throw error;
  }
  const exitCode = pty.exitCode == null ? pty.code : pty.exitCode;
  const handshake = ptyHandshake(runId, exitCode, pty.report);
  await writeHandshake(handshake);
  if (!handshake.ok) throw new Error('pty-report-not-pass');
  return { status, pty, handshake };
}

async function writeHostPtyHandshake(adbPath, serial, pkg, payload) {
  const text = JSON.stringify(payload) + '\n';
  const child = spawn(adbPath, [
    '-s', serial, 'shell',
    `run-as ${pkg} sh -c 'cat > files/local-provider-e2e/current/host-pty.json'`,
  ], { stdio: ['pipe', 'ignore', 'ignore'] });
  const done = new Promise((resolve) => {
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000);
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve(code);
    });
  });
  child.stdin.end(text);
  const code = await done;
  if (code !== 0) throw new Error(`host-pty handshake write exit ${code}`);
  await new Promise((resolve) => {
    const chmod = spawn(adbPath, ['-s', serial, 'shell', `run-as ${pkg} chmod 600 files/local-provider-e2e/current/host-pty.json`], { stdio: 'ignore' });
    const timer = setTimeout(() => chmod.kill('SIGKILL'), 10000);
    chmod.on('close', () => { clearTimeout(timer); resolve(); });
  });
  return payload;
}


function instrumentationCounts(raw) {
  const text = String(raw || '');
  const ok = text.match(/\bOK \((\d+) tests?\)/);
  const failures = text.match(/FAILURES!!!\s*Tests run:\s*(\d+),\s*Failures:\s*(\d+)/);
  const code = text.match(/INSTRUMENTATION_CODE:\s*(-?\d+)/);
  const tests = ok ? Number(ok[1]) : failures ? Number(failures[1]) : 0;
  const failed = failures ? Number(failures[2]) : ok ? 0 : null;
  const fatal = /(INSTRUMENTATION_FAILED|shortMsg=|Process crashed|FAILURE|Exception:|Error:)/i.test(text);
  return {
    tests,
    failures: failed,
    okLine: Boolean(ok),
    failuresLine: Boolean(failures),
    fatal,
    instrumentationCode: code ? Number(code[1]) : null,
    pass: Boolean(ok) && tests > 0 && failed === 0 && !fatal &&
      code !== null && Number(code[1]) === -1,
  };
}

function normalizeEvidence(raw, { instrumentation, cold, host } = {}) {
  const evidence = raw && typeof raw === 'object' ? raw : {};
  const facts = evidence.facts && typeof evidence.facts === 'object' ? evidence.facts : {};
  const actions = evidence.actions && typeof evidence.actions === 'object' ? evidence.actions : {};
  const failed = Boolean(evidence.failure) || evidence.phase === 'failed';
  const coldEvidence = cold && typeof cold === 'object' ? cold : {};
  const coldFacts = coldEvidence.facts && typeof coldEvidence.facts === 'object' ? coldEvidence.facts : {};
  const coldFailed = Boolean(coldEvidence.failure) || coldEvidence.phase === 'failed';
  return {
    runtime: {
      status: !failed && instrumentation && instrumentation.pass ? 'pass' : 'fail',
      launchId: facts.firstRuntimeLaunchId || null,
      secondLaunchId: facts.secondRuntimeLaunchId || null,
    },
    artifact: {
      verified: !failed &&
        Boolean(facts.installedPackage && facts.installedPackage.stock === true) &&
        Boolean(facts.pluginAssets && Array.isArray(facts.pluginAssets.files) && facts.pluginAssets.files.length === 2) &&
        Boolean(facts.toolEndpoint && facts.toolEndpoint.mode === 'rw-------') &&
        Boolean(facts.pluginConfig && facts.pluginConfig.pluginListed === true),
      installedPackage: facts.installedPackage || null,
      pluginFiles: facts.pluginAssets && Array.isArray(facts.pluginAssets.files) ? facts.pluginAssets.files.length : 0,
      endpointMode: facts.toolEndpoint ? facts.toolEndpoint.mode || null : null,
      configPluginListed: Boolean(facts.pluginConfig && facts.pluginConfig.pluginListed === true),
      transport: facts.toolTransport || null,
    },
    auth: {
      status: !failed && Number(facts.authCookieLength || 0) > 0 ? 'pass' : 'fail',
      cookieLength: Number(facts.authCookieLength || 0),
    },
    browser: {
      chain: !failed && actions.open === 1 && actions.type === 1 && actions.click === 1 &&
        actions.scroll === 1 && actions.capture === 1 ? 'pass' : 'fail',
      reason: failed ? String(evidence.failure || 'test-failed') : '',
    },
    actions: {
      open: Number(actions.open || 0),
      type: Number(actions.type || 0),
      click: Number(actions.click || 0),
      scroll: Number(actions.scroll || 0),
      capture: Number(actions.capture || 0),
      snapshot: Number(actions.snapshot || 0),
      unauthorizedSnapshot: Number(actions.unauthorizedSnapshot || 0),
    },
    restarted: {
      status: !failed && facts.secondRuntimeLaunchId && facts.firstRuntimeLaunchId !== facts.secondRuntimeLaunchId ? 'pass' : 'fail',
    },
    cold: {
      status: cold && !coldFailed && Number(coldFacts.coldRuntimeLaunchId || 0) > 0 &&
        instrumentation && instrumentation.pass ? 'pass' : 'fail',
      launchId: coldFacts.coldRuntimeLaunchId || null,
    },
    failure: evidence.failure || null,
  };
}

function verdict(evidence, host = {}) {
  const reasons = [];
  if (!evidence || evidence.runtime?.status !== 'pass') reasons.push('runtime-not-pass');
  if (evidence?.artifact?.verified !== true) reasons.push('artifact-not-verified');
  if (evidence?.auth?.status !== 'pass') reasons.push('auth-not-pass');
  if (evidence?.browser?.chain !== 'pass') {
    reasons.push(`browser-chain:${evidence?.browser?.chain || 'missing'}${evidence?.browser?.reason ? `:${evidence.browser.reason}` : ''}`);
  }
  if (/no-provider|No Android browser provider|transport/i.test(String(evidence?.browser?.reason || ''))) {
    reasons.push('browser-no-provider-or-transport');
  }
  for (const action of ['open', 'type', 'click', 'scroll', 'capture']) {
    if (Number(evidence?.actions?.[action] || 0) !== 1) reasons.push(`action-${action}-count=${evidence?.actions?.[action] || 0}`);
  }
  if (evidence?.restarted?.status !== 'pass') reasons.push('stop-restart-not-pass');
  if (evidence?.cold?.status !== 'pass') reasons.push('cold-start-not-pass');
  if (host.instrumentation && (host.instrumentation.pass !== true || host.instrumentation.adbExit !== 0)) {
    reasons.push('instrumentation-not-pass');
  }
  if (host.coldInstrumentation && (host.coldInstrumentation.pass !== true || host.coldInstrumentation.adbExit !== 0)) {
    reasons.push('cold-instrumentation-not-pass');
  }
  if (host.health && host.health.status !== 'pass') reasons.push('host-health-not-pass');
  // The initial open no longer waits for a human Allow tap: its ready grant is the production
  // default. Take over and the explicit Resume agent handback remain required real UI taps.
  if (!host.taps || !host.taps.takeover || !host.taps.resumeAgent) {
    reasons.push('required-real-ui-taps-missing');
  }
  if (!host.pty || host.pty.ok !== true) reasons.push('pty-not-pass');
  return { ok: reasons.length === 0, reasons };
}

function cookiePairs(header) {
  return String(header || '')
    .split(';')
    .map((part) => part.trim())
    .filter((part) => part.includes('='))
    .map((part) => {
      const index = part.indexOf('=');
      return [part.slice(0, index), part.slice(index + 1)];
    })
    .filter(([name, value]) => name && value);
}

function writeCookieJar(file, header) {
  const pairs = cookiePairs(header);
  if (pairs.length === 0) throw new Error('cookie header has no pairs');
  const lines = [
    '# Netscape HTTP Cookie File',
    ...pairs.map(([name, value]) => `#HttpOnly_127.0.0.1\tFALSE\t/\tFALSE\t0\t${name}\t${value}`),
    '',
  ].join('\n');
  fs.writeFileSync(file, lines, { mode: 0o600 });
  fs.chmodSync(file, 0o600);
  return pairs.length;
}

function readPassword(adbFn, pkg) {
  return Promise.resolve(adbFn(['exec-out', 'run-as', pkg, 'cat', 'files/runtime/ui-password'])).then((result) => {
    if (!result || result.code !== 0) throw new Error('ui-password unreadable (debuggable build and installed runtime required)');
    const value = String(result.stdout || '').trim();
    if (!value) throw new Error('ui-password is empty');
    return value;
  });
}

function adb(adbPath, serial, command, timeoutMs = 120000) {
  return new Promise((resolve) => {
    const child = spawn(adbPath, ['-s', serial, ...command], { stdio: ['ignore', 'pipe', 'pipe'] });
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    const stdout = [];
    const stderr = [];
    child.stdout.on('data', (chunk) => stdout.push(chunk));
    child.stderr.on('data', (chunk) => stderr.push(chunk));
    child.on('close', (code) => {
      clearTimeout(timer);
      const out = Buffer.concat(stdout);
      resolve({
        code,
        stdout: out.toString('utf8'),
        stderr: Buffer.concat(stderr).toString('utf8'),
        raw: out,
      });
    });
  });
}

function httpRequest(url, { method = 'GET', headers = {}, body = null, timeoutMs = 30000 } = {}) {
  let target;
  try { target = new URL(url); } catch (_error) { return Promise.reject(new Error('invalid url')); }
  if (target.protocol !== 'http:') return Promise.reject(new Error('protocol refused'));
  const payload = body == null ? null : Buffer.from(body);
  return new Promise((resolve, reject) => {
    let settled = false;
    let request = null;
    let response = null;
    const timer = setTimeout(() => fail(new Error('request timed out')), timeoutMs);
    const fail = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (response) response.destroy();
      if (request) request.destroy();
      reject(error);
    };
    request = http.request({
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method,
      agent: false,
      headers: {
        ...headers,
        Connection: 'close',
        ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': payload.length } : {}),
      },
    }, (incoming) => {
      response = incoming;
      if (incoming.statusCode >= 300 && incoming.statusCode < 400) {
        incoming.resume();
        fail(new Error(`redirect refused ${incoming.statusCode}`));
        return;
      }
      const chunks = [];
      let size = 0;
      incoming.on('data', (chunk) => {
        size += chunk.length;
        if (size > HOST_RESPONSE_LIMIT) fail(new Error('response too large'));
        else chunks.push(chunk);
      });
      incoming.on('aborted', () => fail(new Error('response aborted')));
      incoming.on('close', () => {
        if (!settled && incoming.complete === false) fail(new Error('incomplete response'));
      });
      incoming.on('end', () => {
        if (settled) return;
        if (incoming.complete === false || incoming.aborted) {
          fail(new Error('incomplete response'));
          return;
        }
        settled = true;
        clearTimeout(timer);
        resolve({
          status: incoming.statusCode,
          headers: incoming.headers,
          body: Buffer.concat(chunks).toString('utf8'),
        });
      });
      incoming.on('error', fail);
    });
    request.on('error', fail);
    if (payload) request.end(payload);
    else request.end();
  });
}

function setCookieHeader(response) {
  const values = response.headers['set-cookie'] || [];
  return values.map((item) => String(item).split(';')[0]).filter(Boolean).join('; ');
}

function parseForwardList(raw) {
  return String(raw || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [serial, local, remote] = line.split(/\s+/);
      return { serial, local, remote };
    });
}

async function ensureForward(adbFn, serial) {
  const listed = await adbFn(['forward', '--list']);
  if (listed.code !== 0) throw new Error('adb forward --list failed');
  const conflict = parseForwardList(listed.stdout).find((item) => item.local === `tcp:${HOST_PORT}`);
  if (conflict) throw new Error(`host port ${HOST_PORT} is already forwarded (${conflict.serial} -> ${conflict.remote})`);
  const added = await adbFn(['forward', `tcp:${HOST_PORT}`, `tcp:${GUEST_PORT}`]);
  if (added.code !== 0) throw new Error('adb forward failed');
  const verify = await adbFn(['forward', '--list']);
  const mine = parseForwardList(verify.stdout).find((item) => item.local === `tcp:${HOST_PORT}`);
  if (!mine || mine.serial !== serial || mine.remote !== `tcp:${GUEST_PORT}`) {
    throw new Error('forward verification failed');
  }
  return {
    serial,
    local: mine.local,
    remote: mine.remote,
    remove: async () => {
      const before = await adbFn(['forward', '--list']);
      const current = parseForwardList(before.stdout).find((item) => item.local === `tcp:${HOST_PORT}`);
      if (current && current.serial === serial) await adbFn(['forward', '--remove', `tcp:${HOST_PORT}`]);
    },
  };
}

async function readJsonFile(adbFn, pkg, name, timeoutMs = 15000) {
  const result = await adbFn(['exec-out', 'run-as', pkg, 'cat', `${STATUS_DIR}/${name}`], timeoutMs);
  if (result.code !== 0 || !result.stdout.trim()) return null;
  try { return JSON.parse(result.stdout); } catch (_error) { return null; }
}

async function readRunJson(adbFn, pkg, name, runId) {
  const parsed = await readJsonFile(adbFn, pkg, name);
  return parsed && parsed.runId === runId ? parsed : null;
}

async function waitForPhase(adbFn, pkg, phase, budgetMs) {
  const deadline = Date.now() + budgetMs;
  while (Date.now() < deadline) {
    const status = await readJsonFile(adbFn, pkg, 'status.json');
    if (status && status.phase === phase) return status;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return null;
}

async function tapLabel(adbFn, label, { attempts = 20 } = {}) {
  for (let index = 0; index < attempts; index += 1) {
    const dump = await adbFn(['shell', 'uiautomator', 'dump', '/sdcard/oc-window.xml'], 30000);
    if (dump.code !== 0) throw new Error(`uiautomator dump failed code=${dump.code} stderr=${redact(dump.stderr || '', [])}`);
    const xml = await adbFn(['exec-out', 'cat', '/sdcard/oc-window.xml']);
    await adbFn(['shell', 'rm', '-f', '/sdcard/oc-window.xml']);
    const node = selectAllowNode(xml.stdout, label);
    if (node) {
      const tap = await adbFn(['shell', 'input', 'tap', String(node.x), String(node.y)]);
      if (tap.code !== 0) throw new Error('real input tap failed');
      return node;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return null;
}

async function collectFailureEvidence(adbFn, pkg, secrets, runId) {
  const files = {};
  for (const name of ['status.json', 'evidence.json', 'cold-status.json', 'cold-evidence.json']) {
    const parsed = await readJsonFile(adbFn, pkg, name).catch(() => null);
    files[name.replace('.json', '')] = parsed && (!runId || parsed.runId === runId) ? parsed : null;
  }
  const pidResult = await adbFn(['shell', 'pidof', pkg], 15000).catch(() => ({ code: 1, stdout: '' }));
  const pids = String(pidResult.stdout || '').trim().split(/\s+/).filter((value) => /^\d+$/.test(value)).slice(0, 3);
  const logcat = [];
  for (const pid of pids) {
    const result = await adbFn(['logcat', '-d', '-t', '200', `--pid=${pid}`], 20000).catch(() => null);
    if (result && result.stdout) logcat.push(redact(result.stdout, secrets));
  }
  return {
    pids,
    status: files.status,
    evidence: files.evidence,
    coldStatus: files['cold-status'],
    coldEvidence: files['cold-evidence'],
    // Only the current package's processes, bounded lines; no global user log.
    logcat: logcat.join('\n').slice(0, 40000),
  };
}

function startInstrumentation(adbPath, serial, pkg, classSpec, runId, log) {
  const component = `${pkg}.test/androidx.test.runner.AndroidJUnitRunner`;
  const child = spawn(adbPath, [
    '-s', serial, 'shell', 'am', 'instrument', '-r', '-w',
    '-e', 'class', classSpec,
    '-e', 'runId', runId,
    component,
  ], { stdio: ['ignore', 'pipe', 'pipe'] });
  const chunks = [];
  const record = (chunk) => {
    const text = chunk.toString('utf8');
    chunks.push(text);
    log(text);
  };
  child.stdout.on('data', record);
  child.stderr.on('data', record);
  const done = new Promise((resolve) => {
    child.on('close', (code) => resolve({ code, raw: chunks.join('') }));
  });
  return { child, done };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const runId = args.runId || createRunId();
  requireFreshOutputRoot(args.out);
  fs.mkdirSync(path.dirname(args.out), { recursive: true, mode: 0o700 });
  fs.mkdirSync(args.out, { recursive: false, mode: 0o700 });
  fs.chmodSync(args.out, 0o700);
  const logFile = path.join(args.out, 'host.log');
  const logFd = fs.openSync(logFile, 'wx', 0o600);
  const logStream = fs.createWriteStream(logFile, { fd: logFd, autoClose: true });
  const secrets = [];
  const log = (text) => logStream.write(redact(text, secrets));
  const resultPath = path.join(args.out, 'result.json');
  const evidence = { host: {}, instrumentation: null, coldInstrumentation: null };
  let forward = null;
  let adbFn = null;
  let coldInstrument = null;
  let exitCode = 1;
  try {
    adbFn = (command, timeoutMs) => adb(args.adb, args.serial, command, timeoutMs);
    const state = await adbFn(['get-state']);
    if (state.code !== 0 || !/device/.test(state.stdout)) throw new Error(`${args.serial} is not available`);
    const props = parseProps((await adbFn(['shell', 'getprop'])).stdout);
    const emulator = emulatorProps(props);
    const pmPath = await adbFn(['shell', 'pm', 'path', args.package]);
    if (pmPath.code !== 0 || !pmPath.stdout.includes('package:')) throw new Error(`${args.package} is not installed`);
    const testPath = await adbFn(['shell', 'pm', 'path', `${args.package}.test`]);
    if (testPath.code !== 0 || !testPath.stdout.includes('package:')) throw new Error(`${args.package}.test is not installed`);
    const apkPath = pmPath.stdout.split('\n').map((line) => line.replace('package:', '').trim()).find(Boolean);
    const apkSha = (await adbFn(['shell', 'sha256sum', apkPath])).stdout.trim().split(/\s+/)[0];
    if (!/^[0-9a-f]{64}$/.test(apkSha || '')) throw new Error('APK sha256 unavailable on device');
    const versionName = /versionName=([^\s]+)/.exec((await adbFn(['shell', 'dumpsys', 'package', args.package])).stdout);
    forward = await ensureForward(adbFn, args.serial);

    const instrument = startInstrumentation(
      args.adb,
      args.serial,
      args.package,
      'io.andchamber.app.browser.LocalProviderRuntimeE2ETest#realLocalRuntimeEntry',
      runId,
      log,
    );
    const size = parseWmSize((await adbFn(['shell', 'wm', 'size'])).stdout);
    if (!size) throw new Error('wm size unavailable');
    // An explicit open gets the ready default grant; the remaining real human taps are Take over
    // (revocation) and the manual fixture page's Allow (re-grant after that takeover).
    const tapPhaseLabels = {
      'await-chat-open': 'Open',
      'await-takeover-tap': 'Take over',
      'await-resume-agent-tap': 'Resume agent',
    };
    const taps = {};
    const tapErrors = {};
    const tapStates = {};
    const tapReasons = {};
    let hostHealth = null;
    let cookie = '';
    let uiPassword = '';
    let pty = null;
    const seen = new Set();
    let finished = false;
    const pollDeadline = Date.now() + 32 * 60 * 1000;
    instrument.done.then(() => { finished = true; });
    while (!finished) {
      if (Date.now() > pollDeadline) {
        instrument.child.kill('SIGKILL');
        throw new Error('instrumentation status polling timed out');
      }
      const status = await readRunJson(adbFn, args.package, 'status.json', runId);
      if (status) {
        if (!seen.has(status.phase)) {
          seen.add(status.phase);
          log(`test-phase ${status.phase}${status.detail ? ` (${status.detail})` : ''}\n`);
        }
        if (!hostHealth && status.state !== 'stopped' &&
          ['await-chat-open', 'runtime-ready', 'initial-open-granted', 'await-takeover-tap',
            'await-resume-agent-tap', 'resume-agent-active', 'actions-done', 'restart', 'done'].includes(status.phase)) {
          uiPassword = await readPassword(adbFn, args.package);
          secrets.push(uiPassword);
          const endpointsNow = endpoints(HOST_PORT);
          const health = await httpRequest(endpointsNow.health);
          if (health.status !== 200) throw new Error(`guest health HTTP ${health.status}`);
          if (!healthReady(health.body)) throw new Error('guest health is not ready (status/isOpenCodeReady)');
          const login = await httpRequest(endpointsNow.authSession, {
            method: 'POST',
            body: JSON.stringify({ password: uiPassword }),
          });
          if (login.status !== 200) throw new Error(`auth/session HTTP ${login.status}`);
          cookie = setCookieHeader(login);
          if (!cookie) throw new Error('auth/session returned no cookie');
          secrets.push(cookie);
          const systemInfo = await httpRequest(endpointsNow.systemInfo, { headers: { Cookie: cookie } });
          const openCodeVersion = await httpRequest(endpointsNow.openCodeVersion, { headers: { Cookie: cookie } });
          const info = await httpRequest(endpointsNow.info, { headers: { Cookie: cookie } });
          if (systemInfo.status !== 200 || openCodeVersion.status !== 200 || info.status !== 200) {
            throw new Error(`authenticated API HTTP system=${systemInfo.status} opencode=${openCodeVersion.status} info=${info.status}`);
          }
          const openCodeVersionBody = parseJson(openCodeVersion.body);
          if (!openCodeVersionBody || typeof openCodeVersionBody.version !== 'string') {
            throw new Error('opencode version schema invalid');
          }
          hostHealth = {
            status: 'pass',
            health: health.body.slice(0, 300),
            isOpenCodeReady: true,
            systemInfoStatus: systemInfo.status,
            openCodeVersionStatus: openCodeVersion.status,
            openCodeVersion: openCodeVersionBody.version,
            infoStatus: info.status,
          };
          log(`host-health health=${health.status} system=${systemInfo.status} opencode=${openCodeVersion.status} info=${info.status}\n`);
        }
        const label = tapPhaseLabels[status.phase];
        if (label && !taps[status.phase]) {
          if (!status.tapTarget) {
            const reason = `no-target:${status.detail || 'waiting'}`;
            if (tapReasons[status.phase] !== reason) {
              tapReasons[status.phase] = reason;
              tapErrors[status.phase] = reason;
              log(`tap-target-missing phase=${status.phase} ${reason}\n`);
            }
          } else {
            const state = tapStates[status.phase] || (tapStates[status.phase] = {});
            const result = await tapExportedTarget(adbFn, status.tapTarget, {
              runId,
              phase: status.phase,
              label,
              package: args.package,
              now: Date.now(),
              screenWidth: size.width,
              screenHeight: size.height,
            }, state);
            if (result.tapped) {
              taps[status.phase] = result;
              delete tapErrors[status.phase];
              log(`tap phase=${status.phase} x=${result.x} y=${result.y} label=${status.tapTarget.label} windowId=${status.tapTarget.windowId} exit=${result.exitCode}\n`);
            } else {
              const reason = result.reason || 'tap-rejected';
              if (tapReasons[status.phase] !== reason) {
                tapReasons[status.phase] = reason;
                tapErrors[status.phase] = reason;
                log(`tap-rejected phase=${status.phase} ${reason}\n`);
              }
            }
          }
        }
      }
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    const instrumentResult = await withTimeout(instrument.done, 32 * 60 * 1000, () => instrument.child.kill('SIGKILL'));
    const counts = instrumentationCounts(instrumentResult.raw);
    evidence.instrumentation = { ...counts, adbExit: instrumentResult.code };
    log(`instrumentation tests=${counts.tests} failures=${counts.failures} okLine=${counts.okLine} code=${counts.instrumentationCode} adbExit=${instrumentResult.code}\n`);
    const testEvidence = await readRunJson(adbFn, args.package, 'evidence.json', runId);
    if (!counts.pass || instrumentResult.code !== 0) {
      throw new Error(`instrumentation failed (tests=${counts.tests}, failures=${counts.failures}, code=${counts.instrumentationCode}, adbExit=${instrumentResult.code})`);
    }
    if (!testEvidence) throw new Error(`test evidence.json missing for run ${runId}`);
    if (testEvidence.failure) throw new Error(`test failed: ${testEvidence.failure}`);

    // Cold start: force-stop the whole package (guest server included), then keep the cold child
    // alive while the test waits for the PTY handshake. No login/PTY happens after instrumentation.
    await adbFn(['shell', 'am', 'force-stop', args.package]);
    await new Promise((resolve) => setTimeout(resolve, 2000));
    coldInstrument = startInstrumentation(
      args.adb,
      args.serial,
      args.package,
      'io.andchamber.app.browser.LocalProviderRuntimeE2ETest#coldStartAfterForceStop',
      runId,
      log,
    );
    let coldCoord;
    try {
      coldCoord = await coordinateColdPty({
        runId,
        coldChild: coldInstrument.child,
        coldDone: coldInstrument.done,
        readStatus: () => readRunJson(adbFn, args.package, 'cold-status.json', runId),
        login: async () => {
          const endpointsNow = endpoints(HOST_PORT);
          const login = await httpRequest(endpointsNow.authSession, {
            method: 'POST',
            body: JSON.stringify({ password: uiPassword }),
          });
          if (login.status !== 200) throw new Error(`cold auth/session HTTP ${login.status}`);
          cookie = setCookieHeader(login);
          if (!cookie) throw new Error('cold auth/session returned no cookie');
          secrets.push(cookie);
          const jar = path.join(args.out, 'cookies.txt');
          writeCookieJar(jar, cookie);
          return cookie;
        },
        runPty: async (sessionCookie) => {
          const jar = path.join(args.out, 'cookies.txt');
          const ptyScript = path.join(__dirname, 'x86_probe', 'pty-io.mjs');
          return new Promise((resolve) => {
            const child = spawn(process.execPath, [ptyScript, 'X86_LOCAL_PROVIDER_PTY', args.out, jar, String(HOST_PORT)], {
              stdio: ['ignore', 'pipe', 'pipe'],
              env: { ...process.env, OC_PORT: String(HOST_PORT) },
            });
            const chunks = [];
            const timer = setTimeout(() => child.kill('SIGKILL'), 120000);
            child.stdout.on('data', (chunk) => { chunks.push(chunk.toString('utf8')); });
            child.stderr.on('data', (chunk) => { chunks.push(chunk.toString('utf8')); });
            child.on('close', (code) => {
              clearTimeout(timer);
              const raw = chunks.join('');
              log(raw);
              const reportLine = raw.split('\n').filter((line) => line.startsWith('REPORT ')).pop();
              let report = null;
              try { report = reportLine ? JSON.parse(reportLine.slice('REPORT '.length)) : null; } catch (_error) { report = null; }
              resolve({ code, report, raw });
            });
          });
        },
        writeHandshake: (payload) => writeHostPtyHandshake(args.adb, args.serial, args.package, payload),
        timeoutMs: 3 * 60 * 1000,
        pollMs: 1000,
      });
    } catch (error) {
      coldInstrument.child.kill('SIGKILL');
      throw error;
    }
    const coldResult = await withTimeout(coldInstrument.done, 6 * 60 * 1000, () => coldInstrument.child.kill('SIGKILL'));
    const coldCounts = instrumentationCounts(coldResult.raw);
    evidence.coldInstrumentation = { ...coldCounts, adbExit: coldResult.code, adbPid: coldInstrument.child.pid };
    log(`cold-instrumentation tests=${coldCounts.tests} failures=${coldCounts.failures} okLine=${coldCounts.okLine} code=${coldCounts.instrumentationCode} adbExit=${coldResult.code}\n`);
    const coldEvidence = await readRunJson(adbFn, args.package, 'cold-evidence.json', runId);
    if (!coldCounts.pass || coldResult.code !== 0) {
      throw new Error(`cold instrumentation failed (tests=${coldCounts.tests}, failures=${coldCounts.failures}, code=${coldCounts.instrumentationCode}, adbExit=${coldResult.code})`);
    }
    if (!coldEvidence) throw new Error(`cold evidence.json missing for run ${runId}`);
    if (coldEvidence.failure) throw new Error(`cold test failed: ${coldEvidence.failure}`);
    pty = coldCoord.pty.report;

    evidence.host = {
      serial: args.serial,
      package: args.package,
      runId,
      hostPid: process.pid,
      hostServerStarted: false,
      packagePath: apkPath,
      apkSha256: apkSha,
      versionName: versionName ? versionName[1] : null,
      abi: emulator.abi,
      sdk: emulator.sdk,
      forward: `${forward.local}->${forward.remote}`,
      health: hostHealth,
      taps: {
        chatOpen: taps['await-chat-open'] || null,
        takeover: taps['await-takeover-tap'] || null,
        resumeAgent: taps['await-resume-agent-tap'] || null,
      },
      tapErrors: {
        chatOpen: tapErrors['await-chat-open'] || null,
        takeover: tapErrors['await-takeover-tap'] || null,
        resumeAgent: tapErrors['await-resume-agent-tap'] || null,
      },
      pty,
      ptyExit: coldCoord.pty.code,
    };
    const normalized = normalizeEvidence(testEvidence, {
      instrumentation: evidence.instrumentation,
      cold: coldEvidence,
      host: evidence.host,
    });
    const result = verdict(normalized, {
      instrumentation: evidence.instrumentation,
      coldInstrumentation: evidence.coldInstrumentation,
      health: hostHealth,
      taps: evidence.host.taps,
      pty,
    });
    const output = {
      scope: 'true-local PRoot guest AndroidJUnitRunner entry',
      generatedAt: new Date().toISOString(),
      serial: args.serial,
      package: args.package,
      runId,
      instrumentedComponent: `${args.package}.test/androidx.test.runner.AndroidJUnitRunner`,
      counts: evidence.instrumentation,
      coldCounts: evidence.coldInstrumentation,
      host: evidence.host,
      normalized,
      verdict: result,
    };
    fs.writeFileSync(resultPath, JSON.stringify(output, null, 2) + '\n', { mode: 0o600 });
    fs.chmodSync(resultPath, 0o600);
    console.log(redact(`verdict=${result.ok ? 'PASS' : 'FAIL'} reasons=${result.reasons.join(',') || 'none'} out=${args.out}`, secrets));
    exitCode = result.ok ? 0 : 1;
  } catch (error) {
    const failureEvidence = adbFn
      ? await collectFailureEvidence(adbFn, args.package, secrets, runId).catch(() => null)
      : null;
    const output = {
      scope: 'true-local PRoot guest AndroidJUnitRunner entry',
      generatedAt: new Date().toISOString(),
      serial: args.serial,
      package: args.package,
      runId,
      error: redact(error && error.message ? error.message : error, secrets),
      failurePhase: failureEvidence?.status?.phase || failureEvidence?.coldStatus?.phase || null,
      failureEvidence,
      counts: evidence.instrumentation,
      coldCounts: evidence.coldInstrumentation,
      coldAdbPid: coldInstrument?.child?.pid || null,
    };
    fs.writeFileSync(resultPath, JSON.stringify(output, null, 2) + '\n', { mode: 0o600 });
    fs.chmodSync(resultPath, 0o600);
    console.log(redact(`verdict=FAIL reason=${output.error} phase=${output.failurePhase || 'unknown'} out=${args.out}`, secrets));
    exitCode = 1;
  } finally {
    if (forward) await forward.remove().catch((error) => log(`forward cleanup failed: ${error.message}\n`));
    logStream.end();
  }
  process.exitCode = exitCode;
}

module.exports = {
  DEDICATED_SERIAL,
  DEDICATED_PACKAGE,
  PRODUCTION_PACKAGE,
  HOST_PORT,
  GUEST_PORT,
  ENDPOINTS,
  endpoints,
  parseArgs,
  requireFreshOutputRoot,
  createRunId,
  validRunId,
  readRunJson,
  tapLabel,
  parseWmSize,
  foregroundPackage,
  validateExportedTarget,
  tapExportedTarget,
  ptyReportOk,
  ptyHandshake,
  coordinateColdPty,
  writeHostPtyHandshake,
  requireSerial,
  requirePackage,
  parseProps,
  emulatorProps,
  redact,
  onceAction,
  withTimeout,
  selectAllowNode,
  instrumentationCounts,
  normalizeEvidence,
  verdict,
  cookiePairs,
  writeCookieJar,
  readPassword,
  parseForwardList,
  setCookieHeader,
  decodeAgentToolResponse,
  healthReady,
  openCodeVersionMatches,
  resolveCapturePath,
  httpRequest,
  collectFailureEvidence,
};

if (require.main === module) {
  main();
}
