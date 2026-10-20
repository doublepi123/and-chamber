#!/usr/bin/env node
'use strict';

const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');

const PACKAGE = 'io.andchamber.app';
const STATUS_REMOTE = 'files/browser-mcp-e2e/status.json';
const COMMAND_REMOTE = 'files/browser-mcp-e2e/command.json';
const CREDENTIAL_REMOTE = 'files/browser-mcp-e2e/credential.json';
const NAV_URL = 'https://example.com/browser-mcp-e2e';
const SECOND_URL = 'https://example.com/browser-mcp-e2e?page=second';
const MARKER = 'emulator-marker';
const TOOLS = ['browser_navigate', 'browser_snapshot', 'browser_click', 'browser_type', 'browser_scroll', 'browser_capture'];
const ADAPTER = path.resolve(__dirname, '../app/src/main/assets/browser-control/browser-mcp.cjs');
const BUDGET_MS = 110000;
const FINISH_RESERVE_MS = 10000;
const INITIAL_WAIT_MS = 15000;
const COMMAND_SCRIPT = 'tmp=$(mktemp files/browser-mcp-e2e/command.tmp.XXXXXX) && cat > "$tmp" && mv -f "$tmp" files/browser-mcp-e2e/command.json';

function parseArgs(argv) {
  const args = {
    serial: '',
    out: '',
    adb: process.env.ANDROID_HOME
      ? path.join(process.env.ANDROID_HOME, 'platform-tools/adb')
      : path.join(process.env.HOME, 'Android/Sdk/platform-tools/adb'),
  };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (key === '--serial') args.serial = value;
    if (key === '--out') args.out = value;
    if (key === '--adb') args.adb = value;
    if (key.startsWith('--')) index += 1;
  }
  if (!/^emulator-\d+$/.test(args.serial || '')) throw new Error('serial must match emulator-N');
  if (!args.out || !path.isAbsolute(args.out)) throw new Error('out must be an absolute directory');
  return args;
}

function shellQuote(value) {
  return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

function adbShellArg(script) {
  return `run-as ${PACKAGE} sh -c ${shellQuote(script)}`;
}

function writeCommandArgv(serial) {
  return ['-s', serial, 'shell', adbShellArg(COMMAND_SCRIPT)];
}

function credentialExistsArgv(serial) {
  return ['-s', serial, 'shell', `run-as ${PACKAGE} test -f ${CREDENTIAL_REMOTE}`];
}

function statusArgv(serial) {
  return ['-s', serial, 'exec-out', 'run-as', PACKAGE, 'cat', STATUS_REMOTE];
}

function statusExistsArgv(serial) {
  return ['-s', serial, 'shell', `run-as ${PACKAGE} test -f ${STATUS_REMOTE}`];
}

function missingStatusOutput(stdout) {
  return stdout.trim() === `cat: ${STATUS_REMOTE}: No such file or directory`;
}

function remainingMs(started, now, budget = BUDGET_MS, reserve = 0) {
  return budget - (now - started) - reserve;
}

function boundedTimeout(requested, remaining) {
  const ms = Math.min(requested, remaining);
  if (ms <= 0) throw new Error('deadline exhausted');
  return ms;
}

function assertEndpoint(credential, port) {
  const expected = `http://127.0.0.1:${port}/v1/command`;
  if (!credential || credential.endpoint !== expected) throw new Error('endpoint mismatch');
  return expected;
}

function openIfEndpoint(credential, port, spawnFn) {
  assertEndpoint(credential, port);
  return spawnFn();
}

function labeledInt(text, label) {
  const match = String(text).match(new RegExp(`(?:^|[^A-Za-z0-9_-])${label}:(\\d+)(?!\\d)`));
  return match ? Number(match[1]) : null;
}

function snapshotLabel(snapshot, label) {
  return labeledInt(JSON.stringify(snapshot), label);
}

function noteWithValue(snapshot, value) {
  return (snapshot.elements || []).find((item) => item && item.name === 'note' && item.value === value);
}

function redact(text, token) {
  let value = String(text == null ? '' : text);
  value = value.replace(/Bearer\s+\S+/gi, 'Bearer [redacted]');
  if (token) value = value.split(token).join('[redacted]');
  return value.slice(0, 500);
}

class Report {
  constructor(out) {
    this.out = out;
    this.checks = [];
    fs.mkdirSync(out, { recursive: true, mode: 0o700 });
  }

  add(name, ok, detail) {
    this.checks.push({ name, result: ok ? 'PASS' : 'FAIL', detail: redact(detail).slice(0, 240) });
  }

  write(extra = {}) {
    const failed = this.checks.filter((item) => item.result === 'FAIL').map((item) => item.name);
    const body = { result: failed.length === 0 ? 'PASS' : 'FAIL', checks: this.checks, failed, ...extra };
    fs.writeFileSync(path.join(this.out, 'result.json'), `${JSON.stringify(body, null, 2)}\n`, { mode: 0o600 });
    process.stdout.write(`${JSON.stringify({ result: body.result, failed })}\n`);
    return failed.length === 0 ? 0 : 1;
  }
}

function noteCleanup(report, code) {
  report.add('cleanup-forward', code === 0, code === 0 ? 'removed own forward' : 'adb nonzero');
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const timeoutMs = options.timeoutMs || 8000;
    if (timeoutMs <= 0) {
      reject(new Error('deadline exhausted'));
      return;
    }
    const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'pipe'] });
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`${path.basename(command)} timed out`));
    }, timeoutMs);
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    if (options.input) child.stdin.write(options.input);
    child.stdin.end();
    child.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ code, stdout, stderr: redact(stderr, options.token) });
    });
  });
}

function adb(args, commandArgs, options = {}) {
  const timeoutMs = boundedTimeout(options.timeoutMs || 8000, options.remaining());
  return run(args.adb, ['-s', args.serial, ...commandArgs], { ...options, timeoutMs });
}

function validateStatus(status, runId) {
  const phases = new Set(['disabled', 'enabled', 'taken_over', 'hidden', 'finished', 'failed']);
  if (!status || !phases.has(status.phase)) throw new Error('status phase invalid');
  if (typeof status.runId !== 'string' || status.runId.length < 1 || status.runId.length > 80) throw new Error('runId invalid');
  if (runId && status.runId !== runId) throw new Error('runId changed');
  if (status.credentialFile !== CREDENTIAL_REMOTE) throw new Error('credential path rejected');
  if (status.phase === 'enabled' && (!Number.isInteger(status.port) || status.port < 1 || status.port > 65535)) {
    throw new Error('port invalid');
  }
  return status;
}

function interpretStatus(result, runId) {
  if (!result || result.code !== 0) throw new Error('status read failed');
  if (Buffer.byteLength(result.stdout || '') > 16 * 1024) throw new Error('status too large');
  if (missingStatusOutput(result.stdout || '')) {
    if (runId) throw new Error('status missing after runId');
    return null;
  }
  let parsed;
  try {
    parsed = JSON.parse(result.stdout);
  } catch (_error) {
    if (runId) throw new Error('status malformed');
    return null;
  }
  return validateStatus(parsed, runId);
}

async function readStatus(args, clock, runId) {
  const result = await adb(args, statusArgv(args.serial).slice(2), { timeoutMs: 8000, remaining: () => clock.left(FINISH_RESERVE_MS) });
  return interpretStatus(result, runId);
}

async function waitStatus(args, clock, predicate, timeoutMs, runId) {
  const started = Date.now();
  let last = null;
  while (Date.now() - started < timeoutMs) {
    const slice = clock.left(FINISH_RESERVE_MS);
    if (slice <= 0) throw new Error('deadline exhausted');
    last = await readStatus(args, clock, runId);
    if (last && predicate(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, Math.min(400, slice)));
  }
  throw new Error(`status timeout at ${last ? last.phase : 'unread'}`);
}

async function writeCommand(args, clock, action, runId) {
  const command = { id: crypto.randomUUID(), action };
  const result = await adb(args, ['shell', adbShellArg(COMMAND_SCRIPT)], {
    input: `${JSON.stringify(command)}\n`,
    timeoutMs: 8000,
    remaining: () => clock.left(FINISH_RESERVE_MS),
  });
  if (result.code !== 0) throw new Error(`command ${action} was not written`);
  return waitStatus(args, clock, (status) => status.lastCommandId === command.id, boundedTimeout(20000, clock.left(FINISH_RESERVE_MS)), runId);
}

async function credentialExists(args, clock) {
  const result = await adb(args, credentialExistsArgv(args.serial).slice(2), {
    timeoutMs: 8000,
    remaining: () => clock.left(FINISH_RESERVE_MS),
  });
  return result.code === 0;
}

async function copyCredential(args, clock, hostFile, port) {
  const result = await adb(args, ['exec-out', 'run-as', PACKAGE, 'cat', CREDENTIAL_REMOTE], {
    timeoutMs: 8000,
    remaining: () => clock.left(FINISH_RESERVE_MS),
  });
  if (result.code !== 0 || result.stdout.length === 0 || result.stdout.length > 8192) throw new Error('credential read failed');
  const parsed = JSON.parse(result.stdout);
  assertEndpoint(parsed, port);
  fs.writeFileSync(hostFile, result.stdout, { mode: 0o600 });
  return parsed;
}

function portFree(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.listen(port, '127.0.0.1', () => server.close(() => resolve(true)));
  });
}

class Mcp {
  constructor(child, token) {
    this.child = child;
    this.token = token || '';
    this.buffer = '';
    this.pending = new Map();
    this.nextId = 1;
    this.stderr = '';
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => this.onData(chunk));
    child.stderr.on('data', (chunk) => { this.stderr += redact(chunk, this.token); });
    child.on('error', (error) => this.rejectAll(error));
    child.on('exit', (code) => this.rejectAll(new Error(`adapter exited ${code}`)));
  }

  rejectAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pending.clear();
  }

  onData(chunk) {
    this.buffer += chunk;
    let newline = this.buffer.indexOf('\n');
    while (newline !== -1) {
      const line = this.buffer.slice(0, newline).trim();
      this.buffer = this.buffer.slice(newline + 1);
      if (line) {
        const message = JSON.parse(line);
        const pending = this.pending.get(message.id);
        if (pending) {
          this.pending.delete(message.id);
          clearTimeout(pending.timer);
          pending.resolve(message);
        }
      }
      newline = this.buffer.indexOf('\n');
    }
  }

  request(method, params, timeoutMs) {
    const id = this.nextId;
    this.nextId += 1;
    if (timeoutMs <= 0) return Promise.reject(new Error('deadline exhausted'));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  notify(method) {
    this.child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method })}\n`);
  }

  close() {
    this.rejectAll(new Error('closed'));
    this.child.stdin.end();
  }
}

function spawnAdapter(credentialFile) {
  const child = spawn(process.execPath, [ADAPTER], {
    env: { ...process.env, ANDCHAMBER_BROWSER_ENDPOINT_FILE: credentialFile || '' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  return new Mcp(child);
}

function toolFailed(message) {
  if (message.error) return message.error.message || 'rpc error';
  if (message.result && message.result.isError === true) return 'tool error';
  return '';
}

async function handshake(mcp, timeoutMs) {
  const init = await mcp.request('initialize', { protocolVersion: '2025-06-18', capabilities: {} }, timeoutMs);
  if (init.error) throw new Error(init.error.message);
  mcp.notify('notifications/initialized');
  const listed = await mcp.request('tools/list', {}, timeoutMs);
  if (listed.error || (listed.result && listed.result.isError)) throw new Error('tools/list failed');
  const names = (listed.result.tools || []).map((tool) => tool.name);
  if (TOOLS.some((name) => !names.includes(name))) throw new Error('tool list mismatch');
  return names;
}

function parseSnapshot(message) {
  const failed = toolFailed(message);
  if (failed) throw new Error(failed);
  const text = message.result.content[0].text;
  const outer = JSON.parse(text);
  return JSON.parse(outer.snapshot);
}

async function pollSnapshot(mcp, clock, ready) {
  const started = Date.now();
  let last = 'not ready';
  while (clock.left(FINISH_RESERVE_MS) > 0 && Date.now() - started < 20000) {
    const timeoutMs = boundedTimeout(8000, clock.left(FINISH_RESERVE_MS));
    const reply = await mcp.request('tools/call', { name: 'browser_snapshot', arguments: {} }, timeoutMs);
    const failed = toolFailed(reply);
    if (!failed) {
      try {
        const snapshot = parseSnapshot(reply);
        if (ready(snapshot)) return snapshot;
        last = 'fixture not ready';
      } catch (error) {
        last = error.message;
      }
    } else {
      last = failed;
    }
    const pause = Math.min(700, clock.left(FINISH_RESERVE_MS));
    if (pause <= 0) break;
    await new Promise((resolve) => setTimeout(resolve, pause));
  }
  throw new Error(clock.left(FINISH_RESERVE_MS) <= 0 ? 'deadline exhausted' : last);
}

function element(snapshot, role, name) {
  return (snapshot.elements || []).find((item) => item.role === role && item.name === name);
}

async function proveReplacement(actions) {
  const before = await actions.read();
  const ref = actions.refFrom(before);
  if (actions.staleRef && actions.staleRef(ref)) throw new Error('navigation ref was stale before navigation');
  await actions.navigate();
  if (!(await actions.oldRefFails(ref))) throw new Error('old ref survived navigation');
  const after = await actions.read();
  if (after.url !== SECOND_URL) throw new Error('url mismatch');
  await actions.type(actions.refFrom(after));
  return { ref, url: after.url };
}

async function capture(mcp, clock, out, viewport) {
  const reply = await mcp.request('tools/call', { name: 'browser_capture', arguments: {} }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
  if (toolFailed(reply)) throw new Error(toolFailed(reply));
  const image = reply.result.content[0];
  if (image.type !== 'image' || image.mimeType !== 'image/png') throw new Error('capture was not png');
  const bytes = Buffer.from(image.data, 'base64');
  if (bytes.toString('ascii', 1, 4) !== 'PNG') throw new Error('png signature missing');
  if (bytes.readUInt32BE(16) !== viewport.width || bytes.readUInt32BE(20) !== viewport.height) throw new Error('png size mismatch');
  fs.writeFileSync(path.join(out, 'capture.png'), bytes, { mode: 0o600 });
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

async function focusedIsFixture(args, clock) {
  const result = await adb(args, ['shell', 'dumpsys', 'window'], { timeoutMs: 8000, remaining: () => clock.left(FINISH_RESERVE_MS) });
  return result.code === 0 && /mFocusedApp=.*io\.andchamber\.app/.test(result.stdout);
}

async function createForward(args, clock, port, owned) {
  if (!(await portFree(port))) throw new Error('forward port busy');
  const result = await adb(args, ['forward', '--no-rebind', `tcp:${port}`, `tcp:${port}`], {
    timeoutMs: 8000,
    remaining: () => clock.left(FINISH_RESERVE_MS),
  });
  if (result.code !== 0) throw new Error('adb forward failed');
  owned.push(`tcp:${port}`);
}

async function removeForward(args, clock, spec) {
  const result = await adb(args, ['forward', '--remove', spec], {
    timeoutMs: 8000,
    remaining: () => Math.max(1000, clock.left(0)),
  });
  if (result.code !== 0) throw new Error('forward remove failed');
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const report = new Report(args.out);
  const credentialHost = path.join(args.out, 'credential.json');
  const owned = [];
  const started = Date.now();
  const clock = {
    left(reserve = 0) {
      return remainingMs(started, Date.now(), BUDGET_MS, reserve);
    },
  };
  let runId = '';
  let mcp = null;
  try {
    const initial = await waitStatus(args, clock, (status) => status && status.phase === 'disabled', Math.min(INITIAL_WAIT_MS, clock.left(FINISH_RESERVE_MS)), '');
    runId = initial.runId;
    report.add('initial-disabled', initial.phase === 'disabled' && initial.port == null, initial.phase);
    report.add('initial-credential-absent', !(await credentialExists(args, clock)), 'fixed path');
    mcp = spawnAdapter('');
    const names = await handshake(mcp, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('list-tools', names.length === 6, names.join(','));
    const denied = await mcp.request('tools/call', { name: 'browser_snapshot', arguments: {} }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('disabled-call-rejected', Boolean(toolFailed(denied)), toolFailed(denied) || 'unexpected success');
    mcp.close();
    mcp = null;

    const enabled = await writeCommand(args, clock, 'enable', runId);
    report.add('enable', enabled.phase === 'enabled', enabled.phase);
    await createForward(args, clock, enabled.port, owned);
    const credential = await copyCredential(args, clock, credentialHost, enabled.port);
    mcp = openIfEndpoint(credential, enabled.port, () => spawnAdapter(credentialHost));
    mcp.token = credential.token;
    await handshake(mcp, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    const navigated = await mcp.request('tools/call', { name: 'browser_navigate', arguments: { url: NAV_URL } }, boundedTimeout(20000, clock.left(FINISH_RESERVE_MS)));
    report.add('navigate', !toolFailed(navigated), toolFailed(navigated) || 'once');
    const first = await pollSnapshot(mcp, clock, (snapshot) => element(snapshot, 'input', 'note') && element(snapshot, 'button', 'Apply') && snapshotLabel(snapshot, 'click-count') === 0);
    const leaked = JSON.stringify(first).includes('do-not-read') || (first.elements || []).some((item) => /password|file/.test(`${item.name} ${item.role}`));
    report.add('fixture-ready', !leaked && snapshotLabel(first, 'click-count') === 0, leaked ? 'secret leaked' : 'count 0');
    const oldApply = element(first, 'button', 'Apply').ref;
    const typed = await mcp.request('tools/call', { name: 'browser_type', arguments: { ref: element(first, 'input', 'note').ref, text: MARKER } }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('type', !toolFailed(typed), toolFailed(typed) || 'once');
    const typedSnap = await pollSnapshot(mcp, clock, (snapshot) => Boolean(noteWithValue(snapshot, MARKER)) && snapshotLabel(snapshot, 'click-count') === 0);
    report.add('type-visible', Boolean(noteWithValue(typedSnap, MARKER)), 'name=note value exact');
    const apply = element(typedSnap, 'button', 'Apply');
    const clicked = await mcp.request('tools/call', { name: 'browser_click', arguments: { ref: apply.ref } }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('click', !toolFailed(clicked), toolFailed(clicked) || 'once');
    const counted = await pollSnapshot(mcp, clock, (snapshot) => snapshotLabel(snapshot, 'click-count') === 1);
    report.add('click-count', snapshotLabel(counted, 'click-count') === 1, 'exactly 1');
    const stale = await mcp.request('tools/call', { name: 'browser_click', arguments: { ref: oldApply } }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('stale-ref', Boolean(toolFailed(stale)), toolFailed(stale) || 'unexpected success');
    const scrolled = await mcp.request('tools/call', { name: 'browser_scroll', arguments: { direction: 'down' } }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('scroll', !toolFailed(scrolled), toolFailed(scrolled) || 'once');
    const scrolledSnap = await pollSnapshot(mcp, clock, (snapshot) => snapshotLabel(snapshot, 'scroll-y') > 0);
    report.add('scroll-observed', snapshotLabel(scrolledSnap, 'scroll-y') > 0, 'scroll-y');
    const reset = await mcp.request('tools/call', { name: 'browser_scroll', arguments: { direction: 'top' } }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('scroll-reset', !toolFailed(reset), toolFailed(reset) || 'top once');
    const fresh = await pollSnapshot(mcp, clock, (snapshot) => snapshotLabel(snapshot, 'scroll-y') === 0 && element(snapshot, 'input', 'note') && element(snapshot, 'button', 'Apply'));
    await new Promise((resolve) => setTimeout(resolve, Math.min(150, clock.left(FINISH_RESERVE_MS))));
    const hash = await capture(mcp, clock, args.out, enabled.viewport);
    report.add('capture', Boolean(hash), 'png size checked; pixels not verified');
    let replacementReads = 0;
    const replacement = await proveReplacement({
      read: async () => {
        if (replacementReads++ === 0) return fresh;
        return pollSnapshot(mcp, clock, (snapshot) => snapshot.url === SECOND_URL && element(snapshot, 'input', 'note'));
      },
      refFrom: (snapshot) => element(snapshot, 'input', 'note').ref,
      navigate: async () => {
        const started = await mcp.request('tools/call', { name: 'browser_navigate', arguments: { url: SECOND_URL } }, boundedTimeout(20000, clock.left(FINISH_RESERVE_MS)));
        if (toolFailed(started)) throw new Error(toolFailed(started));
      },
      oldRefFails: async (ref) => Boolean(toolFailed(await mcp.request('tools/call', { name: 'browser_click', arguments: { ref } }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS))))),
      type: async (ref) => {
        const typedSecond = await mcp.request('tools/call', { name: 'browser_type', arguments: { ref, text: MARKER } }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
        if (toolFailed(typedSecond)) throw new Error(toolFailed(typedSecond));
      },
    });
    report.add('second-navigation', true, 'once');
    report.add('new-view-old-ref', true, 'old ref rejected before new snapshot');
    const secondSnap = await pollSnapshot(mcp, clock, (snapshot) => snapshot.url === SECOND_URL && element(snapshot, 'input', 'note'));
    report.add('second-url', secondSnap.url === SECOND_URL && replacement.url === SECOND_URL, secondSnap.url || 'missing url');
    const secondSeen = await pollSnapshot(mcp, clock, (snapshot) => Boolean(noteWithValue(snapshot, MARKER)));
    report.add('second-view-usable', Boolean(noteWithValue(secondSeen, MARKER)), 'new note accepted marker');

    const taken = await writeCommand(args, clock, 'takeover', runId);
    const gone = !(await credentialExists(args, clock));
    const rejected = await mcp.request('tools/call', { name: 'browser_snapshot', arguments: {} }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('takeover', taken.phase === 'taken_over' && gone && Boolean(toolFailed(rejected)), taken.phase);
    mcp.close();
    mcp = null;

    await removeForward(args, clock, owned[0]);
    owned.shift();
    const restored = await writeCommand(args, clock, 'enable', runId);
    await createForward(args, clock, restored.port, owned);
    const restoredCredential = await copyCredential(args, clock, credentialHost, restored.port);
    mcp = openIfEndpoint(restoredCredential, restored.port, () => spawnAdapter(credentialHost));
    mcp.token = restoredCredential.token;
    await handshake(mcp, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    const restoredCall = await mcp.request('tools/call', { name: 'browser_snapshot', arguments: {} }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('reenabled', restored.phase === 'enabled' && !toolFailed(restoredCall), restored.phase);
    if (!(await focusedIsFixture(args, clock))) throw new Error('focused app is not the fixture');
    const home = await adb(args, ['shell', 'input', 'keyevent', 'KEYCODE_HOME'], { timeoutMs: 8000, remaining: () => clock.left(FINISH_RESERVE_MS) });
    if (home.code !== 0) throw new Error('home key failed');
    const hidden = await waitStatus(args, clock, (status) => status.phase === 'hidden', boundedTimeout(20000, clock.left(FINISH_RESERVE_MS)), runId);
    const hiddenGone = !(await credentialExists(args, clock));
    const hiddenCall = await mcp.request('tools/call', { name: 'browser_snapshot', arguments: {} }, boundedTimeout(8000, clock.left(FINISH_RESERVE_MS)));
    report.add('home-hidden', hidden.phase === 'hidden' && hiddenGone && Boolean(toolFailed(hiddenCall)), hidden.phase);
    const restarted = await adb(args, ['shell', 'am', 'start', '-n', 'io.andchamber.app/.MainActivity'], { timeoutMs: 8000, remaining: () => clock.left(FINISH_RESERVE_MS) });
    if (restarted.code !== 0) throw new Error('activity restart failed');
    const afterRestart = await readStatus(args, clock, runId);
    report.add('no-autoenable', afterRestart.phase !== 'enabled', 'state only; activity restart is not a new control session');
  } catch (error) {
    report.add('runner', false, error.message || 'runner failed');
  } finally {
    if (mcp) mcp.close();
    try {
      if (!runId) {
        const prepared = await waitStatus(args, { left: () => 10000 }, (status) => status && status.phase, 5000, '').catch(() => null);
        if (prepared) runId = prepared.runId;
      }
      if (!runId) throw new Error('finish skipped: status was never established');
      await writeCommand(args, { left: () => 10000 }, 'finish', runId);
      await waitStatus(args, { left: () => 10000 }, (status) => status && status.phase === 'finished', 10000, runId);
      report.add('finish', true, 'finished');
    } catch (error) {
      report.add('finish', false, error.message || 'finish failed');
    }
    let cleanupCode = 0;
    for (const spec of owned) {
      const removed = await adb(args, ['forward', '--remove', spec], { timeoutMs: 8000, remaining: () => 8000 }).catch(() => ({ code: 1 }));
      if (removed.code !== 0) cleanupCode = removed.code || 1;
    }
    if (owned.length === 0) noteCleanup(report, 0);
    else noteCleanup(report, cleanupCode);
    fs.rmSync(credentialHost, { force: true });
  }
  const adapterSha256 = crypto.createHash('sha256').update(fs.readFileSync(ADAPTER)).digest('hex');
  process.exitCode = report.write({
    serial: args.serial,
    adapter: path.basename(ADAPTER),
    adapterSha256,
    validationScope: 'host Node production MCP adapter to Android emulator RPC/WebView; on-device runtime not exercised',
  });
}

if (require.main === module) main();

module.exports = {
  parseArgs,
  shellQuote,
  adbShellArg,
  writeCommandArgv,
  credentialExistsArgv,
  statusArgv,
  statusExistsArgv,
  missingStatusOutput,
  interpretStatus,
  remainingMs,
  boundedTimeout,
  assertEndpoint,
  openIfEndpoint,
  labeledInt,
  snapshotLabel,
  noteWithValue,
  redact,
  noteCleanup,
  proveReplacement,
  toolFailed,
  parseSnapshot,
  element,
  SECOND_URL,
  CREDENTIAL_REMOTE,
  COMMAND_SCRIPT,
};
