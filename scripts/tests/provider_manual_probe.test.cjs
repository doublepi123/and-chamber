const { test } = require('node:test');
const assert = require('node:assert/strict');
const { runManualSubmit, LOGIN, WELCOME } = require('../lib/provider_manual_probe.cjs');

function state(over = {}) {
  const basePage = { url: LOGIN, ready: 'complete', inputValue: '', formPresent: true, posts: 0, loads: 1, clicks: 0, submits: 0 };
  return {
    phase: 'fixture-state',
    page: { ...basePage, ...(over.page || {}) },
    native: { selectedTabId: 'tab', owner: 'owner', viewHandle: 1, viewGeneration: 2, documentEpoch: 3, shown: true, pageReady: true, mouseMode: false, agentOn: false, mode: 'MANUAL', error: 'none', ...(over.native || {}) },
    postArrivals: over.postArrivals ?? 0,
    ime: null,
  };
}

function harness(script) {
  const calls = [];
  let clock = 0;
  const deps = {
    async read() { calls.push('read'); return script.read(calls); },
    async readPosts() { calls.push('posts'); return script.posts; },
    async ensureExpanded() { calls.push('expand'); return true; },
    async tap(label) { calls.push(`tap:${label}`); return script.tap(label); },
    async type(value) { calls.push(`type:${value}`); return script.type(value); },
    async restoreIfExpandedByProbe() { calls.push('restore'); return script.restore ?? true; },
    async prepareSubmit() { calls.push('prepare'); return script.prepare ? script.prepare(calls) : true; },
    async save(label, evidence) { calls.push(`save:${label}`); script.saved = evidence; },
    now: () => clock,
    async sleep(ms) { clock += ms; },
  };
  return { deps, calls };
}

const expected = { tabId: 'tab', ownerSessionId: 'owner', loginOpenOk: true };

test('time spent in window and input setup does not consume the landing observation budget', async () => {
  let phase = 'login';
  const h = harness({
    posts: 0,
    read: () => phase === 'landed'
      ? state({ page: { url: WELCOME, posts: 1 }, postArrivals: 1 })
      : state({ page: { inputFocused: true, inputValue: phase === 'typed' ? 'fixture-user' : '' }, native: { hasFocus: true } }),
    tap: (label) => {
      if (label === 'Sign in') phase = 'landed';
      return { phase: 'tapped', visible: true, hit: true, sent: true };
    },
  });
  h.deps.ensureExpanded = async () => { await h.deps.sleep(12000); return true; };
  h.deps.type = async () => { await h.deps.sleep(12000); phase = 'typed'; return true; };
  const result = await runManualSubmit(h.deps, expected);
  assert.equal(result.ok, true);
  assert.equal(result.finalState.postArrivals, 1);
  assert.equal(h.calls.filter((item) => item === 'tap:Sign in').length, 1);
});

test('one successful submit increments posts and arrivals exactly once', async () => {
  let step = 0;
  const h = harness({
    posts: 0,
    read: () => {
      step += 1;
      if (step < 4) return state({ page: { inputFocused: true }, native: { hasFocus: true } });
      if (step === 4) return state({ page: { inputValue: 'fixture-user' } });
      return state({ page: { url: WELCOME, posts: 1, formPresent: false }, postArrivals: 1 });
    },
    tap: () => ({ phase: 'tapped', visible: true, hit: true, sent: true, sx: 1, sy: 2 }),
    type: () => true,
  });
  const result = await runManualSubmit(h.deps, expected);
  assert.equal(result.ok, true);
  assert.equal(result.beforePosts, 0);
  assert.equal(result.beforeArrivals, 0);
  assert.equal(result.landing.posts, 1);
  assert.equal(result.finalState.postArrivals, 1);
  assert.deepEqual(h.calls.filter((item) => item.startsWith('tap')), ['tap:login-user', 'tap:Sign in']);
  assert.equal(h.calls.filter((item) => item.startsWith('type')).length, 1);
  assert.equal(h.calls.includes('allow'), false);
});

test('a failed precondition does not tap or type', async () => {
  const h = harness({ posts: 0, read: () => state({ native: { owner: 'other' } }), tap: () => assert.fail('tap'), type: () => assert.fail('type') });
  const result = await runManualSubmit(h.deps, expected);
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'timeout');
  assert.equal(h.calls.some((item) => item.startsWith('tap') || item.startsWith('type')), false);
});

test('a missed sign-in tap does not retry', async () => {
  let reads = 0;
  const h = harness({
    posts: 0,
    read: () => state(reads++ > 2 ? { page: { inputValue: 'fixture-user', inputFocused: true }, native: { hasFocus: true } } : { page: { inputFocused: true }, native: { hasFocus: true } }),
    tap: (label) => label === 'login-user'
      ? { phase: 'tapped', visible: true, hit: true, sent: true }
      : { phase: 'tap-missing', visible: true, hit: false, sent: false },
    type: () => true,
  });
  const result = await runManualSubmit(h.deps, expected);
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'signin-tap-miss');
  assert.equal(h.calls.filter((item) => item === 'tap:Sign in').length, 1);
});

test('focus false does not type and incomplete input keeps the last state', async () => {
  const unfocused = harness({
    posts: 0,
    read: () => state({ page: { inputFocused: false }, native: { hasFocus: false } }),
    tap: () => ({ phase: 'tapped', visible: true, hit: true, sent: true }),
    type: () => assert.fail('type'),
  });
  const missed = await runManualSubmit(unfocused.deps, expected);
  assert.equal(missed.reason, 'field-focus-timeout');
  assert.equal(missed.last.page.inputFocused, false);
  assert.equal(typeof missed.elapsedMs, 'number');

  let reads = 0;
  const partial = harness({
    posts: 0,
    read: () => {
      reads += 1;
      return reads < 4
        ? state({ page: { inputFocused: true }, native: { hasFocus: true } })
        : state({ page: { inputFocused: true, inputValue: 'fix' }, native: { hasFocus: true } });
    },
    tap: () => ({ phase: 'tapped', visible: true, hit: true, sent: true }),
    type: () => true,
  });
  const result = await runManualSubmit(partial.deps, expected);
  assert.equal(result.reason, 'input-incomplete');
  assert.equal(result.value, 'fix');
  assert.equal(result.last.page.inputValue, 'fix');
  assert.equal(result.trace.at(-1).page.inputValue, 'fix');
  assert.equal(partial.calls.filter((item) => item === 'tap:Sign in').length, 0);
});

test('shown IME backs once and a hidden IME does not', async () => {
  let backs = 0;
  const shown = harness({
    posts: 0,
    read: (calls) => calls.filter((item) => item === 'read').length < 4
      ? state({ page: { inputFocused: true }, native: { hasFocus: true } })
      : state({ page: { inputValue: 'fixture-user', inputFocused: true }, native: { hasFocus: true } }),
    tap: () => ({ phase: 'tapped', visible: true, hit: true, sent: true }),
    type: () => true,
    prepare: (calls) => { if (calls.filter((item) => item === 'prepare').length === 1) backs += 1; return true; },
  });
  const ready = await runManualSubmit(shown.deps, expected);
  assert.equal(ready.reason, 'timeout');
  assert.equal(backs, 1);
  assert.equal(shown.calls.filter((item) => item === 'tap:Sign in').length, 1);

  const hidden = harness({
    posts: 0,
    read: () => state({ page: { inputValue: 'fixture-user', inputFocused: true }, native: { hasFocus: true } }),
    tap: (label) => label === 'login-user' ? { phase: 'tapped', visible: true, hit: true, sent: true } : assert.fail('signin'),
    type: () => true,
    prepare: () => false,
  });
  const stopped = await runManualSubmit(hidden.deps, expected);
  assert.equal(stopped.reason, 'prepare-submit-failed');
  assert.equal(hidden.calls.filter((item) => item === 'tap:Sign in').length, 0);
  assert.equal(stopped.last.page.inputValue, 'fixture-user');
});

test('unchanged posts and arrivals fail even with a welcome url', async () => {
  let reads = 0;
  const h = harness({
    posts: 2,
    read: () => {
      reads += 1;
      if (reads === 4) return state({ page: { inputValue: 'fixture-user', inputFocused: true }, native: { hasFocus: true } });
      return reads < 4
        ? state({ page: { inputFocused: true }, native: { hasFocus: true } })
        : state({ page: { url: WELCOME, posts: 2, inputFocused: true }, native: { hasFocus: true }, postArrivals: 0 });
    },
    tap: () => ({ phase: 'tapped', visible: true, hit: true, sent: true }),
    type: () => true,
  });
  const result = await runManualSubmit(h.deps, expected);
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'timeout');
});
