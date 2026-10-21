const LOGIN = 'https://example.com/openchamber-provider-login';
const WELCOME = 'https://example.com/openchamber-provider-welcome';
const BUDGET_MS = 20000;
const TOTAL_BUDGET_MS = 120000;

async function runManualSubmit(deps, expected) {
  const trace = [];
  const taps = [];
  const save = async (label, evidence) => deps.save(label, evidence);
  if (!expected.loginOpenOk) {
    const failed = fail('login-open-failed', { trace, taps });
    await save('precondition', failed);
    return failed;
  }
  const started = deps.now();
  const ready = await waitFor(deps, started, (state) => loginReady(state, expected));
  if (!ready.ok) {
    const failed = fail(ready.reason, { trace: ready.trace, taps, last: ready.last });
    await save('precondition', failed);
    return failed;
  }
  const beforePosts = await deps.readPosts();
  const beforeArrivals = ready.state.postArrivals;
  if (!Number.isInteger(beforePosts) || beforePosts < 0 || !Number.isInteger(beforeArrivals) || beforeArrivals < 0) {
    const failed = fail('baseline-invalid', { trace: ready.trace, taps, beforePosts, beforeArrivals });
    await save('baseline', failed);
    return failed;
  }
  const expanded = await deps.ensureExpanded();
  if (expanded !== true) return fail('expand-failed', { trace: ready.trace, taps, beforePosts, beforeArrivals });
  const field = await deps.tap('login-user');
  taps.push(field);
  if (!acceptedTap(field)) {
    const current = await deps.read();
    const failed = fail('field-tap-miss', { trace: [current], last: current, taps, beforePosts, beforeArrivals, elapsedMs: deps.now() - started });
    await save('field-tap', failed);
    return failed;
  }
  const focused = await waitFor(deps, started, (state) => state.page?.inputFocused === true && state.native?.hasFocus === true);
  if (!focused.ok) {
    const failed = evidence('field-focus-timeout', started, deps, { trace: focused.trace, last: focused.last, taps, beforePosts, beforeArrivals });
    await save('input', failed);
    return failed;
  }
  const typed = await deps.type('fixture-user');
  if (typed !== true) {
    const failed = evidence('type-failed', started, deps, { taps, beforePosts, beforeArrivals, type: typed });
    await save('input', failed);
    return failed;
  }
  const value = await waitFor(deps, started, (state) => state.page?.inputValue === 'fixture-user');
  if (!value.ok) {
    const failed = evidence('input-incomplete', started, deps, { trace: value.trace, state: value.state, last: value.last, taps, beforePosts, beforeArrivals, value: value.last?.page?.inputValue });
    await save('input', failed);
    return failed;
  }
  await save('input', { ok: true, value: value.state.page.inputValue, focused: value.state.page.inputFocused, loads: value.state.page.loads, clicks: value.state.page.clicks, submits: value.state.page.submits, trace: value.trace, elapsedMs: deps.now() - started });
  const prepared = deps.prepareSubmit ? await deps.prepareSubmit(value.state) : true;
  if (prepared !== true) {
    const current = await deps.read();
    const failed = evidence('prepare-submit-failed', started, deps, { trace: [current], last: current, taps, beforePosts, beforeArrivals, prepare: prepared });
    await save('prepare', failed);
    return failed;
  }
  const signIn = await deps.tap('Sign in');
  taps.push(signIn);
  if (!acceptedTap(signIn)) {
    const current = await deps.read();
    const failed = fail('signin-tap-miss', { trace: [current], taps, beforePosts, beforeArrivals, elapsedMs: deps.now() - started, current });
    await save('signin-tap', failed);
    return failed;
  }
  const landing = await waitFor(deps, started, (state) => landed(state, expected, beforePosts, beforeArrivals));
  const restored = await deps.restoreIfExpandedByProbe();
  if (!landing.ok) {
    const failed = fail(landing.reason, { trace: landing.trace, taps, beforePosts, beforeArrivals, last: landing.last, restored });
    await save('landing', failed);
    return failed;
  }
  if (restored !== true) {
    const failed = fail('restore-failed', { trace: landing.trace, taps, beforePosts, beforeArrivals, landing: landing.state, restored });
    await save('restore', failed);
    return failed;
  }
  return {
    ok: true,
    reason: 'submitted',
    trace: landing.trace,
    beforePosts,
    beforeArrivals,
    value: 'fixture-user',
    landing: landing.state.page,
    finalState: landing.state,
    taps,
  };
}

function loginReady(state, expected) {
  const page = state?.page;
  const native = state?.native;
  return state?.phase === 'fixture-state' &&
    page?.url === LOGIN && page.ready === 'complete' && page.formPresent === true &&
    native?.pageReady === true && native.shown === true && native.mouseMode === false &&
    native.selectedTabId === expected.tabId && native.owner === expected.ownerSessionId;
}

function landed(state, expected, beforePosts, beforeArrivals) {
  const page = state?.page;
  const native = state?.native;
  return state?.phase === 'fixture-state' &&
    page?.url === WELCOME && page.ready === 'complete' && native?.pageReady === true &&
    native.selectedTabId === expected.tabId && native.owner === expected.ownerSessionId &&
    page.posts === beforePosts + 1 && state.postArrivals === beforeArrivals + 1;
}

function acceptedTap(status) {
  return status?.phase === 'tapped' && status.visible === true && status.hit === true && status.sent === true;
}

async function waitFor(deps, started, predicate) {
  // A human interaction can spend time opening the window and settling the IME. Give each
  // observation phase its own budget while keeping the complete probe bounded.
  const phaseStarted = deps.now();
  const trace = [];
  let last = null;
  while (deps.now() - phaseStarted <= BUDGET_MS && deps.now() - started <= TOTAL_BUDGET_MS) {
    last = await deps.read();
    trace.push(last);
    if (predicate(last)) return { ok: true, state: last, trace };
    await deps.sleep(1);
  }
  return { ok: false, reason: 'timeout', trace, last };
}

function fail(reason, extra) {
  return { ok: false, reason, trace: extra.trace || [], taps: extra.taps || [], ...extra };
}

function evidence(reason, started, deps, extra) {
  return fail(reason, { ...extra, elapsedMs: deps.now() - started, started });
}

module.exports = { runManualSubmit, LOGIN, WELCOME };
