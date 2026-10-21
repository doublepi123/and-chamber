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

const FOCUS_VALUES = ['phase3', 'manual', 'geometry-gate', 'lifecycle-gate', 'retirement-gate'];
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
  if (args.focus && !FOCUS_VALUES.includes(args.focus)) throw new Error('focus must be phase3, manual, geometry-gate, or lifecycle-gate');
  if (args['manual-viewport'] && args['manual-viewport'] !== 'desktop') throw new Error('manual-viewport must be desktop');
  // Optional single-case filter. Only names already in the fixed lifecycle whitelist are accepted.
  if (args['lifecycle-case'] !== undefined && !LIFECYCLE_CASE_NAMES.includes(args['lifecycle-case'])) {
    throw new Error(`lifecycle-case must be one of: ${LIFECYCLE_CASE_NAMES.join(', ')}`);
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

const HOST_RESPONSE_LIMIT = 1024 * 1024;

function hostRequest(url, options = {}) {
  const method = options.method || 'GET';
  let target;
  try { target = new URL(url); } catch (_error) { return Promise.reject(new Error('invalid url')); }
  if (target.protocol !== 'http:') return Promise.reject(new Error('protocol refused'));
  if (options.retry) return Promise.reject(new Error('retry refused'));
  const body = options.body == null ? null : Buffer.from(options.body);
  const timeoutMs = options.timeoutMs || 60000;
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
    const succeed = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    };
    request = http.request({
      hostname: target.hostname,
      port: target.port,
      path: `${target.pathname}${target.search}`,
      method,
      agent: false,
      headers: {
        ...options.headers,
        Connection: 'close',
        ...(body ? { 'Content-Type': options.contentType || 'application/json', 'Content-Length': body.length } : {}),
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
      let aborted = false;
      incoming.on('aborted', () => { aborted = true; fail(new Error('response aborted')); });
      incoming.on('data', (chunk) => {
        size += chunk.length;
        if (size > HOST_RESPONSE_LIMIT) fail(new Error('response too large'));
        else chunks.push(chunk);
      });
      incoming.on('error', (error) => fail(new Error(`response ${error.code || 'error'}`)));
      incoming.on('end', () => {
        const declared = Number(incoming.headers['content-length']);
        if (aborted || !incoming.complete || (Number.isFinite(declared) && size !== declared)) fail(new Error('response aborted'));
        else succeed({ status: incoming.statusCode, body: Buffer.concat(chunks).toString('utf8'), headers: incoming.headers, socket: incoming.socket, reusedSocket: request.reusedSocket === true });
      });
    });
    request.on('error', (error) => fail(new Error(response ? 'response aborted' : `request failed ${error.code || 'error'}`)));
    request.on('timeout', () => fail(new Error('request timed out')));
    if (body) request.end(body);
    else request.end();
  });
}

function postJson(url, headers, body) {
  return hostRequest(url, { method: 'POST', headers, body, timeoutMs: 60000 });
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
  const statuses = [0, 0];
  const requests = bodies.map((_, index) => http.get({ hostname: '127.0.0.1', port, path: '/api/openchamber/events?browser=1', headers: { Cookie: cookies.map((item) => item.split(';')[0]).join('; ') } }, (response) => {
    statuses[index] = response.statusCode;
    response.on('data', (chunk) => { bodies[index] += chunk.toString('utf8'); });
  }));
  return { bodies, statuses, stop() { requests.forEach((request) => request.destroy()); } };
}

async function clearStatus(args) {
  await adb(args, ['shell', 'run-as', 'io.andchamber.app', 'rm', '-f', 'files/openchamber-provider-status.json']);
}

/** Sends one command and waits for that command's own status (stale status is cleared first). */
async function deviceCommand(args, text, phase) {
  await clearStatus(args);
  // Through stdin, so any quotes in the command (scripts, URLs) reach the file untouched.
  await new Promise((resolve) => {
    const child = spawn(args.adb, ['-s', args.serial, 'shell', "run-as io.andchamber.app sh -c 'cat > files/openchamber-provider-command'"], { stdio: ['pipe', 'ignore', 'ignore'] });
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000);
    child.on('close', () => { clearTimeout(timer); resolve(); });
    child.stdin.end(text);
  });
  return readDeviceStatus(args, phase, 10000);
}

/** Reads the status JSON once it carries [phase]; null on timeout. */
async function readStatusJson(args, timeoutMs, phase = null) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const result = await adb(args, ['exec-out', 'run-as', 'io.andchamber.app', 'cat', 'files/openchamber-provider-status.json']);
    if (result.code === 0 && result.stdout.trim()) {
      let parsed = null;
      try { parsed = JSON.parse(result.stdout); } catch (_error) { parsed = null; }
      if (parsed && (phase == null || parsed.phase === phase)) return parsed;
    }
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

/** After resume the provider reconnects; wait until the server reaches it again (max 30 s). */
async function untilProvider(call) {
  let last = null;
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    last = await call('browser.snapshot');
    if (!/No Android browser provider/.test(last.redacted)) return last;
    await sleep(1000);
  }
  return last;
}

/** Number of native tabs as the trusted chat page sees them through the host bridge; -1 if unreadable. */
async function bridgeTabCount(args) {
  await deviceCommand(args, 'bridge', 'bridge');
  const status = await readStatusJson(args, 5000, 'bridge');
  return status && Array.isArray(status.tabs) ? status.tabs.length : -1;
}

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
    nodes.push({ text: attr('text'), desc: attr('content-desc'), cls: attr('class'), enabled: attr('enabled') !== 'false', visible: attr('visible-to-user') !== 'false', left, top, right, bottom, cx: Math.round((left + right) / 2), cy: Math.round((top + bottom) / 2) });
  }
  return nodes;
}

/** Opens the phone session list: dismiss the OpenCode update banner if it covers the header, retry. */
const sheetTrace = [];

async function openSessionSheet(args) {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    // The isolated OpenCode is older than the published one, so OpenChamber shows an update toast
    // that can cover the header menu button. Dismiss it in the page, like tapping its Dismiss button.
    await deviceCommand(args, `eval:(function(){var n=0;document.querySelectorAll('li.toast button,[data-sonner-toast] button').forEach(function(b){if(/dismiss/i.test((b.innerText||b.getAttribute('aria-label')||'').trim())){b.click();n++;}});return n;})()`, 'eval');
    await sleep(800);
    // Real touch at the button's on-screen centre, mapped from the page's own layout.
    const dismissed = await readStatusJson(args, 3000, 'eval');
    const sent = await deviceCommand(args, 'tap:[aria-label="Open sessions and projects"]', 'tapped');
    const tapped = sent ? await readStatusJson(args, 3000, 'tapped') : await readStatusJson(args, 1000);
    await sleep(2000);
    const nodes = await uiNodes(args);
    sheetTrace.push({ attempt, dismissed: dismissed && dismissed.value, sent, tapped, labels: nodes.filter((n) => n.text || n.desc).map((n) => n.text || n.desc).slice(0, 25) });
    if (!tapped || !tapped.hit) continue;
    if (nodes.some((item) => item.text === 'New chat' || item.desc === 'New chat')) return true;
  }
  return false;
}

async function tapLabelPrefix(args, prefix) {
  const node = (await uiNodes(args)).find((item) => item.text.startsWith(prefix) || item.desc.startsWith(prefix));
  if (!node) return false;
  await adb(args, ['shell', 'input', 'tap', String(node.cx), String(node.cy)]);
  return true;
}

const MENU_LABELS = {
  More: ['More', '更多'],
  'Computer layout': ['Computer layout', '电脑版'],
  Confirm: ['Confirm', '确认'],
  Mouse: ['Mouse', '鼠标'],
};

function tappable(node, screen = null) {
  if (!node || node.enabled === false || node.visible === false) return false;
  if (node.right <= node.left || node.bottom <= node.top) return false;
  if (screen && (node.left < 0 || node.top < 0 || node.right > screen.right || node.bottom > screen.bottom)) return false;
  return true;
}

function decodeEval(status) {
  if (!status || status.phase !== 'eval') return null;
  try {
    const first = JSON.parse(status.value);
    return typeof first === 'string' ? JSON.parse(first) : first;
  } catch (_error) {
    return null;
  }
}

async function readLayoutSample(args, io = {}) {
  const command = io.command || ((text, phase) => deviceCommand(args, text, phase));
  const read = io.read || ((phase) => readStatusJson(args, 5000, phase));
  const nodes = io.nodes || (() => uiNodes(args));
  if (!await command('native-state', 'native-state')) return null;
  const native = await read('native-state');
  if (!native) return null;
  if (!await command('composer-layout', 'composer-layout')) return null;
  const layout = await read('composer-layout');
  if (!await command('eval:JSON.stringify({ready:document.readyState,timeOrigin:performance.timeOrigin})', 'eval')) return null;
  const evaluated = await read('eval');
  return { orientation: native.orientation, native, layout, console: decodeEval(evaluated), nodes: await nodes() };
}

function createManualAdapter(args, io = {}) {
  const adbCall = io.adb || ((command) => adb(args, command));
  const nodes = io.nodes || (() => uiNodes(args));
  const evalPage = io.pageEval || ((script) => pageEval(args, script));
  const nativeRead = io.native || (async () => (await deviceCommand(args, 'native-state', 'native-state') ? await readStatusJson(args, 5000, 'native-state') : null));
  let expandedByProbe = false;
  const tapsTrace = [];
  return {
    read: io.read || (async () => (await deviceCommand(args, 'fixture-state', 'fixture-state') ? await readStatusJson(args, 5000, 'fixture-state') : null)),
    readPosts: io.readPosts || (async () => evalPage('JSON.stringify(Number(sessionStorage.getItem("posts")||0))')),
    ensureExpanded: async () => {
      const current = await nodes();
      const already = current.some((item) => item.text === 'Restore' || item.desc === 'Restore');
      if (!already) {
        const enlarged = await tapLabel(args, 'Enlarge', io);
        if (enlarged) tapsTrace.push('Enlarge');
        expandedByProbe = enlarged === true;
        if (!enlarged) return false;
      }
      const deadline = Date.now() + 5000;
      while (Date.now() <= deadline) {
        const visible = await nodes();
        if (visible.some((item) => item.text === 'Restore' || item.desc === 'Restore')) {
          const stable = io.stable ? await io.stable() : await stablePage(args);
          if (stable.ok) return true;
        }
        await sleep(200);
      }
      return false;
    },
    tap: io.tap || (async (label) => tapLabelStatus(args, label)),
    type: async (value) => {
      const parts = value.split(/(?=-)|(?<=-)/);
      if (parts.length !== 3 || parts.join('') !== value) return false;
      const codes = [];
      for (const word of parts) {
        const typed = await adbCall(['shell', 'input', 'text', word]);
        codes.push(typed && typed.code);
        if (!typed || typed.code !== 0) return { ok: false, codes };
        const seen = io.read
          ? true
          : await waitInputValue(args, (page) => typeof page?.inputValue === 'string' && page.inputValue.endsWith(word));
        if (!seen) return { ok: false, codes, partial: true };
      }
      return true;
    },
    restoreIfExpandedByProbe: async () => {
      if (!expandedByProbe) return true;
      const restored = await tapLabel(args, 'Restore', io);
      if (restored) tapsTrace.push('Restore');
      if (!restored) return false;
      const deadline = Date.now() + 5000;
      while (Date.now() <= deadline) {
        const current = await nodes();
        if (current.some((item) => item.text === 'Enlarge' || item.desc === 'Enlarge')) return true;
        await sleep(200);
      }
      return false;
    },
    pageIdentity: async () => {
      const state = await (io.read || (async () => (await deviceCommand(args, 'fixture-state', 'fixture-state') ? await readStatusJson(args, 5000, 'fixture-state') : null)))();
      return state && state.native && state.native.selectedTabId && state.native.documentEpoch != null ? state.native : null;
    },
    prepareSubmit: io.prepareSubmit || (async () => dismissIme(args)),
    userAgent: async () => evalPage('JSON.stringify(navigator.userAgent.indexOf("Mobile")>=0?"mobile":"desktop")'),
    save: io.save || (async (label, evidence) => fs.writeFileSync(path.join(args.out, `manual-${label}.json`), `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 })),
    now: io.now || Date.now,
    sleep: io.sleep || sleep,
  };
}

async function humanSubmit(args, context, io = {}) {
  const probe = require('./lib/provider_manual_probe.cjs');
  const adapter = createManualAdapter(args, io);
  const submitted = await probe.runManualSubmit(adapter, context);
  if (!submitted.ok) return submitted;
  const before = await adapter.pageIdentity();
  const ua = await adapter.userAgent();
  const after = await adapter.pageIdentity();
  const same = before && after && before.viewHandle === after.viewHandle && before.documentEpoch === after.documentEpoch &&
    before.selectedTabId === after.selectedTabId && before.owner === after.owner &&
    before.selectedTabId === context.tabId && before.owner === context.ownerSessionId;
  if (!same) {
    return { ...submitted, ok: false, reason: 'identity-changed', landing: { ...submitted.landing, ua } };
  }
  return { ...submitted, landing: { ...submitted.landing, ua, documentEpoch: after.documentEpoch, selectedTabId: after.selectedTabId } };
}

async function dismissIme(args) {
  const dump = await adb(args, ['shell', 'dumpsys', 'input_method']);
  const shown = /mInputShown=true/.test(dump.stdout || '');
  const hidden = /mInputShown=false/.test(dump.stdout || '');
  if (!shown && !hidden) return { ok: false, reason: 'ime-unreadable', snippet: String(dump.stdout || '').split('\n').filter((line) => /mInputShown|mImeWindowVis/.test(line)).slice(0, 8) };
  if (!shown) return true;
  const before = await readStatusJson(args, 1000, 'fixture-state');
  const back = await adb(args, ['shell', 'input', 'keyevent', '4']);
  if (!back || back.code !== 0) return { ok: false, reason: 'back-failed', code: back && back.code };
  const deadline = Date.now() + 3000;
  while (Date.now() <= deadline) {
    const again = await adb(args, ['shell', 'dumpsys', 'input_method']);
    const stable = await stablePage(args);
    const state = await deviceCommand(args, 'fixture-state', 'fixture-state') ? await readStatusJson(args, 1000, 'fixture-state') : null;
    const same = before && state && before.native && state.native && before.native.selectedTabId === state.native.selectedTabId && before.page && state.page && state.page.inputValue === 'fixture-user' && state.page.loads === before.page.loads;
    if (/mInputShown=false/.test(again.stdout || '') && stable.ok && same) return true;
    await sleep(200);
  }
  return { ok: false, reason: 'ime-hide-timeout' };
}

async function tapLabelStatus(args, label) {
  if (label === 'Sign in' || label === 'login-user') {
    const stable = await stablePage(args);
    if (!stable.ok) return { phase: 'tap-missing', hit: false, sent: false, reason: `${label}-not-stable`, rects: stable.rects };
  }
  await clearStatus(args);
  await new Promise((resolve) => {
    const child = spawn(args.adb, ['-s', args.serial, 'shell', "run-as io.andchamber.app sh -c 'cat > files/openchamber-provider-command'"], { stdio: ['pipe', 'ignore', 'ignore'] });
    const timer = setTimeout(() => child.kill('SIGKILL'), 15000);
    child.on('close', () => { clearTimeout(timer); resolve(); });
    child.stdin.end(`tap-label:${label}`);
  });
  const started = Date.now();
  while (Date.now() - started < 12000) {
    const status = await readStatusJson(args, 500, null);
    if (status && (status.phase === 'tapped' || status.phase === 'tap-missing')) return status;
    await sleep(200);
  }
  return { phase: 'tap-missing', hit: false, sent: false, reason: 'status-timeout' };
}

async function waitInputValue(args, predicate) {
  const deadline = Date.now() + 1500;
  while (Date.now() <= deadline) {
    const status = await deviceCommand(args, 'fixture-state', 'fixture-state') ? await readStatusJson(args, 1000, 'fixture-state') : null;
    if (status && predicate(status.page)) return true;
    await sleep(100);
  }
  return false;
}

async function stablePage(args) {
  const first = await pageRect(args);
  await sleep(200);
  const second = await pageRect(args);
  const ready = (rect) => rect && rect.w > 0 && rect.h > 0 && rect.pageReady !== false;
  const same = ready(first) && ready(second) && first.w === second.w && first.h === second.h && first.x === second.x && first.y === second.y;
  return { ok: same, rects: [first, second] };
}

async function tapLabel(args, label, io = {}) {
  const labels = MENU_LABELS[label] || [label];
  const nodes = await (io.nodes || (() => uiNodes(args)))();
  const screen = nodes.reduce((max, item) => ({ right: Math.max(max.right, item.right), bottom: Math.max(max.bottom, item.bottom) }), { right: 0, bottom: 0 });
  const node = nodes.find((item) => tappable(item, screen) && (labels.includes(item.text) || labels.includes(item.desc)));
  if (!node) return false;
  const tapped = await (io.adb || ((command) => adb(args, command)))(['shell', 'input', 'tap', String(node.cx), String(node.cy)]);
  return Boolean(tapped && tapped.code === 0);
}

/** The floating window's page: the WebView that is not full screen. */
async function currentPagePoint(args) {
  const native = await deviceCommand(args, 'native-state', 'native-state') ? await readStatusJson(args, 5000, 'native-state') : null;
  const rect = await pageRect(args);
  if (!native || native.shown !== true || native.pageReady !== true || !rect || !(rect.w > 0) || !(rect.h > 0)) return null;
  return { x: rect.x + Math.floor(rect.w / 2), y: rect.y + Math.floor(rect.h / 2), rect, native };
}

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

async function createSession(port, directory, title = null) {
  const login = await postJson(`http://127.0.0.1:${port}/auth/session`, {}, JSON.stringify({ password: UI_PASSWORD }));
  const cookie = (login.headers['set-cookie'] || []).map((item) => item.split(';')[0]).join('; ');
  const created = await postJson(`http://127.0.0.1:${port}/api/session?directory=${encodeURIComponent(directory)}`, { Cookie: cookie }, JSON.stringify(title ? { title } : {}));
  const id = JSON.parse(created.body).data.id;
  if (!/^ses_/.test(id || '')) throw new Error('second session failed');
  return id;
}

async function registerProject(port, directory) {
  if (!directory) return false;
  const login = await postJson(`http://127.0.0.1:${port}/auth/session`, {}, JSON.stringify({ password: UI_PASSWORD }));
  const cookie = (login.headers['set-cookie'] || []).map((item) => item.split(';')[0]).join('; ');
  const id = `path_${Buffer.from(directory).toString('base64url')}`;
  const now = Date.now();
  const body = JSON.stringify({ projects: [{ id, path: directory, addedAt: now, lastOpenedAt: now }], activeProjectId: id, lastDirectory: directory });
  const saved = await hostRequest(`http://127.0.0.1:${port}/api/config/settings`, { method: 'PUT', headers: { Cookie: cookie }, body, timeoutMs: 10000 }).then((response) => response.status === 200).catch(() => false);
  return saved;
}

/** Value of a page-eval command, decoded from evaluateJavascript's JSON string literal. */
async function pageEval(args, script) {
  await deviceCommand(args, `page-eval:${script}`, 'page-eval');
  const status = await readStatusJson(args, 8000, 'page-eval');
  if (!status) return null;
  try {
    const first = JSON.parse(status.value);
    return typeof first === 'string' ? JSON.parse(first) : first;
  } catch (_error) {
    return null;
  }
}

async function pageRect(args) {
  await deviceCommand(args, 'page-rect', 'page-rect');
  return readStatusJson(args, 5000, 'page-rect');
}

/**
 * Phase 3 on the production floating window: it sits above the chat composer, Computer layout
 * gives a 1024 CSS px desktop page with a desktop UA (one GET reload, grant revoked), and Mouse
 * turns real finger gestures into real mouse events the page receives (relative move, click,
 * drag with the button held, wheel). Enlarge and restore keep the page and the mode.
 */
async function collectFocusFailure(args, failure, secrets = []) {
  const stamp = Date.now();
  await screenshot(args, args.out, `focus-failure-${stamp}.png`);
  const dump = await adb(args, ['exec-out', 'uiautomator', 'dump', '/dev/tty']);
  const xml = redact(dump.stdout || '', secrets);
  fs.writeFileSync(path.join(args.out, `focus-failure-${stamp}.xml`), xml, { mode: 0o600 });
  const labels = [];
  for (const match of xml.matchAll(/<node [^>]*>/g)) {
    const text = (match[0].match(/text="([^"]*)"/) || [])[1] || '';
    const desc = (match[0].match(/content-desc="([^"]*)"/) || [])[1] || '';
    const bounds = (match[0].match(/bounds="([^"]*)"/) || [])[1] || '';
    if (text || desc) labels.push({ text, desc, bounds });
  }
  const foreground = await adb(args, ['shell', 'dumpsys', 'activity', 'activities']);
  const packageName = ((foreground.stdout.match(/mResumedActivity:.*? ([A-Za-z0-9_.]+)\//) || [])[1]) || '';
  const native = await deviceCommand(args, 'native-state', 'native-state') ? await readStatusJson(args, 5000, 'native-state') : null;
  const screen = labels.reduce((max, item) => { const b = item.bounds.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/); return b ? { right: Math.max(max.right, Number(b[3])), bottom: Math.max(max.bottom, Number(b[4])) } : max; }, { right: 0, bottom: 0 });
  const candidates = labels.filter((item) => { const b = item.bounds.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/); return b && Number(b[3]) > Number(b[1]) && Number(b[4]) > Number(b[2]) && Number(b[1]) >= 0 && Number(b[2]) >= 0 && Number(b[3]) <= screen.right && Number(b[4]) <= screen.bottom; });
  const body = { ...failure, foreground: packageName, labels: labels.slice(0, 80), candidates, native, tight: labels.some((item) => /keyboard covers the browser|键盘/.test(`${item.text} ${item.desc}`)), overlay: labels.some((item) => /permission|system update|权限|系统更新/.test(`${item.text} ${item.desc}`)) };
  fs.writeFileSync(path.join(args.out, `focus-failure-${stamp}.json`), `${JSON.stringify(body, null, 2)}\n`, { mode: 0o600 });
  return body;
}

async function waitForLabel(args, label, deadline) {
  let attempts = 0;
  const labelDeadline = Math.min(deadline, Date.now() + 25000);
  while (Date.now() < labelDeadline) {
    attempts += 1;
    if (await tapLabel(args, label)) return { found: true, attempts, reason: '' };
    await sleep(400);
  }
  const nodes = await uiNodes(args);
  const tight = nodes.some((item) => /keyboard covers the browser/i.test(`${item.text} ${item.desc}`));
  const overlay = nodes.some((item) => /permission|system update/i.test(`${item.text} ${item.desc}`));
  return { found: false, attempts, reason: tight ? 'tight-warning' : overlay ? 'system-overlay' : 'label-missing', failedLabel: label };
}

async function focusMenu(deps, deadline) {
  const trace = [];
  const tap = async (label) => {
    const found = await deps.waitForLabel(label, deadline);
    if (found && found.found) trace.push(label);
    else trace.push(found);
    return Boolean(found && found.found);
  };
  if (!await tap('More')) return { ok: false, trace, reason: 'More missing' };
  if (!await tap('Computer layout')) return { ok: false, trace, reason: 'Computer layout missing' };
  if (!await tap('Confirm')) return { ok: false, trace, reason: 'Confirm missing' };
  if (!await tap('More')) return { ok: false, trace, reason: 'More missing before Mouse' };
  if (!await tap('Mouse')) return { ok: false, trace, reason: 'Mouse missing' };
  return { ok: trace.filter((label) => label === 'Mouse').length === 1, trace, reason: 'Mouse opened once' };
}

async function focusManual(args, checks, secrets, agent, sessionID) {
  const call = (action, input = {}) => tool(agent, sessionID, action, input, secrets);
  const check = (name, pass, detail) => checks.push({ name, result: pass ? 'PASS' : 'FAIL', detail: redact(detail, secrets).slice(0, 240) });
  const opened = await call('browser.open', { url: FIXTURE });
  if (!opened.ok) return check('manual-focus-open', false, opened.redacted);
  await tapLabel(args, 'Allow agent');
  const allowed = await call('browser.snapshot');
  check('manual-focus-allowed', allowed.ok, allowed.redacted);
  const point = await currentPagePoint(args);
  if (!point) return check('manual-focus-takeover', false, 'page rect not ready');
  await adb(args, ['shell', 'input', 'tap', String(point.x), String(point.y)]);
  const revoked = await call('browser.snapshot');
  const native = await deviceCommand(args, 'native-state', 'native-state') ? await readStatusJson(args, 5000, 'native-state') : null;
  check('manual-focus-takeover', !revoked.ok && /AUTHORIZATION_REQUIRED/.test(revoked.redacted) && native && native.agentOn !== true, revoked.redacted);
  const login = await call('browser.open', args['manual-viewport'] === 'desktop'
    ? { url: 'https://example.com/openchamber-provider-login', viewport: 'desktop' }
    : { url: 'https://example.com/openchamber-provider-login' });
  const submitted = login.ok ? await humanSubmit(args, { tabId: login.data && login.data.tabId, ownerSessionId: sessionID, loginOpenOk: login.ok }) : { ok: false, reason: login.redacted };
  if (!submitted.ok) return check('manual-focus-submit', false, submitted.reason);
  await tapLabel(args, 'Allow agent');
  const landed = await call('browser.snapshot');
  check('manual-focus-cookie', landed.ok && /oc_fixture_login=signed-in-42/.test((landed.data && landed.data.text) || '') && /posts:1/.test((landed.data && landed.data.text) || ''), landed.redacted);
}

/**
 * Device adapter for the geometry gate. Every device IO call is injected through `io`, so the Node tests
 * run this same code against a simulated device. Tool replies are awaited and normalised here: `ok` is a
 * real boolean, `code` is the real error code (or the code found in the reply text), and raw text is kept.
 */
const geometryProbe = require('./lib/provider_geometry_gate_probe.cjs');

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
    allow: () => mut(async () => {
      // A tap is not a grant: after the tap, read-only poll the registry grant (bounded).
      const tapped = (await io.tapAllow()) === true;
      const started = io.now();
      let state = null;
      let grantPresent = false;
      while (io.now() - started < 3000) {
        state = await diag();
        grantPresent = Boolean(state && state.registryGrantPresent === true);
        if (grantPresent) break;
        await io.sleepMs(200);
      }
      return {
        ok: tapped && grantPresent,
        tapped,
        grantPresent,
        detail: !tapped ? 'allow-missed' : grantPresent ? 'granted' : 'tapped-without-grant',
      };
    }, { ok: false, tapped: false, grantPresent: false, detail: FENCED.code }),
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
 * the JS metrics. Enlarged comes from the live UI (the Restore button is shown only while enlarged).
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
    // Enlarged is read from the live UI: the Restore button is visible only while the window is enlarged.
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
    tapEnlarge: () => mut(async () => ({ ok: (await io.tapLabelRaw('Enlarge')) === true }), { ok: false, code: FENCED.code }),
    tapRestore: () => mut(async () => ({ ok: (await io.tapLabelRaw('Restore')) === true }), { ok: false, code: FENCED.code }),
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
      frame?.viewHandle === measuredSource.viewHandle && JSON.stringify(frame.physical) === JSON.stringify(measuredSource.frame) &&
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
          !p || p.geometryReady !== true || p.viewHandle !== rect.viewHandle || !inside({ x:p.pointerViewX, y:p.pointerViewY }, f)) return null;
      const s = f.width / v.width;
      const dom = await transport.dom(id, { x:v.offsetLeft + p.pointerViewX / s, y:v.offsetTop + p.pointerViewY / s });
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
      const target = cssClientToScreen(0, 0, f.width, v.width, (left+right)/2, (top+bottom)/2, v.offsetLeft, v.offsetTop);
      if (!inside(target, f)) return null;
      return { target, frame:f, pointer:{x:p.pointerViewX,y:p.pointerViewY}, hit:dom.hit,
        viewHandle:rect.viewHandle, origin:dom.timeOrigin, record, page:dom.page };
    },
    atomicFingerRaw: async (spec, canSend = () => true) => {
      const identity = await transport.identity();
      const record = identity && currentRecordOf(identity);
      const rect = await transport.frame(), f = rect?.physical;
      if (!f || rect.viewHandle !== spec.viewHandle || !isNum(spec.dx) || !isNum(spec.dy) ||
          identity?.currentIdentity !== spec.viewHandle || !record || String(record.origin) !== String(spec.origin) ||
          JSON.stringify(f) !== JSON.stringify(spec.frame) || !Number.isInteger(spec.steps) || spec.steps < 1 || spec.steps > 4 ||
          !inside(spec, f) || !inside({x:spec.x+spec.dx,y:spec.y+spec.dy}, f)) return false;
      // The ONLY screen conversion. gate-finger/native use local coordinates unchanged.
      if (!canSend()) return false;
      return transport.atomic({ ...spec, x:Math.round(f.left+spec.x), y:Math.round(f.top+spec.y) });
    },
  };
}

/**
 * Focused B4 gate (retirement-gate). Each case gets a fresh RETIRE page, a real Mouse enable through the
 * production menu, and a fresh fenced adapter. A timed-out case fences itself and stops the focus.
 * phaseComplete is never true; this is two targeted cases, not the whole B4 device gate.
 */
async function focusRetirementGate(args, checks, secrets, agent, sessionID, home, options = {}) {
  const check = (name, flow) => checks.push({ name, result: flow && flow.ok ? 'PASS' : 'FAIL', detail: redact(JSON.stringify(flow), secrets).slice(0, 240) });
  const save = (name, flow) => fs.writeFileSync(path.join(args.out, name), `${JSON.stringify(flow, null, 2)}\n`, { mode: 0o600 });
  const call = (action, input = {}) => tool(agent, sessionID, action, input, secrets);
  const RETIRE_URL = 'https://example.com/openchamber-provider-retire';
  const readLifecycleRawOnce = async () => (await deviceCommand(args, 'lifecycle-diag', 'lifecycle-diag') ? readStatusJson(args, 5000, 'lifecycle-diag') : null);
  const io = {
    now: Date.now,
    sleepMs: sleep,
    readLifecycleRaw: async () => readLifecycleRawOnce(),
    readPageRaw: async () => {
      const report = await pageEval(args, 'JSON.stringify(Object.assign(JSON.parse(window.__report()),{nextClicks:window.__clicks.filter(x=>x==="next").length,boxClicks:window.__clicks.filter(x=>x==="box").length}))');
      return report && typeof report === 'object' ? report : null;
    },
    // Current view comes from native currentIdentity, not from the placeholder surface, which stays on screen.
    readNativeViewRaw: async () => {
      const diag = await readLifecycleRawOnce();
      if (!diag) return null;
      return { currentViewPresent: diag.currentIdentity != null };
    },
    fingerRaw: async (spec) => {
      const map = { down: 'down', move: 'move', 'primary-up': 'primary-up', 'secondary-down': 'secondary-down', 'secondary-up': 'secondary-up', up: 'up', cancel: 'cancel' };
      const action = map[spec.action];
      if (!action || !isNum(spec.x) || !isNum(spec.y)) return false;
      const reply = await deviceCommand(args, `gate-finger:${action},${Math.round(spec.x)},${Math.round(spec.y)}`, 'gate-finger');
      return reply === true;
    },
    // Button-free native mouse event (enter/hover/exit/wheel). Diagnostic delivery only; no press, release or grant.
    nativeGateRaw: async (spec, canSend = () => true) => {
      const before = await readLifecycleRawOnce();
      const origin = before && currentRecordOf(before);
      const parts = spec.split(','), point={x:Number(parts[1]),y:Number(parts[2])};
      const rect = await pageRect(args), f=rect?.physical;
      if (!origin || before.currentIdentity !== rect?.viewHandle || !f ||
          !['enter','hover','exit','wheel'].includes(parts[0]) || !isNum(point.x) || !isNum(point.y) ||
          point.x < 0 || point.y < 0 || point.x >= f.width || point.y >= f.height) return null;
      if (!canSend()) return null;
      const reply = await deviceCommand(args, `gate-native:${spec}`, 'gate-native');
      const status = reply === true ? await readStatusJson(args, 5000, 'gate-native') : null;
      const after = await readLifecycleRawOnce(), record = after && currentRecordOf(after);
      return status?.sent === true && status.buttons === 0 && after?.currentIdentity === before.currentIdentity &&
        record && record.viewId === origin.viewId && String(record.origin) === String(origin.origin)
        ? { sent: true, dispatched: status.dispatched } : null;
    },
    agentSnapshotRaw: async () => gateSnapshotResult(await call('browser.snapshot')),
    allowAttemptRaw: async () => {
      const tapped = await tapLabel(args, 'Allow agent');
      const diag = await readLifecycleRawOnce();
      return { tapped: tapped === true, granted: diag ? diag.grantForSelected === true : null };
    },
    // A string result must be JSON-encoded in the page, or pageEval's decode returns null.
    readCookieRaw: async () => {
      const value = await pageEval(args, 'JSON.stringify(document.cookie)');
      return typeof value === 'string' ? value : null;
    },
    // Writes this case's unique test cookie on the current (old) page only. Example.com test cookie, no profile reset.
    writeCookieRaw: async (value) => {
      await pageEval(args, `document.cookie=${JSON.stringify(`oc_fixture_retire=${value}; path=/; max-age=3600`)}`);
      const back = await pageEval(args, 'JSON.stringify(document.cookie)');
      return typeof back === 'string' && back.includes(`oc_fixture_retire=${value}`);
    },
    tapLabelRaw: (label) => tapLabel(args, label),
    enableMouseRaw: (canSend = () => true) => switchMouseOn(args, {
      readMouse: () => readNativeMouseMode(args),
      tap: (label) => canSend() ? tapLabel(args, label) : false,
      wait: sleep, now: Date.now,
    }),
  };
  Object.assign(io, createRetirementPointerIO({
    identity: readLifecycleRawOnce,
    frame: () => pageRect(args),
    dom: (id, pointer) => pageEval(args, `JSON.stringify((function(){var e=document.getElementById(${JSON.stringify(id)});if(!e)return null;var r=e.getBoundingClientRect();var h=document.elementFromPoint(${JSON.stringify(pointer.x)},${JSON.stringify(pointer.y)});return {timeOrigin:performance.timeOrigin,box:{left:r.left,top:r.top,right:r.right,bottom:r.bottom},hit:!!h&&(h===e||e.contains(h)),page:JSON.parse(window.__report())};})())`),
    atomic: (p) => deviceCommand(args, `finger:${p.x},${p.y},${Math.round(p.dx)},${Math.round(p.dy)},0,${p.steps}`, 'fingered'),
  }));
  const check0 = await io.readLifecycleRaw();
  if (!check0) {
    const flow = { ok: false, reason: 'lifecycle-diag-missing', detail: 'lifecycle-diag returned no phase; refusing, not passing' };
    save('retirement-precondition.json', flow);
    check('retirement-precondition', flow);
    return;
  }
  for (const item of RETIREMENT_CASES) {
    const fence = createCaseFence();
    const adapters = createRetirementGateAdapters(io, fence);
    // Fresh page, real Mouse enable through the production menu. A failed setup refuses the case, runs nothing.
    const opened = await call('browser.open', { url: RETIRE_URL, viewport: 'desktop' });
    if (!opened.ok) {
      const setup = { ok: false, reason: 'open-failed', opened: opened.redacted };
      save(`${item.name}-prepare.json`, setup);
      check(`${item.name}-prepare`, setup);
      break;
    }
    const tapped = await tapLabel(args, 'Allow agent');
    const mouse = tapped ? await switchMouseOn(args) : false;
    if (!mouse) {
      const setup = { ok: false, reason: 'mouse-enable-failed', tapped };
      save(`${item.name}-prepare.json`, setup);
      check(`${item.name}-prepare`, setup);
      break;
    }
    // The unique cookie value for this case, written only on this old page, before any press.
    const token = crypto.randomBytes(8).toString('hex');
    const cookieSet = await adapters.writeTestCookie(token);
    if (!cookieSet.ok) {
      const setup = { ok: false, reason: 'test-cookie-not-written', cookieSet };
      save(`${item.name}-prepare.json`, setup);
      check(`${item.name}-prepare`, setup);
      break;
    }
    const deps = { ...adapters, now: io.now, sleep: io.sleepMs };
    const probe = require('./lib/provider_retirement_gate_probe.cjs');
    const flow = await runOneRetirementCase(item, { deps, fence, probe, probeDeadlineMs: options.probeDeadlineMs || 90000, save, now: io.now });
    save(`${item.name}.json`, flow);
    check(item.name, flow);
    if (!flow.ok || flow.terminal === 'client-timeout-no-retry') break;
  }
  // The two targeted cases never claim a phase or overall pass.
}

/** Production Mouse on: More -> Mouse, once. Only the menu label is tapped; no mode or grant is written. */
/**
 * The one shared primitive that makes native Mouse mode on. Idempotent:
 *  - reads the real native mouseMode first. When it is already true, it returns true and taps nothing. The Window
 *    menu then offers only "Touch" (the "Mouse" item does not exist), so a tap here would fail.
 *  - when it is false, it taps More -> Mouse exactly once, then reads native mouseMode back with a bounded wait.
 *  - an unreadable native state returns false. Nothing is assumed, and nothing is tapped blind.
 * A tap is never the proof: only the native read afterwards counts.
 */
async function switchMouseOn(args, io = null) {
  // The device primitives are injectable for tests; production always uses the real adb-backed ones.
  const read = io ? io.readMouse : () => readNativeMouseMode(args);
  const tap = io ? io.tap : (label) => tapLabel(args, label);
  const wait = io ? io.wait : sleep;
  const now = io ? io.now : Date.now;
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

/** Native mouseMode from lifecycle-diag: true or false, or null when unreadable. Never defaults. */
async function readNativeMouseMode(args) {
  const diag = (await deviceCommand(args, 'lifecycle-diag', 'lifecycle-diag')) ? await readStatusJson(args, 5000, 'lifecycle-diag') : null;
  return diag && typeof diag.mouseMode === 'boolean' ? diag.mouseMode : null;
}

/** Normalises a snapshot reply to the code the probe checks. A transport error is not an authorization result. */
function gateSnapshotResult(raw) {
  return gateResult(raw);
}

/** One retirement case under its own fence. A timeout fences the case, stops it, and never starts the next. */
async function runOneRetirementCase(item, { deps, fence, probe, probeDeadlineMs, save, now }) {
  const probeRun = Promise.resolve().then(() => probe[item.flow](deps));
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
  if (outcome !== TIMED_OUT) return outcome.flow;
  fence.stop();
  const settled = await Promise.race([
    probeRun.then((late) => ({ late }), () => ({ late: { ok: false, reason: 'probe-threw-late' } })),
    new Promise((resolve) => setTimeout(() => resolve(TIMED_OUT), SETTLE_GRACE_MS)),
  ]);
  if (settled === TIMED_OUT) {
    save(`${item.name}-pending-failed.json`, { ok: false, reason: 'probe-unsettled-after-fence', terminal: 'pending-failed', blockedAfterFence: fence.state.blocked, mutationsSent: fence.state.mutations, now: now() });
    throw new GeometryGateAbort(item.name, 'retirement probe did not settle after fence');
  }
  return { ok: false, reason: 'probe-deadline', terminal: 'client-timeout-no-retry', deadlineMs: probeDeadlineMs, mutationsSent: fence.state.mutations, blockedAfterFence: fence.state.blocked };
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

function copyPng(from, to) {
  const fs = require('node:fs');
  if (!fs.existsSync(from)) return false;
  const bytes = fs.readFileSync(from);
  if (!bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return false;
  fs.writeFileSync(to, bytes, { mode: 0o600 });
  return true;
}

async function focusGeometryGate(args, checks, secrets, agent, sessionID, home) {
  const check = (name, flow) => checks.push({ name, result: flow && flow.ok ? 'PASS' : 'FAIL', detail: redact(JSON.stringify(flow), secrets).slice(0, 240) });
  const save = (name, flow) => fs.writeFileSync(path.join(args.out, name), `${JSON.stringify(flow, null, 2)}\n`, { mode: 0o600 });
  const call = (action, input = {}) => tool(agent, sessionID, action, input, secrets);
  const io = {
    call,
    now: Date.now,
    sleepMs: sleep,
    tapAllow: () => tapLabel(args, 'Allow agent'),
    deviceCmd: (text, phase) => deviceCommand(args, text, phase),
    readStatus: (phase) => readStatusJson(args, 1000, phase),
    readDiagRaw: async () => (await deviceCommand(args, 'geometry-diag', 'geometry-diag') ? readStatusJson(args, 5000, 'geometry-diag') : null),
    dragTitleRaw: () => dragFloatTitle(args, 'left'),
    pageEvalRaw: (script) => pageEval(args, script),
    removeFile: async (name) => fs.rmSync(path.join(args.out, name), { force: true }),
    // Copied out of the app home before the home directory is removed in main().
    saveCapture: async (relative, name) => copyPng(path.join(home, relative), path.join(args.out, name)),
    fileExists: async (name) => fs.existsSync(path.join(args.out, name)),
  };
  await runGeometryGateCases(io, { url: FIXTURE, save, check, expectedSessionId: sessionID });
}

/**
 * Drags the floating window by its title. The start point is the title's own top-left area (pure title,
 * not a button), and success requires the measured Surface bounds to move. A default right-anchored
 * window is not dragged left into the old wrong position: direction 'left' only moves when there is room.
 */
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

/**
 * Lifecycle case runner. It uses the same fence model as the geometry gate: a timed-out case is fenced,
 * its own hold is released, the probe gets a bounded settle, and an unsettled probe aborts the whole focus.
 * A timed-out case never starts the next one.
 */
/**
 * Scene budget for lifecycle cases. One lifecycle read costs several serial device round trips (diag, fixture,
 * page metrics, timeOrigin, uiautomator), so a case with several reads needs more than the single-request limit.
 * The production tool deadline is unchanged; this budget belongs only to the test scene.
 */
const LIFECYCLE_SCENE_MS = 90000;

async function runOneLifecycleCase(item, { deps, fence, adapters, probeDeadlineMs, save, now, sceneLog }) {
  const lifecycleProbe = require('./lib/provider_lifecycle_gate_probe.cjs');
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

/**
 * Focused lifecycle gate. It drives the real fixture, real UI taps and real wheel/touch injections through
 * the same tool/UI helpers the other focus gates use. Missing producer fields or lifecycle-diag refuse the case.
 */
async function focusLifecycleGate(args, checks, secrets, agent, sessionID, home, options = {}) {
  const check = (name, flow) => checks.push({ name, result: flow && flow.ok ? 'PASS' : 'FAIL', detail: redact(JSON.stringify(flow), secrets).slice(0, 240) });
  const save = (name, flow) => fs.writeFileSync(path.join(args.out, name), `${JSON.stringify(flow, null, 2)}\n`, { mode: 0o600 });
  const call = (action, input = {}) => tool(agent, sessionID, action, input, secrets);
  // Explicit scene budget. Each stage inside a case keeps its own 20 s wait limit (provider_lifecycle_gate_probe STAGE_MS).
  const probeDeadlineMs = options.probeDeadlineMs || LIFECYCLE_SCENE_MS;
  const sceneLog = [];
  const io = {
    call,
    now: Date.now,
    sleepMs: sleep,
    otherUrl: 'https://example.com/openchamber-provider-second',
    newDocumentUrl: 'https://example.com/openchamber-provider-second',
    // Producer: lifecycle-diag (restore, state, events). Read after its own command, not a stale status.
    readLifecycleRaw: async () => (await deviceCommand(args, 'lifecycle-diag', 'lifecycle-diag') ? readStatusJson(args, 5000, 'lifecycle-diag') : null),
    // fixture-state: page url/loads/posts and native frame, plus top-level postArrivals.
    readFixtureRaw: async () => (await deviceCommand(args, 'fixture-state', 'fixture-state') ? readStatusJson(args, 5000, 'fixture-state') : null),
    // Page JS: the fixture's own __report() (ua, clientWidth, media1000, sy) and the document's timeOrigin.
    // Both are typed, a missing value is null. Nothing is defaulted here.
    readMetricsRaw: async () => {
      const report = await pageEval(args, 'window.__report()');
      const origin = await pageEval(args, 'performance.timeOrigin');
      if (!report || typeof report !== 'object') return null;
      return { ...report, timeOrigin: typeof origin === 'number' ? origin : null };
    },
    // Live UI: Restore is visible only while enlarged. The surface frame is read from the same snapshot.
    readUiRaw: async () => {
      const nodes = await uiNodes(args);
      const restore = nodes.some((item) => item.text === 'Restore' || item.desc === 'Restore');
      const enlarge = nodes.some((item) => item.text === 'Enlarge' || item.desc === 'Enlarge');
      const surface = nodes.find((item) => item.desc === 'BrowserFloatSurface') || null;
      return { enlarged: restore ? true : enlarge ? false : null, surface: surface ? { left: surface.left, top: surface.top, right: surface.right, bottom: surface.bottom } : null };
    },
    // No mousewheel injection command exists in the instrumentation yet. The only real scroll input is the
    // two-finger touch gesture, so the "wheel" step is a real two-finger touch scroll, recorded as such.
    wheelRaw: async (dy) => {
      const rect = await pageRect(args);
      if (!rect || !(rect.w > 0)) return false;
      return deviceCommand(args, `two-finger-scroll:${rect.x + Math.floor(rect.w / 2)},${rect.y + Math.floor(rect.h / 2)},${dy}`, 'scrolled');
    },
    twoFingerRaw: async (dy) => {
      const rect = await pageRect(args);
      if (!rect || !(rect.w > 0)) return false;
      return deviceCommand(args, `two-finger-scroll:${rect.x + Math.floor(rect.w / 2)},${rect.y + Math.floor(rect.h / 2)},${dy}`, 'scrolled');
    },
    tapLabelRaw: (label) => tapLabel(args, label),
    switchModeRaw: async (target) => {
      const tapped = await tapLabel(args, 'More');
      if (!tapped) return false;
      await sleep(800);
      const item = await tapLabel(args, target === 'computer' ? 'Computer layout' : 'Phone layout');
      if (!item) return false;
      await sleep(800);
      return tapLabel(args, 'Confirm');
    },
    selectRaw: async (tabId) => deviceCommand(args, `select:${tabId}`, 'selected'),
    // Production Mouse on through the real More -> Mouse menu, once. Mouse is a mode, not a grant.
    enableMouseRaw: async () => switchMouseOn(args),
    // Fresh login POST for the POST case only. Its baseline comes from this submit's own result.
    submitLoginFresh: async () => {
      const opened = await call('browser.open', { url: io.postLoginUrl });
      if (!opened.ok) return { ok: false, reason: 'login-open-failed', opened: opened.redacted };
      const tabId = opened.data && opened.data.tabId ? opened.data.tabId : null;
      const submitted = await humanSubmit(args, { tabId, ownerSessionId: sessionID, loginOpenOk: opened.ok });
      if (!submitted || !submitted.ok) return { ok: false, reason: submitted && submitted.reason ? submitted.reason : 'submit-failed', tabId };
      return { ok: true, tabId, beforePosts: submitted.beforePosts, beforeArrivals: submitted.beforeArrivals, landing: submitted.landing, finalState: submitted.finalState };
    },
    releaseOwnedRaw: async () => true,
  };
  // Long page = MOUSE fixture (2400px tall, has window.__report). Fixture URLs are the real ones from the fixtures.
  io.postLoginUrl = 'https://example.com/openchamber-provider-login';
  io.longUrl = 'https://example.com/openchamber-provider-mouse';
  io.fixtureUrl = 'https://example.com/openchamber-provider-mouse';
  io.submitLoginRaw = io.submitLoginFresh;
  const adapters = createLifecycleGateAdapters(io);
  const precondition = await adapters.readLifecycle();
  if (!precondition) {
    const flow = { ok: false, reason: 'lifecycle-diag-missing', detail: 'lifecycle-diag returned no phase; refusing, not passing' };
    save('lifecycle-precondition.json', flow);
    check('lifecycle-precondition', flow);
    return;
  }
  // Optional single-case selection. The selection is written so a partial run is never read as the full six.
  const selectedCase = args['lifecycle-case'] || null;
  const cases = selectedCase ? LIFECYCLE_CASES.filter((item) => item.name === selectedCase) : LIFECYCLE_CASES;
  save('selection.json', { focus: 'lifecycle-gate', selected: cases.map((item) => item.name), total: LIFECYCLE_CASES.length, partial: Boolean(selectedCase), phaseComplete: false, note: 'a selected subset is never an overall pass' });
  for (const item of cases) {
    const fence = createCaseFence();
    const caseAdapters = createLifecycleGateAdapters(io, fence);
    // Real case setup first, then the flow. A failed setup refuses the case and runs nothing else.
    const setup = await prepareLifecycleCase(item, { adapters: caseAdapters, io, expectedSessionId: sessionID });
    if (!setup.ok) {
      save(`${item.name}-prepare.json`, setup);
      check(`${item.name}-prepare`, setup);
      continue;
    }
    const deps = {
      ...caseAdapters,
      readLifecycle: caseAdapters.readLifecycle,
      lastRead: caseAdapters.lastRead,
      now: io.now,
      sleep: io.sleepMs,
      baseline: setup.baseline || null,
      submitLoginPost: async () => setup.baseline ? { ok: true } : caseAdapters.submitLoginPost(),
      direction: item.direction || null,
    };
    const flow = await runOneLifecycleCase(item, { deps, fence, adapters: caseAdapters, probeDeadlineMs, save, now: io.now, sceneLog });
    save(`${item.name}.json`, flow);
    check(item.name, flow);
    // Every case writes its scene timing, so a failure can be separated into transport time and page behaviour.
    save(`${item.name}-scene.json`, { case: item.name, sceneMs: flow.sceneMs, sceneBudgetMs: probeDeadlineMs, outcome: flow.ok ? 'completed' : flow.reason, scenes: sceneLog.filter((entry) => entry.case === item.name) });
    // A required case that fails stops the run: no later case is started (requested rule).
    if (!flow.ok) break;
  }
}

async function dragFloatTitle(args, direction = 'left') {
  const before = await uiNodes(args);
  const title = before.find((item) => item.text === 'Browser' || item.text === '浏览器');
  const surfaceBefore = before.find((item) => item.desc === 'BrowserFloatSurface');
  if (!title || !surfaceBefore) return false;
  const fromX = title.left + 8;
  const fromY = title.top + 6;
  const toX = direction === 'left' ? Math.max(4, title.left - 40) : title.left + 48;
  if (toX === title.left) return false;
  const swipe = await adb(args, ['shell', 'input', 'swipe', String(fromX), String(fromY), String(toX), String(fromY), '300']);
  await sleep(600);
  const after = await uiNodes(args);
  const surfaceAfter = after.find((item) => item.desc === 'BrowserFloatSurface');
  if (!surfaceAfter) return false;
  const dx = surfaceAfter.left - surfaceBefore.left;
  const dy = surfaceAfter.top - surfaceBefore.top;
  return Boolean(swipe && swipe.code === 0 && Math.abs(dx) >= 8 && Math.abs(dy) <= 4);
}

async function focusPhaseThree(args, checks, secrets, agent, sessionID, deps = {}) {
  const deadline = Date.now() + 10 * 60 * 1000;
  const call = deps.call || ((action, input = {}) => tool(agent, sessionID, action, input, secrets));
  const check = (name, pass, detail) => checks.push({ name, result: pass ? 'PASS' : 'FAIL', detail: redact(detail, secrets).slice(0, 240) });
  const stop = async (name, detail, extra = {}) => {
    check(name, false, detail);
    const diagnostic = deps.collect ? await deps.collect(args, { stopped: name, detail: redact(detail, secrets), ...extra }, secrets) : null;
    const body = { stopped: name, detail: redact(detail, secrets), extra, diagnostic, checks };
    if (deps.record) deps.record(body);
    else {
      fs.mkdirSync(args.out, { recursive: true });
      fs.writeFileSync(path.join(args.out, 'phase3-focus-last.json'), `${JSON.stringify(body, null, 2)}\n`, { mode: 0o600 });
    }
  };
  const wait = deps.waitForLabel || ((label) => waitForLabel(args, label, deadline));
  if (Date.now() > deadline) return stop('focus-budget', '10 minute budget exhausted');
  const opened = await call('browser.open', { url: 'https://example.com/openchamber-provider-mouse' });
  if (!opened.ok) return stop('focus-open', opened.redacted);
  check('focus-open', true, opened.redacted);
  const unauthorized = await call('browser.snapshot');
  if (unauthorized.ok || !/AUTHORIZATION_REQUIRED/.test(unauthorized.redacted)) return stop('focus-unauthorized', unauthorized.redacted);
  check('focus-unauthorized', true, unauthorized.redacted);
  const menu = await focusMenu({ waitForLabel: wait }, deadline);
  if (!menu.ok) return stop('focus-mouse-menu', `${menu.reason}: ${JSON.stringify(menu.trace)}`, { failedLabel: 'More', trace: menu.trace });
  check('focus-desktop-confirm', true, menu.trace.join(','));
  check('focus-mouse-menu', menu.trace.filter((label) => label === 'Mouse').length === 1, menu.trace.join(','));
  const geometry = deps.geometry || (async () => {
    const rect = await pageRect(args);
    const report = await pageEval(args, 'window.__report()');
    return { rect, report };
  });
  let ready = null;
  while (Date.now() < deadline) {
    ready = await geometry();
    if (ready && ready.rect && ready.rect.pointerUi && ready.rect.pointerUi.geometryReady === true && ready.report) break;
    await sleep(500);
  }
  fs.writeFileSync(path.join(args.out, 'phase3-focus-geometry.json'), `${JSON.stringify(ready || {}, null, 2)}\n`, { mode: 0o600 });
  const desktopMetric = ready && ready.report && ready.report.ua === 'desktop' && ready.report.clientWidth >= 1000 && ready.report.clientWidth <= 1048 && ready.report.media1000 === true;
  if (!desktopMetric) return stop('focus-desktop-metric', JSON.stringify({ ua: ready && ready.report && ready.report.ua, clientWidth: ready && ready.report && ready.report.clientWidth, media1000: ready && ready.report && ready.report.media1000, visual: ready && ready.report && ready.report.vw }));
  check('focus-desktop-metric', true, `ua=${ready.report.ua} clientWidth=${ready.report.clientWidth} media1000=${ready.report.media1000}`);
  if (!ready.rect.pointerUi || ready.rect.pointerUi.geometryReady !== true) return stop('focus-geometry-ready', 'geometryReady was not true');
  check('focus-geometry-ready', true, JSON.stringify(ready.rect.pointerUi));
  const before = { rect: ready.rect, visual: ready.rect.page && ready.rect.page.visualViewport, physicalWidth: ready.rect.physical && ready.rect.physical.width, viewHandle: ready.rect.viewHandle };
  if (!before.visual) return stop('focus-geometry-shape', 'pageRect.page.visualViewport missing');
  const startX = ready.rect.x + Math.floor(ready.rect.w / 2);
  const startY = ready.rect.y + Math.floor(ready.rect.h / 2);
  await deviceCommand(args, `finger:${startX},${startY},80,0,0,4`, 'fingered');
  const afterReady = await geometry();
  const moves = await pageEval(args, 'JSON.stringify(window.__log.filter(function(e){return e.t==="pointermove"&&e.p==="mouse"}))');
  const end = (moves || []).slice(-1)[0];
  const after = { rect: afterReady && afterReady.rect, visual: afterReady && afterReady.rect && afterReady.rect.page && afterReady.rect.page.visualViewport, physicalWidth: afterReady && afterReady.rect && afterReady.rect.physical && afterReady.rect.physical.width, viewHandle: afterReady && afterReady.rect && afterReady.rect.viewHandle };
  const expected = after.rect && after.rect.pointerUi && after.visual ? { x: expectedClientPoint(after.rect.pointerUi.pointerViewX, after.visual.offsetLeft, after.physicalWidth, after.visual.width), y: expectedClientPoint(after.rect.pointerUi.pointerViewY, after.visual.offsetTop, after.physicalWidth, after.visual.width) } : null;
  const hotspot = { before, after, moves, end, expected, native: { before: ready.rect.pointerUi, after: after.rect && after.rect.pointerUi, viewHandle: { before: ready.rect.viewHandle, after: after.rect && after.rect.viewHandle }, documentEpoch: { before: ready.rect.documentEpoch, after: after.rect && after.rect.documentEpoch } }, metric: ready.report };
  fs.writeFileSync(path.join(args.out, 'phase3-focus-hotspot.json'), `${JSON.stringify(hotspot, null, 2)}\n`, { mode: 0o600 });
  if (!gestureMatches(before, after, end, 15)) return stop('focus-hotspot', 'pointer hotspot did not match measured geometry; see phase3-focus-hotspot.json');
  check('focus-relative', true, 'hotspot matched');
  await deviceCommand(args, `finger:${ready.rect.x + 80},${ready.rect.y + 80},0,0,0,1`, 'fingered');
  const taps = await pageEval(args, 'JSON.stringify(window.__log.filter(function(e){return e.t==="pointerdown"||e.t==="pointerup"}))');
  if (!taps || !taps.some((item) => item.p === 'mouse' && item.bs === 1)) return stop('focus-primary-tap', 'primary mouse button was not observed');
  check('focus-primary', true, JSON.stringify(taps).slice(0, 160));
  await deviceCommand(args, `two-finger-scroll:${ready.rect.x + 80},${ready.rect.y + 80},-80`, 'scrolled');
  const wheels = await pageEval(args, 'JSON.stringify(window.__log.filter(function(e){return e.t==="wheel"}))');
  if (!Array.isArray(wheels) || wheels.length === 0) return stop('focus-wheel', 'wheel missing');
  check('focus-wheel', true, JSON.stringify(wheels).slice(0, 160));
}

async function waitForReady(args, deadline = Date.now() + 20000) {
  let last = null;
  let stable = 0;
  while (Date.now() < deadline) {
    const native = await deviceCommand(args, 'native-state', 'native-state') ? await readStatusJson(args, 3000, 'native-state') : null;
    const rect = await pageRect(args);
    const same = last && native && rect && last.viewHandle === native.viewHandle && last.w === rect.w && last.h === rect.h;
    stable = same && native.pageReady === true && native.pointerUi && native.pointerUi.geometryReady === true && native.pointerUi.viewHandle === native.viewHandle ? stable + 1 : 0;
    last = native && rect ? { viewHandle: native.viewHandle, w: rect.w, h: rect.h, native, rect } : null;
    if (stable >= 2) return last;
    await sleep(300);
  }
  return null;
}

async function phaseThree(args, call, check, screenshotDirectory, context = {}) {
  const sessionID = context.sessionID;
  const opened = await call('browser.open', { url: 'https://example.com/openchamber-provider-mouse' });
  await sleep(5000);
  check('p3-open-mouse-fixture', opened.ok, opened.redacted);
  const nodes = await uiNodes(args);
  const composer = nodes.find((item) => item.text === 'Plan, build, or ask' || item.desc === 'Plan, build, or ask');
  const takeOver = nodes.find((item) => item.text === 'Take over' || item.desc === 'Take over');
  const initialLayout = await deviceCommand(args, 'composer-layout', 'composer-layout') ? await readStatusJson(args, 5000, 'composer-layout') : null;
  const initialSurface = (await uiNodes(args)).find((item) => item.desc === 'BrowserFloatSurface');
  const initialDecision = layoutAccepts(initialLayout, initialSurface, null);
  check('p3-window-above-composer', initialDecision.pass, JSON.stringify(initialDecision));
  const mobile = await pageEval(args, 'window.__report()');
  const loadsBefore = await pageEval(args, 'String(window.__loads)');
  await tapLabel(args, 'More');
  await sleep(1500);
  await tapLabel(args, 'Computer layout');
  await sleep(800);
  const confirmed = await tapLabel(args, 'Confirm');
  check('p3-desktop-confirm', confirmed, confirmed ? 'tapped Confirm' : 'confirm button missing');
  await sleep(6000);
  const desktop = await pageEval(args, 'window.__report()');
  const loadsAfter = await pageEval(args, 'String(window.__loads)');
  check('p3-desktop-layout', Boolean(mobile && desktop && mobile.ua === 'mobile' && desktop.ua === 'desktop' && desktop.w === 1024 && Number(loadsAfter) === Number(loadsBefore) + 1), `mobile ${mobile && mobile.ua} desktop ${desktop && desktop.ua} innerWidth ${desktop && desktop.w} loads ${loadsBefore}->${loadsAfter}`);
  const revoked = await call('browser.snapshot');
  check('p3-mode-switch-revokes', !revoked.ok && /AUTHORIZATION_REQUIRED/.test(revoked.redacted), revoked.redacted);
  await tapLabel(args, 'More');
  await sleep(1500);
  await tapLabel(args, 'Mouse');
  const mouseReady = await waitForReady(args);
  if (!mouseReady || mouseReady.native.mouseMode !== true) { check('p3-mouse-ready', false, 'mouse geometry was not ready; gesture not sent'); return; }
  const sampleGeometry = async (label) => {
    const rect = await pageRect(args);
    const page = await pageEval(args, 'JSON.stringify(window.__geometry&&window.__geometry())');
    const visual = page && page.visualViewport;
    const physicalWidth = rect && (rect.physical ? rect.physical.width : rect.w);
    const scale = visualScale(physicalWidth, visual && visual.width);
    return { label, rect, page, visual, physicalWidth, scale, viewHandle: rect && rect.viewHandle, devicePixelRatio: page && page.devicePixelRatio, visualScale: visual && visual.scale };
  };
  const ready = await sampleGeometry('small');
  fs.writeFileSync(path.join(args.out, 'phase3-geometry.json'), `${JSON.stringify({ samples: [ready], note: 's = physicalWidth / visualViewport.width. It is not devicePixelRatio. pointerUi is copied from presenter or marked missing; static labels are not native geometry.' }, null, 2)}\n`, { mode: 0o600 });
  const rect = ready.rect;
  if (!rect || !ready.page || ready.scale == null) { check('p3-page-rect', false, 'missing measured visual geometry'); return; }
  if (!rect.pointerUi || rect.pointerUi.missing) { check('p3-pointer-ui', false, 'missing presenter.pointerUi'); return; }
  check('p3-pointer-ui', rect.pointerUi.geometryReady === true, JSON.stringify(rect.pointerUi));
  const cx = rect.x + Math.floor(rect.w / 2);
  const cy = rect.y + Math.floor(rect.h / 2);
  const ratio = ready.scale;
  const mouseEvents = async (filter) => pageEval(args, `JSON.stringify(window.__log.filter(function(e){return ${filter}}))`);
  const reset = () => pageEval(args, "(window.__log=[],'r')");

  // Establish the pointer position with a first small move, then measure a 240 px swipe from it.
  await reset();
  await deviceCommand(args, `finger:${cx - 200},${cy},60,0,0,4`, 'fingered');
  await sleep(600);
  const start = ((await mouseEvents("e.t==='pointermove'&&e.p==='mouse'")) || []).slice(-1)[0];
  await reset();
  await deviceCommand(args, `finger:${cx - 200},${cy},240,0,0,12`, 'fingered');
  await sleep(800);
  const moves = (await mouseEvents("e.t==='pointermove'&&e.p==='mouse'")) || [];
  const end = moves.slice(-1)[0];
  const moved = start && end ? end.x - start.x : 0;
  const expected = expectedClientDelta(240, ready.physicalWidth, ready.visual && ready.visual.width);
  const afterMove = await sampleGeometry('gesture');
  const fingerCss = expectedClientPoint((cx - 200) - rect.x, ready.visual.offsetLeft, ready.physicalWidth, ready.visual.width);
  const landed = fingerJump(moves[0], fingerCss);
  const matched = gestureMatches(ready, afterMove, end, 15);
  check('p3-mouse-relative-move', moves.length > 2 && expected != null && Math.abs(moved - expected) < 15 && !landed && matched, `pointer moved ${moved} CSS px for 240 WebView px (expected ${expected == null ? 'missing' : Math.round(expected)}); s=${ready.scale} dpr=${ready.devicePixelRatio}; jumped to finger: ${Boolean(landed)}; hotspot=${matched}`);

  await reset();
  await deviceCommand(args, `finger:${cx},${cy},0,0,0,1`, 'fingered');
  await sleep(800);
  const taps = ((await mouseEvents("['pointerdown','pointerup','click'].indexOf(e.t)>=0")) || []).map((e) => `${e.t}:${e.p}`);
  check('p3-mouse-click', JSON.stringify(taps) === JSON.stringify(['pointerdown:mouse', 'pointerup:mouse', 'click:mouse']), JSON.stringify(taps));

  // Steer the pointer onto the box centre (CSS 460,190) by relative moves, then drag it.
  for (let k = 0; k < 3; k += 1) {
    const last = ((await mouseEvents("e.p==='mouse'")) || []).slice(-1)[0];
    if (!last) break;
    const dx = Math.round((460 - last.x) * ratio);
    const dy = Math.round((190 - last.y) * ratio);
    await deviceCommand(args, `finger:${cx - Math.round(dx / 2)},${cy - Math.round(dy / 2)},${dx},${dy},0,10`, 'fingered');
    await sleep(500);
  }
  const boxBefore = await pageEval(args, 'JSON.stringify([document.getElementById("box").offsetLeft,document.getElementById("box").offsetTop])');
  await reset();
  await deviceCommand(args, `finger:${cx},${cy},150,0,500,12`, 'fingered');
  await sleep(800);
  const boxAfter = await pageEval(args, 'JSON.stringify([document.getElementById("box").offsetLeft,document.getElementById("box").offsetTop])');
  const held = ((await mouseEvents("e.t==='pointermove'&&e.bs===1")) || []).length;
  check('p3-mouse-drag', Boolean(boxBefore && boxAfter && boxAfter[0] - boxBefore[0] > 100 && held > 0), `box ${JSON.stringify(boxBefore)}->${JSON.stringify(boxAfter)} held moves ${held}`);

  await reset();
  const scrollBefore = await pageEval(args, 'String(Math.round(scrollY))');
  await deviceCommand(args, `two-finger-scroll:${cx},${cy},-240`, 'scrolled');
  await sleep(1500);
  const scrollAfter = await pageEval(args, 'String(Math.round(scrollY))');
  const wheels = ((await mouseEvents("e.t==='wheel'")) || []).length;
  check('p3-mouse-wheel', Number(scrollAfter) > Number(scrollBefore) && wheels > 0, `scrollY ${scrollBefore}->${scrollAfter} wheel ${wheels}`);

  const loadsBeforeEnlarge = await pageEval(args, 'String(window.__loads)');
  const scrollBeforeEnlarge = await pageEval(args, 'String(scrollY)');
  await tapLabel(args, 'Enlarge');
  await sleep(2000);
  const enlargedSample = await sampleGeometry('enlarged');
  await screenshot(args, args.out, 'p3-enlarged.png');
  await tapLabel(args, 'Restore');
  await sleep(2000);
  const restoredSample = await sampleGeometry('restored');
  const scrolledSample = await sampleGeometry('scrolled');
  const loadsAfterRestore = await pageEval(args, 'String(window.__loads)');
  const scrollAfterRestore = await pageEval(args, 'String(scrollY)');
  const preservedScroll = require('./lib/provider_lifecycle_gate_probe.cjs').restoredWithin({ y: scrollBeforeEnlarge }, { y: scrollAfterRestore }) === true;
  const still = await pageEval(args, 'window.__report()');
  check('p3-enlarge-restore-no-reload', Boolean(enlargedSample.rect && restoredSample.rect && enlargedSample.rect.h > rect.h * 2 && restoredSample.rect.h === rect.h && loadsAfterRestore === loadsBeforeEnlarge && preservedScroll && still && still.ua === 'desktop'), `enlarged ${enlargedSample.rect && enlargedSample.rect.h} restored ${restoredSample.rect && restoredSample.rect.h} small ${rect.h} loads ${loadsBeforeEnlarge}->${loadsAfterRestore} scroll ${scrollBeforeEnlarge}->${scrollAfterRestore}`);
  const samples = [ready, enlargedSample, restoredSample, scrolledSample];
  fs.writeFileSync(path.join(args.out, 'phase3-geometry.json'), `${JSON.stringify({ samples, formula: 'expectedCss = physicalDelta / (physicalWidth / visualViewport.width)', note: 's is visual scale, not devicePixelRatio. pointerUi missing is a missing core API, not a pass.' }, null, 2)}\n`, { mode: 0o600 });
  const distinct = new Set(samples.map((item) => item.label)).size === samples.length && enlargedSample.rect && restoredSample.rect && enlargedSample.rect.h !== restoredSample.rect.h;
  check('p3-geometry-after-size-and-scroll', distinct && samples.every((item) => item.scale != null && item.page && item.page.scrollY != null && item.rect && item.rect.pointerUi && item.rect.pointerUi.missing !== true && item.rect.pointerUi.geometryReady === true), JSON.stringify(samples.map((item) => ({ label: item.label, h: item.rect && item.rect.h, scale: item.scale, dpr: item.devicePixelRatio, scrollY: item.page && item.page.scrollY }))));
  const layoutProbe = require('./lib/provider_layout_probe.cjs');
  const layoutFlow = await layoutProbe.runLayoutCycle({
    rotate: async (orientation) => deviceCommand(args, `orientation:${orientation}`, 'oriented'),
    read: () => readLayoutSample(args),
    tap: (label) => tapLabel(args, label),
    save: async (label, evidence) => fs.writeFileSync(path.join(args.out, `layout-${label}.json`), `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 }),
    now: Date.now,
    sleep,
  });
  check('p3-layout-bounds', layoutFlow.ok, JSON.stringify(layoutFlow));
  await tapLabel(args, 'More');
  await sleep(800);
  const touchTapped = await tapLabel(args, 'Touch');
  const touchState = await deviceCommand(args, 'native-state', 'native-state') ? await readStatusJson(args, 5000, 'native-state') : null;
  if (!touchTapped || !touchState || touchState.mouseMode !== false) { check('p3-direct-touch-pinch', false, 'Touch menu did not leave mouse mode'); return; }
  const pinchRect = await pageRect(args);
  const pinchX = pinchRect ? pinchRect.x + Math.floor(pinchRect.w / 2) : null;
  const pinchY = pinchRect ? pinchRect.y + Math.floor(pinchRect.h / 2) : null;
  const pinchBefore = await pageEval(args, 'JSON.stringify({w:(visualViewport&&visualViewport.width)||0,s:(visualViewport&&visualViewport.scale)||0,loads:window.__loads})');
  if (pinchX != null) await deviceCommand(args, `pinch:${pinchX},${pinchY}`, 'pinched');
  await sleep(1500);
  const pinchAfter = await pageEval(args, 'JSON.stringify({w:(visualViewport&&visualViewport.width)||0,s:(visualViewport&&visualViewport.scale)||0,loads:window.__loads})');
  check('p3-direct-touch-pinch', Boolean(pinchBefore && pinchAfter && pinchAfter.loads === pinchBefore.loads && (pinchAfter.w !== pinchBefore.w || pinchAfter.s !== pinchBefore.s)), `before ${JSON.stringify(pinchBefore)} after ${JSON.stringify(pinchAfter)}`);
  const loginOpen = await call('browser.open', { url: 'https://example.com/openchamber-provider-login' });
  await sleep(2500);
  const submitted = await humanSubmit(args, { tabId: loginOpen.data && loginOpen.data.tabId, ownerSessionId: sessionID, loginOpenOk: loginOpen.ok });
  const posted = submitted.ok ? submitted.landing : null;
  if (!posted) check('p3-post-mode-refuses-reload', false, `submit prerequisite failed: ${submitted.reason}`);
  if (posted) {
  await tapLabel(args, 'More');
  await sleep(800);
  await tapLabel(args, posted.ua === 'mobile' ? 'Computer layout' : 'Phone layout');
  await sleep(800);
  const postConfirm = await tapLabel(args, 'Confirm');
  const afterPostMode = await pageEval(args, 'JSON.stringify({url:location.href, ua:navigator.userAgent.indexOf("Mobile")>=0?"mobile":"desktop", loads:window.__loads, posts:window.__posts, cookie:document.cookie})');
  const modeState = await deviceCommand(args, 'native-state', 'native-state') ? await readStatusJson(args, 5000, 'native-state') : null;
  fs.writeFileSync(path.join(args.out, 'p3-post-mode.json'), `${JSON.stringify({ posted, afterPostMode, modeState, postConfirm }, null, 2)}\n`, { mode: 0o600 });
  check('p3-post-mode-refuses-reload', loginOpen.ok && postConfirm && postModeRefused(posted, { ...afterPostMode, reopenRequired: modeState && modeState.pageError === 'reopen-required' }), `posted=${JSON.stringify(posted)} after=${JSON.stringify(afterPostMode)} confirm=${postConfirm}`);
  }
  const desktopOpen = await call('browser.open', { url: 'https://example.com/openchamber-provider-mouse', viewport: 'desktop' });
  await sleep(4000);
  const desktopReport = await pageEval(args, 'window.__report()');
  const desktopRevoked = await call('browser.snapshot');
  await tapLabel(args, 'Allow agent');
  const resize = await call('browser.resize', { viewport: 'mobile' });
  await sleep(4000);
  const mobileReport = await pageEval(args, 'window.__report()');
  const resizeRevoked = await call('browser.snapshot');
  await tapLabel(args, 'Allow agent');
  const resizeAllowed = await call('browser.snapshot');
  check('p3-open-resize-viewport', viewportApplied(desktopOpen, desktopReport, 'desktop', 1024) && viewportApplied(resize, mobileReport, 'mobile', null) && !desktopRevoked.ok && /AUTHORIZATION_REQUIRED/.test(desktopRevoked.redacted) && !resizeRevoked.ok && /AUTHORIZATION_REQUIRED/.test(resizeRevoked.redacted) && resizeAllowed.ok, `open ${desktopOpen.redacted} resize ${resize.redacted}`);
  const measured = await pageEval(args, 'JSON.stringify(window.__geometry&&window.__geometry())');
  const captured = await call('browser.capture', { label: 'p3' });
  const captureFile = captured && captured.data && captured.data.path;
  let captureBytes = null;
  try {
    captureBytes = captureFile ? fs.readFileSync(path.join(screenshotDirectory, captureFile)) : null;
  } catch (_error) {
    captureBytes = null;
  }
  const capturePass = checkCapture(captured, measured, () => captureBytes);
  const evidence = { captured, measured, filePresent: Boolean(captureBytes), pass: capturePass };
  fs.writeFileSync(path.join(args.out, 'p3-agent-capture.json'), `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
  if (captureBytes) fs.writeFileSync(path.join(args.out, 'p3-agent-capture.png'), captureBytes);
  check('p3-capture-css-metrics', capturePass, captured && captured.redacted);
  await screenshot(args, args.out, 'p3-mouse.png');
}

function rawRequest(port, method, urlPath, headers, body) {
  return hostRequest(`http://127.0.0.1:${port}${urlPath}`, { method, headers, body, timeoutMs: 4000 })
    .then((response) => ({ status: response.status, body: response.body.slice(0, 160) }))
    .catch((error) => ({ status: 'error', body: error.message }));
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

function visualScale(physicalWidth, visualWidth) {
  const width = Number(physicalWidth);
  const visual = Number(visualWidth);
  if (!(width > 0) || !(visual > 0)) return null;
  return width / visual;
}

function expectedClientDelta(physicalDelta, physicalWidth, visualWidth) {
  const scale = visualScale(physicalWidth, visualWidth);
  return scale == null ? null : physicalDelta / scale;
}

function cssClientToScreen(screenLeft, screenTop, viewWidth, visualWidth, clientX, clientY, offsetLeft, offsetTop) {
  const scale = visualScale(viewWidth, visualWidth);
  if (scale == null) return null;
  return { x: screenLeft + (clientX - offsetLeft) * scale, y: screenTop + (clientY - offsetTop) * scale, scale };
}

function expectedClientPoint(pointerView, visualOffset, physicalWidth, visualWidth) {
  const scale = visualScale(physicalWidth, visualWidth);
  if (scale == null || pointerView == null || visualOffset == null) return null;
  return visualOffset + pointerView / scale;
}

function viewportApplied(result, report, expectedUa, expectedLayout) {
  if (!result || result.ok !== true || !report) return false;
  if (String(result.redacted || '').match(/unsupported|not applied/i)) return false;
  if (report.ua !== expectedUa) return false;
  if (expectedLayout != null && Number(report.w) !== expectedLayout) return false;
  return true;
}

function manualLandingPass(submitted, snapshot) {
  const data = snapshot && snapshot.data;
  if (!submitted || submitted.ok !== true || submitted.value !== 'fixture-user' || submitted.beforePosts == null || !submitted.landing || !data) return false;
  if (submitted.landing.posts !== submitted.beforePosts + 1) return false;
  if (!snapshot.ok || data.url !== 'https://example.com/openchamber-provider-welcome') return false;
  return /oc_fixture_login=signed-in-42/.test(data.text || '') && new RegExp(`posts:${submitted.landing.posts}(?!\\d)`).test(data.text || '');
}

function postModeRefused(before, after) {
  if (!before || !after) return false;
  if (!(before.posts >= 1) || before.loads == null || before.url == null) return false;
  if (!/openchamber-provider-welcome|openchamber-provider-login\/submit/.test(before.url)) return false;
  return before.url === after.url && before.ua === after.ua && before.loads === after.loads && before.posts === after.posts && after.reopenRequired === true;
}

function gestureMatches(before, after, endEvent, tolerance) {
  const beforeUi = before && before.rect && before.rect.pointerUi;
  const afterUi = after && after.rect && after.rect.pointerUi;
  const visual = after && after.visual;
  if (!beforeUi || !afterUi || !visual || afterUi.geometryReady !== true) return false;
  if (beforeUi.viewHandle !== after.viewHandle || afterUi.viewHandle !== after.viewHandle) return false;
  if (beforeUi.geometryEpoch == null || afterUi.geometryEpoch !== beforeUi.geometryEpoch) return false;
  const predictedX = expectedClientPoint(afterUi.pointerViewX, visual.offsetLeft, after.physicalWidth, visual.width);
  const predictedY = expectedClientPoint(afterUi.pointerViewY, visual.offsetTop, after.physicalWidth, visual.width);
  if (predictedX == null || predictedY == null || !endEvent) return false;
  return Math.abs(endEvent.x - predictedX) < tolerance && Math.abs(endEvent.y - predictedY) < tolerance;
}

function layoutAccepts(snapshot, surface, tight, nativeState = {}) {
  const measurement = snapshot && snapshot.measurement;
  const web = snapshot && snapshot.mainWebView;
  const frame = snapshot && snapshot.usableFrame;
  if (!snapshot || snapshot.validityReason !== 'accepted' || snapshot.fallback === true || !measurement || !web || !frame) return { pass: false, reason: 'measurement-missing' };
  if (!(measurement.viewportWidth > 0) || !(web.width > 0) || !(measurement.nativeWidth > 0) || measurement.nativeWidth !== web.width || measurement.nativeHeight !== web.height) return { pass: false, reason: 'dimension-mismatch' };
  if (measurement.consoleGeneration !== snapshot.consoleGeneration || measurement.nativeGeneration !== snapshot.nativeGeneration) return { pass: false, reason: 'generation-mismatch' };
  const scale = web.width / measurement.viewportWidth;
  const composerTop = web.y + measurement.top * scale;
  const inside = (box) => box && [box.left, box.top, box.right, box.bottom].every(Number.isFinite) && box.right > box.left && box.bottom > box.top && box.left >= frame.left && box.top >= frame.top && box.right <= frame.right && box.bottom <= frame.bottom;
  if (tight) return { pass: inside(tight) && nativeState.agentOn === false, reason: nativeState.agentOn === false ? 'tight-visible' : 'tight-granted', composerTop, scale };
  if (!surface) return { pass: false, reason: 'surface-missing', composerTop, scale };
  return { pass: inside(surface) && surface.bottom <= composerTop, reason: 'surface-bounds', composerTop, scale };
}

function windowClearsComposer(nodes) {
  const composer = nodes.find((item) => item.text === 'Plan, build, or ask' || item.desc === 'Plan, build, or ask');
  const footer = nodes.find((item) => item.text === 'Allow agent' || item.desc === 'Allow agent' || item.text === 'Take over' || item.desc === 'Take over');
  const title = nodes.find((item) => item.text === 'Browser' || item.desc === 'Browser');
  const tight = nodes.some((item) => /keyboard covers the browser/i.test(`${item.text} ${item.desc}`));
  if (tight) return false;
  if (!composer || !footer || !title) return false;
  const screen = nodes.reduce((max, item) => ({ right: Math.max(max.right, item.right), bottom: Math.max(max.bottom, item.bottom) }), { right: 0, bottom: 0 });
  return footer.bottom <= composer.top && footer.left >= 0 && footer.top >= 0 && footer.right <= screen.right && footer.bottom <= screen.bottom;
}

function fingerJump(firstMove, fingerCssX) {
  if (!firstMove || fingerCssX == null) return true;
  return Math.abs(firstMove.x - fingerCssX) < 5;
}

function pngSize(bytes) {
  if (!bytes || bytes.length < 24 || !bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return null;
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

function checkCapture(captured, measured, readPng) {
  const file = captured && captured.data && captured.data.path ? readPng(captured.data.path) : null;
  const size = pngSize(file);
  const css = captured && captured.data && captured.data.css;
  const visual = measured && measured.visualViewport;
  if (!captured || captured.ok !== true || !css || !visual || !size || !measured) return false;
  if (css.dpr == null || !(css.width > 1) || !(css.height > 1)) return false;
  const near = (left, right) => Math.abs(Number(left) - Number(right)) <= 0.5;
  const png = captured.data.png || {};
  return size.width === png.width && size.height === png.height && near(css.width, measured.innerWidth) && near(css.height, measured.innerHeight) && near(css.visualWidth, visual.width) && near(css.visualHeight, visual.height) && near(css.dpr, measured.devicePixelRatio);
}

function exactLabel(text, label) {
  const match = String(text || '').match(new RegExp(`(?:^|[^A-Za-z0-9_-])${label}:(\\d+)(?!\\d)`));
  return match ? Number(match[1]) : null;
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
  fs.writeFileSync(path.join(args.out, 'server.pid'), `${child.pid}\n`, { mode: 0o600 });
  let stderr = '';
  let stderrRaw = '';
  let stdout = '';
  let logcat = null;
  const instrumentState = { exitCode: null, endedAt: null };
  child.stderr.on('data', (chunk) => { stderrRaw += chunk; stderr += redact(chunk, secrets); });
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  try {
    const evidence = buildEvidence(path.resolve(__dirname, '..'), args.skipBuild === 'true');
    fs.writeFileSync(path.join(args.out, 'build-evidence.json'), `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
    if (args.skipBuild === 'true') checks.push({ name: 'fresh-build-identity', result: 'FAIL', detail: 'skipBuild uses an unproven APK; hash recorded, not a PASS' });
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
      // Record the artifacts actually built and installed, not whichever APKs preceded this run.
      fs.writeFileSync(path.join(args.out, 'build-evidence.json'), `${JSON.stringify(buildEvidence(repo, false), null, 2)}\n`, { mode: 0o600 });
    }
    // Clear only this runner's handshakes: an interrupted test may leave an unconsumed command or
    // hold marker. Keep browser profiles and cookies intact for the stateful-browser contract.
    await adb(args, ['shell', 'run-as io.andchamber.app rm -f files/openchamber-provider-status.json files/openchamber-provider-stop files/openchamber-provider-command files/openchamber-provider-hold files/openchamber-provider-dhold']);
    await adb(args, ['logcat', '-G', '16M']);
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
    // androidTest-only in-process relay 127.0.0.1:port -> 10.0.2.2:port (adb reverse forwards no bytes
    // on this emulator, and the shell nc relay dropped about one connection in sixty).
    checks.push({ name: 'transport', result: 'PASS', detail: 'androidTest in-app relay → 10.0.2.2' });
    const instrumentLog = fs.createWriteStream(path.join(args.out, 'instrumentation.log'), { flags: 'a', mode: 0o600 });
    const instrument = spawn(args.adb, ['-s', args.serial, 'shell', 'am', 'instrument', '-w', '-e', 'port', String(port), '-e', 'relay', 'app', '-e', 'token', providerToken, '-e', 'password', UI_PASSWORD, 'io.andchamber.app.test/io.andchamber.app.browser.OpenChamberProviderInstrumentation'], { stdio: ['ignore', 'pipe', 'pipe'] });
    const ignoredInstrumentState = instrumentState;
    const recordInstrument = (chunk) => instrumentLog.write(redact(chunk.toString('utf8'), secrets));
    instrument.stdout.on('data', recordInstrument);
    instrument.stderr.on('data', recordInstrument);
    instrument.on('close', (code) => { instrumentState.exitCode = code; instrumentState.endedAt = new Date().toISOString(); instrumentLog.write(`exitCode=${code} endedAt=${instrumentState.endedAt}\n`); });
    logcat = spawn(args.adb, ['-s', args.serial, 'logcat', '-v', 'time', 'AndChamberProvider:V', 'OcProviderTest:V', '*:S'], { stdio: ['ignore', 'pipe', 'pipe'] });
    const logcatFile = fs.createWriteStream(path.join(args.out, 'provider-logcat-stream.txt'), { flags: 'a', mode: 0o600 });
    logcat.stdout.on('data', (chunk) => logcatFile.write(redact(chunk.toString('utf8'), secrets)));
    logcat.stderr.on('data', (chunk) => logcatFile.write(redact(chunk.toString('utf8'), secrets)));
    const attached = await readDeviceStatus(args, 'attached', 120000);
    const failed = await readDeviceStatus(args, 'failed', 1);
    checks.push({ name: 'instrumentation-attached', result: attached && !failed ? 'PASS' : 'FAIL', detail: failed ? 'instrumentation failed' : attached ? 'status attached' : 'status missing' });
    if (!attached || failed || instrumentState.exitCode != null) throw new Error(instrumentState.exitCode != null ? `instrumentation exited ${instrumentState.exitCode}` : failed ? 'instrumentation failed before ready' : 'instrumentation did not attach');
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
    if (args.focus === 'geometry-gate') {
      await focusGeometryGate(args, checks, secrets, agent, sessionID, home);
    } else if (args.focus === 'lifecycle-gate') {
      await focusLifecycleGate(args, checks, secrets, agent, sessionID, home);
    } else if (args.focus === 'phase3') {
      await focusPhaseThree(args, checks, secrets, agent, sessionID);
    } else if (args.focus === 'manual') {
      await focusManual(args, checks, secrets, agent, sessionID);
    } else {
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

    // The stale ref must come from the last snapshot before takeover, not an earlier one.
    const latestBeforeTakeover = await call('browser.snapshot');
    const latestRef = ((latestBeforeTakeover.data && latestBeforeTakeover.data.elements) || []).find((item) => item.name === 'Apply');
    check('pre-takeover-ref', latestBeforeTakeover.ok && !!latestRef, latestBeforeTakeover.redacted);
    // Human touch on the page area takes over synchronously.
    const pagePoint = await currentPagePoint(args);
    if (pagePoint) await adb(args, ['shell', 'input', 'tap', String(pagePoint.x), String(pagePoint.y)]);
    await sleep(1500);
    const tookSnap = await call('browser.snapshot');
    const tookClick = apply ? await call('browser.click', { selector: apply.selector }) : { ok: false, redacted: '' };
    check('takeover-revokes', !!pagePoint && !tookSnap.ok && /AUTHORIZATION_REQUIRED/.test(tookSnap.redacted) && !tookClick.ok && /AUTHORIZATION_REQUIRED/.test(tookClick.redacted), `${tookSnap.redacted} | ${tookClick.redacted}`);
    await screenshot(args, args.out, 'takeover.png');
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
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
    check('second-session-no-read', !secondSnap.ok && /AUTHORIZATION_REQUIRED/.test(secondSnap.redacted), secondSnap.redacted);
    const firstTab = opened.data.tabId;
    const secondNamed = await call('browser.snapshot', { tabId: firstTab }, second);
    check('second-session-named-tab-refused', !secondNamed.ok && /AUTHORIZATION_REQUIRED/.test(secondNamed.redacted), secondNamed.redacted);
    const firstAgain = await call('browser.snapshot');
    check('first-session-still-authorized', firstAgain.ok, firstAgain.redacted);

    // Human sign-in in Manual mode: real taps submit a POST form; the cookie then survives into Agent mode.
    const loginOpen = await call('browser.open', { url: 'https://example.com/openchamber-provider-login' });
    await sleep(2500);
    const submitted = await humanSubmit(args, { tabId: loginOpen.data && loginOpen.data.tabId, ownerSessionId: sessionID, loginOpenOk: loginOpen.ok });
    let manualCookie = null;
    if (loginOpen.ok && submitted.ok && submitted.landing && submitted.landing.ready === 'complete') {
      await sleep(3000);
      await screenshot(args, args.out, 'manual-login.png');
      await tapLabel(args, 'Allow agent');
      await sleep(2500);
      manualCookie = await call('browser.snapshot');
    }
    const manualText = (manualCookie && manualCookie.data && manualCookie.data.text) || '';
    check('manual-post-js-cookie-redirect', manualLandingPass(submitted, manualCookie), manualCookie ? `url=${manualCookie.data && manualCookie.data.url} before=${submitted.beforePosts} after=${submitted.landing && submitted.landing.posts} text=${manualText}` : `open=${loginOpen.redacted} submit=${submitted.reason}`);

    // Background revokes reads.
    await adb(args, ['shell', 'input', 'keyevent', 'KEYCODE_HOME']);
    await sleep(1500);
    const background = await call('browser.snapshot');
    check('background-blocks', !background.ok && /AUTHORIZATION_REQUIRED|UNAVAILABLE|No Android browser provider/.test(background.redacted), background.redacted);
    await adb(args, ['shell', 'am', 'start', '-n', 'io.andchamber.app/.MainActivity']);
    const resumed = await untilProvider(call);
    check('resume-still-unauthorized', !resumed.ok && /AUTHORIZATION_REQUIRED/.test(resumed.redacted), resumed.redacted);
    const tabsBeforeHomeOpen = await bridgeTabCount(args);
    await adb(args, ['shell', 'input', 'keyevent', 'KEYCODE_HOME']);
    await sleep(1500);
    const pausedOpen = await call('browser.open', { url: FIXTURE });
    await adb(args, ['shell', 'am', 'start', '-n', 'io.andchamber.app/.MainActivity']);
    await untilProvider(call);
    const tabsAfterHomeOpen = await bridgeTabCount(args);
    check('home-open-creates-nothing', !pausedOpen.ok && /AUTHORIZATION_REQUIRED|UNAVAILABLE|No Android browser provider/.test(pausedOpen.redacted) && tabsBeforeHomeOpen > 0 && tabsAfterHomeOpen === tabsBeforeHomeOpen, `${pausedOpen.redacted} tabs ${tabsBeforeHomeOpen}->${tabsAfterHomeOpen}`);
    const ordinary = await ordinaryClients(port, login.headers['set-cookie'] || []);
    const during = await call('browser.snapshot');
    await sleep(1000);
    ordinary.stop();
    check('ordinary-clients-no-browser-request', ordinary.statuses.every((status) => status === 200) && ordinary.bodies.every((body) => body.includes('event-stream-ready') && !body.includes('browser-control-request')), `streams=${ordinary.bodies.length}`);
    // The phone console has no Browser rail panel; the native window is the only browser surface.
    // Prove the chat page holds no iframe and the host bridge works after a tool open.
    await deviceCommand(args, 'iframes', 'iframes');
    const iframeStatus = await readStatusJson(args, 5000, 'iframes');
    await deviceCommand(args, 'bridge', 'bridge');
    const bridgeStatus = await readStatusJson(args, 5000, 'bridge');
    check('frontend-no-iframe', iframeStatus && iframeStatus.iframes === 0 && bridgeStatus && Array.isArray(bridgeStatus.tabs) && bridgeStatus.tabs.length > 0, JSON.stringify({ iframeStatus, tabs: bridgeStatus && Array.isArray(bridgeStatus.tabs) ? bridgeStatus.tabs.length : null }));
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
    await clearStatus(args);
    await adb(args, ['shell', 'run-as io.andchamber.app touch files/openchamber-provider-hold']);
    const held = call('browser.type', { selector: heldSelector, value: 'held-value' });
    const heldStatus = await readStatusJson(args, 15000, 'held');
    const wrongClaim = await rawRequest(port, 'POST', '/api/browser-control/claim', { 'x-andchamber-browser-token': providerToken }, JSON.stringify({ requestId: heldStatus && heldStatus.requestId, connectionId: 'wrong-connection' }));
    const wrongNonce = await rawRequest(port, 'POST', '/api/browser-control/result', { 'x-andchamber-browser-token': providerToken }, JSON.stringify({ requestId: heldStatus && heldStatus.requestId, connectionId: heldStatus && heldStatus.connectionId, claimNonce: 'wrong-nonce', ok: true, data: {} }));
    const wrongResultConnection = await rawRequest(port, 'POST', '/api/browser-control/result', { 'x-andchamber-browser-token': providerToken }, JSON.stringify({ requestId: heldStatus && heldStatus.requestId, connectionId: 'wrong-connection', claimNonce: 'wrong-nonce', ok: true, data: {} }));
    check('real-pending-wrong-connection', Boolean(heldStatus && heldStatus.requestId) && /"granted":false/.test(wrongClaim.body) && /"matched":false/.test(wrongNonce.body) && /"matched":false/.test(wrongResultConnection.body), `${wrongClaim.status} ${wrongNonce.status} ${wrongResultConnection.status}`);
    await adb(args, ['shell', 'run-as io.andchamber.app rm -f files/openchamber-provider-hold']);
    const heldResult = await held;
    const heldSnap = await call('browser.snapshot');
    check('held-type-completes', heldResult.ok && ((heldSnap.data && heldSnap.data.elements) || []).some((item) => item.name === 'note' && item.value === 'held-value'), heldResult.redacted);
    const disconnectSnap = await call('browser.snapshot');
    const disconnectNote = ((disconnectSnap.data && disconnectSnap.data.elements) || []).find((item) => item.name === 'note');
    check('disconnect-ref-fresh', disconnectSnap.ok && !!disconnectNote, disconnectSnap.redacted);
    await clearStatus(args);
    await adb(args, ['shell', 'run-as io.andchamber.app touch files/openchamber-provider-hold']);
    const dropped = call('browser.type', { selector: disconnectNote ? disconnectNote.selector : 'missing-ref', value: 'after-disconnect' }).catch((error) => ({ ok: false, redacted: `transport: ${error.message}` }));
    await readStatusJson(args, 15000, 'held');
    // Stopping the relay closes every open socket, so the provider SSE really drops.
    await deviceCommand(args, 'relay-stop', 'relay-stopped');
    const droppedResult = await dropped;
    check('claim-disconnect-no-mutation', droppedResult.ok === false, droppedResult.redacted);
    await adb(args, ['shell', 'run-as io.andchamber.app rm -f files/openchamber-provider-hold']);
    await deviceCommand(args, 'relay-start', 'relay-started');
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
    await screenshot(args, args.out, 'page-a.png');
    await adb(args, ['shell', 'run-as io.andchamber.app sh -c \'printf %s "select:' + (secondOpen.data && secondOpen.data.tabId) + '" > files/openchamber-provider-command\'']);
    await readDeviceStatus(args, 'selected', 10000);
    const firstAfterSelect = await call('browser.snapshot');
    check('first-fails-after-second-selected', !firstAfterSelect.ok && /AUTHORIZATION_REQUIRED/.test(firstAfterSelect.redacted), firstAfterSelect.redacted);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const secondAfterAllow = await call('browser.snapshot', {}, second);
    check('second-marker', secondAfterAllow.ok && /^second\b/.test((secondAfterAllow.data && secondAfterAllow.data.text) || ''), secondAfterAllow.redacted);
    const firstAfterSecondAllow = await call('browser.snapshot');
    check('first-still-refused-after-second-allow', !firstAfterSecondAllow.ok && /AUTHORIZATION_REQUIRED/.test(firstAfterSecondAllow.redacted), firstAfterSecondAllow.redacted);
    await screenshot(args, args.out, 'page-b.png');
    // Back to the page session A is using now (the fixture re-opened before the hold steps).
    const tabA = reopened && reopened.data && reopened.data.tabId;
    await deviceCommand(args, `select:${tabA}`, 'selected');
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const backToA = await call('browser.snapshot');
    check('switch-back-to-a', backToA.ok && /^provider\b/.test((backToA.data && backToA.data.text) || ''), backToA.redacted);
    await screenshot(args, args.out, 'page-a-again.png');
    const dispatchSnap = await call('browser.snapshot');
    const dispatchNote = ((dispatchSnap.data && dispatchSnap.data.elements) || []).find((item) => item.name === 'note');
    await clearStatus(args);
    await adb(args, ['shell', 'run-as io.andchamber.app touch files/openchamber-provider-dhold']);
    const dispatched = dispatchNote ? call('browser.type', { selector: dispatchNote.selector, value: 'dispatched-value' }) : Promise.resolve({ ok: true, redacted: 'no dispatch ref' });
    const dispatchedStatus = await readStatusJson(args, 15000, 'dispatched');
    check('dispatched-status', Boolean(dispatchedStatus && dispatchedStatus.phase === 'dispatched'), JSON.stringify(dispatchedStatus || {}));
    const dispatchPoint = await currentPagePoint(args);
    if (dispatchPoint) await adb(args, ['shell', 'input', 'tap', String(dispatchPoint.x), String(dispatchPoint.y)]);
    await sleep(1500);
    await adb(args, ['shell', 'run-as io.andchamber.app rm -f files/openchamber-provider-dhold']);
    const dispatchedResult = await dispatched;
    // The type already ran in the page when control was taken: its outcome is unknown, not refused.
    check('dispatched-cancel-fails', dispatchedResult.ok === false && /OUTCOME_UNKNOWN/.test(dispatchedResult.redacted), dispatchedResult.redacted);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const allowAfterDispatch = await call('browser.snapshot');
    const reopenHint = (await uiNodes(args)).some((item) => /re-?open|unavailable/i.test(`${item.text} ${item.desc}`));
    check('allow-refused-until-reopen', !allowAfterDispatch.ok && (/AUTHORIZATION_REQUIRED|UNAVAILABLE/.test(allowAfterDispatch.redacted) || reopenHint), allowAfterDispatch.redacted);
    const replaced = await call('browser.open', { url: FIXTURE });
    await sleep(2500);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const reopenedAfterDispatch = await call('browser.snapshot');
    check('reopen-after-dispatched-cancel', replaced.ok && reopenedAfterDispatch.ok, reopenedAfterDispatch.redacted);
    // Session revoke must start from a live grant, and go through the real phone session list without
    // the Browser tab ever having been opened (its context sync must live in the always-mounted shell).
    // The phone session list shows sessions grouped under registered projects. Register the
    // directory the sessions live in (OpenCode places them in the server's home) the way adding a
    // project in the UI does, so the list can show both chats.
    await registerProject(port, location && location.directory);
    const otherSession = await createSession(port, fixtureDir, 'oc-switch-target');
    await deviceCommand(args, `navigate:${sessionID}`, 'navigated');
    await sleep(4000);
    await untilProvider(call);
    await tapLabel(args, 'Dismiss');
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const authorized = await call('browser.snapshot');
    check('authorized-before-session-switch', authorized.ok, authorized.redacted);
    const switchSheet = await openSessionSheet(args);
    await screenshot(args, args.out, 'session-sheet.png');
    fs.writeFileSync(path.join(args.out, 'session-sheet-nodes.json'), JSON.stringify((await uiNodes(args)).filter((n) => n.text || n.desc).map((n) => `${n.text || n.desc} [${n.left},${n.top}][${n.right},${n.bottom}]`), null, 1));
    const switchRow = await tapLabelPrefix(args, 'oc-switch-target');
    await sleep(4000);
    const switched = await call('browser.snapshot');
    const switchUi = await uiNodes(args);
    const switchLog = await adb(args, ['logcat', '-d', '-s', 'AndChamberProvider:*']);
    check('session-switch-revokes', switchSheet && switchRow && !switched.ok && /AUTHORIZATION_REQUIRED/.test(switched.redacted) && !switchUi.some((item) => item.text === 'Agent control is on' || item.desc === 'Agent control is on') && /revoke reason=session-changed/.test(switchLog.stdout), `sheet=${switchSheet} row=${switchRow} ${switched.redacted}`);
    // Back to the first chat through the same list, allow again, then clear with New chat.
    await openSessionSheet(args);
    const firstRow = await tapLabelPrefix(args, 'Untitled session');
    await sleep(4000);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const regranted = await call('browser.snapshot');
    check('regrant-before-context-clear', firstRow && regranted.ok, regranted.redacted);
    // Count session-changed revokes instead of clearing logcat, so the full provider log is kept.
    const countSessionChanged = async () => ((await adb(args, ['logcat', '-d', '-s', 'AndChamberProvider:*'])).stdout.match(/revoke reason=session-changed/g) || []).length;
    const sessionChangedBefore = await countSessionChanged();
    await openSessionSheet(args);
    const newChat = await tapLabel(args, 'New chat');
    await sleep(4000);
    const cleared = await call('browser.snapshot');
    const clearUi = await uiNodes(args);
    const sessionChangedAfter = await countSessionChanged();
    check('empty-context-revokes', newChat && !cleared.ok && /AUTHORIZATION_REQUIRED/.test(cleared.redacted) && !clearUi.some((item) => item.text === 'Agent control is on' || item.desc === 'Agent control is on') && sessionChangedAfter > sessionChangedBefore, `newChat=${newChat} revokes ${sessionChangedBefore}->${sessionChangedAfter} ${cleared.redacted}`);
    const reloaded = await deviceCommand(args, 'reload', 'reloaded');
    // The reloaded console rebinds the host once its document commits; poll the bridge for it.
    let reloadBridge = null;
    let bridgeAfterReload = false;
    const reloadDeadline = Date.now() + 30000;
    while (Date.now() < reloadDeadline) {
      bridgeAfterReload = await deviceCommand(args, 'bridge', 'bridge');
      reloadBridge = await readStatusJson(args, 5000, 'bridge');
      if (reloadBridge && Array.isArray(reloadBridge.tabs)) break;
      await sleep(1000);
    }
    const reloadSnapshot = await untilProvider(call);
    check('reload-requires-allow', !reloadSnapshot.ok && /AUTHORIZATION_REQUIRED/.test(reloadSnapshot.redacted), reloadSnapshot.redacted);
    check('bridge-after-reload', reloaded && bridgeAfterReload && reloadBridge && Array.isArray(reloadBridge.tabs), JSON.stringify(reloadBridge || {}));
    await deviceCommand(args, `navigate:${sessionID}`, 'navigated');
    // Wait for the new document to commit and the host to rebind before using the entry.
    await sleep(4000);
    await untilProvider(call);
    const navDeadline = Date.now() + 20000;
    while (Date.now() < navDeadline) {
      if (await bridgeTabCount(args) >= 0) break;
      await sleep(1000);
    }
    await deviceCommand(args, 'bridge', 'bridge');
    const beforeEntryStatus = await readStatusJson(args, 5000, 'bridge');
    const idsBeforeEntry = new Set(((beforeEntryStatus && beforeEntryStatus.tabs) || []).map((tab) => tab.tabId));
    const tabsBeforeEntry = beforeEntryStatus && Array.isArray(beforeEntryStatus.tabs) ? beforeEntryStatus.tabs.length : -1;
    const openedEntry = await deviceCommand(args, `open-entry:${FIXTURE2}`, 'entry-opened');
    const entryStatus = await readStatusJson(args, 5000, 'entry-opened');
    await sleep(3000);
    await deviceCommand(args, 'bridge', 'bridge');
    const tabsAfterEntry = await readStatusJson(args, 5000, 'bridge');
    const entryTabs = tabsAfterEntry && Array.isArray(tabsAfterEntry.tabs) ? tabsAfterEntry.tabs : [];
    // The tab the entry just created: the one id that was not there before (an older tab may share the URL).
    const createdTab = entryTabs.find((tab) => !idsBeforeEntry.has(tab.tabId) && tab.url === FIXTURE2);
    const nativeWindow = await uiNodes(args);
    const iframesAfterEntry = await deviceCommand(args, 'iframes', 'iframes') ? await readStatusJson(args, 5000, 'iframes') : null;
    const entryParts = {
      opened: Boolean(openedEntry),
      oneMoreTab: tabsBeforeEntry >= 0 && entryTabs.length === tabsBeforeEntry + 1,
      // The entry records the chat page's current session; navigate:sessionID put the page there.
      owner: Boolean(createdTab && createdTab.ownerSessionId === sessionID),
      active: Boolean(createdTab && createdTab.active === true),
      window: nativeWindow.some((item) => item.text === 'Browser' || item.desc === 'Browser'),
      noIframe: Boolean(iframesAfterEntry && iframesAfterEntry.iframes === 0),
    };
    check('browser-entry-presents', Object.values(entryParts).every(Boolean), `owner=${createdTab && createdTab.ownerSessionId} expected=${sessionID} active=${createdTab && createdTab.active} ${JSON.stringify(entryParts)} tabs ${tabsBeforeEntry}->${entryTabs.length}`);
    await tapLabel(args, 'Allow agent');
    await sleep(2500);
    const entryPage = await call('browser.snapshot');
    check('browser-entry-second-page', entryPage.ok && /^second\b/.test((entryPage.data && entryPage.data.text) || ''), entryPage.redacted);

    // Phase 3: window placement, desktop layout, real mouse, enlarge/restore without reload.
    await phaseThree(args, call, check, home, { sessionID });
    }
  } catch (error) {
    checks.push({ name: 'runner', result: 'FAIL', detail: redact(error.message, secrets) });
    fs.writeFileSync(path.join(args.out, 'server-stderr.log'), redact(stderr, secrets), { mode: 0o600 });
  } finally {
    await adb(args, ['shell', 'run-as io.andchamber.app touch files/openchamber-provider-stop']);
    await adb(args, ['shell', 'rm -f /data/local/tmp/oc-provider-ui.xml']);
    await sleep(1500);
    if (logcat) { try { process.kill(logcat.pid, 'SIGTERM'); } catch (_error) { logcat.kill('SIGTERM'); } }
    fs.writeFileSync(path.join(args.out, 'instrumentation-exit.json'), `${JSON.stringify(instrumentState, null, 2)}\n`, { mode: 0o600 });
    try { process.kill(-child.pid, 'SIGTERM'); } catch (_error) { child.kill('SIGTERM'); }
    // The in-app relay stops with the instrumentation (stop file above).
    fs.rmSync(home, { recursive: true, force: true });
  }
  fs.writeFileSync(path.join(args.out, 'session-sheet-trace.json'), JSON.stringify(sheetTrace, null, 1), { mode: 0o600 });
  const providerLog = await adb(args, ['logcat', '-d', '-s', 'AndChamberProvider:*', 'OcProviderTest:*']);
  const providerLogText = providerLog.stdout.split(providerToken).join('[redacted]');
  fs.writeFileSync(path.join(args.out, 'provider-logcat.txt'), providerLogText, { mode: 0o600 });
  const sessionRevoke = checks.find((item) => item.name === 'session-switch-revokes');
  if (sessionRevoke && sessionRevoke.result === 'PASS' && !providerLogText.includes('revoke reason=session-changed')) {
    sessionRevoke.result = 'FAIL';
    sessionRevoke.detail = 'missing revoke reason=session-changed';
  }
  const reloadCheck = checks.find((item) => item.name === 'reload-requires-allow');
  if (reloadCheck && reloadCheck.result === 'PASS' && !providerLogText.includes('console document invalidated')) {
    reloadCheck.result = 'FAIL';
    reloadCheck.detail = 'missing console document invalidated';
  }
  const body = result(checks, args.focus);
  fs.writeFileSync(path.join(args.out, 'result.json'), `${JSON.stringify(body, null, 2)}\n`, { mode: 0o600 });
  process.stdout.write(`${JSON.stringify({ result: body.result, failed: body.failed })}\n`);
  process.exitCode = body.result === 'PASS' ? 0 : 1;
}

if (require.main === module) main();
function apkIdentity(file) {
  if (!fs.existsSync(file)) return { file, present: false, sha256: null };
  return { file, present: true, sha256: crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'), bytes: fs.statSync(file).size, mtimeMs: fs.statSync(file).mtimeMs };
}

function sourceDigest(repo) {
  const { execFileSync } = require('node:child_process');
  const relative = (file) => path.relative(repo, file).split(path.sep).join('/');
  try {
    const dirty = execFileSync('git', ['status', '--porcelain', '--', 'app/src', 'scripts'], { cwd: repo, encoding: 'utf8' }).trim();
    const files = [...new Set(execFileSync('git', ['ls-files', '-c', '-o', '--exclude-standard', '--', 'app/src/main', 'app/src/androidTest', 'app/src/test', 'scripts'], { cwd: repo, encoding: 'utf8' }).trim().split('\n').filter(Boolean))].sort();
    const hash = crypto.createHash('sha256');
    files.forEach((file) => hash.update(`${relative(path.join(repo, file))}\n`).update(fs.readFileSync(path.join(repo, file))));
    return { dirty: dirty.length > 0, paths: files.map((file) => relative(path.join(repo, file))), digest: hash.digest('hex') };
  } catch (_error) {
    return { dirty: null, paths: [], digest: null };
  }
}

function buildEvidence(repo, skipBuild) {
  const head = spawnSyncGit(repo);
  return {
    skipBuild: skipBuild === true,
    passAllowed: skipBuild !== true,
    runner: 'io.andchamber.app.browser.OpenChamberProviderInstrumentation',
    providerRunnerFlag: '-Pandchamber.providerRunner=true',
    git: head,
    source: sourceDigest(repo),
    apks: [
      apkIdentity(path.join(repo, 'app/build/outputs/apk/debug/app-debug.apk')),
      apkIdentity(path.join(repo, 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')),
    ].map((item) => ({ ...item, file: path.relative(repo, item.file).split(path.sep).join('/') })),
  };
}

function spawnSyncGit(repo) {
  try {
    const { execFileSync } = require('node:child_process');
    return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim();
  } catch (_error) {
    return null;
  }
}

async function probeStaleSocket(options = {}) {
  const sockets = [];
  const server = http.createServer((request, response) => {
    request.resume();
    sockets.push({ path: request.url, socket: request.socket, remote: `${request.socket.remoteAddress}:${request.socket.remotePort}` });
    response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': 20, Connection: 'close' });
    response.end('{"ok":true,"data":{}}');
    setTimeout(() => request.socket.destroy(), options.idleMs == null ? 40 : options.idleMs);
  });
  server.keepAliveTimeout = 1;
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  const phases = [];
  try {
    const first = await hostRequest(`http://127.0.0.1:${port}/open`, { method: 'POST', body: '{}', timeoutMs: 2000 });
    phases.push({ name: 'open', status: first.status, reusedSocket: first.reusedSocket, remote: `${first.socket.remoteAddress}:${first.socket.remotePort}` });
    await new Promise((resolve) => setTimeout(resolve, options.delayMs == null ? 80 : options.delayMs));
    const second = await hostRequest(`http://127.0.0.1:${port}/snapshot`, { method: 'POST', body: '{}', timeoutMs: 2000 });
    phases.push({ name: 'snapshot', status: second.status, reusedSocket: second.reusedSocket, remote: `${second.socket.remoteAddress}:${second.socket.remotePort}` });
    return { phases, requests: sockets.length, socketIds: sockets.map((item) => item.remote), reused: new Set(sockets.map((item) => item.socket)).size < sockets.length };
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

module.exports = { createRetirementPointerIO, currentRecordOf, gateSnapshotResult, parseArgs, redact, agentToolBody, childEnv, findAgentTool, classify, exactLabel, visualScale, expectedClientDelta, expectedClientPoint, cssClientToScreen, viewportApplied, postModeRefused, manualLandingPass, gestureMatches, fingerJump, checkCapture, pngSize, layoutAccepts, windowClearsComposer, focusMenu, focusPhaseThree, focusGeometryGate, createGeometryGateAdapters, runGeometryGateCases, focusLifecycleGate, createLifecycleGateAdapters, runOneLifecycleCase, lifecycleNormalise, prepareLifecycleCase, ensureNativeMouseOn, switchMouseOn, LIFECYCLE_CASES, focusRetirementGate, createRetirementGateAdapters, lastRetiredClicksOf, RETIREMENT_CASES, createCaseFence, FOCUS_VALUES, prepareGeometryCaseForTest: prepareGeometryCase, geometryPageReady, GEOMETRY_CASES, collectFocusFailure, humanSubmit, createManualAdapter, readLayoutSample, decodeEval, sourceDigest, tappable, MENU_LABELS, result, hostRequest, probeStaleSocket, buildEvidence, FIXTURE };
