#!/usr/bin/env node
'use strict';

const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

// Patched OpenChamber checkout (packages/web). Pass --source; the env var is a fallback.
const SOURCE = process.env.OPENCHAMBER_PROVIDER_SOURCE || '';
const FIXTURE = 'https://example.com/openchamber-provider';
const FIXTURE2 = 'https://example.com/openchamber-provider-second';
const UI_PASSWORD = 'provider-emulator-password';

function parseArgs(argv) {
  const args = { serial: '', out: '', source: SOURCE, adb: path.join(process.env.HOME, 'Android/Sdk/platform-tools/adb') };
  for (let index = 0; index < argv.length; index += 1) {
    if (!argv[index].startsWith('--')) continue;
    args[argv[index].slice(2)] = argv[index + 1];
    index += 1;
  }
  if (!/^emulator-\d+$/.test(args.serial || '')) throw new Error('serial must match emulator-N');
  if (!args.out || !path.isAbsolute(args.out)) throw new Error('absolute out required');
  if (require.main === module && (!args.source || !fs.existsSync(path.join(args.source, 'bin/cli.js')))) {
    throw new Error('--source must point at the patched OpenChamber packages/web checkout');
  }
  return args;
}

function redact(value, secrets = []) {
  let text = String(value == null ? '' : value);
  for (const secret of secrets) if (secret) text = text.split(secret).join('[redacted]');
  return text.replace(/Bearer\s+\S+/gi, 'Bearer [redacted]').slice(0, 300);
}

function agentToolBody(action, input, sessionID) {
  if (!/^ses_/.test(sessionID || '')) throw new Error('real session id required');
  return JSON.stringify({ tool: 'openchamber_web', sessionID, input: { action, ...input } });
}

function childEnv(pid, parentPid, readFileSync = fs.readFileSync) {
  const status = readFileSync(`/proc/${pid}/status`, 'utf8');
  const ppid = Number((status.match(/^PPid:\s+(\d+)/m) || [])[1]);
  if (ppid !== parentPid) throw new Error('child is not in this process tree');
  const values = new Map();
  for (const part of readFileSync(`/proc/${pid}/environ`).toString('utf8').split('\0')) {
    const index = part.indexOf('=');
    if (index > 0) values.set(part.slice(0, index), part.slice(index + 1));
  }
  return values;
}

function findAgentTool(parentPid, listPids = () => fs.readdirSync('/proc').filter((name) => /^\d+$/.test(name)), readFileSync = fs.readFileSync) {
  for (const pid of listPids()) {
    try {
      const env = childEnv(pid, parentPid, readFileSync);
      if (env.get('OPENCHAMBER_AGENT_TOOL_URL') && env.get('OPENCHAMBER_AGENT_TOOL_TOKEN')) {
        return { url: env.get('OPENCHAMBER_AGENT_TOOL_URL'), token: env.get('OPENCHAMBER_AGENT_TOOL_TOKEN') };
      }
    } catch (_error) {
      continue;
    }
  }
  throw new Error('OpenCode child did not expose agent-tool env');
}

function postJson(url, headers, body) {
  return new Promise((resolve, reject) => {
    const target = new URL(url);
    const request = http.request({ hostname: target.hostname, port: target.port, path: `${target.pathname}${target.search}`, method: 'POST', headers: { ...headers, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }, timeout: 60000 }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks).toString('utf8'), headers: response.headers }));
    });
    request.on('error', () => reject(new Error('request failed')));
    request.on('timeout', () => { request.destroy(); reject(new Error('request timed out')); });
    request.end(body);
  });
}

function classify(body) {
  const parsed = JSON.parse(body);
  return { ok: parsed.ok === true, data: parsed.data || null, error: parsed.error || null };
}

async function tool(agent, sessionID, action, input, secrets) {
  let response;
  try {
    response = await postJson(agent.url, { Authorization: `Bearer ${agent.token}` }, agentToolBody(action, input, sessionID));
  } catch (error) {
    return { status: 'error', ok: false, data: null, error: null, redacted: `transport: ${error.message}` };
  }
  const parsed = classify(response.body);
  return { status: response.status, ...parsed, redacted: redact(response.body, secrets).slice(0, 240) };
}

async function screenshot(args, out, name) {
  const result = await adb(args, ['exec-out', 'screencap', '-p']);
  if (result.code === 0 && result.raw.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47]))) {
    fs.writeFileSync(path.join(out, name), result.raw, { mode: 0o600 });
  }
}

function adb(args, command, timeoutMs = 60000) {
  return new Promise((resolve) => {
    const child = spawn(args.adb, ['-s', args.serial, ...command], { stdio: ['ignore', 'pipe', 'pipe'] });
    // A dead emulator must not hang the run: every adb call is bounded.
    const timer = setTimeout(() => child.kill('SIGKILL'), timeoutMs);
    child.on('close', () => clearTimeout(timer));
    const chunks = [];
    child.stdout.on('data', (chunk) => { chunks.push(chunk); });
    child.on('close', (code) => {
      const raw = Buffer.concat(chunks);
      resolve({ code, stdout: raw.toString('utf8'), raw });
    });
  });
}

function ordinaryClients(port, cookies) {
  const bodies = ['', ''];
  const requests = bodies.map((_, index) => http.get({ hostname: '127.0.0.1', port, path: '/api/openchamber/events?browser=1', headers: { Cookie: cookies.map((item) => item.split(';')[0]).join('; ') } }, (response) => {
    response.on('data', (chunk) => { bodies[index] += chunk.toString('utf8'); });
  }));
  return { bodies, stop() { requests.forEach((request) => request.destroy()); } };
}

async function readStatusJson(args, timeoutMs) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const result = await adb(args, ['exec-out', 'run-as', 'io.andchamber.app', 'cat', 'files/openchamber-provider-status.json']);
    if (result.code === 0 && result.stdout.includes('"requestId"')) return JSON.parse(result.stdout);
    if (result.code === 0 && result.stdout.includes('"phase":"iframes:')) return JSON.parse(result.stdout);
    await sleep(300);
  }
  return null;
}

async function readDeviceStatus(args, phase, timeoutMs) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const result = await adb(args, ['exec-out', 'run-as', 'io.andchamber.app', 'cat', 'files/openchamber-provider-status.json']);
    if (result.code === 0 && result.stdout.includes(`"phase":"${phase}"`)) return true;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  return false;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function uiNodes(args) {
  await adb(args, ['shell', 'uiautomator', 'dump', '/data/local/tmp/oc-provider-ui.xml']);
  const dump = await adb(args, ['exec-out', 'cat', '/data/local/tmp/oc-provider-ui.xml']);
  const nodes = [];
  for (const match of dump.stdout.matchAll(/<node [^>]*>/g)) {
    const node = match[0];
    const attr = (name) => (node.match(new RegExp(`${name}="([^"]*)"`)) || [])[1] || '';
    const b = attr('bounds').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (!b) continue;
    const [left, top, right, bottom] = b.slice(1).map(Number);
    nodes.push({ text: attr('text'), desc: attr('content-desc'), cls: attr('class'), left, top, right, bottom, cx: Math.round((left + right) / 2), cy: Math.round((top + bottom) / 2) });
  }
  return nodes;
}

async function tapLabel(args, label) {
  const node = (await uiNodes(args)).find((item) => item.text === label || item.desc === label);
  if (!node) return false;
  await adb(args, ['shell', 'input', 'tap', String(node.cx), String(node.cy)]);
  return true;
}

/** The floating window's page: the WebView that is not full screen. */
async function webViewInWindow(args) {
  const views = (await uiNodes(args)).filter((item) => item.cls.endsWith('WebView'));
  views.sort((a, b) => (a.right - a.left) * (a.bottom - a.top) - (b.right - b.left) * (b.bottom - b.top));
  return views.length > 1 ? views[0] : null;
}

function childPids(parent) {
  return fs.readdirSync('/proc').filter((name) => /^\d+$/.test(name)).filter((pid) => {
    try { return Number((fs.readFileSync(`/proc/${pid}/status`, 'utf8').match(/^PPid:\s+(\d+)/m) || [])[1]) === parent; } catch (_error) { return false; }
  }).map(Number);
}

async function createSession(port, directory) {
  const login = await postJson(`http://127.0.0.1:${port}/auth/session`, {}, JSON.stringify({ password: UI_PASSWORD }));
  const cookie = (login.headers['set-cookie'] || []).map((item) => item.split(';')[0]).join('; ');
  const created = await postJson(`http://127.0.0.1:${port}/api/session?directory=${encodeURIComponent(directory)}`, { Cookie: cookie }, '{}');
  const id = JSON.parse(created.body).data.id;
  if (!/^ses_/.test(id || '')) throw new Error('second session failed');
  return id;
}

function rawRequest(port, method, urlPath, headers, body) {
  return new Promise((resolve) => {
    const request = http.request({ host: '127.0.0.1', port, method, path: urlPath, timeout: 4000, headers: { ...headers, ...(body ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } : {}) } }, (response) => {
      let data = '';
      response.on('data', (chunk) => { data += chunk; if (data.length > 400) response.destroy(); });
      const done = () => resolve({ status: response.statusCode, body: data.slice(0, 160) });
      response.on('end', done);
      response.on('close', done);
    });
    request.on('timeout', () => { request.destroy(); resolve({ status: 'open-stream', body: '' }); });
    request.on('error', (error) => resolve({ status: 'error', body: error.code || '' }));
    if (body) request.write(body);
    request.end();
  });
}

async function forge(port, token) {
  const bad = '0'.repeat(64);
  const header = (value) => ({ 'x-andchamber-browser-token': value });
  const claim = JSON.stringify({ requestId: 'forged', connectionId: 'forged' });
  const resultBody = JSON.stringify({ requestId: 'forged', connectionId: 'forged', claimNonce: 'forged', ok: true, data: {} });
  const cases = [
    ['claim-wrong-token', 'POST', '/api/browser-control/claim', header(bad), claim, (r) => r.status === 401],
    ['claim-no-token', 'POST', '/api/browser-control/claim', {}, claim, (r) => r.status === 401],
    ['claim-unknown-request', 'POST', '/api/browser-control/claim', header(token), claim, (r) => r.status === 200 && /"granted":false/.test(r.body)],
    ['result-unclaimed', 'POST', '/api/browser-control/result', header(token), resultBody, (r) => r.status === 200 && /"matched":false/.test(r.body)],
    ['result-wrong-token', 'POST', '/api/browser-control/result', header(bad), resultBody, (r) => r.status === 401],
    ['sse-with-origin', 'GET', '/api/openchamber/events?provider=android', { ...header(token), origin: 'https://attacker.example' }, null, (r) => r.status === 403],
    ['sse-wrong-token', 'GET', '/api/openchamber/events?provider=android', header(bad), null, (r) => r.status === 401],
    ['sse-second-provider', 'GET', '/api/openchamber/events?provider=android', header(token), null, (r) => r.status === 409],
    ['sse-token-in-query', 'GET', `/api/openchamber/events?provider=android&token=${token}`, {}, null, (r) => r.status === 401],
  ];
  const out = [];
  for (const [name, method, urlPath, headers, body, expect] of cases) {
    const response = await rawRequest(port, method, urlPath, headers, body);
    out.push({ name, pass: expect(response), status: response.status, body: response.body.split(token).join('[redacted]') });
  }
  return out;
}

function exactLabel(text, label) {
  const match = String(text || '').match(new RegExp(`(?:^|[^A-Za-z0-9_-])${label}:(\\d+)(?!\\d)`));
  return match ? Number(match[1]) : null;
}

function result(checks) {
  const failed = checks.filter((item) => item.result !== 'PASS').map((item) => item.name);
  return { result: failed.length === 0 ? 'PASS' : 'FAIL', checks, failed, report: 'real broker and emulator WebView intended; no LLM and no Android PRoot' };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const checks = [];
  const providerToken = crypto.randomBytes(32).toString('hex');
  const secrets = [providerToken, UI_PASSWORD];
  fs.mkdirSync(args.out, { recursive: true, mode: 0o700 });
  const home = fs.mkdtempSync(path.join(args.out, 'home-'));
  const port = 18080 + Math.floor(Math.random() * 1000);
  const child = spawn('setsid', [process.execPath, 'bin/cli.js', 'serve', '--foreground', '--port', String(port)], {
    cwd: args.source,
    env: { HOME: home, PATH: process.env.PATH, XDG_CONFIG_HOME: path.join(home, '.config'), XDG_DATA_HOME: path.join(home, '.local/share'), XDG_STATE_HOME: path.join(home, '.local/state'), OPENCHAMBER_BROWSER_PROVIDER: 'android', ANDCHAMBER_BROWSER_PROVIDER_TOKEN: providerToken, OPENCHAMBER_UI_PASSWORD: UI_PASSWORD, OPENCHAMBER_HOST: '127.0.0.1' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stderr = '';
  let stderrRaw = '';
  let stdout = '';
  child.stderr.on('data', (chunk) => { stderrRaw += chunk; stderr += redact(chunk, secrets); });
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  try {
    if (args.skipBuild !== 'true') {
      const repo = path.resolve(__dirname, '..');
      const build = await new Promise((resolve) => {
        const gradle = spawn('./gradlew', [':app:assembleDebug', ':app:assembleDebugAndroidTest', '-Pandchamber.providerRunner=true', '--offline', '-q'], { cwd: repo, stdio: 'ignore', env: { ...process.env, ANDROID_HOME: process.env.ANDROID_HOME || path.join(process.env.HOME, 'Android/Sdk') } });
        gradle.on('close', resolve);
      });
      if (build !== 0) throw new Error('gradle build failed');
      for (const apk of ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
        const installed = await adb(args, ['install', '-r', '-t', path.join(repo, apk)]);
        if (installed.code !== 0) throw new Error(`install failed: ${apk}`);
      }
    }
    await adb(args, ['shell', 'run-as io.andchamber.app rm -f files/openchamber-provider-status.json files/openchamber-provider-stop']);
    await adb(args, ['logcat', '-c']);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('health timeout')), 90000);
      const poll = () => http.get({ hostname: '127.0.0.1', port, path: '/health', timeout: 2000 }, (response) => {
        let body = '';
        response.on('data', (chunk) => { body += chunk; });
        response.on('end', () => response.statusCode === 200 && /"isOpenCodeReady"\s*:\s*true/.test(body) ? (clearTimeout(timer), resolve()) : setTimeout(poll, 500));
      }).on('error', () => setTimeout(poll, 300));
      poll();
    });
    checks.push({ name: 'health-opencode-ready', result: 'PASS', detail: 'isolated health' });
    const login = await postJson(`http://127.0.0.1:${port}/auth/session`, {}, JSON.stringify({ password: UI_PASSWORD }));
    if (login.status !== 200) throw new Error(`auth/session HTTP ${login.status}`);
    const fixtureDir = path.join(args.out, 'fixture-dir');
    fs.mkdirSync(fixtureDir, { recursive: true });
    const session = await postJson(`http://127.0.0.1:${port}/api/session?directory=${encodeURIComponent(fixtureDir)}`, { Cookie: (login.headers['set-cookie'] || []).join(';'), 'x-opencode-directory': fixtureDir }, '{}');
    const sessionJson = JSON.parse(session.body);
    const sessionID = sessionJson.data && sessionJson.data.id;
    const location = sessionJson.data && sessionJson.data.location;
    if (!/^ses_/.test(sessionID || '')) throw new Error(`session API HTTP ${session.status}`);
    fs.writeFileSync(path.join(args.out, 'session-shape.json'), JSON.stringify({ keys: Object.keys(sessionJson), dataKeys: sessionJson.data ? Object.keys(sessionJson.data) : [], locationKeys: location && typeof location === 'object' ? Object.keys(location) : [], locationPathType: location && typeof location.path }), { mode: 0o600 });
    checks.push({ name: 'real-session', result: 'PASS', detail: `location=${JSON.stringify(location).slice(0, 180)}` });
    await adb(args, ['reverse', '--remove', `tcp:${port}`]);
    const relay = await adb(args, ['shell', `nohup nc -L -s 127.0.0.1 -p ${port} nc 10.0.2.2 ${port} >/dev/null 2>&1 & echo $! > /data/local/tmp/relay${port}.pid`]);
    if (relay.code !== 0) throw new Error('emulator nc relay failed');
    checks.push({ name: 'transport', result: 'PASS', detail: 'emulator nc relay → 10.0.2.2' });
    const instrument = spawn(args.adb, ['-s', args.serial, 'shell', 'am', 'instrument', '-w', '-e', 'port', String(port), '-e', 'token', providerToken, '-e', 'password', UI_PASSWORD, 'io.andchamber.app.test/io.andchamber.app.browser.OpenChamberProviderInstrumentation'], { stdio: 'ignore' });
    instrument.unref();
    const attached = await readDeviceStatus(args, 'attached', 45000);
    checks.push({ name: 'instrumentation-attached', result: attached ? 'PASS' : 'FAIL', detail: attached ? 'status attached' : 'status missing' });
    const agent = findAgentTool(child.pid);
    secrets.push(agent.token);
    let opened = null;
    const openDeadline = Date.now() + 30000;
    while (Date.now() < openDeadline) {
      opened = await tool(agent, sessionID, 'browser.open', { url: FIXTURE }, secrets);
      if (!String(opened.redacted).includes('No Android browser provider')) break;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    checks.push({ name: 'agent-tool-open', result: opened.ok && opened.data && opened.data.tabId && opened.data.drivable === false ? 'PASS' : 'FAIL', detail: opened.redacted });
    await screenshot(args, args.out, 'open.png');
    const unauthorized = await tool(agent, sessionID, 'browser.snapshot', {}, secrets);
    checks.push({ name: 'unauthorized-snapshot', result: !unauthorized.ok && String(unauthorized.redacted).includes('AUTHORIZATION_REQUIRED') ? 'PASS' : 'FAIL', detail: unauthorized.redacted });
    const call = (action, input = {}, sid = sessionID) => tool(agent, sid, action, input, secrets);
    const check = (name, pass, detail) => checks.push({ name, result: pass ? 'PASS' : 'FAIL', detail: redact(detail, secrets).slice(0, 240) });

    // Production "Allow agent" button, found by its label, tapped through real input.
    const allowed = await tapLabel(args, 'Allow agent');
    check('allow-button-tap', allowed, allowed ? 'tapped by label' : 'label not found');
    await sleep(2500);
    const snap = await call('browser.snapshot');
    const elements = (snap.data && snap.data.elements) || [];
    const note = elements.find((item) => item.name === 'note');
    const apply = elements.find((item) => item.name === 'Apply');
    check('snapshot-after-allow', snap.ok && snap.data.drivable === true && note && apply, snap.redacted);
    check('xfo-csp-top-level', snap.ok && /framed:top/.test(snap.data.text || ''), (snap.data && snap.data.text) || snap.redacted);

    const typed = note ? await call('browser.type', { selector: note.selector, value: 'hello-provider' }) : { ok: false, redacted: 'no ref' };
    const clicked = apply ? await call('browser.click', { selector: apply.selector }) : { ok: false, redacted: 'no ref' };
    const after = await call('browser.snapshot');
    const afterNote = ((after.data && after.data.elements) || []).find((item) => item.name === 'note');
    check('type-value', typed.ok && afterNote && afterNote.value === 'hello-provider', typed.redacted);
    check('click-count', clicked.ok && exactLabel(after.data && after.data.text, 'click-count') === 1, clicked.redacted);
    const beforeScroll = exactLabel(after.data && after.data.text, 'scroll-y') || 0;
    const scrolled = await call('browser.scroll', { direction: 'down' });
    const scrollSnap = await call('browser.snapshot');
    const afterScroll = exactLabel(scrollSnap.data && scrollSnap.data.text, 'scroll-y');
    check('scroll', scrolled.ok && afterScroll != null && afterScroll > beforeScroll, scrollSnap.redacted);
    const captured = await call('browser.capture', { label: 'e2e' });
    const capturePath = captured.ok && captured.data.path ? path.join(home, captured.data.path) : '';
    const png = capturePath && fs.existsSync(capturePath) ? fs.readFileSync(capturePath) : null;
    const pngOk = png && png.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) && png.readUInt32BE(16) > 0 && png.readUInt32BE(20) > 0;
    if (pngOk) fs.copyFileSync(capturePath, path.join(args.out, 'agent-capture.png'));
    check('capture-file', pngOk, pngOk ? `png ${png.readUInt32BE(16)}x${png.readUInt32BE(20)}` : captured.redacted);
    await screenshot(args, args.out, 'actions.png');

    // Human touch on the page area takes over synchronously.
    const pageBounds = await webViewInWindow(args);
    if (pageBounds) await adb(args, ['shell', 'input', 'tap', String(pageBounds.cx), String(pageBounds.cy)]);
    await sleep(1500);
    const tookSnap = await call('browser.snapshot');
    const tookClick = apply ? await call('browser.click', { selector: apply.selector }) : { ok: false, redacted: '' };
    check('takeover-revokes', !!pageBounds && !tookSnap.ok && /AUTHORIZATION_REQUIRED/.test(tookSnap.redacted) && !tookClick.ok && /AUTHORIZATION_REQUIRED/.test(tookClick.redacted), `${tookSnap.redacted} | ${tookClick.redacted}`);
    await screenshot(args, args.out, 'takeover.png');
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const latestBeforeTakeover = after;
    const latestRef = ((latestBeforeTakeover.data && latestBeforeTakeover.data.elements) || []).find((item) => item.name === 'Apply');
    const stale = latestRef ? await call('browser.click', { selector: latestRef.selector }) : { ok: true, redacted: 'no latest ref' };
    check('stale-ref-after-regrant', !stale.ok && /REF_EXPIRED/.test(stale.redacted), stale.redacted);
    const fresh = await call('browser.snapshot');
    const freshApply = ((fresh.data && fresh.data.elements) || []).find((item) => item.name === 'Apply');
    const freshClick = freshApply ? await call('browser.click', { selector: freshApply.selector }) : { ok: false, redacted: 'no fresh ref' };
    check('fresh-ref-after-regrant', freshClick.ok, freshClick.redacted);

    // Protocol forgery against the real server.
    const forged = await forge(port, providerToken);
    for (const item of forged) check(`forge:${item.name}`, item.pass, `${item.status} ${item.body}`);

    // Token never reaches the OpenCode child, the server log or logcat.
    const childLeak = childPids(child.pid).some((pid) => { try { return fs.readFileSync(`/proc/${pid}/environ`).includes(providerToken); } catch (_error) { return false; } });
    const logcat = await adb(args, ['logcat', '-d']);
    check('token-absent', !childLeak && !stdout.includes(providerToken) && !stderrRaw.includes(providerToken) && !logcat.stdout.includes(providerToken), `child=${childLeak}`);

    // Second real session: its open must not replace the first session's page, and it cannot read it.
    const second = await createSession(port, fixtureDir);
    const secondSnap = await call('browser.snapshot', {}, second);
    check('second-session-no-read', !secondSnap.ok, secondSnap.redacted);
    const firstTab = opened.data.tabId;
    const secondNamed = await call('browser.snapshot', { tabId: firstTab }, second);
    check('second-session-named-tab-refused', !secondNamed.ok, secondNamed.redacted);
    const firstAgain = await call('browser.snapshot');
    check('first-session-still-authorized', firstAgain.ok, firstAgain.redacted);

    // Human sign-in in Manual mode: real taps submit a POST form; the cookie then survives into Agent mode.
    const loginOpen = await call('browser.open', { url: 'https://example.com/openchamber-provider-login' });
    await sleep(2500);
    await tapLabel(args, 'Take over');
    await sleep(800);
    const form = await webViewInWindow(args);
    let manualCookie = null;
    if (loginOpen.ok && form) {
      // The form has one input at the top and the submit button under it.
      await adb(args, ['shell', 'input', 'tap', String(form.cx), String(form.top + 40)]);
      await adb(args, ['shell', 'input', 'text', 'fixture-user']);
      await adb(args, ['shell', 'input', 'keyevent', 'KEYCODE_ENTER']);
      await sleep(3000);
      await screenshot(args, args.out, 'manual-login.png');
      await tapLabel(args, 'Allow agent');
      await sleep(2500);
      manualCookie = await call('browser.snapshot');
    }
    const manualText = (manualCookie && manualCookie.data && manualCookie.data.text) || '';
    check('manual-post-js-cookie-redirect', !!manualCookie && manualCookie.ok && /oc_fixture_login=signed-in-42/.test(manualText), manualCookie ? (manualText || manualCookie.redacted) : `open=${loginOpen.redacted} form=${!!form}`);

    // Background revokes reads.
    await adb(args, ['shell', 'input', 'keyevent', 'KEYCODE_HOME']);
    await sleep(1500);
    const background = await call('browser.snapshot');
    check('background-blocks', !background.ok, background.redacted);
    await adb(args, ['shell', 'am', 'start', '-n', 'io.andchamber.app/.MainActivity']);
    await sleep(2000);
    const resumed = await call('browser.snapshot');
    check('resume-still-unauthorized', !resumed.ok && /AUTHORIZATION_REQUIRED/.test(resumed.redacted), resumed.redacted);
    const ordinary = await ordinaryClients(port, login.headers['set-cookie'] || []);
    const during = await call('browser.snapshot');
    await sleep(1000);
    ordinary.stop();
    check('ordinary-clients-no-browser-request', ordinary.bodies.every((body) => !body.includes('browser-control-request')) && (during.ok || /AUTHORIZATION_REQUIRED|UNAVAILABLE/.test(during.redacted)), `streams=${ordinary.bodies.length}`);
    await adb(args, ['shell', 'run-as io.andchamber.app sh -c \'printf iframes > files/openchamber-provider-command\'']);
    const iframeStatus = await readStatusJson(args, 10000);
    check('frontend-no-iframe', iframeStatus && iframeStatus.phase === 'iframes:0', JSON.stringify(iframeStatus || {}));
    // The hold scenarios need the interactive fixture again (the sign-in step left the welcome page),
    // a live grant and refs from the current document.
    const reopened = await call('browser.open', { url: FIXTURE });
    check('reopen-fixture-before-hold', reopened.ok, reopened.redacted);
    await sleep(2500);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const holdSnap = await call('browser.snapshot');
    const holdNote = ((holdSnap.data && holdSnap.data.elements) || []).find((item) => item.name === 'note');
    check('regrant-before-hold', holdSnap.ok && !!holdNote, holdSnap.redacted);
    const heldSelector = holdNote ? holdNote.selector : note.selector;
    await adb(args, ['shell', 'run-as io.andchamber.app touch files/openchamber-provider-hold']);
    const held = call('browser.type', { selector: heldSelector, value: 'held-value' });
    const heldStatus = await readStatusJson(args, 15000);
    const wrongClaim = await rawRequest(port, 'POST', '/api/browser-control/claim', { 'x-andchamber-browser-token': providerToken }, JSON.stringify({ requestId: heldStatus && heldStatus.requestId, connectionId: 'wrong-connection' }));
    const wrongNonce = await rawRequest(port, 'POST', '/api/browser-control/result', { 'x-andchamber-browser-token': providerToken }, JSON.stringify({ requestId: heldStatus && heldStatus.requestId, connectionId: heldStatus && heldStatus.connectionId, claimNonce: 'wrong-nonce', ok: true, data: {} }));
    const wrongResultConnection = await rawRequest(port, 'POST', '/api/browser-control/result', { 'x-andchamber-browser-token': providerToken }, JSON.stringify({ requestId: heldStatus && heldStatus.requestId, connectionId: 'wrong-connection', claimNonce: 'wrong-nonce', ok: true, data: {} }));
    check('real-pending-wrong-connection', Boolean(heldStatus && heldStatus.requestId) && /"granted":false/.test(wrongClaim.body) && /"matched":false/.test(wrongNonce.body) && /"matched":false/.test(wrongResultConnection.body), `${wrongClaim.status} ${wrongNonce.status} ${wrongResultConnection.status}`);
    await adb(args, ['shell', 'run-as io.andchamber.app rm -f files/openchamber-provider-hold']);
    const heldResult = await held;
    const heldSnap = await call('browser.snapshot');
    check('held-type-completes', heldResult.ok && ((heldSnap.data && heldSnap.data.elements) || []).some((item) => item.name === 'note' && item.value === 'held-value'), heldResult.redacted);
    await adb(args, ['shell', 'run-as io.andchamber.app touch files/openchamber-provider-hold']);
    const dropped = call('browser.type', { selector: heldSelector, value: 'after-disconnect' }).catch((error) => ({ ok: false, redacted: `transport: ${error.message}` }));
    await readStatusJson(args, 15000);
    // nc -L forks one child per connection; kill the listener and its children so the SSE really drops.
    await adb(args, ['shell', `pid=$(cat /data/local/tmp/relay${port}.pid); for c in $(ps -A -o pid,ppid | awk -v p="$pid" '$2==p{print $1}'); do kill "$c"; done; kill "$pid"`]);
    const droppedResult = await dropped;
    check('claim-disconnect-no-mutation', droppedResult.ok === false, droppedResult.redacted);
    await adb(args, ['shell', 'run-as io.andchamber.app rm -f files/openchamber-provider-hold']);
    await adb(args, ['shell', `nohup nc -L -s 127.0.0.1 -p ${port} nc 10.0.2.2 ${port} >/dev/null 2>&1 & echo $! > /data/local/tmp/relay${port}.pid`]);
    // Wait for the provider to reconnect, then the old grant must be gone.
    let reconnected = null;
    const reconnectDeadline = Date.now() + 30000;
    while (Date.now() < reconnectDeadline) {
      reconnected = await call('browser.snapshot');
      if (!/No Android browser provider/.test(reconnected.redacted)) break;
      await sleep(1000);
    }
    check('reconnect-requires-allow', !reconnected.ok && /AUTHORIZATION_REQUIRED/.test(reconnected.redacted), reconnected.redacted);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const afterReconnect = await call('browser.snapshot');
    const reconnectNote = ((afterReconnect.data && afterReconnect.data.elements) || []).find((item) => item.name === 'note');
    check('disconnect-did-not-type', afterReconnect.ok && !!reconnectNote && reconnectNote.value === 'held-value', afterReconnect.redacted);
    const secondOpen = await call('browser.open', { url: FIXTURE2 }, second);
    const firstStill = await call('browser.snapshot');
    check('second-open-keeps-first', secondOpen.ok && firstStill.ok && /^provider\b/.test((firstStill.data && firstStill.data.text) || ''), `${secondOpen.redacted} | ${firstStill.redacted}`);
    await adb(args, ['shell', 'run-as io.andchamber.app sh -c \'printf %s "select:' + (secondOpen.data && secondOpen.data.tabId) + '" > files/openchamber-provider-command\'']);
    await readDeviceStatus(args, 'selected', 10000);
    const firstAfterSelect = await call('browser.snapshot');
    check('first-fails-after-second-selected', !firstAfterSelect.ok && /AUTHORIZATION_REQUIRED/.test(firstAfterSelect.redacted), firstAfterSelect.redacted);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const secondAfterAllow = await call('browser.snapshot', {}, second);
    check('second-marker', secondAfterAllow.ok && /^second\b/.test((secondAfterAllow.data && secondAfterAllow.data.text) || ''), secondAfterAllow.redacted);
    const firstAfterSecondAllow = await call('browser.snapshot');
    check('first-still-refused-after-second-allow', !firstAfterSecondAllow.ok, firstAfterSecondAllow.redacted);
  } catch (error) {
    checks.push({ name: 'runner', result: 'FAIL', detail: redact(error.message, secrets) });
    fs.writeFileSync(path.join(args.out, 'server-stderr.log'), redact(stderr, secrets), { mode: 0o600 });
  } finally {
    await adb(args, ['shell', 'run-as io.andchamber.app touch files/openchamber-provider-stop']);
    await adb(args, ['shell', 'rm -f /data/local/tmp/oc-provider-ui.xml']);
    await sleep(1500);
    try { process.kill(-child.pid, 'SIGTERM'); } catch (_error) { child.kill('SIGTERM'); }
    await adb(args, ['shell', `pid=$(cat /data/local/tmp/relay${port}.pid 2>/dev/null); if [ -n "$pid" ]; then kill "$pid"; fi; rm -f /data/local/tmp/relay${port}.pid`]);
    fs.rmSync(home, { recursive: true, force: true });
  }
  const body = result(checks);
  fs.writeFileSync(path.join(args.out, 'result.json'), `${JSON.stringify(body, null, 2)}\n`, { mode: 0o600 });
  process.stdout.write(`${JSON.stringify({ result: body.result, failed: body.failed })}\n`);
  process.exitCode = body.result === 'PASS' ? 0 : 1;
}

if (require.main === module) main();
module.exports = { parseArgs, redact, agentToolBody, childEnv, findAgentTool, classify, exactLabel, result, FIXTURE };
