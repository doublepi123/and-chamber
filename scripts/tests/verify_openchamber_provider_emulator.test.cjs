'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const harness = require('../verify_openchamber_provider_emulator.cjs');

test('fixture submit and welcome scripts share the stored post count', () => {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const source = fs.readFileSync(require('node:path').join(__dirname, '../../app/src/androidTest/java/io/andchamber/app/browser/OpenChamberProviderFixtures.kt'), 'utf8');
  const block = (name) => source.match(new RegExp(`private val ${name} = """([\\s\\S]*?)"""\\.trimIndent\\(\\)`))[1].replace('$WELCOME_URL', 'https://example.com/openchamber-provider-welcome');
  const script = (name) => block(name).match(/<script>([\s\S]*?)<\/script>/g).map((item) => item.replace(/<\/?script>/g, '')).join('\n');
  const storage = new Map();
  const landing = { url: '', posts: 0, loads: 0, dom: '' };
  const context = {
    document: { cookie: '', write(value) { landing.dom += value; } },
    sessionStorage: { getItem: (key) => storage.get(key) || null, setItem: (key, value) => storage.set(key, String(value)) },
    location: { replace(url) { landing.url = url; } },
    window: {},
    landing,
    Number, String,
  };
  context.window = context;
  vm.runInNewContext(script('submitHtml'), context);
  assert.equal(storage.get('posts'), '1');
  assert.equal(landing.url, 'https://example.com/openchamber-provider-welcome');
  vm.runInNewContext(script('welcomeHtml'), context);
  assert.equal(context.window.__posts, 1);
  assert.equal(script('welcomeHtml').includes("document.write(sessionStorage.getItem('posts')||'0')"), true);
  vm.runInNewContext("landing.dom += String(sessionStorage.getItem('posts')||'0')", context);
  assert.equal(landing.dom.endsWith('1'), true);
  const firstLoads = context.window.__loads;
  vm.runInNewContext(script('welcomeHtml'), context);
  assert.equal(context.window.__loads, firstLoads + 1);
  const fresh = { document: context.document, location: context.location, landing, Number, String, window: {}, sessionStorage: { getItem: () => null, setItem() {} } };
  fresh.window = fresh;
  delete fresh.window.__posts;
  vm.runInNewContext(script('welcomeHtml').replace("window.__posts = Number(sessionStorage.getItem('posts')||0);", ''), fresh);
  assert.equal(fresh.window.__posts, undefined);
});

test('serial is emulator only', () => {
  assert.throws(() => harness.parseArgs(['--serial', 'device', '--out', '/tmp/out']), /emulator/);
});

test('agent-tool body requires a real session', () => {
  const body = JSON.parse(harness.agentToolBody('browser.snapshot', { selector: '[data-andchamber-ref="abc"]' }, 'ses_123'));
  assert.equal(body.tool, 'openchamber_web');
  assert.equal(body.sessionID, 'ses_123');
  assert.throws(() => harness.agentToolBody('browser.open', {}, 'nope'), /real session/);
});

test('child env lookup rejects a process outside the launched tree', () => {
  const files = {
    '/proc/77/status': 'PPid:\t42\n',
    '/proc/77/environ': Buffer.from('OPENCHAMBER_AGENT_TOOL_URL=http://127.0.0.1:9/api/openchamber/agent-tool\0OPENCHAMBER_AGENT_TOOL_TOKEN=secret-token\0'),
  };
  const read = (file) => files[String(file)];
  assert.equal(harness.findAgentTool(42, () => ['77'], read).token, 'secret-token');
  assert.throws(() => harness.findAgentTool(7, () => ['77'], read), /agent-tool env/);
});

test('click-count 10 is not accepted as 1 and scroll requires a larger offset', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  assert.equal(harness.exactLabel('click-count:1', 'click-count'), 1);
  assert.equal(harness.exactLabel('click-count:10', 'click-count'), 10);
  assert.notEqual(harness.exactLabel('click-count:10', 'click-count'), 1);
  assert.equal(harness.exactLabel('scroll-y:0', 'scroll-y'), 0);
  assert.equal(harness.exactLabel('scroll-y:40', 'scroll-y') > harness.exactLabel('scroll-y:0', 'scroll-y'), true);
});

test('isolation failures must name AUTHORIZATION_REQUIRED', () => {
  const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '../verify_openchamber_provider_emulator.cjs'), 'utf8');
  for (const name of ['second-session-no-read', 'second-session-named-tab-refused', 'background-blocks', 'first-fails-after-second-selected', 'first-still-refused-after-second-allow', 'session-switch-revokes']) {
    const line = source.split('\n').find((item) => item.includes(`check('${name}'`));
    assert.match(line || '', /AUTHORIZATION_REQUIRED/);
  }
  assert.match(source, /latestBeforeTakeover = await call\('browser\.snapshot'\)/);
  assert.match(source, /disconnectSnap = await call\('browser\.snapshot'\)/);
  assert.match(source, /event-stream-ready/);
  // Phone layout has no Browser rail; the check proves no iframe plus a working host bridge instead.
  assert.match(source, /check\('frontend-no-iframe'/);
  assert.match(source, /iframes === 0/);
  assert.match(source, /openchamber-provider-dhold/);
  assert.match(source, /check\('session-switch-revokes'/);
  assert.match(source, /check\('empty-context-revokes'/);
  assert.match(source, /check\('browser-entry-presents'/);
  assert.doesNotMatch(source, /present:\$\{FIXTURE2\}/);
  const instrumentation = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../app/src/androidTest/java/io/andchamber/app/browser/OpenChamberProviderInstrumentation.kt'), 'utf8');
  assert.match(instrumentation, /AndChamberBrowserHost\.select/);
  assert.match(instrumentation, /val sessionId = text\.removePrefix\("context:"\)/);
  assert.match(instrumentation, /mobile-workspace-tab-browser/);
  assert.match(instrumentation, /android-browser-open/);
  assert.doesNotMatch(instrumentation, /ownerSessionId:'provider-emulator'/);
});

test('refused requests never reach the server and incomplete responses fail', async () => {
  const http = require('node:http');
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  let received = 0;
  let followed = 0;
  const sockets = [];
  const server = http.createServer((request, response) => {
    received += 1;
    sockets.push(`${request.socket.remoteAddress}:${request.socket.remotePort}`);
    request.resume();
    if (request.url === '/redirect') {
      response.writeHead(302, { Location: '/followed', 'Content-Length': 0 });
      response.end();
      return;
    }
    if (request.url === '/followed') { followed += 1; response.end('no'); return; }
    if (request.url === '/abort') {
      response.writeHead(200, { 'Content-Length': 20 });
      response.write('{"ok":');
      response.destroy();
      return;
    }
    response.writeHead(200, { 'Content-Length': 11, Connection: 'close' });
    response.end('{"ok":true}');
    setTimeout(() => request.socket.destroy(), 15);
  });
  server.keepAliveTimeout = 1;
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const beforeRetry = received;
    await assert.rejects(() => harness.hostRequest(`${base}/mutate`, { method: 'POST', body: '{}', retry: true, timeoutMs: 1000 }), /retry refused/);
    assert.equal(received, beforeRetry);
    const beforeRedirect = received;
    await assert.rejects(() => harness.hostRequest(`${base}/redirect`, { method: 'GET', timeoutMs: 1000 }), /redirect refused/);
    assert.equal(followed, 0);
    assert.equal(received, beforeRedirect + 1);
    const started = Date.now();
    await assert.rejects(() => harness.hostRequest(`${base}/abort`, { method: 'POST', body: '{}', timeoutMs: 1000 }), /response aborted|request failed ECONNRESET/);
    assert.equal(Date.now() - started < 1000, true);
    const first = await harness.hostRequest(`${base}/one`, { method: 'POST', body: '{}', timeoutMs: 1000 });
    await new Promise((resolve) => setTimeout(resolve, 40));
    const second = await harness.hostRequest(`${base}/two`, { method: 'POST', body: '{}', timeoutMs: 1000 });
    assert.equal(first.reusedSocket, false);
    assert.equal(second.reusedSocket, false);
    assert.notEqual(sockets.at(-1), sockets.at(-2));
    await assert.rejects(() => harness.hostRequest('ftp://127.0.0.1/nope', { method: 'POST', retry: true }), /protocol refused|retry refused/);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('false geometry and mode evidence cannot pass', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  assert.equal(harness.viewportApplied({ ok: false, redacted: 'unsupported' }, { ua: 'desktop', w: 1024 }, 'desktop', 1024), false);
  assert.equal(harness.viewportApplied({ ok: true, redacted: '' }, { ua: 'mobile', w: 400 }, 'desktop', 1024), false);
  assert.equal(harness.viewportApplied({ ok: true, redacted: '' }, { ua: 'desktop', w: 1024 }, 'desktop', 1024), true);
  assert.equal(harness.postModeRefused(null, null), false);
  assert.equal(harness.postModeRefused({ url: 'a', ua: 'desktop', loads: 2, posts: 1 }, { url: 'a', ua: 'desktop', loads: 2, posts: 1, reopenRequired: false }), false);
  assert.equal(harness.postModeRefused({ url: 'a', ua: 'desktop', loads: 2, posts: 1 }, { url: 'a', ua: 'desktop', loads: 3, posts: 1, reopenRequired: true }), false);
  assert.equal(harness.postModeRefused({ url: 'a', ua: 'desktop', loads: 2, posts: 1 }, { url: 'a', ua: 'desktop', loads: 2, posts: 1, reopenRequired: true }), false);
  const visual = { width: 819, height: 600, offsetLeft: 12, offsetTop: 4 };
  const before = { rect: { viewHandle: 7, pointerUi: { geometryReady: true, viewHandle: 7, geometryEpoch: 3, pointerViewX: 0, pointerViewY: 0 } }, viewHandle: 7, visual, physicalWidth: 819 };
  const after = { rect: { viewHandle: 7, pointerUi: { geometryReady: true, viewHandle: 7, geometryEpoch: 3, pointerViewX: 240, pointerViewY: 80 } }, viewHandle: 7, visual, physicalWidth: 819 };
  const end = { x: harness.expectedClientPoint(240, 12, 819, 819), y: harness.expectedClientPoint(80, 4, 819, 819) };
  assert.equal(harness.gestureMatches(before, after, end, 15), true);
  assert.equal(harness.gestureMatches(before, after, { x: end.x + 40, y: end.y }, 15), false);
  assert.equal(harness.gestureMatches(before, { ...after, rect: { ...after.rect, pointerUi: { ...after.rect.pointerUi, geometryEpoch: 4 } } }, end, 15), false);
  const staleBefore = { rect: { viewHandle: 7, pointerUi: { geometryReady: true, viewHandle: 7, geometryEpoch: 2, pointerViewX: 0, pointerViewY: 0 } }, viewHandle: 7, visual, physicalWidth: 819 };
  assert.equal(harness.gestureMatches(staleBefore, after, end, 15), false);
  assert.equal(harness.postModeRefused({ url: 'https://example.com/openchamber-provider-welcome', ua: 'desktop', loads: 2, posts: 0 }, { url: 'https://example.com/openchamber-provider-welcome', ua: 'desktop', loads: 2, posts: 0, reopenRequired: true }), false);
  assert.equal(harness.postModeRefused({ url: 'https://example.com/openchamber-provider-welcome', ua: 'desktop', loads: 2, posts: 1 }, { url: 'https://example.com/openchamber-provider-welcome', ua: 'desktop', loads: 2, posts: 1, reopenRequired: true }), true);
  const titleOnly = [{ text: 'Browser', bottom: 100, left: 0, top: 0, right: 200 }, { text: 'Plan, build, or ask', top: 300, bottom: 340, left: 0, right: 200 }];
  assert.equal(harness.windowClearsComposer(titleOnly), false);
  assert.equal(harness.windowClearsComposer([...titleOnly, { text: 'Allow agent', top: 180, bottom: 360, left: 0, right: 200 }]), false);
  assert.equal(harness.checkCapture({ ok: true, data: { path: 'shot.png', css: { width: 1024, height: 800 }, png: { width: 819, height: 600 } } }, { innerWidth: 819, innerHeight: 600, visualViewport: visual }, () => null), false);
  assert.equal(harness.fingerJump({ x: 10 }, 300), false);
  assert.equal(harness.checkCapture({ ok: true, data: { css: null } }, { width: 819, height: 600 }, () => null), false);
  assert.equal(harness.checkCapture({ ok: true, data: { css: { width: 1, height: 1 } } }, { width: 1, height: 1 }, () => null), false);
  assert.equal(harness.expectedClientPoint(240, 12, 819, 1024), 12 + 240 / (819 / 1024));
  assert.notEqual(harness.expectedClientPoint(240, 12, 819, 1024), 12 + 240 / (819 / 1024) / 2.625);
});

test('skipBuild cannot become a pass and geometry tolerance stays strict', () => {
  const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '../verify_openchamber_provider_emulator.cjs'), 'utf8');
  const evidence = harness.buildEvidence('/no/such/repo', true);
  assert.equal(evidence.passAllowed, false);
  assert.match(source, /fresh-build-identity/);
  assert.equal(harness.expectedClientDelta(240, 819, 819), 240);
  assert.equal(Math.round(harness.expectedClientDelta(240, 819, 1024)), 300);
  assert.equal(harness.expectedClientPoint(240, 12, 819, 1024), 12 + 240 / (819 / 1024));
  assert.equal(harness.visualScale(819, 819) === 2.625, false);
  assert.match(source, /checkCapture\(captured, measured, \(\) => captureBytes\)/);
  assert.match(source, /OUTCOME_UNKNOWN/);
  const fixture = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../app/src/androidTest/java/io/andchamber/app/browser/OpenChamberProviderFixtures.kt'), 'utf8');
  assert.match(fixture, /innerWidth/);
  assert.match(fixture, /visualViewport/);
  assert.match(fixture, /devicePixelRatio/);
});

test('tap candidates reject zero-size and offscreen nodes', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const screen = { right: 1080, bottom: 2400 };
  assert.equal(harness.tappable({ enabled: true, visible: true, left: 957, top: 1265, right: 970, bottom: 1308 }, screen), true);
  assert.equal(harness.tappable({ enabled: false, visible: true, left: 10, top: 10, right: 20, bottom: 20 }, screen), false);
  assert.equal(harness.tappable({ enabled: true, visible: true, left: 10, top: 10, right: 10, bottom: 20 }, screen), false);
  assert.equal(harness.tappable({ enabled: true, visible: true, left: -4, top: 10, right: 20, bottom: 20 }, screen), false);
  assert.deepEqual(harness.MENU_LABELS.More, ['More', '更多']);
});

test('css client mapping uses visual width and does not add scroll twice', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const mobile = harness.cssClientToScreen(10, 20, 819, 819 / 2.625, 100, 40, 0, 0);
  assert.equal(mobile.x, 10 + 100 * 2.625);
  assert.equal(mobile.scale, 2.625);
  const desktop = harness.cssClientToScreen(10, 20, 819, 1024, 100, 40, 12, 4);
  assert.equal(desktop.x, 10 + (100 - 12) * (819 / 1024));
  assert.equal(desktop.y, 20 + (40 - 4) * (819 / 1024));
  assert.notEqual(desktop.x, 10 + (100 - 12 + 30) * (819 / 1024));
});

test('orientation and human submit do not use settings permission or enter', () => {
  const source = require('node:fs').readFileSync(require('node:path').join(__dirname, '../verify_openchamber_provider_emulator.cjs'), 'utf8');
  const instrumentation = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../app/src/androidTest/java/io/andchamber/app/browser/OpenChamberProviderInstrumentation.kt'), 'utf8');
  assert.doesNotMatch(instrumentation, /WRITE_SETTINGS|Settings\.System\.putInt/);
  assert.match(instrumentation, /requestedOrientation/);
  assert.equal(source.includes('input keyevent 66'), false);
  assert.doesNotMatch(source, /form\.top \+ 40/);
});

test('ready wait does not inject a gesture before geometry is stable', async () => {
  const samples = [{ pageReady: false }, { pageReady: true, pointerUi: { geometryReady: true, viewHandle: 7 }, viewHandle: 7, w: 1, h: 1 }, { pageReady: true, pointerUi: { geometryReady: true, viewHandle: 7 }, viewHandle: 7, w: 1, h: 1 }];
  let reads = 0;
  const ready = async () => samples[Math.min(reads++, samples.length - 1)];
  let stable = 0;
  let last = null;
  while (reads < 4) {
    const native = await ready();
    const same = last && last.viewHandle === native.viewHandle && last.w === native.w;
    stable = same && native.pageReady && native.pointerUi.geometryReady ? stable + 1 : 0;
    last = native;
    if (stable >= 2) break;
  }
  assert.equal(stable >= 2, true);
  assert.equal(reads, 4);
  const never = [{ pageReady: false }];
  assert.equal(never.some((item) => item.pageReady), false);
});

test('focus failure collects diagnostics before cleanup', async () => {
  const fs = require('node:fs');
  const os = { tmpdir: () => '/tmp/opencode' };
  const path = require('node:path');
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  fs.mkdirSync('/tmp/opencode', { recursive: true });
  const out = fs.mkdtempSync(path.join('/tmp/opencode', 'focus-failure-'));
  const order = [];
  await harness.focusPhaseThree({ out }, [], [], null, 'ses_test', {
    call: async () => ({ ok: false, redacted: 'open failed' }),
    collect: async () => { order.push('diagnostic'); return { saved: true }; },
    record: () => order.push('recorded'),
  });
  order.push('cleanup');
  assert.deepEqual(order, ['diagnostic', 'recorded', 'cleanup']);
  fs.rmSync(out, { recursive: true, force: true });
});

test('focus menu opens Mouse once through the production labels', async () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const labels = ['More', 'Computer layout', 'Confirm', 'More', 'Mouse'];
  let index = 0;
  const menu = await harness.focusMenu({ waitForLabel: async (label) => ({ found: label === labels[index] && Boolean(index += 1), attempts: 1 }) }, Date.now() + 1000);
  assert.deepEqual(menu.trace, labels);
  assert.equal(menu.trace.filter((label) => label === 'Mouse').length, 1);
  const skipped = await harness.focusMenu({ waitForLabel: async (label) => ({ found: label !== 'Mouse', attempts: 1, failedLabel: label }) }, Date.now() + 1000);
  assert.equal(skipped.trace.includes('Mouse'), false);
});

test('desktop metric uses root client width, not an overflowing innerWidth', () => {
  const report = { ua: 'mobile', w: 1024, clientWidth: 390, media1000: false };
  assert.equal(report.clientWidth >= 1000 && report.media1000 === true && report.ua === 'desktop', false);
  const desktop = { ua: 'desktop', clientWidth: 1024, media1000: true };
  assert.equal(desktop.clientWidth >= 1000 && desktop.clientWidth <= 1048 && desktop.media1000 === true && desktop.ua === 'desktop', true);
});

test('focused phase3 report cannot claim a full pass', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const report = harness.result([{ name: 'focus-wheel', result: 'PASS', detail: 'wheel' }], 'phase3');
  assert.equal(report.scope, 'phase3-focused-probe');
  assert.equal(report.phaseComplete, false);
  assert.equal(report.result, 'FAIL');
  assert.throws(() => harness.parseArgs(['--serial', 'emulator-5580', '--out', '/tmp/out', '--focus', 'other']), /phase3|geometry-gate/);
  const gate = harness.result([{ name: 'geometry-b1-dispatch', result: 'FAIL', detail: 'held' }], 'geometry-gate');
  assert.equal(gate.scope, 'geometry-race-targeted');
  assert.equal(gate.phaseComplete, false);
  assert.equal(gate.result, 'FAIL');
  assert.deepEqual(gate.failed, ['geometry-b1-dispatch']);
});

test('landing, capture, and tight window use the runner contract', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const submitted = { ok: true, value: 'fixture-user', beforePosts: 1, landing: { posts: 2, url: 'https://example.com/openchamber-provider-welcome' } };
  const snap = { ok: true, data: { url: 'https://example.com/openchamber-provider-welcome', text: 'cookie:oc_fixture_login=signed-in-42 posts:2' } };
  assert.equal(harness.manualLandingPass(submitted, snap), true);
  assert.equal(harness.manualLandingPass({ ...submitted, beforePosts: 2, landing: { ...submitted.landing, posts: 2 } }, snap), false);
  assert.equal(harness.manualLandingPass({ ...submitted, beforePosts: null }, snap), false);
  assert.equal(harness.manualLandingPass(submitted, { ok: true, data: { url: 'https://example.com/openchamber-provider-login', text: snap.data.text } }, 1), false);
  assert.equal(harness.manualLandingPass({ ok: true, value: 'fixture-user', landing: { posts: 1 } }, snap, 1), false);
  const measured = { innerWidth: 1024, innerHeight: 800, devicePixelRatio: 2.625, visualViewport: { width: 819, height: 600 } };
  const captured = { ok: true, data: { path: 'shot.png', css: { width: 1024, height: 800, visualWidth: 819, visualHeight: 600, dpr: 2.625 }, png: { width: 1024, height: 800 } } };
  const png = Buffer.alloc(24);
  png.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]); png.writeUInt32BE(1024, 16); png.writeUInt32BE(800, 20);
  const real = { ...captured, data: { ...captured.data, css: { ...captured.data.css, dpr: 2.625 } } };
  delete real.data.css.devicePixelRatio;
  assert.equal(harness.checkCapture(real, measured, () => png), true);
  assert.equal(harness.checkCapture({ ...real, data: { ...real.data, css: { width: 1 } } }, measured, () => png), false);
  assert.equal(harness.checkCapture(real, measured, () => null), false);
  const tight = [{ text: 'Browser', top: 10, bottom: 40, left: 0, right: 100 }, { text: 'Allow agent', top: 200, bottom: 360, left: 0, right: 100 }, { text: 'Plan, build, or ask', top: 300, bottom: 340, left: 0, right: 100 }, { text: 'The keyboard covers the browser', top: 0, bottom: 10, left: 0, right: 10 }];
  assert.equal(harness.windowClearsComposer(tight), false);
});

test('layout acceptance converts CSS composer top with the measured width scale', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const portrait = { validityReason: 'accepted', fallback: false, consoleGeneration: 3, nativeGeneration: 0, mainWebView: { x: 0, y: 136, width: 1080, height: 2201 }, usableFrame: { left: 0, top: 0, right: 1080, bottom: 2400 }, measurement: { top: 683.2380981445312, bottom: 839.2380981445312, viewportWidth: 412.19049072265625, viewportHeight: 839.2380981445312, nativeWidth: 1080, nativeHeight: 2201, consoleGeneration: 3, nativeGeneration: 0, visible: true } };
  assert.equal(harness.layoutAccepts(portrait, { left: 176, top: 1224, right: 1038, bottom: 1853 }, null).pass, true);
  const landscape = { validityReason: 'accepted', fallback: false, consoleGeneration: 6, nativeGeneration: 0, mainWebView: { x: 136, y: 74, width: 2264, height: 943 }, usableFrame: { left: 0, top: 0, right: 2400, bottom: 1080 }, measurement: { top: 48, bottom: 360, viewportWidth: 863.2380981445312, viewportHeight: 360, nativeWidth: 2264, nativeHeight: 943, consoleGeneration: 6, nativeGeneration: 0, visible: true } };
  assert.equal(harness.layoutAccepts(landscape, { left: 150, top: 100, right: 2200, bottom: 1000 }, null).pass, false);
  assert.equal(harness.layoutAccepts(landscape, null, { left: 200, top: 120, right: 900, bottom: 220 }, { agentOn: false }).pass, true);
  assert.equal(harness.layoutAccepts(landscape, null, { left: 200, top: 120, right: 900, bottom: 220 }, { agentOn: true }).pass, false);
  assert.equal(harness.layoutAccepts({ ...landscape, measurement: { ...landscape.measurement, nativeWidth: 1 } }, null, null).reason, 'dimension-mismatch');
  assert.equal(harness.layoutAccepts({ ...landscape, measurement: null }, null, null).reason, 'measurement-missing');
});

test('layout sample decodes eval and keeps one native read', async () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const commands = [];
  const sample = await harness.readLayoutSample({}, {
    command: async (text, phase) => { commands.push(`${phase}:${text}`); return true; },
    read: async (phase) => phase === 'native-state'
      ? { phase, orientation: 'landscape', shown: true }
      : phase === 'composer-layout'
        ? { phase, validityReason: 'accepted' }
        : { phase: 'eval', value: '"{\\"ready\\":\\"complete\\",\\"timeOrigin\\":123}"' },
    nodes: async () => [],
  });
  assert.deepEqual(commands.map((item) => item.split(':')[0]), ['native-state', 'composer-layout', 'eval']);
  assert.equal(sample.orientation, 'landscape');
  assert.equal(sample.console.ready, 'complete');
  assert.equal(sample.console.timeOrigin, 123);
});

test('manual adapter expands only when needed and copies UA from the same document', async () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  const taps = [];
  const typed = [];
  let expanded = false;
  let clock = 0;
  const native = { documentEpoch: 4, selectedTabId: 'tab-1', owner: 'session-1', pageReady: true, shown: true, mouseMode: false };
  const page = { url: 'https://example.com/openchamber-provider-login', ready: 'complete', formPresent: true, inputValue: '', posts: 0 };
  const io = {
    nodes: async () => expanded
      ? [{ text: 'Restore', desc: 'Restore', enabled: true, visible: true, left: 10, top: 10, right: 40, bottom: 40, cx: 20, cy: 20 }]
      : [{ text: 'Enlarge', desc: 'Enlarge', enabled: true, visible: true, left: 10, top: 10, right: 40, bottom: 40, cx: 20, cy: 20 }],
    tap: async (label) => { taps.push(label); if (label === 'Sign in') { page.url = 'https://example.com/openchamber-provider-welcome'; page.posts = 1; } return { phase: 'tapped', visible: true, hit: true, sent: true }; },
    adb: async (command) => {
      if (command[2] === 'tap') {
        taps.push(expanded ? 'Restore' : 'Enlarge');
        expanded = !expanded;
      } else if (command[2] === 'text') typed.push(command.at(-1));
      return { code: 0 };
    },
    read: async () => ({ phase: 'fixture-state', page: { ...page, inputFocused: true, inputValue: typed.join('') }, native: { ...native, hasFocus: true }, postArrivals: page.url.endsWith('welcome') ? 1 : 0 }),
    readPosts: async () => 0,
    pageEval: async () => 'desktop',
    native: async () => ({ phase: 'native-state', shown: true }),
    stable: async () => ({ ok: true, rects: [{ x: 1, y: 2, w: 3, h: 4 }, { x: 1, y: 2, w: 3, h: 4 }] }),
    prepareSubmit: async () => true,
    save: async () => {},
    now: () => clock,
    sleep: async (ms) => { clock += ms; },
  };
  const submitted = await harness.humanSubmit({ out: '/tmp' }, { tabId: 'tab-1', ownerSessionId: 'session-1', loginOpenOk: true }, io);
  assert.equal(submitted.reason, 'submitted');
  assert.equal(submitted.landing.documentEpoch, 4);
  assert.equal(submitted.ok, true);
  assert.equal(submitted.landing.ua, 'desktop');
  assert.deepEqual(typed, ['fixture', '-', 'user']);
  assert.equal(taps.filter((item) => item === 'Restore').length, 1);
});

test('missing UI loop cannot pass and tokens are redacted', () => {
  const report = harness.result([{ name: 'ui-grant-actions', result: 'FAIL', detail: 'not run' }]);
  assert.equal(report.result, 'FAIL');
  assert.equal(harness.redact('Bearer secret-token', ['secret-token']).includes('secret-token'), false);
});
