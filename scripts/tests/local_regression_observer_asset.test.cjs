'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const ASSET = path.join(__dirname, '..', '..', 'app', 'src', 'androidTest', 'assets', 'local-regression-observer.js');
const SOURCE = fs.readFileSync(ASSET, 'utf8');
const FIXTURES = path.join(__dirname, '..', '..', 'app', 'src', 'androidTest', 'java', 'io', 'andchamber', 'app', 'browser', 'OpenChamberProviderFixtures.kt');
const FIXTURE_SOURCE = fs.readFileSync(FIXTURES, 'utf8');
const ORIGIN = 'https://example.com/openchamber-provider';

function sandbox(overrides = {}) {
  const state = {
    location: { href: overrides.href || ORIGIN },
    document: {
      readyState: overrides.ready || 'complete',
      cookie: overrides.cookie || '',
      documentElement: {
        clientWidth: overrides.clientWidth === undefined ? 412 : overrides.clientWidth,
        clientHeight: overrides.clientHeight === undefined ? 840 : overrides.clientHeight,
        scrollHeight: overrides.scrollHeight === undefined ? 3000 : overrides.scrollHeight,
      },
      getElementById: (id) => (id === 'note' && overrides.note ? overrides.note : null),
    },
    navigator: { userAgent: overrides.userAgent || 'Mozilla/5.0 (Linux; Android 14) AppleWebKit Mobile' },
    performance: { timeOrigin: overrides.timeOrigin === undefined ? 100.5 : overrides.timeOrigin },
    sessionStorage: {
      getItem: (key) => (overrides.storage && Object.prototype.hasOwnProperty.call(overrides.storage, key)
        ? overrides.storage[key]
        : null),
    },
    matchMedia: () => ({ matches: overrides.media1000 === undefined ? true : overrides.media1000 }),
    innerWidth: overrides.innerWidth === undefined ? 412 : overrides.innerWidth,
    innerHeight: overrides.innerHeight === undefined ? 840 : overrides.innerHeight,
    scrollX: 0,
    scrollY: overrides.scrollY === undefined ? 1200 : overrides.scrollY,
    devicePixelRatio: overrides.dpr === undefined ? 2.625 : overrides.dpr,
    visualViewport: overrides.visualViewport === undefined
      ? { width: 412, height: 840, offsetLeft: 0, offsetTop: 0, scale: 1, pageLeft: 0, pageTop: 0 }
      : overrides.visualViewport,
    JSON,
    Math,
    Number,
    String,
    Array,
    Object,
  };
  state.window = state;
  for (const key of ['__loads', '__posts', '__clicks', '__log', '__report', 'clicks']) {
    if (Object.prototype.hasOwnProperty.call(overrides, key)) state[key] = overrides[key];
  }
  return state;
}

function observe(overrides) {
  const value = vm.runInNewContext(SOURCE, sandbox(overrides));
  assert.equal(typeof value, 'string');
  return JSON.parse(value);
}

test('missing fixture fields stay null instead of defaulting to zero or empty text', () => {
  const out = observe({});
  assert.equal(out.url, ORIGIN);
  assert.equal(out.ready, 'complete');
  assert.equal(out.timeOrigin, 100.5);
  assert.equal(out.ua, 'mobile');
  assert.equal(out.uaMobileOrDesktop, 'mobile');
  assert.equal(out.clientWidth, 412);
  assert.equal(out.media1000, true);
  assert.equal(out.scrollY, 1200);
  assert.equal(out.sy, 1200);
  assert.equal(out.maxScrollY, 2160);
  assert.equal(out.dpr, 2.625);
  assert.equal(out.loads, null);
  assert.equal(out.posts, null);
  assert.equal(out.noteValue, null);
  assert.equal(out.clicks, null);
  assert.equal(out.wheel, null);
  assert.equal(out.releases, null);
  assert.equal(out.boxCaptured, null);
  assert.equal(out.log, null);
  assert.equal(out.report, null);
  assert.equal(out.reportError, null);
  assert.equal(out.retireCookie, false);
});

test('MOUSE fixture reads real loads/posts and decodes a double-encoded __report once', () => {
  const report = {
    w: 412,
    clicks: 2,
    wheel: 1,
    releases: 0,
    buttons: 0,
    boxCaptured: true,
    logTail: [],
  };
  const out = observe({
    href: 'https://example.com/openchamber-provider-mouse',
    __loads: 3,
    storage: { posts: '4' },
    __report: () => JSON.stringify(JSON.stringify(report)),
  });
  assert.equal(out.loads, 3);
  assert.equal(out.posts, 4);
  assert.equal(out.clicks, 2);
  assert.equal(out.wheel, 1);
  assert.equal(out.releases, 0);
  assert.equal(out.buttons, 0);
  assert.equal(out.boxCaptured, true);
  assert.deepEqual(out.report, report);
  assert.equal(out.reportError, null);
});

test('retirement fixture reports cookie presence only and keeps real pointer counters', () => {
  const out = observe({
    href: 'https://example.com/openchamber-provider-retire',
    cookie: 'oc_fixture_retire=retire-secret-42; other=1',
    __clicks: ['next', 'box'],
    __log: [{ t: 'pointerdown', p: 'mouse' }],
    __report: () => JSON.stringify({
      clicks: 2,
      wheel: 2,
      releases: 3,
      buttons: 0,
      boxCaptured: null,
      pointerId: 5,
      logTail: [{ t: 'pointerup' }],
    }),
  });
  assert.equal(out.retireCookie, true);
  assert.equal(out.clicks, 2);
  assert.equal(out.wheel, 2);
  assert.equal(out.releases, 3);
  assert.equal(out.boxCaptured, null);
  assert.equal(out.nextClicks, 1);
  assert.equal(out.boxClicks, 1);
  assert.equal(out.pointerId, 5);
  assert.equal(out.logTail.length, 1);
  assert.equal(out.log.length, 1);
  assert.ok(!JSON.stringify(out).includes('retire-secret-42'));
});

test('non-finite values are not evidence', () => {
  const out = observe({
    timeOrigin: NaN,
    dpr: Infinity,
    __loads: NaN,
    __posts: Infinity,
    __report: () => JSON.stringify({ clicks: NaN, wheel: Infinity }),
  });
  assert.equal(out.timeOrigin, null);
  assert.equal(out.dpr, null);
  assert.equal(out.loads, null);
  assert.equal(out.posts, null);
  assert.equal(out.clicks, null);
  assert.equal(out.wheel, null);
});

test('a throwing __report does not hide the other real fields', () => {
  const out = observe({
    storage: { posts: '1' },
    __loads: 2,
    __report: () => { throw new Error('report failed'); },
  });
  assert.equal(out.loads, 2);
  assert.equal(out.posts, 1);
  assert.equal(out.report, null);
  assert.equal(out.reportError, 'Error');
  assert.equal(out.clicks, null);
});

test('welcome/page initialization is read, never invented', () => {
  const out = observe({
    href: 'https://example.com/openchamber-provider-welcome',
    storage: { posts: '1', loads: '2' },
  });
  assert.equal(out.posts, 1);
  assert.equal(out.loads, null);
  assert.equal(out.noteValue, null);
  assert.equal(out.log, null);
});

function fixtureBody(name) {
  const patterns = {
    html: /fun html\(marker: String\): String = """([\s\S]*?)"""\.trimIndent\(\)/,
    login: /private val loginHtml = """([\s\S]*?)"""\.trimIndent\(\)/,
    submit: /private val submitHtml = """([\s\S]*?)"""\.trimIndent\(\)/,
    welcome: /private val welcomeHtml = """([\s\S]*?)"""\.trimIndent\(\)/,
  };
  const match = patterns[name].exec(FIXTURE_SOURCE);
  assert.ok(match, `missing fixture ${name}`);
  return match[1]
    .replace(/\$WELCOME_URL/g, 'https://example.com/openchamber-provider-welcome')
    .replace(/\$LOGIN_SUBMIT_URL/g, 'https://example.com/openchamber-provider-login/submit')
    .replace(/\$marker/g, 'provider');
}

function fixtureScripts(name) {
  return [...fixtureBody(name).matchAll(/<script>([\s\S]*?)<\/script>/g)]
    .map((match) => match[1])
    .filter((script) => !script.includes('document.write'));
}

function newFixtureView() {
  return { store: new Map(), cookie: '', redirect: null };
}

function runFixtureDocument(name, view) {
  const win = {};
  const node = {
    addEventListener() {},
    setPointerCapture() {},
    hasPointerCapture() { return false; },
    offsetLeft: 0,
    offsetTop: 0,
    style: {},
  };
  const document = {
    cookie: view.cookie,
    documentElement: { clientWidth: 412, clientHeight: 840 },
    getElementById: () => node,
    querySelector: () => node,
    querySelectorAll: () => [],
    addEventListener() {},
  };
  const location = {
    href: 'https://example.com/openchamber-provider',
    replace(url) { view.redirect = url; },
  };
  const visualViewport = { width: 412, height: 840, offsetLeft: 0, offsetTop: 0, scale: 1 };
  const storage = {
    getItem: (key) => (view.store.has(key) ? view.store.get(key) : null),
    setItem: (key, value) => view.store.set(key, String(value)),
    removeItem: (key) => view.store.delete(key),
  };
  const sandbox = {
    window: win,
    document,
    location,
    sessionStorage: storage,
    navigator: { userAgent: 'Mozilla/5.0 (Linux; Android 14) Mobile' },
    performance: { timeOrigin: 1 },
    visualViewport,
    innerWidth: 412,
    innerHeight: 840,
    devicePixelRatio: 2,
    scrollX: 0,
    scrollY: 0,
    addEventListener() {},
    matchMedia: () => ({ matches: false }),
    JSON,
    Math,
    Number,
    String,
    Array,
    Object,
    isFinite,
  };
  win.addEventListener = () => {};
  win.document = document;
  win.location = location;
  win.visualViewport = visualViewport;
  win.innerWidth = 412;
  win.innerHeight = 840;
  win.devicePixelRatio = 2;
  win.scrollX = 0;
  win.scrollY = 0;
  vm.createContext(sandbox);
  for (const script of fixtureScripts(name)) vm.runInContext(script, sandbox);
  view.cookie = document.cookie;
  return {
    loads: win.__loads,
    posts: win.__posts,
    clicks: win.clicks,
    cookie: document.cookie,
    redirect: view.redirect,
  };
}

test('ORIGIN counter initialization is real and sticky per view', () => {
  const view = newFixtureView();
  const first = runFixtureDocument('html', view);
  assert.equal(first.loads, 1);
  assert.equal(first.posts, 0);
  assert.equal(view.store.get('loads'), '1');
  assert.equal(view.store.get('posts'), '0');
  const second = runFixtureDocument('html', view);
  assert.equal(second.loads, 2);
  assert.equal(second.posts, 0);
  const other = runFixtureDocument('html', newFixtureView());
  assert.equal(other.loads, 1, 'a new WebView sessionStorage starts independent');
  assert.equal(other.posts, 0);
});

test('LOGIN initializes its own loads/posts without inventing a POST', () => {
  const view = newFixtureView();
  const first = runFixtureDocument('login', view);
  assert.equal(first.loads, 1);
  assert.equal(first.posts, 0);
  const second = runFixtureDocument('login', view);
  assert.equal(second.loads, 2);
  assert.equal(second.posts, 0);
});

test('the real POST increments posts once and welcome keeps it', () => {
  const view = newFixtureView();
  const opened = runFixtureDocument('html', view);
  assert.equal(opened.loads, 1);
  assert.equal(opened.posts, 0);
  const submitted = runFixtureDocument('submit', view);
  assert.equal(view.store.get('posts'), '1');
  assert.match(submitted.cookie, /oc_fixture_login=signed-in-42/);
  assert.equal(submitted.redirect, 'https://example.com/openchamber-provider-welcome');
  const landed = runFixtureDocument('welcome', view);
  assert.equal(landed.posts, 1, 'welcome must read the real post count, not a constant');
  assert.equal(landed.loads, 2);
  assert.match(landed.cookie, /oc_fixture_login=signed-in-42/);
  const again = runFixtureDocument('welcome', view);
  assert.equal(again.posts, 1);
  assert.equal(again.loads, 3);
  const fresh = runFixtureDocument('welcome', newFixtureView());
  assert.equal(fresh.posts, 0, 'a fresh view with no POST is not reported as post=1');
  assert.equal(fresh.loads, 1);
});
