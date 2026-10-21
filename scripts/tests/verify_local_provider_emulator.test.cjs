'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const driver = require('../verify_local_provider_emulator.cjs');

test('serial is locked to emulator-5582 and refuses physical/shared devices', () => {
  assert.equal(driver.requireSerial('emulator-5582'), undefined);
  for (const serial of ['emulator-5580', 'emulator-55821', 'device', 'R58M123', '']) {
    assert.throws(() => driver.requireSerial(serial), /emulator-5582|physical\/shared/);
  }
  assert.throws(() => driver.parseArgs(['--serial', 'emulator-5580', '--out', '/tmp/x']), /emulator-5582/);
  assert.throws(() => driver.parseArgs(['--serial', 'emulator-5582', '--out', `${path.resolve(__dirname, '..')}/build/leak`]), /outside the repository/);
});

test('package guard refuses the production app unless explicitly allowed', () => {
  assert.equal(driver.requirePackage('io.andchamber.localprovider'), 'io.andchamber.localprovider');
  assert.throws(() => driver.requirePackage('io.andchamber.app'), /production package/);
  assert.equal(driver.requirePackage('io.andchamber.app', { allowProductionPackage: true }), 'io.andchamber.app');
  assert.throws(() => driver.requirePackage('not a package'), /invalid package/);
});

test('native service URLs are the exact forwarded guest endpoints', () => {
  const urls = driver.endpoints(23000);
  assert.deepEqual(urls, {
    health: 'http://127.0.0.1:23000/health',
    systemInfo: 'http://127.0.0.1:23000/api/system/info',
    openCodeVersion: 'http://127.0.0.1:23000/api/opencode/version',
    info: 'http://127.0.0.1:23000/api/info',
    authSession: 'http://127.0.0.1:23000/auth/session',
  });
});

test('secret redaction removes password, token, cookie and Bearer values', () => {
  const password = 'pw-super-secret-value';
  const token = 'agent-token-123456';
  const cookie = 'oc_session=abcdef123456';
  const text = `password=${password} Authorization: Bearer ${token} set-cookie: ${cookie} {"password":"${password}"}`;
  const out = driver.redact(text, [password, token, cookie]);
  assert.equal(out.includes(password), false);
  assert.equal(out.includes(token), false);
  assert.equal(out.includes('abcdef123456'), false);
  assert.match(out, /Bearer \[redacted\]/);
  assert.match(driver.redact('{"password":"secret-value"}', ['secret-value']), /"password":"\[redacted\]"/);
});

test('emulator guard requires qemu and x86_64', () => {
  assert.deepEqual(driver.emulatorProps({
    'ro.kernel.qemu': '1',
    'ro.product.cpu.abi': 'x86_64',
    'ro.product.cpu.abilist': 'x86_64,arm64-v8a',
    'ro.build.version.sdk': '35',
  }), { abi: 'x86_64', sdk: 35 });
  assert.throws(() => driver.emulatorProps({ 'ro.kernel.qemu': '0', 'ro.product.cpu.abi': 'x86_64' }), /not an emulator/);
  assert.throws(() => driver.emulatorProps({ 'ro.kernel.qemu': '1', 'ro.product.cpu.abi': 'arm64-v8a' }), /not x86_64/);
});

test('Allow agent is found by label and tapped at the real bounds centre', () => {
  const xml = '<hierarchy><node text="Cancel" bounds="[0,0][10,10]" /><node class="android.widget.TextView" text="Allow agent" bounds="[100,200][300,280]" /></hierarchy>';
  assert.deepEqual(driver.selectAllowNode(xml), {
    label: 'Allow agent', left: 100, top: 200, right: 300, bottom: 280, x: 200, y: 240,
  });
  assert.equal(driver.selectAllowNode(xml, 'Always allow'), null);
  assert.equal(driver.selectAllowNode('<node text="Allow agent" bounds="[10,10][10,10]" />'), null);
});

test('instrumentation counts keep official result separate from adb exit', () => {
  const ok = driver.instrumentationCounts('INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_CODE: -1\nOK (1 test)\n');
  assert.deepEqual(ok, {
    tests: 1, failures: 0, okLine: true, failuresLine: false, fatal: false,
    instrumentationCode: -1, pass: true,
  });
  const bad = driver.instrumentationCounts('FAILURES!!! Tests run: 2, Failures: 1\nINSTRUMENTATION_CODE: 0\n');
  assert.equal(bad.pass, false);
  assert.equal(bad.tests, 2);
  assert.equal(bad.failures, 1);
  // An adb exit 0 with a failure line must never be a pass.
  assert.equal(driver.instrumentationCounts('FAILURES!!! Tests run: 1, Failures: 1\n').pass, false);
  // OK alone is not enough: code must be -1, N must be > 0, and fatal markers win.
  assert.equal(driver.instrumentationCounts('OK (0 tests)\nINSTRUMENTATION_CODE: -1\n').pass, false);
  assert.equal(driver.instrumentationCounts('OK (1 test)\nINSTRUMENTATION_CODE: 0\n').pass, false);
  assert.equal(driver.instrumentationCounts('OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_STATUS: shortMsg=Process crashed.\n').pass, false);
});

test('each mutating action is fenced to exactly one execution', async () => {
  const once = driver.onceAction('tap');
  assert.equal(await once(() => 7), 7);
  assert.throws(() => once(() => 8), /only once/);
});

test('timeout fence rejects and does not retry', async () => {
  let calls = 0;
  let killed = 0;
  const pending = new Promise(() => { calls += 1; });
  await assert.rejects(
    driver.withTimeout(pending, 20, () => { killed += 1; }),
    /timeout after 20ms/,
  );
  assert.equal(calls, 1);
  assert.equal(killed, 1);
});

test('a missing provider or transport failure is FAIL, never PASS', () => {
  const result = driver.verdict({
    runtime: { status: 'pass' },
    artifact: { verified: true },
    auth: { status: 'pass' },
    browser: { chain: 'blocked', reason: 'no-provider' },
    actions: { open: 1, type: 1, click: 1, scroll: 1, capture: 1 },
    restarted: { status: 'pass' },
    cold: { status: 'pass' },
  }, {
    instrumentation: { pass: true },
    coldInstrumentation: { pass: true },
    health: { status: 'pass' },
    pty: { ok: true },
  });
  assert.equal(result.ok, false);
  assert.match(result.reasons.join(','), /browser-no-provider-or-transport/);
  const transport = driver.verdict({
    runtime: { status: 'pass' },
    artifact: { verified: true },
    auth: { status: 'pass' },
    browser: { chain: 'fail', reason: 'transport: refused' },
    actions: { open: 1, type: 1, click: 1, scroll: 1, capture: 1 },
    restarted: { status: 'pass' },
    cold: { status: 'pass' },
  }, {
    instrumentation: { pass: true }, coldInstrumentation: { pass: true }, health: { status: 'pass' }, pty: { ok: true },
  });
  assert.equal(transport.ok, false);
});

test('full pass needs runtime, artifact, auth, five once-actions, restart, cold, health and PTY', () => {
  const evidence = {
    runtime: { status: 'pass' },
    artifact: { verified: true },
    auth: { status: 'pass' },
    browser: { chain: 'pass', reason: '' },
    actions: { open: 1, type: 1, click: 1, scroll: 1, capture: 1, snapshot: 2, unauthorizedSnapshot: 1 },
    restarted: { status: 'pass' },
    cold: { status: 'pass' },
  };
  const host = {
    instrumentation: { pass: true, adbExit: 0 }, coldInstrumentation: { pass: true, adbExit: 0 }, health: { status: 'pass' },
    taps: {
      chatOpen: { label: 'Open', x: 1, y: 2 },
      initialAllow: { label: 'Allow agent', x: 1, y: 2 },
      takeover: { label: 'Take over', x: 1, y: 2 },
      fixtureAllow: { label: 'Allow agent', x: 1, y: 2 },
    }, pty: { ok: true },
  };
  assert.deepEqual(driver.verdict(evidence, host), { ok: true, reasons: [] });
  assert.equal(driver.verdict(evidence, { ...host, pty: { ok: false } }).ok, false);
  assert.equal(driver.verdict({ ...evidence, actions: { ...evidence.actions, type: 2 } }, host).ok, false);
  assert.equal(driver.verdict(evidence, { ...host, instrumentation: { pass: false } }).ok, false);
});

test('normalized evidence from a failed test is never a pass', () => {
  const normalized = driver.normalizeEvidence({
    phase: 'failed',
    failure: 'snapshot-not-drivable: No Android browser provider',
    facts: { installedProviderFiles: 2, installedProviderPayloadDigest: 'abc', authCookieLength: 64 },
    actions: { open: 1, type: 0, click: 0, scroll: 0, capture: 0 },
  }, {
    instrumentation: { pass: true },
    cold: { phase: 'done', facts: { coldRuntimeLaunchId: 9 } },
    host: { allowTap: null },
  });
  assert.equal(normalized.browser.chain, 'fail');
  assert.equal(driver.verdict(normalized, {
    instrumentation: { pass: true }, coldInstrumentation: { pass: true }, health: { status: 'pass' }, pty: { ok: true },
  }).ok, false);
});

test('cookie jar is written outside the repository with mode 0600', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'oc-local-provider-'));
  const file = path.join(dir, 'cookies.txt');
  try {
    assert.equal(driver.writeCookieJar(file, 'oc_session=abc; other=def'), 2);
    assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    const content = fs.readFileSync(file, 'utf8');
    assert.match(content, /#HttpOnly_127\.0\.0\.1\tFALSE\t\/\tFALSE\t0\toc_session\tabc/);
    assert.equal(driver.cookiePairs('oc_session=abc; other=def').length, 2);
    assert.throws(() => driver.writeCookieJar(file, ''), /no pairs/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('ui password is read through run-as, never printed, and empty means fail', async () => {
  const secret = 'do-not-print-this-password';
  const calls = [];
  const value = await driver.readPassword(async (command) => {
    calls.push(command.join(' '));
    return { code: 0, stdout: `${secret}\n` };
  }, 'io.andchamber.localprovider');
  assert.equal(value, secret);
  assert.match(calls[0], /run-as io\.andchamber\.localprovider cat files\/runtime\/ui-password/);
  assert.equal(driver.redact(`value=${value}`, [value]).includes(secret), false);
  await assert.rejects(driver.readPassword(async () => ({ code: 0, stdout: '\n' }), 'pkg'), /empty/);
  await assert.rejects(driver.readPassword(async () => ({ code: 1, stdout: secret }), 'pkg'), /unreadable/);
});

test('forward list parsing keeps the serial and both endpoints', () => {
  assert.deepEqual(driver.parseForwardList('emulator-5582 tcp:23000 tcp:3000\nemulator-5580 tcp:23001 tcp:3000\n'), [
    { serial: 'emulator-5582', local: 'tcp:23000', remote: 'tcp:3000' },
    { serial: 'emulator-5580', local: 'tcp:23001', remote: 'tcp:3000' },
  ]);
});
test('agent-tool decoder matches the real envelope and unwraps content/output without inventing success', () => {
  const openEnvelope = {
    schemaVersion: 1,
    ok: true,
    action: 'browser.open',
    data: { tabId: 'tab-1', drivable: false, supportedActions: ['browser.open'] },
  };
  const direct = driver.decodeAgentToolResponse(JSON.stringify(openEnvelope));
  assert.equal(direct.ok, true);
  assert.deepEqual(direct.data, openEnvelope.data);
  assert.equal(direct.action, 'browser.open');

  const content = driver.decodeAgentToolResponse(JSON.stringify({ data: { content: JSON.stringify(openEnvelope) } }));
  assert.equal(content.ok, true);
  assert.equal(content.data.tabId, 'tab-1');

  const output = driver.decodeAgentToolResponse(JSON.stringify({ data: { output: openEnvelope } }));
  assert.equal(output.ok, true);
  assert.equal(output.data.tabId, 'tab-1');

  const denied = driver.decodeAgentToolResponse(JSON.stringify({
    schemaVersion: 1,
    ok: false,
    action: 'browser.snapshot',
    error: { message: 'Authorization required', kind: 'runtime', code: 'AUTHORIZATION_REQUIRED', status: 403 },
  }));
  assert.equal(denied.ok, false);
  assert.equal(denied.errorCode, 'AUTHORIZATION_REQUIRED');
  assert.match(denied.error, /AUTHORIZATION_REQUIRED/);
  assert.match(denied.error, /Authorization required/);

  const nestedDenied = driver.decodeAgentToolResponse(JSON.stringify({
    data: { content: JSON.stringify({ ok: false, error: { message: 'No Android browser provider', kind: 'runtime' } }) },
  }));
  assert.equal(nestedDenied.ok, false);
  assert.match(nestedDenied.error, /No Android browser provider/);

  assert.equal(driver.decodeAgentToolResponse('{"ok":true}').ok, true);
  assert.equal(driver.decodeAgentToolResponse('{"data":{"nothing":true}}').ok, false);
  assert.equal(driver.decodeAgentToolResponse('not-json').ok, false);
});

test('health readiness requires the actual schema fields, not a missing default', () => {
  assert.equal(driver.healthReady('{"status":"ok","isOpenCodeReady":true}'), true);
  assert.equal(driver.healthReady('{"status":"ok"}'), false);
  assert.equal(driver.healthReady('{"status":"ok","isOpenCodeReady":false}'), false);
  assert.equal(driver.healthReady('{"isOpenCodeReady":true}'), false);
  assert.equal(driver.healthReady('not-json'), false);
});

test('OpenCode version comparison is exact, not a substring', () => {
  assert.equal(driver.openCodeVersionMatches('{"version":"2.0.32"}', '2.0.32'), true);
  assert.equal(driver.openCodeVersionMatches('{"version":"2.0.320"}', '2.0.32'), false);
  assert.equal(driver.openCodeVersionMatches('{"version":null}', '2.0.32'), false);
  assert.equal(driver.openCodeVersionMatches('{"version":"2.0.32"}', '2.0.3'), false);
});

test('capture path is confined to /root -> RuntimePaths.home and .openchamber/screenshots', () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'oc-capture-home-'));
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'oc-capture-outside-'));
  try {
    fs.mkdirSync(path.join(home, '.openchamber', 'screenshots'), { recursive: true });
    const image = path.join(home, '.openchamber', 'screenshots', 'one.png');
    fs.writeFileSync(image, 'png');
    assert.equal(driver.resolveCapturePath('.openchamber/screenshots/one.png', home), image);
    assert.equal(driver.resolveCapturePath('/root/.openchamber/screenshots/one.png', home), image);
    assert.equal(driver.resolveCapturePath('/data/user/0/io.andchamber.app/files/one.png', home), null);
    assert.equal(driver.resolveCapturePath('../outside.png', home), null);
    assert.equal(driver.resolveCapturePath('.openchamber/other/one.png', home), null);

    const link = path.join(home, '.openchamber', 'screenshots', 'link');
    fs.symlinkSync(outside, link);
    fs.writeFileSync(path.join(outside, 'escape.png'), 'png');
    assert.equal(driver.resolveCapturePath('.openchamber/screenshots/link/escape.png', home), null);
  } finally {
    fs.rmSync(home, { recursive: true, force: true });
    fs.rmSync(outside, { recursive: true, force: true });
  }
});

test('HTTP helper rejects a real socket partial body instead of parsing it', async () => {
  const net = require('node:net');
  const server = net.createServer((socket) => {
    socket.end('HTTP/1.1 200 OK\r\nContent-Length: 100\r\nContent-Type: application/json\r\n\r\n{"ok":');
    socket.destroy();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  try {
    await assert.rejects(
      driver.httpRequest(`http://127.0.0.1:${port}/api/openchamber/agent-tool`, { timeoutMs: 3000 }),
      /incomplete response|socket hang up|aborted/i,
    );
  } finally {
    server.closeAllConnections?.();
    await new Promise((resolve) => server.close(resolve));
  }
});

test('failure collection reads only the package status/evidence and bounded PID logs', async () => {
  const calls = [];
  const logs = 'line1\ntoken=secret-token\nline3';
  const adbFn = async (command) => {
    calls.push(command.join(' '));
    if (command.includes('pidof')) return { code: 0, stdout: '4242\n' };
    if (command[0] === 'logcat') return { code: 0, stdout: logs };
    if (command.some((part) => String(part).endsWith('status.json'))) {
      return { code: 0, stdout: '{"phase":"await-allow-tap"}' };
    }
    if (command.some((part) => String(part).endsWith('evidence.json'))) {
      return { code: 0, stdout: '{"phase":"failed","failure":"no-provider"}' };
    }
    return { code: 0, stdout: '{}' };
  };
  const result = await driver.collectFailureEvidence(adbFn, 'io.andchamber.localprovider', ['secret-token']);
  assert.equal(result.status.phase, 'await-allow-tap');
  assert.equal(result.evidence.failure, 'no-provider');
  assert.deepEqual(result.pids, ['4242']);
  assert.equal(result.logcat.includes('secret-token'), false);
  assert.match(calls.join('\n'), /logcat -d -t 200 --pid=4242/);
  assert.equal(calls.some((line) => /logcat -d(?!.*--pid)/.test(line)), false);
});

test('output root must not exist; a previous run is never appended', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'oc-output-root-'));
  try {
    const fresh = path.join(dir, 'run');
    assert.equal(driver.requireFreshOutputRoot(fresh), fresh);
    fs.mkdirSync(fresh);
    assert.throws(() => driver.requireFreshOutputRoot(fresh), /already exists/);
    assert.throws(() => driver.requireFreshOutputRoot('relative/run'), /absolute/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('run ids are non-secret, valid, and stale status is ignored', async () => {
  const id = driver.createRunId(() => 0, () => Buffer.from('aabbccdd', 'hex'));
  assert.match(id, /^tl-[a-z0-9]+-[0-9a-f]{8}$/);
  assert.equal(driver.validRunId(id), true);
  assert.equal(driver.validRunId('short'), false);
  assert.equal(driver.validRunId('has space'), false);
  const adbFn = async () => ({ code: 0, stdout: '{"runId":"old-run-123","phase":"failed"}' });
  assert.equal(await driver.readRunJson(adbFn, 'pkg', 'status.json', 'new-run-123'), null);
  assert.equal((await driver.readRunJson(adbFn, 'pkg', 'status.json', 'old-run-123')).phase, 'failed');
});

test('UI labels used by the chat/takeover stages are found exactly', () => {
  const xml = '<hierarchy>' +
    '<node text="Open" bounds="[10,20][110,80]" />' +
    '<node text="Allow agent" bounds="[10,100][210,160]" />' +
    '<node text="Take over" bounds="[10,180][210,240]" />' +
    '</hierarchy>';
  assert.equal(driver.selectAllowNode(xml, 'Open').x, 60);
  assert.equal(driver.selectAllowNode(xml, 'Allow agent').x, 110);
  assert.equal(driver.selectAllowNode(xml, 'Take over').x, 110);
  assert.equal(driver.selectAllowNode(xml, 'Close'), null);
});

test('exported target tap validates run/phase/package/freshness/area before one adb tap', async () => {
  const target = {
    label: 'Allow agent', left: 10, top: 20, right: 110, bottom: 80, windowId: 7,
    observedAtMs: 1000, ownerPackage: 'io.andchamber.localprovider',
    phase: 'await-allow-tap', runId: 'run-12345', enabled: true,
  };
  const expected = {
    runId: 'run-12345', phase: 'await-allow-tap', label: 'Allow agent',
    package: 'io.andchamber.localprovider', now: 2000, nowProvider: () => 2000, screenWidth: 1080, screenHeight: 2400,
  };
  assert.equal(driver.validateExportedTarget(target, expected), null);
  const calls = [];
  const adbFn = async (command) => {
    calls.push(command.join(' '));
    if (command[0] === 'shell' && command[1] === 'dumpsys') {
      return { code: 0, stdout: 'mCurrentFocus=Window{1 u0 io.andchamber.localprovider/io.andchamber.app.MainActivity}\n' };
    }
    if (command[0] === 'shell' && command[1] === 'input') return { code: 0, stdout: '' };
    return { code: 0, stdout: '' };
  };
  const state = {};
  const first = await driver.tapExportedTarget(adbFn, target, expected, state);
  assert.equal(first.tapped, true);
  assert.equal(first.x, 60);
  assert.equal(first.y, 50);
  assert.equal(first.exitCode, 0);
  assert.equal(calls.filter((line) => line.includes('input tap')).length, 1);
  const second = await driver.tapExportedTarget(adbFn, target, expected, state);
  assert.equal(second.tapped, false);
  assert.equal(second.reason, 'already-tapped');
  assert.equal(calls.filter((line) => line.includes('input tap')).length, 1);
});

test('exported target rejects wrong run/phase/package/stale/disabled/noarea with zero taps', async () => {
  const base = {
    label: 'Allow agent', left: 10, top: 20, right: 110, bottom: 80, windowId: 7,
    observedAtMs: 1000, ownerPackage: 'io.andchamber.localprovider',
    phase: 'await-allow-tap', runId: 'run-12345', enabled: true,
  };
  const expected = {
    runId: 'run-12345', phase: 'await-allow-tap', label: 'Allow agent',
    package: 'io.andchamber.localprovider', now: 2000, screenWidth: 1080, screenHeight: 2400,
  };
  const variants = [
    ['wrong-run', { ...base, runId: 'other-run-1' }, expected],
    ['wrong-phase', { ...base, phase: 'await-chat-open' }, expected],
    ['wrong-package', { ...base, ownerPackage: 'other.package' }, expected],
    ['stale-target', { ...base, observedAtMs: 0 }, { ...expected, now: 5000 }],
    ['disabled-target', { ...base, enabled: false }, expected],
    ['no-area', { ...base, right: 10 }, expected],
    ['outside-screen', { ...base, right: 2000 }, expected],
  ];
  for (const [reason, target, exp] of variants) {
    assert.equal(driver.validateExportedTarget(target, exp), reason);
    let taps = 0;
    const adbFn = async (command) => {
      if (command.includes('input') && command.includes('tap')) taps += 1;
      return { code: 0, stdout: '' };
    };
    const result = await driver.tapExportedTarget(adbFn, target, exp, {});
    assert.equal(result.tapped, false, reason);
    assert.equal(taps, 0, `${reason} must not tap`);
  }
});

test('foreground package parser and wm size parser use real command output', () => {
  assert.equal(driver.foregroundPackage('mCurrentFocus=Window{1 u0 io.andchamber.localprovider/io.andchamber.app.MainActivity}'), 'io.andchamber.localprovider');
  assert.equal(driver.foregroundPackage('mResumedActivity: ActivityRecord{1 u0 io.andchamber.localprovider/.MainActivity}'), 'io.andchamber.localprovider');
  assert.equal(driver.foregroundPackage('mCurrentFocus=Window{1 u0 com.android.permissioncontroller/.GrantPermissionsActivity}'), 'com.android.permissioncontroller');
  assert.deepEqual(driver.parseWmSize('Physical size: 1080x2400\n'), { width: 1080, height: 2400 });
  assert.deepEqual(driver.parseWmSize('Physical size: 1080x2400\nOverride size: 720x1600\n'), { width: 720, height: 1600 });
  assert.equal(driver.parseWmSize('no size'), null);
});

test('cold PTY coordination runs one login and one PTY while cold instrumentation is alive', async () => {
  let coldFinished = false;
  let resolveCold;
  const coldDone = new Promise((resolve) => { resolveCold = () => { coldFinished = true; resolve({ code: 0, raw: '' }); }; });
  const calls = { login: 0, pty: 0 };
  const written = [];
  const report = {
    tag: 'X86_LOCAL_PROVIDER_PTY', ok: true, standaloneMarkerLine: true, markersComplete: true, doneLine: true,
    gitVersion: 'git version 2', nodeVersion: 'v24.18.1', opencodeVersion: 'opencode v2.0.32',
  };
  const result = await driver.coordinateColdPty({
    runId: 'run-cold-1',
    coldChild: { exitCode: null },
    coldDone,
    readStatus: async () => ({ runId: 'run-cold-1', cold: true, phase: 'await-host-pty' }),
    login: async () => { calls.login += 1; return 'cookie-value'; },
    runPty: async () => { calls.pty += 1; return { code: 0, report, raw: 'REPORT ...' }; },
    writeHandshake: async (payload) => { written.push(payload); },
    timeoutMs: 1000,
    pollMs: 1,
    sleep: async () => {},
  });
  assert.equal(calls.login, 1);
  assert.equal(calls.pty, 1);
  assert.equal(written.length, 1);
  assert.equal(written[0].ok, true);
  assert.equal(written[0].runId, 'run-cold-1');
  assert.equal(coldFinished, false, 'PTY handshake must complete before cold instrumentation finishes');
  resolveCold();
  assert.equal(result.handshake.ok, true);
});

test('cold PTY coordination never logs in after an early child exit or stale/old status', async () => {
  let login = 0;
  let pty = 0;
  let written = 0;
  await assert.rejects(driver.coordinateColdPty({
    runId: 'run-cold-2',
    coldChild: { exitCode: 0 },
    coldDone: Promise.resolve({ code: 0, raw: 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n' }),
    readStatus: async () => null,
    login: async () => { login += 1; return 'cookie'; },
    runPty: async () => { pty += 1; return { code: 0, report: null }; },
    writeHandshake: async () => { written += 1; },
    timeoutMs: 1000,
    pollMs: 1,
    sleep: async () => {},
  }), /cold-exited-before-pty-phase/);
  assert.equal(login, 0);
  assert.equal(pty, 0);
  assert.equal(written, 0);
});

test('failed or marker-incomplete PTY report writes ok:false, never ok:true', async () => {
  for (const report of [
    { ok: false, standaloneMarkerLine: false, markersComplete: false, doneLine: false },
    { ok: true, standaloneMarkerLine: true, markersComplete: false, doneLine: true, gitVersion: 'git', nodeVersion: 'node', opencodeVersion: 'opencode' },
    null,
  ]) {
    let written = null;
    let pty = 0;
    await assert.rejects(driver.coordinateColdPty({
      runId: 'run-cold-3',
      coldChild: { exitCode: null },
      coldDone: new Promise(() => {}),
      readStatus: async () => ({ runId: 'run-cold-3', cold: true, phase: 'await-host-pty' }),
      login: async () => 'cookie',
      runPty: async () => { pty += 1; return { code: report === null ? 1 : 0, report }; },
      writeHandshake: async (payload) => { written = payload; },
      timeoutMs: 1000,
      pollMs: 1,
      sleep: async () => {},
    }), /pty-report-not-pass/);
    assert.equal(pty, 1);
    assert.equal(written.ok, false);
    assert.equal(written.exitCode, report === null ? 1 : 0);
  }
});

test('official counts alone are never success without code -1, N>0, zero failures and adb exit', () => {
  assert.equal(driver.instrumentationCounts('OK (1 test)\n').pass, false);
  assert.equal(driver.instrumentationCounts('OK (1 test)\nINSTRUMENTATION_CODE: -1\n').pass, true);
  assert.equal(driver.instrumentationCounts('OK (0 tests)\nINSTRUMENTATION_CODE: -1\n').pass, false);
  assert.equal(driver.instrumentationCounts('FAILURES!!! Tests run: 1, Failures: 1\nINSTRUMENTATION_CODE: -1\n').pass, false);
  assert.equal(driver.verdict({
    runtime: { status: 'pass' }, artifact: { verified: true }, auth: { status: 'pass' },
    browser: { chain: 'pass', reason: '' },
    actions: { open: 1, type: 1, click: 1, scroll: 1, capture: 1 },
    restarted: { status: 'pass' }, cold: { status: 'pass' },
  }, {
    instrumentation: { pass: true, adbExit: 1 }, coldInstrumentation: { pass: true, adbExit: 0 },
    health: { status: 'pass' }, taps: { initialAllow: {}, takeover: {}, fixtureAllow: {} }, pty: { ok: true },
  }).ok, false);
});
