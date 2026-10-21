#!/usr/bin/env node
'use strict';

// Host driver for the true-local guest matrix (6 lifecycle + 2 retirement cases).
//
// It sends fixed commands to the long-lived LocalProviderRegressionTest on the dedicated emulator and
// runs the existing pure probes over typed host adapters. It never starts a host Node/OpenChamber
// server, never reads a provider token, never installs or builds an APK, and never taps a guessed
// coordinate. A missing producer reader/field is recorded and the affected case refuses.
//
// Preconditions are owned by the parent lane: a fresh debug build of io.andchamber.localprovider with
// the provider artifact staged, the installed runtime already applied, and the test package present.

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const {
  MATRIX,
  FIXTURES,
  SCOPES,
  CASE_SCOPES,
  expectedCaseNames,
  parseArgs,
  casesForScope,
  createRunId,
  requireOutsideFreshOut,
  createMatrixClient,
  createAdbProtocolTransport,
  createResponseArchive,
  createGuestGateIo,
  createGuestRetirementIo,
  waitForStatus,
  missingLifecycleFields,
  missingRetirementFields,
  runFencedProbe,
  createCaseFence,
} = require('./lib/local_provider_matrix_io.cjs');

const harness = require('./verify_openchamber_provider_emulator.cjs');
const lifecycleProbe = require('./lib/provider_lifecycle_gate_probe.cjs');
const retirementProbe = require('./lib/provider_retirement_gate_probe.cjs');
const {
  instrumentationCounts,
  parseWmSize,
  parseProps,
  emulatorProps,
  tapExportedTarget,
  redact,
} = require('./verify_local_provider_emulator.cjs');

const COMPONENT = `${MATRIX.package}.test/${MATRIX.runner}`;
const HOST_POLL_MS = 500;
const BOOT_TAP_PHASE = /^await[-:]/;

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
      resolve({
        code,
        stdout: Buffer.concat(stdout).toString('utf8'),
        stderr: Buffer.concat(stderr).toString('utf8'),
      });
    });
  });
}

function adbFnFor(args) {
  return (command, timeoutMs) => adb(args.adb, args.serial, command, timeoutMs);
}

function readPrivateText(adbFn, pkg, relative, timeoutMs = 15000) {
  return adbFn(['exec-out', 'run-as', pkg, 'cat', relative], timeoutMs)
    .then((result) => (result.code === 0 ? result.stdout : null));
}

function parseJson(text) {
  try { return JSON.parse(String(text || '')); } catch (_error) { return null; }
}

function saveJson(out, name, value) {
  const file = path.join(out, name);
  const temporary = `${file}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: MATRIX.evidenceMode });
  fs.chmodSync(temporary, MATRIX.evidenceMode);
  fs.renameSync(temporary, file);
}

function ensureOut(out) {
  requireOutsideFreshOut(out);
  fs.mkdirSync(path.dirname(out), { recursive: true, mode: MATRIX.evidenceDirMode });
  fs.mkdirSync(out, { recursive: false, mode: MATRIX.evidenceDirMode });
  fs.chmodSync(out, MATRIX.evidenceDirMode);
}

function screenTap(adbFn, bounds) {
  const x = Math.round((bounds.left + bounds.right) / 2);
  const y = Math.round((bounds.top + bounds.bottom) / 2);
  return adbFn(['shell', 'input', 'tap', String(x), String(y)], 15000).then((result) => {
    if (result.code !== 0) throw new Error(`input tap exit ${result.code}`);
    return { x, y };
  });
}

function humanType(adbFn, text) {
  // Fixed ASCII from the host lane; no shell metacharacters, no token and no page JS.
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(text)) throw new Error('human type text refused');
  const startedAtMs = Date.now();
  return adbFn(['shell', 'input', 'text', text], 15000).then((result) => ({
    exitCode: result.code,
    startedAtMs,
    finishedAtMs: Date.now(),
  }));
}

function pressBack(adbFn) {
  return adbFn(['shell', 'input', 'keyevent', '4'], 15000).then((result) => {
    if (result.code !== 0) throw new Error(`input keyevent exit ${result.code}`);
    return true;
  });
}

function buildInstrumentArgs({ serial, runId, caseNames }) {
  const csv = caseNames.join(',');
  // The producer's fixed argument is cases=<finite comma whitelist>. No other case argument is sent.
  return [
    '-s', serial, 'shell', 'am', 'instrument', '-r', '-w',
    '-e', 'class', MATRIX.testClass,
    '-e', 'runId', runId,
    '-e', 'cases', csv,
    COMPONENT,
  ];
}

function startInstrumentation({ adbPath, serial, runId, caseNames, log }) {
  const child = spawn(adbPath, buildInstrumentArgs({ serial, runId, caseNames }), { stdio: ['ignore', 'pipe', 'pipe'] });
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

/** Reads only this run id's private files. A stale file is null, never evidence for this run. */
async function readRunFile(client, name) {
  const raw = await client.readJson(name);
  return raw && raw.runId === client.runId ? raw : null;
}

async function waitForRunFile(client, name, budgetMs = 5000, pollMs = 100) {
  const deadline = Date.now() + budgetMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await readRunFile(client, name);
    if (last) return last;
    await new Promise((resolve) => setTimeout(resolve, pollMs));
  }
  return last;
}

async function checkPreconditions({ adbFn, pkg, log }) {
  const state = await adbFn(['get-state']);
  if (state.code !== 0 || !/device/.test(state.stdout)) throw new Error(`${MATRIX.serial} is not available`);
  const props = parseProps((await adbFn(['shell', 'getprop'])).stdout);
  const emulator = emulatorProps(props);

  const pmPath = await adbFn(['shell', 'pm', 'path', pkg]);
  if (pmPath.code !== 0 || !pmPath.stdout.includes('package:')) throw new Error(`${pkg} is not installed`);
  const testPath = await adbFn(['shell', 'pm', 'path', `${pkg}.test`]);
  if (testPath.code !== 0 || !testPath.stdout.includes('package:')) throw new Error(`${pkg}.test is not installed`);
  const instrumentation = await adbFn(['shell', 'pm', 'list', 'instrumentation']);
  if (instrumentation.code !== 0 || !instrumentation.stdout.includes(COMPONENT)) {
    throw new Error(`instrumentation runner missing: ${COMPONENT}`);
  }

  const apkPath = pmPath.stdout.split('\n').map((line) => line.replace('package:', '').trim()).find(Boolean);
  const sha = await adbFn(['shell', 'sha256sum', apkPath]);
  const deviceSha256 = String(sha.stdout || '').trim().split(/\s+/)[0];
  if (!/^[0-9a-f]{64}$/.test(deviceSha256 || '')) throw new Error('APK sha256 unavailable on device');

  const metadataRaw = await readPrivateText(adbFn, pkg, 'files/runtime/environment/metadata.json');
  const metadata = parseJson(metadataRaw);
  if (!metadata) throw new Error('installed runtime metadata missing');
  if (metadata.abi !== 'x86_64') throw new Error(`installed runtime abi is ${metadata.abi}, expected x86_64`);
  if (metadata.openChamberVersion !== '2.4.0') {
    throw new Error(`installed OpenChamber is ${metadata.openChamberVersion}, expected 2.4.0`);
  }
  // Paired asset presence: the installed package must carry the deployed provider marker. The in-process
  // test re-verifies every pin against the APK asset (that full hash check is not duplicated on the host).
  const moduleRoot = 'files/runtime/environment/rootfs/usr/local/lib/node_modules/@openchamber/web';
  const markerRaw = await readPrivateText(adbFn, pkg, `${moduleRoot}/provider-artifact.json`);
  const marker = parseJson(markerRaw);
  if (!marker) throw new Error('installed provider-artifact.json missing (paired asset not applied)');
  const packageRaw = await readPrivateText(adbFn, pkg, `${moduleRoot}/package.json`);
  const packageJson = parseJson(packageRaw);
  if (!packageJson || packageJson.name !== '@openchamber/web' || packageJson.version !== '2.4.0') {
    throw new Error('installed @openchamber/web package.json is missing or not 2.4.0');
  }
  const installLog = await readPrivateText(adbFn, pkg, 'files/runtime/logs/install.log');
  const prootInLog = /5\.1\.107\.98/.test(String(installLog || ''));
  if (!prootInLog) log('note: 5.1.107.98 not found in install.log; in-process provenance verifies PRoot\n');
  const versionName = /versionName=([^\s]+)/.exec((await adbFn(['shell', 'dumpsys', 'package', pkg])).stdout);
  return {
    serial: MATRIX.serial,
    package: pkg,
    abi: emulator.abi,
    sdk: emulator.sdk,
    apkPath,
    deviceSha256,
    hostSha256: null,
    hostShaNote: 'outer build lane records the source/build APK hash; this driver only reads the installed APK hash',
    versionName: versionName ? versionName[1] : null,
    metadata: {
      runtimeVersion: metadata.runtimeVersion,
      alpineVersion: metadata.alpineVersion,
      abi: metadata.abi,
      port: metadata.port,
      openChamberVersion: metadata.openChamberVersion,
      openCodeVersion: metadata.openCodeVersion,
      nodeVersion: metadata.nodeVersion,
    },
    providerMarker: { packageName: marker.packageName || null, version: marker.version || null },
    prootPinnedInLog: prootInLog,
    instrumentationRunner: COMPONENT,
    hostServerStarted: false,
  };
}

/**
 * Boot wait. It repeats read-only status reads, and performs one real ADB tap per exported await phase.
 * The target is validated for run id/phase/label/package/window/enabled/freshness before any input; a
 * missing target is recorded and never replaced by a fixed coordinate or a presenter grant.
 */
async function waitBoot({ client, adbFn, pkg, screen, log, instrument = null, budgetMs = MATRIX.bootBudgetMs }) {
  const tapStates = new Map();
  const tapAttempts = [];
  const missingTargets = [];
  const result = await waitForStatus({
    client,
    phases: ['ready'],
    budgetMs,
    pollMs: HOST_POLL_MS,
    isClosed: () => Boolean(instrument && instrument.child && instrument.child.exitCode !== null),
    onStatus: async (status) => {
      if (instrument && instrument.child && instrument.child.exitCode !== null) {
        throw new Error(`instrumentation exited during boot with code ${instrument.child.exitCode}`);
      }
      if (status.phase !== 'booting' && BOOT_TAP_PHASE.test(status.phase)) {
        // The producer may publish the full tapTarget object or the flat {label,bounds} pair. Only a
        // fully measured target is used; a missing window id/package/fresh time is refused, not guessed.
        const tapTarget = status.tapTarget || (status.label && status.bounds && status.windowId != null
          ? {
            label: status.label,
            ...status.bounds,
            windowId: status.windowId,
            ownerPackage: status.ownerPackage || pkg,
            enabled: true,
            observedAtMs: status.updatedAtMs,
            phase: status.phase,
            runId: status.runId,
          }
          : null);
        if (!tapTarget) {
          if (!missingTargets.some((item) => item.phase === status.phase)) {
            missingTargets.push({ phase: status.phase, reason: 'tapTarget-missing' });
            log(`boot-tap-target-missing phase=${status.phase}\n`);
          }
          return;
        }
        const key = `${status.phase}:${tapTarget.label}`;
        const state = tapStates.get(key) || {};
        tapStates.set(key, state);
        const tap = await tapExportedTarget(adbFn, tapTarget, {
          runId: client.runId,
          phase: status.phase,
          label: tapTarget.label,
          package: pkg,
          screenWidth: screen.width,
          screenHeight: screen.height,
          now: Date.now(),
        }, state);
        if (tap.tapped) {
          tapAttempts.push({ phase: status.phase, label: tapTarget.label, x: tap.x, y: tap.y, exitCode: tap.exitCode });
          log(`boot-tap phase=${status.phase} x=${tap.x} y=${tap.y} exit=${tap.exitCode}\n`);
        } else if (tap.reason !== 'already-tapped') {
          if (!missingTargets.some((item) => item.phase === status.phase && item.reason === tap.reason)) {
            missingTargets.push({ phase: status.phase, reason: tap.reason });
            log(`boot-tap-rejected phase=${status.phase} reason=${tap.reason}\n`);
          }
        }
      }
    },
  });
  if (result.closed) {
    throw new Error(`instrumentation exited before ready: phase=${result.lastPhase || 'unknown'}`);
  }
  if (result.timedOut) {
    throw new Error(`boot timeout: phase=${result.lastPhase || 'unknown'} missingTargets=${JSON.stringify(missingTargets)}`);
  }
  if (result.status && result.status.phase === 'failed') {
    throw new Error(`boot failed: ${result.status.failure || 'producer-failed'}`);
  }
  if (!result.status || result.status.phase !== 'ready') {
    throw new Error(`boot ended in phase ${result.status ? result.status.phase : 'unknown'}`);
  }
  return { status: result.status, taps: tapAttempts, missingTargets };
}

function lifecycleItem(name) {
  return harness.LIFECYCLE_CASES.find((item) => item.name === name) || null;
}

function retirementItem(name) {
  return harness.RETIREMENT_CASES.find((item) => item.name === name) || null;
}

const RETIREMENT_IO_METHODS = Object.freeze([
  'call', 'readLifecycleRaw', 'readNativeViewRaw', 'readPageRaw', 'readFrameRaw',
  'readTargetRaw', 'fingerRaw', 'atomicFingerRaw', 'enableMouseRaw', 'nativeGateRaw',
  'agentSnapshotRaw', 'allowAttemptRaw', 'readCookieRaw', 'writeCookieRaw', 'tapLabelRaw',
  'sleepMs', 'now',
]);

/** Preflight the literal adapter surface before any target page/UI action. Never inject a stub. */
function auditRetirementIo(io) {
  if (!io || typeof io !== 'object') return ['<io-object>'];
  return RETIREMENT_IO_METHODS.filter((name) => typeof io[name] !== 'function');
}

async function prepareRetirement({ guest, retirement, item }) {
  const missingMethods = auditRetirementIo(retirement);
  if (missingMethods.length) {
    return { ok: false, reason: 'retirement-adapter-method-missing', methods: missingMethods };
  }
  const opened = await guest.call('browser.open', { url: FIXTURES.retire, viewport: 'desktop' });
  if (!opened.ok) return { ok: false, reason: 'retire-open-failed', opened };
  const tabId = opened.data && opened.data.tabId ? opened.data.tabId : null;
  if (!tabId) return { ok: false, reason: 'retire-open-no-tab', opened };
  const deadline = Date.now() + 15000;
  let state = null;
  while (Date.now() < deadline) {
    state = await guest.readLifecycleRaw();
    if (state && state.selectedTabId === tabId && state.pageReady === true && typeof state.mouseMode === 'boolean') break;
    await guest.sleepMs(300);
  }
  if (!state || state.selectedTabId !== tabId || state.pageReady !== true) {
    return { ok: false, reason: 'retire-page-not-ready', tabId, state: state && { selectedTabId: state.selectedTabId, pageReady: state.pageReady } };
  }
  const allow = await guest.tapAllowAndVerify({ budgetMs: 3000 });
  if (!allow.ok) return { ok: false, reason: 'allow-not-granted', allow };
  const mouse = await guest.enableMouseRaw();
  const afterMouse = await guest.readLifecycleRaw();
  if (!mouse || !afterMouse || afterMouse.mouseMode !== true) {
    return { ok: false, reason: 'mouse-enable-failed', mouse, mouseMode: afterMouse && afterMouse.mouseMode };
  }
  if (item.name === 'retirement-b-true-cancel-reopen') {
    const token = crypto.randomBytes(16).toString('hex');
    // createGuestRetirementIo exposes the literal writeCookieRaw(value) -> boolean contract; the
    // producer returns {set:true} only after its own readback. Never add a writeTestCookie true stub.
    const written = await retirement.writeCookieRaw(token);
    if (written !== true) {
      return {
        ok: false,
        reason: 'test-cookie-not-written',
        missing: guest.missingReport(),
        readFailures: retirement.readFailures ? retirement.readFailures() : [],
      };
    }
  }
  const frame = await retirement.readFrameRaw();
  const aim = frame ? await retirement.readTargetRaw('box') : null;
  if (!frame || !aim) {
    return {
      ok: false,
      reason: 'retirement-geometry-reader-missing',
      missing: guest.missingReport(),
      frame: frame ? { viewHandle: frame.viewHandle } : null,
    };
  }
  return { ok: true, tabId, selectedTabId: tabId, frame: true, aim: { hit: aim.hit, viewHandle: aim.viewHandle } };
}

/**
 * Case boundary: a synchronous or async exception is a failed case, saved with its actual redacted
 * message. It never aborts the run before the existing finish(false) path can stop the JUnit test.
 */
async function runLifecycleCase(args) {
  try {
    return await runLifecycleCaseInner(args);
  } catch (error) {
    const result = {
      name: args.item.name,
      ok: false,
      reason: 'case-exception',
      stage: 'lifecycle',
      error: redact(error && error.message ? error.message : error),
      readFailures: args.guest && args.guest.readFailures ? args.guest.readFailures() : [],
    };
    try { args.save(`${args.item.name}-exception.json`, result); } catch (_saveError) { /* keep result */ }
    args.log(`case ${args.item.name} exception=${result.error}\n`);
    return result;
  }
}

async function runLifecycleCaseInner({ item, guest, expectedSessionId, save, log }) {
  const fence = createCaseFence();
  const adapters = harness.createLifecycleGateAdapters(guest, fence);
  const setup = await harness.prepareLifecycleCase(item, {
    adapters,
    io: guest,
    expectedSessionId,
  });
  let scrollSetupFile = null;
  if (item.setup === 'longPageScrolled' && typeof guest.scrollSetupDiagnostics === 'function') {
    const scrollDiagnostics = guest.scrollSetupDiagnostics();
    if (scrollDiagnostics) {
      scrollSetupFile = `${item.name}-scroll-setup.json`;
      save(scrollSetupFile, { case: item.name, setup, diagnostics: scrollDiagnostics });
    }
  }
  if (!setup.ok) {
    const prepareRecord = scrollSetupFile ? { ...setup, diagnostics: { scrollSetup: scrollSetupFile } } : setup;
    save(`${item.name}-prepare.json`, prepareRecord);
    return {
      name: item.name,
      ok: false,
      reason: `prepare:${setup.reason || 'failed'}`,
      setup: prepareRecord,
      missing: guest.missingReport(),
      diagnostics: scrollSetupFile ? { scrollSetup: scrollSetupFile } : null,
    };
  }
  const state = await adapters.readLifecycle();
  const missing = missingLifecycleFields(state);
  if (missing.length) {
    const result = {
      name: item.name,
      ok: false,
      reason: `missing-producer-field:${missing[0]}`,
      missingFields: missing,
      missing: guest.missingReport(),
      state: state ? {
        pageReady: state.pageReady,
        selectedTabId: state.selectedTabId,
        desktopMode: state.desktopMode,
        mouseMode: state.mouseMode,
        url: state.url,
      } : null,
    };
    save(`${item.name}.json`, result);
    log(`case ${item.name} refused: ${result.reason}\n`);
    return result;
  }
  const sceneLog = [];
  const deps = {
    ...adapters,
    readLifecycle: adapters.readLifecycle,
    lastRead: adapters.lastRead,
    now: guest.now,
    sleep: guest.sleepMs,
    baseline: setup.baseline || null,
    submitLoginPost: async () => (setup.baseline ? { ok: true } : adapters.submitLoginPost()),
    direction: item.direction || null,
  };
  const flow = await harness.runOneLifecycleCase(item, {
    deps,
    fence,
    adapters,
    probeDeadlineMs: MATRIX.sceneBudgetMs,
    save,
    now: guest.now,
    sceneLog,
  });
  let scrollGestureFile = null;
  if (item.setup === 'longPageScrolled' && typeof guest.scrollSetupDiagnostics === 'function') {
    const latestGesture = guest.scrollSetupDiagnostics();
    if (latestGesture) {
      scrollGestureFile = `${item.name}-scroll-gesture.json`;
      save(scrollGestureFile, {
        case: item.name,
        flow: { ok: flow.ok === true, reason: flow.reason || (flow.ok ? 'completed' : 'failed'), sceneMs: flow.sceneMs },
        diagnostics: latestGesture,
      });
    }
  }
  save(`${item.name}.json`, flow);
  save(`${item.name}-scene.json`, {
    case: item.name,
    sceneMs: flow.sceneMs,
    sceneBudgetMs: flow.sceneBudgetMs,
    outcome: flow.ok ? 'completed' : flow.reason,
    scenes: sceneLog.filter((entry) => entry.case === item.name),
  });
  return {
    name: item.name,
    ok: flow.ok === true,
    reason: flow.reason || (flow.ok ? 'completed' : 'failed'),
    flow,
    missing: guest.missingReport(),
    diagnostics: scrollSetupFile || scrollGestureFile ? {
      ...(scrollSetupFile ? { scrollSetup: scrollSetupFile } : {}),
      ...(scrollGestureFile ? { scrollGesture: scrollGestureFile } : {}),
    } : null,
  };
}

async function runRetirementCase(args) {
  try {
    return await runRetirementCaseInner(args);
  } catch (error) {
    const result = {
      name: args.item.name,
      ok: false,
      reason: 'case-exception',
      stage: 'retirement',
      error: redact(error && error.message ? error.message : error),
      readFailures: args.retirement && args.retirement.readFailures
        ? args.retirement.readFailures()
        : args.guest && args.guest.readFailures ? args.guest.readFailures() : [],
    };
    try { args.save(`${args.item.name}-exception.json`, result); } catch (_saveError) { /* keep result */ }
    args.log(`case ${args.item.name} exception=${result.error}\n`);
    return result;
  }
}

async function runRetirementCaseInner({ item, guest, retirement, save, log }) {
  const fence = createCaseFence();
  const adapters = harness.createRetirementGateAdapters(retirement, fence);
  const setup = await prepareRetirement({ guest, retirement, item });
  if (!setup.ok) {
    save(`${item.name}-prepare.json`, setup);
    return { name: item.name, ok: false, reason: `prepare:${setup.reason || 'failed'}`, setup, missing: guest.missingReport() };
  }
  const state = await adapters.readState();
  const frame = await retirement.readFrameRaw();
  const missing = missingRetirementFields(state, frame);
  if (missing.length) {
    const result = { name: item.name, ok: false, reason: `missing-producer-field:${missing[0]}`, missingFields: missing, missing: guest.missingReport() };
    save(`${item.name}.json`, result);
    log(`case ${item.name} refused: ${result.reason}\n`);
    return result;
  }
  const flow = await runFencedProbe({
    name: item.name,
    flow: (deps) => retirementProbe[item.flow](deps),
    deps: { ...adapters, now: guest.now, sleep: guest.sleepMs },
    fence,
    budgetMs: MATRIX.sceneBudgetMs,
    now: guest.now,
    save,
  });
  let hoverFile = null;
  if (!flow.ok && flow.reason === 'box-hover-unproved') {
    hoverFile = `${item.name}-hover.json`;
    save(hoverFile, {
      case: item.name,
      reason: flow.reason,
      sceneMs: flow.sceneMs,
      sceneBudgetMs: flow.sceneBudgetMs,
      missing: guest.missingReport(),
      diagnostics: typeof retirement.hoverDiagnostics === 'function' ? retirement.hoverDiagnostics() : null,
    });
  }
  save(`${item.name}.json`, flow);
  return {
    name: item.name,
    ok: flow.ok === true,
    reason: flow.reason || (flow.ok ? 'completed' : 'failed'),
    flow,
    missing: guest.missingReport(),
    diagnostics: hoverFile ? { hover: hoverFile } : null,
  };
}

/** Finish summary carries the flat per-case booleans the producer checks and the cases array the host reads. */
function buildFinishSummary({ runId, scope, required, results, details = null, casePartial = false, caseScope = 'all' }) {
  const summary = {
    runId,
    scope,
    casePartial: casePartial === true,
    caseScope,
    required: [...required],
    expectedCases: [...required],
    ok: required.every((name) => results[name] === true),
  };
  for (const name of required) summary[name] = results[name] === true;
  summary.cases = required.map((name) => {
    const detail = details && details[name] ? details[name] : null;
    return { name, ok: results[name] === true, reason: detail && detail.reason ? detail.reason : null };
  });
  return summary;
}

function sameNameSet(actual, expected) {
  if (!Array.isArray(actual)) return false;
  if (actual.length !== expected.length) return false;
  const set = new Set(actual);
  return expected.every((name) => set.has(name));
}

function hostVerdict({
  runId,
  scope,
  required,
  results,
  counts,
  adbExit,
  status,
  evidence,
  finish,
  preconditions,
  boot,
  partialOfFullMatrix = false,
  partialOfRequestedScope = false,
}) {
  const reasons = [];
  const statusExpected = expectedCaseNames(status && status.expectedCases);
  const evidenceExpected = expectedCaseNames(evidence && evidence.expectedCases);
  const checkCaseMetadata = (source, label, expectedCases) => {
    if (!source || typeof source.casePartial !== 'boolean') reasons.push(`${label}-case-partial-unreadable`);
    else if (source.casePartial !== partialOfFullMatrix) reasons.push(`${label}-case-partial-mismatch`);
    if (!source || !CASE_SCOPES.includes(source.caseScope)) reasons.push(`${label}-case-scope-invalid`);
    if (!expectedCases) reasons.push(`${label}-expected-cases-missing`);
    else if (!sameNameSet(expectedCases, required)) reasons.push(`${label}-expected-cases-mismatch`);
  };
  checkCaseMetadata(status, 'status', statusExpected);
  checkCaseMetadata(evidence, 'evidence', evidenceExpected);
  if (!counts || counts.pass !== true) reasons.push('instrumentation-not-pass');
  if (adbExit !== 0) reasons.push(`adb-exit:${adbExit}`);
  if (!counts || counts.tests <= 0) reasons.push('instrumentation-tests-zero');
  if (!counts || counts.failures !== 0) reasons.push(`instrumentation-failures:${counts ? counts.failures : 'unknown'}`);
  if (!counts || counts.instrumentationCode !== -1) reasons.push(`official-code:${counts ? counts.instrumentationCode : 'unknown'}`);
  if (!status || status.runId !== runId || status.phase !== 'done') reasons.push(`status-not-done:${status ? status.phase : 'missing'}`);
  if (!evidence || evidence.runId !== runId || evidence.phase !== 'done') reasons.push('test-evidence-not-done');
  if (evidence && evidence.failure) reasons.push(`test-evidence-failure:${evidence.failure}`);
  if (!finish || finish.ok !== true) reasons.push(`finish-not-ok:${finish ? finish.code : 'missing'}`);
  if (!finish || !finish.data) reasons.push('finish-data-missing');
  if (finish && finish.data) {
    if (!(Number(finish.data.toolCommands) > 0)) reasons.push('finish-tool-commands-zero');
    if (!(Number(finish.data.deviceCommands) > 0)) reasons.push('finish-device-commands-zero');
  }
  for (const name of required) {
    if (results[name] !== true) reasons.push(`case-not-pass:${name}`);
  }
  if (!preconditions || preconditions.deviceSha256 == null) reasons.push('apk-sha-missing');
  if (preconditions && preconditions.serial !== MATRIX.serial) reasons.push(`serial-not-dedicated:${preconditions.serial}`);
  if (preconditions && preconditions.package !== MATRIX.package) reasons.push(`package-not-dedicated:${preconditions.package}`);
  if (preconditions && preconditions.hostServerStarted !== false) reasons.push('host-server-started');
  if (!status || !status.selectedSessionId) {
    reasons.push('selected-session-missing');
  }
  if (!boot || !boot.status || boot.status.phase !== 'ready') reasons.push('boot-not-ready');
  return { ok: reasons.length === 0, reasons };
}

/**
 * Bounded cleanup for the exact test package only. It never broad-kills hosts or other emulators and
 * never targets a package other than the frozen dedicated one. Official counts are then "cannot-get-official",
 * never a fabricated code.
 */
async function stopOwnInstrumentation({ adbFn, pkg, instrument, log }) {
  if (instrument && instrument.child && instrument.child.exitCode === null) {
    instrument.child.kill('SIGTERM');
  }
  if (!adbFn || pkg !== MATRIX.package) return { stopped: false, reason: 'not-owned-package' };
  const stopped = await adbFn(['shell', 'am', 'force-stop', pkg], 15000).catch(() => ({ code: null }));
  log(`cleanup-own-instrumentation package=${pkg} exit=${stopped.code == null ? 'unknown' : stopped.code}\n`);
  return { stopped: stopped.code === 0, exitCode: stopped.code };
}

async function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`usage error: ${redact(error && error.message ? error.message : error)}`);
    process.exitCode = 2;
    return;
  }
  const runId = args.runId || createRunId();
  const cases = casesForScope(args.scope, args.expectedCases);
  const required = cases.map((item) => item.name);
  // Two independent dimensions: full-matrix partiality is the producer's casePartial semantics
  // (required < 8); requested-scope partiality is an explicit subset inside the chosen scope.
  const partialOfFullMatrix = required.length < SCOPES.all.length;
  const partialOfRequestedScope = args.expectedCases.length > 0 && required.length < SCOPES[args.scope].length;
  try {
    ensureOut(args.out);
  } catch (error) {
    console.error(`output error: ${redact(error && error.message ? error.message : error)}`);
    process.exitCode = 2;
    return;
  }
  const logFile = path.join(args.out, 'host.log');
  const fd = fs.openSync(logFile, 'wx', MATRIX.evidenceMode);
  const logStream = fs.createWriteStream(logFile, { fd, autoClose: true });
  const log = (text) => logStream.write(redact(text));
  let instrument = null;
  let adbFn = null;
  let client = null;
  let responseArchive = null;
  let finish = null;
  let finishSent = false;
  let exitCode = 1;
  const results = {};
  const evidence = {
    scope: args.scope,
    runId,
    serial: args.serial,
    package: args.package,
    required: [...required],
    partialOfFullMatrix,
    partialOfRequestedScope,
    cases: [],
    host: null,
    counts: null,
  };

  // One finish per run, and it is test management (not a page mutation). After a transport fence the
  // single-file channel is not trusted, so no finish is retried; cleanup stops only the owned package.
  const finishOnce = async (detail = null) => {
    if (finishSent) return finish;
    finishSent = true;
    if (!client || client.isFenced()) {
      finish = { ok: false, code: 'COMMAND_FENCED', detail: 'no finish after fenced/timeout transport' };
      return finish;
    }
    const summary = buildFinishSummary({
      runId,
      scope: args.scope,
      required,
      results,
      casePartial: partialOfFullMatrix,
      caseScope: partialOfRequestedScope ? 'custom' : args.scope,
      details: Object.fromEntries(evidence.cases.map((item) => [item.name, item])),
    });
    if (detail) summary.detail = String(detail).slice(0, 200);
    try {
      finish = await client.finish(summary);
    } catch (error) {
      finish = { ok: false, code: error.code || 'finish-transport-error', detail: String(error.message || error) };
    }
    log(`finish ok=${Boolean(finish && finish.ok)} code=${finish && finish.code ? finish.code : ''}\n`);
    return finish;
  };

  try {
    responseArchive = createResponseArchive({ dir: path.join(args.out, 'responses') });
    adbFn = adbFnFor(args);
    const preconditions = await checkPreconditions({ adbFn, pkg: args.package, log });
    evidence.host = preconditions;
    log(`preconditions serial=${preconditions.serial} abi=${preconditions.abi} apkSha=${preconditions.deviceSha256.slice(0, 12)}…\n`);

    const size = parseWmSize((await adbFn(['shell', 'wm', 'size'])).stdout);
    if (!size) throw new Error('wm size unavailable; UI target validation cannot run');

    const transport = createAdbProtocolTransport({
      adbPath: args.adb,
      serial: args.serial,
      pkg: args.package,
      dir: MATRIX.dir,
    });
    client = createMatrixClient({
      transport,
      runId,
      onResponse: (context) => responseArchive.save(context),
    });
    instrument = startInstrumentation({
      adbPath: args.adb,
      serial: args.serial,
      runId,
      caseNames: required,
      log,
    });
    const boot = await waitBoot({ client, adbFn, pkg: args.package, screen: size, log, instrument });
    evidence.boot = {
      phase: boot.status.phase,
      selectedSessionId: boot.status.selectedSessionId || null,
      casePartial: boot.status.casePartial,
      caseScope: boot.status.caseScope,
      expectedCases: boot.status.expectedCases,
      taps: boot.taps,
      missingTargets: boot.missingTargets,
    };
    if (!boot.status.selectedSessionId) {
      throw new Error('boot ready without selectedSessionId; tool commands need the real selected session');
    }
    const guest = createGuestGateIo({
      client,
      runId,
      serial: args.serial,
      package: args.package,
      screen: size,
      humanType: (text) => humanType(adbFn, text),
      pressBack: () => pressBack(adbFn),
      tapScreen: (bounds) => screenTap(adbFn, bounds),
    });
    const retirement = createGuestRetirementIo(guest, { client, now: guest.now, sleep: guest.sleepMs });
    let stopCases = false;

    for (let index = 0; index < required.length; index += 1) {
      const name = required[index];
      if (instrument.child.exitCode !== null) {
        throw new Error(`instrumentation exited before case ${name} with code ${instrument.child.exitCode}`);
      }
      if (stopCases) {
        evidence.cases.push({ name, ok: false, reason: 'not-run-after-required-failure' });
        results[name] = false;
        continue;
      }
      if (name.startsWith('lifecycle-')) {
        const item = lifecycleItem(name);
        if (!item) throw new Error(`unknown lifecycle case ${name}`);
        const result = await runLifecycleCase({
          item,
          guest,
          expectedSessionId: boot.status.selectedSessionId,
          save: (fileName, value) => saveJson(args.out, fileName, value),
          log,
        });
        evidence.cases.push(result);
        results[name] = result.ok;
        log(`case ${name} ok=${result.ok} reason=${result.reason}\n`);
        if (!result.ok) stopCases = true;
      } else {
        const item = retirementItem(name);
        if (!item) throw new Error(`unknown retirement case ${name}`);
        const result = await runRetirementCase({
          item,
          guest,
          retirement,
          save: (fileName, value) => saveJson(args.out, fileName, value),
          log,
        });
        evidence.cases.push(result);
        results[name] = result.ok;
        log(`case ${name} ok=${result.ok} reason=${result.reason}\n`);
        if (!result.ok) stopCases = true;
      }
      if (client.isFenced()) {
        stopCases = true;
        break;
      }
    }

    // One finish for the whole run; false summary stops the JUnit test through the existing path.
    finish = await finishOnce();
    evidence.finish = finish;

    const final = await waitForStatus({
      client,
      phases: ['done'],
      budgetMs: client.isFenced() ? 5000 : 60000,
      pollMs: HOST_POLL_MS,
    });
    const status = final.status;
    const testEvidence = await waitForRunFile(client, MATRIX.evidenceFile);
    evidence.status = status ? {
      runId: status.runId,
      phase: status.phase,
      failure: status.failure,
      selectedSessionId: status.selectedSessionId,
      casePartial: status.casePartial,
      caseScope: status.caseScope,
      expectedCases: status.expectedCases,
    } : null;
    evidence.testEvidence = testEvidence;

    const instrumentResult = await Promise.race([
      instrument.done,
      new Promise((resolve) => setTimeout(() => resolve(null), client.isFenced() ? 5000 : 60000)),
    ]);
    let counts = null;
    let adbExit = null;
    if (!instrumentResult) {
      // Bounded cleanup: stop only the exact dedicated package/instrument child, never a broad kill.
      await stopOwnInstrumentation({ adbFn, pkg: args.package, instrument, log });
      evidence.official = { status: 'cannot-get-official', reason: 'instrumentation-not-finished' };
      log('official=cannot-get-official instrumentation-not-finished\n');
    } else {
      counts = instrumentationCounts(instrumentResult.raw);
      adbExit = instrumentResult.code;
      evidence.counts = { ...counts, adbExit };
      fs.writeFileSync(path.join(args.out, 'instrumentation.txt'), redact(instrumentResult.raw), { mode: MATRIX.evidenceMode });
      fs.chmodSync(path.join(args.out, 'instrumentation.txt'), MATRIX.evidenceMode);
    }

    const verdict = hostVerdict({
      runId,
      required,
      results,
      counts,
      adbExit,
      status,
      evidence: testEvidence,
      finish,
      preconditions,
      boot,
      partialOfFullMatrix,
      partialOfRequestedScope,
    });
    evidence.verdict = verdict;
    evidence.responses = responseArchive ? responseArchive.manifest() : null;
    saveJson(args.out, 'result.json', evidence);
    console.log(redact(`verdict=${verdict.ok ? 'PASS' : 'FAIL'} reasons=${verdict.reasons.join(',') || 'none'} out=${args.out}`));
    exitCode = verdict.ok ? 0 : 1;
  } catch (error) {
    // If the failure happened outside the per-case wrapper while the producer is still alive, use the
    // existing finish(false) path once. If that cannot land (boot/pre-client/fenced), stop only the
    // owned package and record official as unavailable rather than inventing a code.
    if (!finishSent && client && instrument && instrument.child.exitCode === null) {
      const attempted = await Promise.race([
        finishOnce('outer-exception').catch(() => null),
        new Promise((resolve) => setTimeout(() => resolve(null), 5000)),
      ]);
      if (attempted) {
        const done = await Promise.race([
          instrument.done,
          new Promise((resolve) => setTimeout(() => resolve(null), 10000)),
        ]);
        if (done) {
          const counts = instrumentationCounts(done.raw);
          evidence.counts = { ...counts, adbExit: done.code };
        }
      }
    }
    if (!evidence.counts && instrument && instrument.child.exitCode === null) {
      await stopOwnInstrumentation({ adbFn, pkg: args.package, instrument, log });
      evidence.official = evidence.official || { status: 'cannot-get-official', reason: 'outer-exception' };
    }
    const failure = {
      scope: args.scope,
      runId,
      serial: args.serial,
      package: args.package,
      required: [...required],
      partialOfFullMatrix,
      partialOfRequestedScope,
      error: redact(error && error.message ? error.message : error),
      cases: evidence.cases,
      host: evidence.host,
      counts: evidence.counts,
      status: evidence.status || null,
      testEvidence: evidence.testEvidence || null,
      finish: evidence.finish || finish || null,
      official: evidence.official || null,
      responses: responseArchive ? responseArchive.manifest() : null,
    };
    if (adbFn) {
      const status = parseJson((await readPrivateText(adbFn, args.package, `${MATRIX.dir}/${MATRIX.statusFile}`).catch(() => null)) || '');
      if (status && status.runId === runId) failure.failureStatus = status;
      const result = parseJson((await readPrivateText(adbFn, args.package, `${MATRIX.dir}/${MATRIX.resultFile}`).catch(() => null)) || '');
      if (result && result.runId === runId) failure.failureResult = result;
    }
    saveJson(args.out, 'result.json', failure);
    console.log(redact(`verdict=FAIL reason=${failure.error} out=${args.out}`));
    exitCode = 1;
  } finally {
    // Only this run's own instrument child is stopped; no pkill, no other emulator command, no app kill.
    if (instrument && instrument.child.exitCode === null) instrument.child.kill('SIGTERM');
    logStream.end();
  }
  process.exitCode = exitCode;
}

module.exports = {
  HOST_POLL_MS,
  COMPONENT,
  parseArgs,
  checkPreconditions,
  waitBoot,
  buildFinishSummary,
  hostVerdict,
  buildInstrumentArgs,
  startInstrumentation,
  RETIREMENT_IO_METHODS,
  auditRetirementIo,
  runLifecycleCase,
  runRetirementCase,
  main,
};

if (require.main === module) {
  main();
}
