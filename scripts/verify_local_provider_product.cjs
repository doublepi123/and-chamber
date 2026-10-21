#!/usr/bin/env node
'use strict';

// Host driver for LocalProviderProductTest#runGuestProduct. It uses the same single-file protocol and
// production app as the matrix lane, but only the product catalog/cases. No Matrix main, no fake Koin,
// no host server/relay, no provider token. Readers not delivered by the producer fail typed.

const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');

const {
  PRODUCT,
  PRODUCT_CASE_NAMES,
  IMPLEMENTED_PRODUCT_CASES,
  PENDING_PRODUCT_CASES,
  readProductCatalog,
  createProductIo,
  PRODUCT_CASE_FUNCTIONS,
  prepareProductCase,
} = require('./lib/local_provider_product_cases.cjs');
const {
  MATRIX,
  createMatrixClient,
  createAdbProtocolTransport,
  createResponseArchive,
  waitForStatus,
  validStatus,
  requireDedicatedSerial,
  requireDedicatedPackage,
  requireOutsideFreshOut,
  expectedCaseNames,
  createCaseFence,
} = require('./lib/local_provider_matrix_io.cjs');
const {
  instrumentationCounts,
  parseWmSize,
  parseProps,
  emulatorProps,
  tapExportedTarget,
  redact,
} = require('./verify_local_provider_emulator.cjs');

const PRODUCT_DIR = 'files/local-provider-regression/current';
const CATALOG_PATH = path.resolve(__dirname, '..', PRODUCT.catalog);
const HOST_POLL_MS = 500;
const SCENE_MS = PRODUCT.sceneBudgetMs;
const SETTLE_MS = 5000;
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
      resolve({ code, stdout: Buffer.concat(stdout).toString('utf8'), stderr: Buffer.concat(stderr).toString('utf8') });
    });
  });
}

function adbFnFor(args) {
  return (command, timeoutMs) => adb(args.adb, args.serial, command, timeoutMs);
}

function createHumanText(adbFn) {
  return async (text) => {
    if (text !== PRODUCT.focusUrl) {
      return { ok: false, code: 'HUMAN_TEXT_NOT_ALLOWED', text: 'fixed-focus-url-required' };
    }
    const startedAt = Date.now();
    // Fixed URL only, passed as one argv (no shell interpolation). This is UI text input, not a
    // credential or a JS assignment.
    const result = await adbFn(['shell', 'input', 'text', text], 15000);
    const endedAt = Date.now();
    return {
      ok: result.code === 0,
      code: result.code,
      command: 'shell input text <fixed-focus-url>',
      startedAt,
      endedAt,
      elapsedMs: endedAt - startedAt,
      stderr: String(result.stderr || '').slice(0, 200),
    };
  };
}

function parseArgs(argv) {
  const args = {
    serial: '',
    package: MATRIX.package,
    adb: path.join(process.env.HOME || '', 'Android/Sdk/platform-tools/adb'),
    out: '',
    cases: [],
    catalog: CATALOG_PATH,
    runId: '',
  };
  let casesSpecified = false;
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
    else if (key === '--catalog') args.catalog = path.resolve(value);
    else if (key === '--run-id') args.runId = value;
    else if (key === '--cases') {
      casesSpecified = true;
      args.cases = value.split(',').map((item) => item.trim()).filter(Boolean);
    } else throw new Error(`unknown option: ${key}`);
  }
  requireDedicatedSerial(args.serial);
  requireDedicatedPackage(args.package);
  requireOutsideFreshOut(args.out);
  if (casesSpecified && args.cases.length === 0) throw new Error('--cases requires a non-empty finite list');
  if (args.runId && !/^[A-Za-z0-9._:-]{8,80}$/.test(args.runId)) throw new Error('invalid --run-id');
  return args;
}

function saveJson(out, name, value) {
  const file = path.join(out, name);
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, `${JSON.stringify(value, null, 2)}\n`, { mode: MATRIX.evidenceMode });
  fs.chmodSync(tmp, MATRIX.evidenceMode);
  fs.renameSync(tmp, file);
}

function createWmGuard(adbFn, log) {
  let original = null;
  let changed = false;
  let lastRestore = null;
  const read = async () => {
    const size = await adbFn(['shell', 'wm', 'size']);
    if (!size || size.code !== 0) {
      return { ok: false, code: 'wm-size-exit', exitCode: size ? size.code : null, detail: String((size && size.stderr) || '').slice(0, 200) };
    }
    const text = size.stdout || '';
    const physical = /Physical size:\s*(\d+)x(\d+)/.exec(text);
    const override = /Override size:\s*(\d+)x(\d+)/.exec(text);
    if (!physical) return { ok: false, code: 'wm-size-parse', detail: text.slice(0, 200) };
    if (override && (Number(override[1]) <= 0 || Number(override[2]) <= 0)) {
      return { ok: false, code: 'wm-size-parse', detail: text.slice(0, 200) };
    }
    const density = await adbFn(['shell', 'wm', 'density']);
    if (!density || density.code !== 0) {
      return { ok: false, code: 'wm-density-exit', exitCode: density ? density.code : null, detail: String((density && density.stderr) || '').slice(0, 200) };
    }
    const densityText = (density.stdout || '').trim();
    if (!/Physical density:\s*\d+/.test(densityText)) {
      return { ok: false, code: 'wm-density-parse', detail: densityText.slice(0, 200) };
    }
    return {
      ok: true,
      raw: text.trim(),
      physical: { width: Number(physical[1]), height: Number(physical[2]) },
      override: override ? { width: Number(override[1]), height: Number(override[2]) } : null,
      density: densityText,
      densityRaw: densityText,
    };
  };
  return {
    async remember() {
      if (original) return original;
      const size = await read();
      if (!size.ok) throw new Error(`${size.code}: ${size.detail}`);
      original = size;
      return original;
    },
    async setCompactSize(spec, canSend) {
      if (typeof canSend === 'function' && !canSend()) return { ok: false, code: 'FENCED', detail: 'case fenced before WM read' };
      if (!isText(spec) || !/^\d{3,5}x\d{3,5}$/.test(spec)) return { ok: false, code: 'wm-size-invalid', detail: String(spec) };
      let base;
      try {
        base = await this.remember();
      } catch (error) {
        return { ok: false, code: 'wm-read-failed', detail: String(error && error.message || error).slice(0, 200) };
      }
      // remember() awaits ADB; fence again immediately before the actual resize mutation.
      if (typeof canSend === 'function' && !canSend()) return { ok: false, code: 'FENCED', detail: 'case fenced after WM read' };
      changed = true;
      const result = await adbFn(['shell', 'wm', 'size', spec]);
      if (result.code !== 0) {
        return { ok: false, code: 'wm-size-set-exit', exitCode: result.code, detail: `${spec}: ${String(result.stderr || '').slice(0, 200)}` };
      }
      const readback = await read();
      if (!readback.ok || readback.override == null ||
          readback.override.width !== Number(spec.split('x')[0]) ||
          readback.override.height !== Number(spec.split('x')[1]) ||
          readback.density !== base.density) {
        return { ok: false, code: 'wm-size-readback', detail: readback.ok ? readback.raw : `${readback.code}: ${readback.detail}`, readback };
      }
      log(`wm-size set=${spec} readback=${readback.raw.replace(/\s+/g, ' ')} density=${readback.density}\n`);
      return readback;
    },
    async restore() {
      if (!changed || !original) {
        lastRestore = { ok: true, code: 'wm-restore-not-needed', detail: 'no owned WM change' };
        return lastRestore;
      }
      const expectedOverride = original.override
        ? { width: original.override.width, height: original.override.height }
        : null;
      const cmd = expectedOverride
        ? ['shell', 'wm', 'size', `${expectedOverride.width}x${expectedOverride.height}`]
        : ['shell', 'wm', 'size', 'reset'];
      const result = await adbFn(cmd);
      if (result.code !== 0) {
        lastRestore = { ok: false, code: 'wm-restore-exit', exitCode: result.code, detail: String(result.stderr || '').slice(0, 200), expectedOverride };
        log(`wm-size restore failed code=${result.code}\n`);
        return lastRestore;
      }
      const readback = await read();
      const sizeMatch = readback.ok &&
        readback.physical.width === original.physical.width &&
        readback.physical.height === original.physical.height &&
        (expectedOverride
          ? Boolean(readback.override && readback.override.width === expectedOverride.width && readback.override.height === expectedOverride.height)
          : readback.override == null);
      const densityMatch = readback.ok && readback.density === original.density;
      if (!sizeMatch || !densityMatch) {
        lastRestore = {
          ok: false,
          code: 'wm-restore-readback',
          detail: readback.ok ? readback.raw : `${readback.code}: ${readback.detail}`,
          expectedOverride,
          readback,
        };
        log(`wm-size restore readback mismatch\n`);
        return lastRestore;
      }
      // Only a successful command and exact readback may clear the owned-change marker.
      changed = false;
      lastRestore = { ok: true, code: 'wm-restored', readback, expectedOverride };
      log(`wm-size restored=${readback.raw.replace(/\s+/g, ' ')} density=${readback.density}\n`);
      return lastRestore;
    },
    original: () => original,
    changed: () => changed,
    lastRestore: () => lastRestore,
  };
}

function isText(value) {
  return typeof value === 'string' && value.length > 0;
}

function startInstrumentation({ adbPath, serial, runId, cases, log }) {
  const child = spawn(adbPath, [
    '-s', serial, 'shell', 'am', 'instrument', '-r', '-w',
    '-e', 'class', PRODUCT.testClass,
    '-e', 'runId', runId,
    '-e', 'cases', cases.join(','),
    PRODUCT.component,
  ], { stdio: ['ignore', 'pipe', 'pipe'] });
  const chunks = [];
  const record = (chunk) => {
    const text = chunk.toString('utf8');
    chunks.push(text);
    log(text);
  };
  child.stdout.on('data', record);
  child.stderr.on('data', record);
  const done = new Promise((resolve) => { child.on('close', (code) => resolve({ code, raw: chunks.join('') })); });
  return { child, done };
}

async function readRunJson(client, name) {
  const raw = await client.readJson(name);
  return raw && raw.runId === client.runId ? raw : null;
}

async function waitBoot({ client, adbFn, pkg, screen, instrument, log }) {
  const taps = [];
  const missing = [];
  const result = await waitForStatus({
    client,
    phases: ['ready'],
    budgetMs: 300000,
    pollMs: HOST_POLL_MS,
    isClosed: () => Boolean(instrument && instrument.child && instrument.child.exitCode !== null),
    onStatus: async (status) => {
      if (status.phase !== 'booting' && BOOT_TAP_PHASE.test(status.phase)) {
        const target = status.tapTarget || (status.label && status.bounds && status.windowId != null ? {
          ...status.bounds,
          label: status.label,
          windowId: status.windowId,
          ownerPackage: status.ownerPackage || pkg,
          enabled: true,
          observedAtMs: status.updatedAtMs,
          phase: status.phase,
          runId: status.runId,
        } : null);
        if (!target) {
          if (!missing.some((item) => item.phase === status.phase)) missing.push({ phase: status.phase, reason: 'tapTarget-missing' });
          return;
        }
        const key = `${status.phase}:${target.label}`;
        const state = taps.find((item) => item.key === key) || { key };
        if (!taps.includes(state)) taps.push(state);
        const tap = await tapExportedTarget(adbFn, target, {
          runId: client.runId,
          phase: status.phase,
          label: target.label,
          package: pkg,
          screenWidth: screen.width,
          screenHeight: screen.height,
          now: Date.now(),
        }, state);
        if (tap.tapped) log(`boot-tap phase=${status.phase} x=${tap.x} y=${tap.y}\n`);
        else if (tap.reason !== 'already-tapped' && !missing.some((item) => item.phase === status.phase && item.reason === tap.reason)) {
          missing.push({ phase: status.phase, reason: tap.reason });
        }
      }
    },
  });
  if (result.closed) throw new Error(`instrumentation exited before ready: phase=${result.lastPhase || 'unknown'}`);
  if (result.timedOut) throw new Error(`boot timeout: phase=${result.lastPhase || 'unknown'}`);
  if (!result.status || result.status.phase === 'failed') throw new Error(`boot failed: ${result.status ? result.status.failure || 'failed' : 'unknown'}`);
  if (result.status.phase !== 'ready') throw new Error(`boot phase ${result.status.phase}`);
  return { status: result.status, taps, missing };
}

async function checkPreconditions({ adbFn, pkg, catalog }) {
  const state = await adbFn(['get-state']);
  if (state.code !== 0 || !/device/.test(state.stdout)) throw new Error(`${MATRIX.serial} is not available`);
  const props = parseProps((await adbFn(['shell', 'getprop'])).stdout);
  const emulator = emulatorProps(props);
  const pmPath = await adbFn(['shell', 'pm', 'path', pkg]);
  const testPath = await adbFn(['shell', 'pm', 'path', `${pkg}.test`]);
  if (pmPath.code !== 0 || !pmPath.stdout.includes('package:')) throw new Error(`${pkg} is not installed`);
  if (testPath.code !== 0 || !testPath.stdout.includes('package:')) throw new Error(`${pkg}.test is not installed`);
  const instrumentation = await adbFn(['shell', 'pm', 'list', 'instrumentation']);
  if (instrumentation.code !== 0 || !instrumentation.stdout.includes(PRODUCT.component)) throw new Error(`product runner missing: ${PRODUCT.component}`);
  const apkPath = pmPath.stdout.split('\n').map((line) => line.replace('package:', '').trim()).find(Boolean);
  const apkSha256 = (await adbFn(['shell', 'sha256sum', apkPath])).stdout.trim().split(/\s+/)[0];
  if (!/^[0-9a-f]{64}$/.test(apkSha256 || '')) throw new Error('APK sha256 unavailable');
  return {
    serial: MATRIX.serial,
    package: pkg,
    abi: emulator.abi,
    sdk: emulator.sdk,
    apkPath,
    apkSha256,
    hostServerStarted: false,
    catalogCases: catalog.cases,
    implementedCases: IMPLEMENTED_PRODUCT_CASES,
    pendingCases: PENDING_PRODUCT_CASES,
  };
}

function markWmRestoreFailure(evidence, failure) {
  evidence.wmRestore = failure;
  const reasons = Array.isArray(evidence.verdict && evidence.verdict.reasons) ? evidence.verdict.reasons.slice() : [];
  if (!reasons.includes('wm-restore-failed')) reasons.push('wm-restore-failed');
  evidence.verdict = { ok: false, reasons };
  return evidence;
}

function buildProductArgs({ serial, runId, cases }) {
  return ['-s', serial, 'shell', 'am', 'instrument', '-r', '-w', '-e', 'class', PRODUCT.testClass, '-e', 'runId', runId, '-e', 'cases', cases.join(','), PRODUCT.component];
}

async function runCase({ io, ctx, name, fence, save, log }) {
  const fn = PRODUCT_CASE_FUNCTIONS[name];
  if (typeof fn !== 'function') return { name, ok: false, reason: 'case-not-implemented' };
  const started = Date.now();
  let timedOut = false;
  let timer;
  const timeout = new Promise((resolve) => { timer = setTimeout(() => { timedOut = true; fence.stop(); resolve(null); }, SCENE_MS); });
  let result;
  let preparation = null;
  try {
    result = await Promise.race([(async () => {
      preparation = await prepareProductCase(io);
      if (!preparation.ok) return { ok: false, reason: preparation.reason, preparation };
      const value = await fn(io, ctx);
      return { ...value, preparation };
    })(), timeout]);
  } catch (error) {
    result = { ok: false, reason: 'case-exception', error: redact(error && error.message || error), preparation };
  } finally { clearTimeout(timer); }
  const elapsed = Date.now() - started;
  if (timedOut || !result) {
    const pending = { name, ok: false, reason: 'case-timeout', sceneMs: elapsed, blockedAfterFence: fence.state.blocked, mutationsSent: fence.state.mutations, preparation };
    save(`${name}-pending-failed.json`, pending);
    return pending;
  }
  const out = { name, ok: result.ok === true, reason: result.reason || (result.ok ? 'completed' : 'failed'), sceneMs: elapsed, result, preparation, readFailures: io.readFailures(), missingFields: io.missingFields() };
  save(`${name}.json`, out);
  if (!result.ok) save(`${name}-facts.json`, { name, reason: result.reason, result, readFailures: io.readFailures(), missingFields: io.missingFields() });
  return out;
}

function productMetadataStatus(status, evidence, required, partial) {
  const reasons = [];
  if (!status || status.phase !== 'done' || status.runId !== required.runId) reasons.push('status-not-done');
  if (!evidence || evidence.phase !== 'done' || evidence.runId !== required.runId) reasons.push('evidence-not-done');
  const statusCases = expectedCaseNames(status && status.expectedCases);
  const evidenceCases = expectedCaseNames(evidence && evidence.expectedCases);
  if (!statusCases || statusCases.length !== required.cases.length || !required.cases.every((name) => statusCases.includes(name))) reasons.push('status-cases-mismatch');
  if (!evidenceCases || evidenceCases.length !== required.cases.length || !required.cases.every((name) => evidenceCases.includes(name))) reasons.push('evidence-cases-mismatch');
  if (status && status.caseScope !== 'product') reasons.push('status-scope-not-product');
  if (status && status.casePartial !== partial) reasons.push('status-partial-mismatch');
  if (evidence && evidence.caseScope !== 'product') reasons.push('evidence-scope-not-product');
  if (evidence && evidence.casePartial !== partial) reasons.push('evidence-partial-mismatch');
  return reasons;
}

async function main() {
  let args;
  try { args = parseArgs(process.argv.slice(2)); } catch (error) {
    console.error(`usage error: ${redact(error && error.message ? error.message : error)}`);
    process.exitCode = 2;
    return;
  }
  let catalog;
  try { catalog = readProductCatalog(args.catalog); } catch (error) {
    console.error(`catalog error: ${redact(error && error.message ? error.message : error)}`);
    process.exitCode = 2;
    return;
  }
  const selected = args.cases.length ? args.cases : catalog.cases;
  const unknown = selected.filter((name) => !catalog.cases.includes(name));
  if (unknown.length) { console.error(`unknown product cases: ${unknown.join(',')}`); process.exitCode = 2; return; }
  if (new Set(selected).size !== selected.length) { console.error('duplicate product cases'); process.exitCode = 2; return; }
  const runId = args.runId || `product-${Date.now().toString(36)}-${Math.random().toString(16).slice(2, 8)}`;
  requireOutsideFreshOut(args.out);
  fs.mkdirSync(path.dirname(args.out), { recursive: true, mode: MATRIX.evidenceDirMode });
  fs.mkdirSync(args.out, { recursive: false, mode: MATRIX.evidenceDirMode });
  fs.chmodSync(args.out, MATRIX.evidenceDirMode);
  const logStream = fs.createWriteStream(path.join(args.out, 'host.log'), { mode: MATRIX.evidenceMode });
  const log = (text) => logStream.write(redact(text));
  const adbFn = adbFnFor(args);
  const results = {};
  const caseResults = [];
  const evidence = { scope: 'product', runId, serial: args.serial, package: args.package, required: selected, selected, catalog, cases: [], error: null, verdict: null, counts: null, host: null };
  let instrument = null;
  let client = null;
  let archive = null;
  let wm = null;
  let finishSent = false;
  let finishResult = null;
  try {
    archive = createResponseArchive({ dir: path.join(args.out, 'responses') });
    const preconditions = await checkPreconditions({ adbFn, pkg: args.package, catalog });
    evidence.host = preconditions;
    wm = createWmGuard(adbFn, log);
    await wm.remember();
    const size = parseWmSize((await adbFn(['shell', 'wm', 'size'])).stdout);
    if (!size) throw new Error('wm size unavailable');
    const transport = createAdbProtocolTransport({ adbPath: args.adb, serial: args.serial, pkg: args.package, dir: PRODUCT_DIR });
    client = createMatrixClient({ transport, runId, onResponse: (context) => archive.save(context) });
    instrument = startInstrumentation({ adbPath: args.adb, serial: args.serial, runId, cases: selected, log });
    const boot = await waitBoot({ client, adbFn, pkg: args.package, screen: size, instrument, log });
    evidence.boot = { phase: boot.status.phase, taps: boot.taps, missingTargets: boot.missing };
    const tapScreen = async (bounds) => {
      const x = Math.round((bounds.left + bounds.right) / 2);
      const y = Math.round((bounds.top + bounds.bottom) / 2);
      const tap = await adbFn(['shell', 'input', 'tap', String(x), String(y)], 15000);
      if (tap.code !== 0) throw new Error(`input tap exit ${tap.code}`);
      return true;
    };
    const ctx = {
      readWmSize: () => wm.remember(),
      setCompactSize: (spec, canSend) => wm.setCompactSize(spec, canSend),
      restoreWmSize: () => wm.restore(),
    };
    let stop = false;
    for (const name of selected) {
      if (stop) {
        const skipped = { name, ok: false, reason: 'not-run-after-required-failure' };
        caseResults.push(skipped); results[name] = false;
        continue;
      }
      const fence = createCaseFence();
      const io = createProductIo({ client, runId, screen: size, tapScreen, humanText: createHumanText(adbFn), fence });
      const result = await runCase({
        io,
        ctx,
        name,
        fence,
        save: (fileName, value) => saveJson(args.out, fileName, value),
        log,
      });
      caseResults.push(result);
      results[name] = result.ok;
      log(`case ${name} ok=${result.ok} reason=${result.reason}\n`);
      if (!result.ok) stop = true;
      if (client.isFenced()) { stop = true; break; }
    }
    evidence.cases = caseResults;

    const summary = { runId, scope: 'product', casePartial: true, caseScope: 'product', required: selected, expectedCases: selected, ok: selected.every((name) => results[name] === true) };
    for (const name of selected) summary[name] = results[name] === true;
    summary.cases = selected.map((name) => ({ name, ok: results[name] === true }));
    let finish = null;
    if (!client.isFenced()) {
      finishSent = true;
      try { finish = await client.finish(summary); } catch (error) { finish = { ok: false, code: error.code || 'finish-error', detail: String(error.message || error) }; }
    } else {
      finish = { ok: false, code: 'COMMAND_FENCED', detail: 'no finish after fenced transport' };
    }
    finishResult = finish;
    evidence.finish = finish;
    const final = await waitForStatus({ client, phases: ['done'], budgetMs: client.isFenced() ? 5000 : 60000, pollMs: HOST_POLL_MS });
    const status = final.status;
    const testEvidence = await readRunJson(client, 'evidence.json');
    evidence.status = status;
    evidence.testEvidence = testEvidence;
    const instrumentResult = await Promise.race([
      instrument.done,
      new Promise((resolve) => setTimeout(() => resolve(null), client.isFenced() ? 5000 : 60000)),
    ]);
    let counts = null;
    let adbExit = null;
    if (instrumentResult) {
      counts = instrumentationCounts(instrumentResult.raw);
      adbExit = instrumentResult.code;
      evidence.counts = { ...counts, adbExit };
      fs.writeFileSync(path.join(args.out, 'instrumentation.txt'), redact(instrumentResult.raw), { mode: MATRIX.evidenceMode });
    } else {
      if (instrument && instrument.child.exitCode === null) instrument.child.kill('SIGTERM');
      const stopped = await adbFn(['shell', 'am', 'force-stop', args.package], 15000).catch(() => ({ code: null }));
      evidence.official = { status: 'cannot-get-official', reason: 'instrumentation-not-finished', cleanupExit: stopped.code == null ? null : stopped.code };
    }
    const reasons = [];
    if (!counts || counts.pass !== true) reasons.push('instrumentation-not-pass');
    if (adbExit !== 0) reasons.push(`adb-exit:${adbExit}`);
    if (!counts || counts.tests <= 0 || counts.failures !== 0 || counts.instrumentationCode !== -1) reasons.push('official-counts-not-pass');
    if (!finish || finish.ok !== true) reasons.push('finish-not-ok');
    if (!counts || !evidence.counts) reasons.push('official-unavailable');
    reasons.push(...productMetadataStatus(status, testEvidence, { runId, cases: selected }, true));
    for (const name of selected) if (results[name] !== true) reasons.push(`case-not-pass:${name}`);
    if (selected.length !== catalog.cases.length && !selected.length) reasons.push('empty-selection');
    evidence.verdict = { ok: reasons.length === 0, reasons };
    evidence.responses = archive.manifest();
    saveJson(args.out, 'result.json', evidence);
    console.log(redact(`verdict=${evidence.verdict.ok ? 'PASS' : 'FAIL'} reasons=${evidence.verdict.reasons.join(',') || 'none'} out=${args.out}`));
    process.exitCode = evidence.verdict.ok ? 0 : 1;
  } catch (error) {
    try { evidence.error = redact(error && error.message ? error.message : error); } catch (_e) { evidence.error = 'error'; }
    if (!finishSent && client && !client.isFenced()) {
      try {
        const falseSummary = { runId, scope: 'product', casePartial: true, caseScope: 'product', required: selected, expectedCases: selected, ok: false };
        for (const name of selected) falseSummary[name] = false;
        evidence.finish = await client.finish(falseSummary);
        finishSent = true;
      } catch (_finishError) { /* bounded */ }
    }
    if (instrument && instrument.child.exitCode === null) {
      instrument.child.kill('SIGTERM');
      await adbFn(['shell', 'am', 'force-stop', args.package], 15000).catch(() => null);
    }
    evidence.cases = caseResults;
    evidence.counts = evidence.counts || null;
    if (archive) evidence.responses = archive.manifest();
    saveJson(args.out, 'result.json', evidence);
    console.log(redact(`verdict=FAIL reason=${evidence.error} out=${args.out}`));
    process.exitCode = 1;
  } finally {
    let restoreFailure = null;
    try {
      if (wm) {
        const firstRestore = await wm.restore();
        if (firstRestore && firstRestore.ok === false) {
          // Record the failure even if one bounded display-maintenance compensation later succeeds.
          // Never retry page actions; only this owned WM restore path may be compensated once.
          restoreFailure = firstRestore;
          if (wm.changed()) {
            const compensated = await wm.restore();
            if (compensated && compensated.ok === true) log('wm cleanup compensated after one bounded retry\n');
          }
        }
      }
    } catch (error) {
      restoreFailure = { ok: false, code: 'wm-restore-exception', detail: String(error && error.message || error) };
      log(`wm cleanup failed: ${restoreFailure.detail}\n`);
    }
    if (restoreFailure) {
      markWmRestoreFailure(evidence, restoreFailure);
      try { saveJson(args.out, 'result.json', evidence); } catch (error) { log(`wm failure result write failed: ${String(error && error.message || error)}\n`); }
      log(`wm restore final failure code=${restoreFailure.code} detail=${restoreFailure.detail}\n`);
      process.exitCode = 1;
    }
    try { if (instrument && instrument.child.exitCode === null) instrument.child.kill('SIGTERM'); } catch (_error) { /* own child only */ }
    logStream.end();
  }
}

module.exports = {
  PRODUCT,
  PRODUCT_CASE_NAMES,
  PRODUCT_DIR,
  parseArgs,
  buildProductArgs,
  createWmGuard,
  waitBoot,
  checkPreconditions,
  runCase,
  productMetadataStatus,
  markWmRestoreFailure,
  createHumanText,
  main,
};

if (require.main === module) main();
