'use strict';

// Product verification lane: fixed readers and case flows for LocalProviderProductTest. This is a new
// product-only lane; it does not call the lifecycle/retirement matrix flows. Any reader the producer
// has not delivered is a typed failure, never a default false/true.

const fs = require('node:fs');
const path = require('node:path');

const {
  createCaseFence,
} = require('../verify_openchamber_provider_emulator.cjs');
const { validateExportedTarget, redact } = require('../verify_local_provider_emulator.cjs');

const PRODUCT = Object.freeze({
  testClass: 'io.andchamber.app.browser.LocalProviderProductTest#runGuestProduct',
  testPackage: 'io.andchamber.localprovider.test',
  component: 'io.andchamber.localprovider.test/androidx.test.runner.AndroidJUnitRunner',
  catalog: 'app/src/androidTest/assets/local-provider-product-cases.json',
  focusUrl: 'https://example.com/openchamber-provider-focused',
  mouseUrl: 'https://example.com/openchamber-provider-mouse',
  loginUrl: 'https://example.com/openchamber-provider-login',
  scope: 'product',
  sceneBudgetMs: 120000,
  maxReads: 8,
});

const CHAT_LABELS = Object.freeze([
  'Browser', 'New chat', 'New session', 'Open sessions and projects',
  'Open workspace panel', 'Open workspace panel, uncommitted changes',
  'Browser address', 'Open',
]);

// The workspace drawer is a portal <section role="dialog"> that Chromium keeps out of its
// accessibility tree, so exactly these two drawer controls are read as a fixed DOM rect instead.
const CHAT_DOM_LABELS = Object.freeze(['Browser', 'Browser address', 'Open']);

function chatTargetCommand(label) {
  return CHAT_DOM_LABELS.includes(label) ? `chat-dom-target:${label}` : `ui-target-chat:${label}`;
}

const PRODUCT_CASE_NAMES = Object.freeze([
  'product-blocked-tight',
  'product-focused-restore',
  'product-desktop-1024',
  'product-mouse-move-click',
  'product-mouse-hover-a11y-off',
  'product-mouse-drag-wheel',
  'product-pinch',
  'product-session-storage',
  'product-console-profile-isolation',
  'product-native-entry',
]);

// This lane is deliberately honest about what is implemented in this bounded host task. The catalog
// still has ten entries; selecting a pending case returns a typed not-implemented failure.
const IMPLEMENTED_PRODUCT_CASES = Object.freeze([
  'product-blocked-tight',
  'product-focused-restore',
  'product-desktop-1024',
  'product-mouse-move-click',
  'product-mouse-hover-a11y-off',
  'product-mouse-drag-wheel',
  'product-pinch',
  'product-session-storage',
  'product-console-profile-isolation',
  'product-native-entry',
]);

const PENDING_PRODUCT_CASES = Object.freeze(PRODUCT_CASE_NAMES.filter((name) => !IMPLEMENTED_PRODUCT_CASES.includes(name)));

function isObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function isNum(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function isText(value) {
  return typeof value === 'string' && value.length > 0;
}

function isBool(value) {
  return typeof value === 'boolean';
}

function documentReadyOk(value) {
  return value === true || value === 'complete' || value === 'interactive';
}

function missing(value) {
  return value === undefined || value === null;
}

/** Catalog is the single source of case names. Missing/duplicate/unknown cases are rejected. */
function parseProductCatalog(raw) {
  if (!isObject(raw)) throw new Error('product catalog must be an object');
  const schema = raw.schemaVersion === undefined ? raw.schema : raw.schemaVersion;
  if (schema !== 1) throw new Error(`product catalog schema must be 1 (got ${String(schema)})`);
  if (raw.suite !== 'local-provider-product') throw new Error('product catalog suite must be local-provider-product');
  if (!Array.isArray(raw.cases)) throw new Error('product catalog cases must be an array');
  const names = raw.cases.map((item) => (isText(item) ? item : isObject(item) && isText(item.name) ? item.name : null));
  if (names.some((name) => !name)) throw new Error('product catalog has an invalid case name');
  if (new Set(names).size !== names.length) throw new Error('product catalog has duplicate cases');
  if (names.length !== PRODUCT_CASE_NAMES.length) throw new Error(`product catalog must contain exactly ${PRODUCT_CASE_NAMES.length} cases`);
  for (const name of PRODUCT_CASE_NAMES) if (!names.includes(name)) throw new Error(`product catalog missing case ${name}`);
  for (const name of names) if (!PRODUCT_CASE_NAMES.includes(name)) throw new Error(`product catalog unknown case ${name}`);
  return { schemaVersion: 1, suite: 'local-provider-product', cases: [...names] };
}

function readProductCatalog(file = path.resolve(__dirname, '..', '..', PRODUCT.catalog)) {
  if (!fs.existsSync(file)) throw new Error(`product catalog missing: ${path.relative(path.resolve(__dirname, '..', '..'), file)}`);
  return parseProductCatalog(JSON.parse(fs.readFileSync(file, 'utf8')));
}

/* ------------------------------------------------------------------------------------------------
 * Product IO. Fixed readers only; no provider token and no generic page JS.
 * ---------------------------------------------------------------------------------------------- */

function createProductIo({
  client,
  runId,
  screen = null,
  tapScreen = null,
  humanText = null,
  fence = null,
  now = Date.now,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
} = {}) {
  if (!client) throw new Error('product client required');
  const canSend = () => !fence || fence.state.stopped !== true;
  const fenced = () => ({ ok: false, code: 'FENCED', detail: 'case fenced after timeout' });
  const readFailures = [];
  const missingFields = [];
  const rememberMissing = (field) => {
    if (!missingFields.includes(field)) missingFields.push(field);
  };
  const recordFailure = (reader, code, detail, command, commandId = null) => {
    readFailures.push({ reader, command, commandId, code: code || 'failed', detail: String(detail || '').slice(0, 300) });
    while (readFailures.length > PRODUCT.maxReads) readFailures.shift();
  };
  const deviceData = async (text, reader) => {
    const result = await client.sendDevice(text);
    if (!result.ok) {
      recordFailure(reader, result.code, result.detail, text, result.id || null);
      return null;
    }
    if (!isObject(result.data)) {
      recordFailure(reader, 'empty-data', 'producer data is not an object', text, result.id || null);
      return null;
    }
    return result.data;
  };

  async function presentationObserve() {
    const data = await deviceData('presentation-observe', 'presentation-observe');
    if (!data || data.phase !== 'presentation-observe') return null;
    for (const key of ['fixture', 'observe', 'ui', 'composer']) {
      if (!isObject(data[key])) rememberMissing(`presentation-observe.${key}`);
      else if (isText(data[key].error)) rememberMissing(`presentation-observe.${key}.error`);
    }
    // A nested safePhase error is not a ready object. Required fixture/observe errors are typed missing,
    // never flattened into a ready presentation with null fields.
    if (!isObject(data.fixture) || !isObject(data.observe) ||
        isText(data.fixture.error) || isText(data.observe.error)) return null;
    return {
      fixture: data.fixture,
      observe: data.observe,
      ui: isObject(data.ui) ? data.ui : null,
      composer: isObject(data.composer) ? data.composer : null,
      raw: data,
    };
  }

  async function readDiag() {
    return deviceData('lifecycle-diag', 'lifecycle-diag');
  }

  function targetOf(data) {
    return isObject(data) && isObject(data.target) ? data.target : data;
  }

  async function tapTargetCommand(text, label, phase) {
    const data = await deviceData(text, text.split(':')[0]);
    // The target read is async; a fence may close while waiting. Re-check immediately before the
    // actual ADB touch so a delayed read never becomes a late mutation.
    if (!canSend()) return fenced();
    if (!isObject(data) || !isObject(screen) || typeof tapScreen !== 'function') return { ok: false, reason: 'target-reader-missing' };
    const target = targetOf(data);
    const rejection = validateExportedTarget(target, {
      runId,
      phase,
      label,
      package: 'io.andchamber.localprovider',
      screenWidth: screen.width,
      screenHeight: screen.height,
      now: now(),
    });
    if (rejection) return { ok: false, reason: rejection, target };
    try {
      await tapScreen({ left: target.left, top: target.top, right: target.right, bottom: target.bottom });
      return { ok: true, target };
    } catch (error) {
      return { ok: false, reason: String(error && error.message || error), target };
    }
  }

  async function tapUi(label) {
    if (!canSend()) return fenced();
    return tapTargetCommand(`ui-target:${label}`, label, 'ui-target');
  }

  async function tapChat(label) {
    if (!canSend()) return fenced();
    return tapTargetCommand(chatTargetCommand(label), label, 'ui-target-chat');
  }

  /** Read-only chat target. Used to choose one visible workspace label before any tap. */
  async function chatTarget(label) {
    if (!CHAT_LABELS.includes(label)) return { ok: false, reason: 'unknown-chat-label' };
    if (!isObject(screen)) return { ok: false, reason: 'screen-missing' };
    const command = chatTargetCommand(label);
    const data = await deviceData(command, command.split(':')[0]);
    if (!isObject(data)) return { ok: false, reason: 'chat-target-missing', data };
    const target = targetOf(data);
    const rejection = validateExportedTarget(target, {
      runId,
      phase: 'ui-target-chat',
      label,
      package: 'io.andchamber.localprovider',
      screenWidth: screen.width,
      screenHeight: screen.height,
      now: now(),
    });
    return rejection ? { ok: false, reason: rejection, target } : { ok: true, target };
  }

  /** Read-only pinned chat browser state. Sensitive non-FOCUS URLs are never exported. */
  async function chatState() {
    const data = await deviceData('chat-browser-state', 'chat-browser-state');
    if (!data || data.phase !== 'chat-browser-state') return { ok: false, reason: 'chat-state-missing', data };
    const safeUrl = (value) => (typeof value === 'string' && (value === '' || value === PRODUCT.focusUrl) ? value : null);
    // The console URL itself is never exported; the device only reports the validated session query.
    if (data.consoleUrl !== undefined) return { ok: false, reason: 'chat-state-url-exported', data: { phase: data.phase } };
    const main = isObject(data.main) ? data.main : null;
    const address = isObject(data.address) ? data.address : null;
    if (!isText(data.sessionId) || data.querySession !== data.sessionId || !main || !address ||
        !isNum(main.viewHandle) || !isBool(main.hasFocus) || !isBool(main.hasWindowFocus) ||
        !isBool(data.documentHasFocus) || !isBool(address.present) || typeof address.value !== 'string' ||
        !isBool(address.focused) || !isText(address.tag) || !isText(address.type) ||
        !isBool(address.enabled) || !isBool(address.readOnly)) {
      return { ok: false, reason: 'chat-state-schema', data };
    }
    const addressValue = safeUrl(address.value);
    if (addressValue === null) return { ok: false, reason: 'chat-address-value-unsafe', data };
    return {
      ok: true,
      sessionId: data.sessionId,
      querySession: data.querySession,
      documentReady: data.documentReady,
      documentHasFocus: data.documentHasFocus,
      main: {
        viewHandle: main.viewHandle,
        hasFocus: main.hasFocus,
        hasWindowFocus: main.hasWindowFocus,
      },
      address: {
        present: address.present,
        value: addressValue,
        focused: address.focused,
        tag: address.tag,
        type: address.type,
        enabled: address.enabled,
        readOnly: address.readOnly,
      },
    };
  }

  /** One fixed exact-URL ADB text entry through the driver; never arbitrary text or JS. */
  async function typeChatAddress(url) {
    if (url !== PRODUCT.focusUrl) return { ok: false, reason: 'chat-address-url-not-allowed' };
    const before = await chatState();
    if (!before.ok) return before;
    if (before.address.value === PRODUCT.focusUrl) {
      return { ok: true, typed: false, alreadyExact: true, before };
    }
    if (before.address.value !== '') return { ok: false, reason: 'chat-address-not-empty', before };
    if (!before.address.present || !before.address.enabled || before.address.readOnly ||
        !before.address.focused || !before.documentHasFocus || !before.main.hasFocus) {
      return { ok: false, reason: 'chat-address-not-focused', before };
    }
    // The chat-state read is async; fence again at the actual human text mutation point.
    if (!canSend()) return { ...fenced(), before };
    if (typeof humanText !== 'function') return { ok: false, reason: 'human-text-missing', before };
    const text = await humanText(PRODUCT.focusUrl);
    return {
      ok: Boolean(text && text.ok === true),
      typed: true,
      alreadyExact: false,
      before,
      text,
    };
  }

  async function fixtureFieldTarget(field) {
    if (!['note', 'Apply'].includes(field)) return null;
    const data = await deviceData(`fixture-field:${field}`, `fixture-field:${field}`);
    if (!isObject(data) || data.phase !== 'fixture-field' || data.field !== field || data.present !== true) return null;
    if (!['INPUT', 'BUTTON'].includes(data.tag)) return null;
    if (typeof data.type !== 'string' || /password|file/i.test(data.type)) return null;
    if (typeof data.value !== 'string' || !isNum(data.valueLength) || data.value.length !== data.valueLength) return null;
    return data;
  }

  async function tapFixtureField(field) {
    if (!canSend()) return { ok: false, code: 'FENCED', detail: 'case fenced after timeout' };
    const result = await client.sendDevice(`tap-label:${field}`);
    const data = result.ok && isObject(result.data) ? result.data : null;
    const visible = Boolean(data && (data.visible === true || data.pointVisible === true));
    return {
      ok: Boolean(data && data.sent === true && data.hit === true && visible),
      data,
      code: result.code || '',
      detail: result.detail || '',
      commandId: result.id || null,
    };
  }

  async function siteState(action, marker) {
    if (!canSend()) return null;
    if (action === 'write') {
      const data = await deviceData(`site-state-write:${marker}`, 'site-state-write');
      return data;
    }
    if (action === 'read') return deviceData('site-state-read', 'site-state-read');
    if (action === 'clear') return deviceData('site-state-clear', 'site-state-clear');
    return null;
  }

  async function profileIsolation() {
    if (!canSend()) return null;
    return deviceData('profile-isolation', 'profile-isolation');
  }

  async function nativeBlockedInput() {
    if (!canSend()) return null;
    return deviceData('native-blocked-input', 'native-blocked-input');
  }

  async function pageRect() {
    return deviceData('page-rect', 'page-rect');
  }

  async function fixtureTarget(id, x = 1, y = 1) {
    return deviceData(`fixture-target:${id},${x},${y}`, 'fixture-target');
  }

  function gesturePoint(value) {
    return Number.isInteger(value) ? String(value) : String(Number(value.toFixed(2)));
  }

  async function gestureTarget(spread) {
    if (!canSend()) return { ok: false, code: 'FENCED', reason: 'case fenced before gesture read' };
    const frame = await pageRect();
    // The frame read is diagnostic; the real mutation must re-check the fence after it resolves.
    if (!canSend()) return { ok: false, code: 'FENCED', reason: 'case fenced after gesture read', frame };
    const physical = frame && isObject(frame.physical) ? frame.physical : null;
    const pointer = frame && isObject(frame.pointerUi) ? frame.pointerUi : null;
    if (!physical || !pointer || !isNum(physical.left) || !isNum(physical.top) ||
        !isNum(physical.width) || !isNum(physical.height) || physical.width <= 0 || physical.height <= 0 ||
        pointer.geometryReady !== true || !isNum(pointer.viewHandle) ||
        !isNum(frame.viewHandle) || pointer.viewHandle !== frame.viewHandle ||
        !isNum(screen.width) || !isNum(screen.height)) {
      return { ok: false, reason: 'gesture-frame-invalid', frame };
    }
    // The frame is read from currentView; confirm the same presenter view is still selected before
    // the single gesture mutation, then re-check the fence again at the send point.
    const diag = await readDiag();
    if (!canSend()) return { ok: false, code: 'FENCED', reason: 'case fenced after gesture source check', frame, diag };
    if (!diag || diag.pageReady !== true || !isNum(diag.viewHandle) || diag.viewHandle !== frame.viewHandle) {
      return { ok: false, reason: 'gesture-source-changed', frame, diag };
    }
    if (physical.left < 0 || physical.top < 0 ||
        physical.left + physical.width > screen.width ||
        physical.top + physical.height > screen.height) {
      return { ok: false, reason: 'gesture-frame-outside-screen', frame };
    }
    const center = { x: physical.left + physical.width / 2, y: physical.top + physical.height / 2 };
    if (center.x - spread < physical.left || center.x + spread > physical.left + physical.width ||
        center.y < physical.top || center.y > physical.top + physical.height) {
      return { ok: false, reason: 'gesture-spread-outside-frame', frame, center, spread };
    }
    return {
      ok: true,
      frame: {
        left: physical.left,
        top: physical.top,
        width: physical.width,
        height: physical.height,
        viewHandle: frame.viewHandle,
        geometryReady: pointer.geometryReady,
      },
      center,
      space: 'screen',
    };
  }

  async function twoFinger(dy) {
    if (!isNum(dy) || dy === 0) return { ok: false, reason: 'gesture-dy-invalid', dy };
    const target = await gestureTarget(60);
    if (!target.ok) return target;
    if (!canSend()) return { ...target, ok: false, code: 'FENCED', reason: 'case fenced before two-finger send' };
    const command = `two-finger-scroll:${gesturePoint(target.center.x)},${gesturePoint(target.center.y)},${gesturePoint(dy)}`;
    const result = await client.sendDevice(command);
    return {
      ok: result.ok && isObject(result.data) && result.data.sent === true,
      command,
      center: target.center,
      frame: target.frame,
      space: target.space,
      dy,
      reply: { ok: result.ok === true, code: result.code || '', detail: result.detail || '', id: result.id || null, data: result.data || null },
    };
  }

  async function pinch() {
    const target = await gestureTarget(140);
    if (!target.ok) return target;
    if (!canSend()) return { ...target, ok: false, code: 'FENCED', reason: 'case fenced before pinch send' };
    const command = `pinch:${gesturePoint(target.center.x)},${gesturePoint(target.center.y)}`;
    const result = await client.sendDevice(command);
    return {
      ok: result.ok && isObject(result.data) && result.data.sent === true,
      command,
      center: target.center,
      frame: target.frame,
      space: target.space,
      reply: { ok: result.ok === true, code: result.code || '', detail: result.detail || '', id: result.id || null, data: result.data || null },
    };
  }

  async function finger(text) {
    if (!canSend()) return false;
    const result = await client.sendDevice(text);
    return result.ok && isObject(result.data) && result.data.sent === true;
  }

  /**
   * One accessibility-off window for the hover case. The producer swaps the instrumentation's
   * UiAutomation to FLAG_DONT_USE_ACCESSIBILITY (Chromium otherwise consumes native hover), and each
   * command re-checks the fence at its own send point. The begin reply is evidence only when the app
   * process reported accessibility disabled; a producer typed failure is returned as a typed failure.
   */
  async function a11yOffBegin() {
    if (!canSend()) return { ...fenced(), ok: false };
    const result = await client.sendDevice('a11y-off:begin');
    const data = result.ok && isObject(result.data) ? result.data : null;
    return {
      ok: Boolean(data && data.phase === 'a11y-off' && data.state === 'off' && data.a11yEnabledAfter === false),
      data,
      code: result.code || '',
      detail: result.detail || '',
      commandId: result.id || null,
    };
  }

  async function a11yOffEnd() {
    if (!canSend()) return { ...fenced(), ok: false };
    const result = await client.sendDevice('a11y-off:end');
    const data = result.ok && isObject(result.data) ? result.data : null;
    return {
      ok: Boolean(data && data.phase === 'a11y-off' && data.state === 'on' && data.a11yEnabled === true),
      data,
      code: result.code || '',
      detail: result.detail || '',
      commandId: result.id || null,
    };
  }

  /** One relative finger move; the reply carries the app-process accessibility state at the gesture. */
  async function hoverFinger(text) {
    if (!canSend()) return { ...fenced(), ok: false };
    const result = await client.sendDevice(text);
    const data = result.ok && isObject(result.data) ? result.data : null;
    return {
      ok: Boolean(data && data.sent === true),
      data,
      code: result.code || '',
      detail: result.detail || '',
      commandId: result.id || null,
    };
  }

  return {
    client,
    runId,
    now,
    sleep,
    canSend: () => canSend(),
    presentation: presentationObserve,
    readDiag,
    tapUi,
    tapChat,
    chatTarget,
    chatState,
    typeChatAddress,
    fixtureFieldTarget,
    tapFixtureField,
    siteState,
    profileIsolation,
    nativeBlockedInput,
    pageRect,
    fixtureTarget,
    twoFinger,
    pinch,
    finger,
    a11yOffBegin,
    a11yOffEnd,
    hoverFinger,
    async open(url, viewport) {
      if (!canSend()) return fenced();
      const result = await client.sendTool('browser.open', viewport ? { url, viewport } : { url });
      return { ...result, tabId: result.data && result.data.tabId ? result.data.tabId : null };
    },
    snapshot: () => client.sendTool('browser.snapshot'),
    type: (selector, value) => canSend() ? client.sendTool('browser.type', { selector, value }) : Promise.resolve(fenced()),
    click: (selector) => canSend() ? client.sendTool('browser.click', { selector }) : Promise.resolve(fenced()),
    capture: (label) => canSend() ? client.sendTool('browser.capture', { label }) : Promise.resolve(fenced()),
    select: (tabId) => canSend() ? client.sendDevice(`select:${tabId}`) : Promise.resolve(fenced()),
    sessionNew: () => canSend() ? client.sendDevice('session:new') : Promise.resolve(fenced()),
    readFailures: () => readFailures.map((item) => ({ ...item })),
    missingFields: () => [...missingFields],
    redact: (value) => redact(value),
  };
}

/* ------------------------------------------------------------------------------------------------
 * Flows. These are product-only; they deliberately do not import the lifecycle/retirement probes.
 * ---------------------------------------------------------------------------------------------- */

async function waitFor(io, read, predicate, label, budgetMs = 20000) {
  const now = typeof io.now === 'function' ? io.now : Date.now;
  const sleep = typeof io.sleep === 'function' ? io.sleep : (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const started = now();
  let last = null;
  const reads = [];
  while (now() - started < budgetMs) {
    last = await read();
    reads.push(last);
    while (reads.length > 8) reads.shift();
    if (predicate(last)) return { ok: true, value: last, reads, elapsedMs: now() - started };
    await sleep(200);
  }
  return { ok: false, value: last, reads, elapsedMs: now() - started, reason: `${label}-timeout` };
}

function fixtureNative(presentation) {
  return presentation && isObject(presentation.fixture) && isObject(presentation.fixture.native)
    ? presentation.fixture.native : null;
}

function fixturePage(presentation) {
  return presentation && isObject(presentation.fixture) && isObject(presentation.fixture.page)
    ? presentation.fixture.page : null;
}

function observeMetrics(presentation) {
  return presentation && isObject(presentation.observe) ? presentation.observe : null;
}

function requireGrant(io, presentation) {
  const current = fixtureNative(presentation) || {};
  return current.grantForSelected === true;
}

function boundsOf(value) {
  if (!isObject(value)) return null;
  if (isObject(value.bounds)) return boundsOf(value.bounds);
  const left = value.left !== undefined ? value.left : value.x;
  const top = value.top !== undefined ? value.top : value.y;
  const width = value.width !== undefined ? value.width : value.w;
  const height = value.height !== undefined ? value.height : value.h;
  const right = value.right !== undefined ? value.right : isNum(left) && isNum(width) ? left + width : null;
  const bottom = value.bottom !== undefined ? value.bottom : isNum(top) && isNum(height) ? top + height : null;
  return [left, top, right, bottom].every(isNum) ? { left, top, right, bottom } : null;
}

function intersects(a, b) {
  return a && b && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
}

/**
 * Project the accepted composer measurement into the real screen rect of the editor. The chat
 * WebView itself covers the target overlay, so its full rect is never a composer-overlap check.
 */
function acceptedComposerRect(composer) {
  if (!isObject(composer) || composer.validityReason !== 'accepted' || composer.fallback === true) return null;
  const web = composer.mainWebView;
  const measurement = composer.measurement;
  if (!isObject(web) || !isObject(measurement)) return null;
  if (!isNum(web.x) || !isNum(web.y) || !isNum(web.width) || !isNum(web.height) || web.width <= 0 || web.height <= 0) return null;
  if (!isNum(composer.consoleGeneration) || !isNum(composer.nativeGeneration) ||
      measurement.consoleGeneration !== composer.consoleGeneration ||
      measurement.nativeGeneration !== composer.nativeGeneration) return null;
  if (measurement.nativeWidth !== web.width || measurement.nativeHeight !== web.height) return null;
  const { top, bottom, viewportWidth, viewportHeight, visible } = measurement;
  if (!isNum(top) || !isNum(bottom) || !isNum(viewportWidth) || !isNum(viewportHeight) ||
      viewportWidth <= 0 || viewportHeight <= 0 || visible !== true ||
      top < 0 || bottom < top || bottom > viewportHeight) return null;
  // ComposerBounds.kt: width ratio, not height ratio or DPR. nativeWidth was checked against the
  // whole chat WebView width; mainWebView itself is never the editor rect.
  const scale = measurement.nativeWidth / viewportWidth;
  if (!isNum(scale) || scale <= 0) return null;
  const editorTop = web.y + top * scale;
  const rawBottom = web.y + bottom * scale;
  const webBottom = web.y + web.height;
  // CSS viewport height is fractional while the native WebView height is an int, so a composer
  // pinned to the viewport bottom can project up to one physical px past the WebView. Clamp only
  // that rounding; anything further is a real mismatch.
  if (!isNum(rawBottom) || rawBottom > webBottom + 1) return null;
  const editorBottom = Math.min(rawBottom, webBottom);
  if (!isNum(editorTop) || editorTop < 0 || editorBottom < editorTop) return null;
  return {
    left: web.x,
    top: editorTop,
    right: web.x + web.width,
    bottom: editorBottom,
    scale,
    source: 'composer-measurement',
  };
}

async function ensureProductMouse(io) {
  const before = await io.readDiag();
  if (!before) return { ok: false, reason: 'mouse-diag-missing', before };
  if (!isBool(before.mouseMode)) return { ok: false, reason: 'mouse-mode-field-missing', before };
  if (before.mouseMode === true) return { ok: true, changed: false, before };
  if ((await io.tapUi('More')).ok !== true) return { ok: false, reason: 'mouse-more-missing', before };
  await io.sleep(800);
  if ((await io.tapUi('Mouse')).ok !== true) return { ok: false, reason: 'mouse-menu-missing', before };
  const ready = await waitFor(io, () => io.readDiag(), (state) => Boolean(state && state.mouseMode === true), 'mouse-enabled', 8000);
  return ready.ok ? { ok: true, changed: true, before, after: ready.value } : { ok: false, reason: 'mouse-not-enabled', before, ready };
}

async function ensureProductTouch(io) {
  const before = await io.readDiag();
  if (!before) return { ok: false, reason: 'touch-diag-missing', before };
  if (!isBool(before.mouseMode)) return { ok: false, reason: 'touch-mode-field-missing', before };
  if (before.mouseMode === false) return { ok: true, changed: false, value: before };
  if ((await io.tapUi('More')).ok !== true) return { ok: false, reason: 'touch-more-missing', before };
  await io.sleep(800);
  if ((await io.tapUi('Touch')).ok !== true) return { ok: false, reason: 'touch-menu-missing', before };
  const ready = await waitFor(io, () => io.readDiag(), (state) => Boolean(state && state.mouseMode === false), 'touch-enabled', 8000);
  return ready.ok ? { ok: true, changed: true, value: ready.value } : { ok: false, reason: 'touch-not-enabled', ready };
}

function allowLabelReady(presentation, tabId, viewHandle) {
  const native = fixtureNative(presentation);
  const ui = presentation && isObject(presentation.ui) ? presentation.ui : null;
  const labels = ui && isObject(ui.labels) ? ui.labels : null;
  const allow = labels && isObject(labels['Allow agent']) ? labels['Allow agent'] : null;
  if (!native || native.selectedTabId !== tabId || native.viewHandle !== viewHandle || native.pageReady !== true) return false;
  if (!allow || allow.enabled !== true || allow.visible !== true) return false;
  return [allow.left, allow.top, allow.right, allow.bottom, allow.windowId].every(isNum) &&
    allow.right > allow.left && allow.bottom > allow.top;
}

async function waitProductReady(io, tabId) {
  const diag = await waitFor(io, () => io.readDiag(), (state) => Boolean(state &&
    state.pageReady === true && state.selectedTabId === tabId && state.pageRetired !== true &&
    isNum(state.viewHandle)), 'product-ready-diag', 15000);
  if (!diag.ok) return { ok: false, reason: 'product-ready-diag-timeout', diag };
  const viewHandle = diag.value.viewHandle;
  const geometry = await waitFor(io, () => io.pageRect(), (state) => Boolean(state &&
    state.viewHandle === viewHandle && isObject(state.pointerUi) &&
    state.pointerUi.geometryReady === true && state.pointerUi.viewHandle === viewHandle), 'product-ready-geometry', 12000);
  if (!geometry.ok) return { ok: false, reason: 'product-ready-geometry-timeout', diag: diag.value, geometry };
  const ui = await waitFor(io, () => io.presentation(),
    (p) => allowLabelReady(p, tabId, viewHandle), 'product-ready-ui', 15000);
  if (!ui.ok) return { ok: false, reason: 'product-ready-ui-timeout', diag: diag.value, geometry: geometry.value, ui };
  const stable = await io.readDiag();
  if (!stable || stable.pageReady !== true || stable.selectedTabId !== tabId || stable.viewHandle !== viewHandle) {
    return { ok: false, reason: 'product-ready-source-changed', before: diag.value, after: stable };
  }
  return { ok: true, tabId, viewHandle, diag: stable, geometry: geometry.value, presentation: ui.value };
}

async function baseOpen(io, url, viewport = 'mobile') {
  const opened = await io.open(url, viewport);
  if (!opened.ok) return { ok: false, reason: 'open-failed', opened };
  if (!opened.tabId) return { ok: false, reason: 'open-no-tab', opened };
  const ready = await waitProductReady(io, opened.tabId);
  if (!ready.ok) return { ok: false, reason: ready.reason, opened, ready };
  return { ok: true, tabId: opened.tabId, opened, ready };
}

/**
 * Serial product state is explicit: a previous blocked case may leave WindowExpansion enlarged,
 * and a focused case leaves the IME shown. Read the actual UI/fixture state first, then perform at
 * most one real Restore or Turn off (closes the selected tab). No blind Back, no assumed fresh WindowExpansion memory, no retry.
 */
async function prepareProductCase(io) {
  const before = await io.presentation();
  if (!before) return { ok: true, actions: [], state: 'no-presentation' };
  const native = fixtureNative(before);
  const labels = before.ui && isObject(before.ui.labels) ? before.ui.labels : null;
  const ime = Boolean(before.fixture && isObject(before.fixture.outerime) && before.fixture.outerime.shown === true);
  const enlarged = Boolean(labels && isObject(labels.Restore));
  const actions = [];
  if (ime) {
    // The float title bar's close control is labelled "Turn off" (presenter.close(selectedTabId)).
    const close = await io.tapUi('Turn off');
    actions.push({ action: 'Turn off', ok: close.ok === true, reason: close.reason || null, commandId: close.commandId || null });
    if (!close.ok) return { ok: false, reason: 'case-prepare-close-failed', actions, before: before.raw };
    const tabId = native && native.selectedTabId ? native.selectedTabId : null;
    const viewHandle = native && isNum(native.viewHandle) ? native.viewHandle : null;
    const closed = await waitFor(io, () => io.readDiag(), (state) => Boolean(state &&
      (tabId == null || state.selectedTabId !== tabId || state.pageReady !== true ||
        (viewHandle != null && state.viewHandle !== viewHandle))), 'case-prepare-close', 8000);
    return closed.ok
      ? { ok: true, actions, state: 'closed-current-tab', before: before.raw }
      : { ok: false, reason: 'case-prepare-close-timeout', actions, before: before.raw, closed };
  }
  if (enlarged) {
    const restore = await io.tapUi('Restore');
    actions.push({ action: 'Restore', ok: restore.ok === true, reason: restore.reason || null, commandId: restore.commandId || null });
    if (!restore.ok) return { ok: false, reason: 'case-prepare-restore-failed', actions, before: before.raw };
    const restored = await waitFor(io, () => io.presentation(), (p) => Boolean(p &&
      p.ui && isObject(p.ui.labels) && isObject(p.ui.labels.Enlarge)), 'case-prepare-restore', 12000);
    return restored.ok
      ? { ok: true, actions, state: 'window-restored', before: before.raw }
      : { ok: false, reason: 'case-prepare-restore-timeout', actions, before: before.raw, restored };
  }
  return { ok: true, actions, state: 'clean', before: before.raw };
}

async function allowOnce(io) {
  const tap = await io.tapUi('Allow agent');
  if (!tap.ok) return { ok: false, reason: 'allow-tap-failed', tap };
  const grant = await waitFor(io, () => io.readDiag(), (state) => Boolean(state && state.grantForSelected === true), 'allow-grant', 8000);
  return grant.ok ? { ok: true, tap, grant } : { ok: false, reason: 'allow-grant-timeout', tap, grant };
}

function blockedCase(io, ctx) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.focusUrl, 'mobile');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const before = await io.presentation();
    if (!before) return { ok: false, reason: 'presentation-missing', missing: 'presentation-observe' };
    const saved = {
      before: before.raw,
      wm: await ctx.readWmSize(),
    };
    let blocked = null;
    try {
      const compact = await ctx.setCompactSize('1080x1000', io.canSend);
      if (compact && compact.ok === false) {
        return { ok: false, reason: compact.code === 'FENCED' ? 'wm-set-fenced' : 'wm-set-failed', saved, compact };
      }
      blocked = await waitFor(io, () => io.presentation(), (p) => {
        const native = fixtureNative(p);
        return Boolean(native && native.presentationBlocked === true);
      }, 'presentation-blocked', 15000);
      if (!blocked.ok) return { ok: false, reason: 'presentation-not-blocked', saved, blocked };
      const snap = await io.snapshot();
      if (snap.ok || snap.code !== 'BROWSER_PAGE_UNAVAILABLE') {
        return { ok: false, reason: 'blocked-snapshot-code', saved, snapshot: snap };
      }
      const capture = await io.capture('product-blocked');
      if (capture.ok || capture.code !== 'BROWSER_PAGE_UNAVAILABLE') {
        return { ok: false, reason: 'blocked-capture-code', saved, capture };
      }
      const blockedDiag = await io.readDiag();
      if (!blockedDiag || blockedDiag.grantForSelected !== false || blockedDiag.agentOn !== false) {
        return { ok: false, reason: 'blocked-grant-agent-not-off', saved, diag: blockedDiag };
      }
      const nativeInput = await io.nativeBlockedInput();
      const afterInput = nativeInput && isObject(nativeInput.after) ? nativeInput.after : null;
      const beforeInput = nativeInput && isObject(nativeInput.before) ? nativeInput.before : null;
      const delta = nativeInput && isObject(nativeInput.delta) ? nativeInput.delta : null;
      const handled = nativeInput && isObject(nativeInput.handled) ? nativeInput.handled : null;
      const allHandled = Boolean(handled) && ['touch', 'generic', 'key'].every((key) =>
        Array.isArray(handled[key]) && handled[key].length === 2 && handled[key].every((value) => value === true));
      const hash64 = (value) => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
      if (!nativeInput || !allHandled || !afterInput || !beforeInput || !delta ||
          delta.keys !== 0 || delta.clicks !== 0 || delta.applies !== 0 ||
          nativeInput.inputChanged !== false ||
          !hash64(beforeInput.inputHash) || !hash64(afterInput.inputHash) ||
          beforeInput.inputHash !== afterInput.inputHash) {
        return { ok: false, reason: 'native-blocked-input-failed', saved, nativeInput };
      }
      const restore = await ctx.restoreWmSize();
      if (restore && restore.ok === false) {
        return { ok: false, reason: 'wm-restore-failed', saved, restore };
      }
      const latched = await io.presentation();
      const latchedNative = latched ? fixtureNative(latched) : null;
      const latchedDiag = await io.readDiag();
      if (!latchedNative || latchedNative.presentationBlocked !== true ||
          !latchedDiag || latchedDiag.grantForSelected !== false || latchedDiag.agentOn !== false) {
        return { ok: false, reason: 'blocked-latch-lost', saved, latched: latched && latched.raw, diag: latchedDiag };
      }
      const enlarged = await io.tapUi('Enlarge');
      if (!enlarged.ok) return { ok: false, reason: 'enlarge-tap-failed', saved, enlarged };
      const recovered = await waitFor(io, () => io.presentation(), (p) => {
        const native = fixtureNative(p);
        return Boolean(native && native.presentationBlocked === false);
      }, 'presentation-recovered', 20000);
      if (!recovered.ok) return { ok: false, reason: 'presentation-not-recovered', saved, recovered };
      const afterRecover = recovered.value;
      const beforePage = fixturePage(before) || {};
      const afterPage = fixturePage(afterRecover) || {};
      const beforeNative = fixtureNative(before) || {};
      const afterNative = fixtureNative(afterRecover) || {};
      const afterDiag = await io.readDiag();
      if (beforeNative.viewHandle !== afterNative.viewHandle ||
          beforePage.timeOrigin !== afterPage.timeOrigin ||
          beforePage.loads !== afterPage.loads ||
          beforePage.noteValue !== afterPage.noteValue ||
          afterNative.hasFocus !== false || afterNative.presentationBlocked !== false ||
          !afterDiag || afterDiag.grantForSelected !== false || afterDiag.agentOn !== false) {
        return { ok: false, reason: 'blocked-recovery-identity', saved, before: before.raw, after: afterRecover.raw, diag: afterDiag };
      }
      return { ok: true, reason: 'completed', saved, snapshotCode: snap.code, captureCode: capture.code, nativeInput, recovered: afterRecover.raw };
    } finally {
      await ctx.restoreWmSize();
    }
  })();
}

function focusedCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.focusUrl, 'desktop');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const snap = await io.snapshot();
    const elements = snap.ok && snap.data && Array.isArray(snap.data.elements) ? snap.data.elements : [];
    const note = elements.find((item) => item && (item.name === 'note' || item.name === 'Note' || item.role === 'textbox'));
    if (!note || !note.selector) return { ok: false, reason: 'note-ref-missing', snap };
    const typed = await io.type(note.selector, 'product-sticky');
    if (!typed.ok) return { ok: false, reason: 'note-type-failed', typed };
    const mouse = await ensureProductMouse(io);
    if (!mouse.ok) return { ok: false, reason: mouse.reason, mouse };
    const beforeScroll = await io.presentation();
    const beforeMetrics = observeMetrics(beforeScroll) || {};
    const beforeSy = isNum(beforeMetrics.metrics ? beforeMetrics.metrics.sy : beforeMetrics.sy)
      ? (beforeMetrics.metrics ? beforeMetrics.metrics.sy : beforeMetrics.sy) : 0;
    const scrollGesture = await io.twoFinger(-240);
    if (!scrollGesture.ok) return { ok: false, reason: 'scroll-not-sent', before: beforeScroll && beforeScroll.raw, gesture: scrollGesture };
    const scrolled = await waitFor(io, () => io.presentation(), (p) => {
      const m = observeMetrics(p) || {};
      const value = m.metrics ? m.metrics.sy : m.sy;
      return isNum(value) && value > beforeSy && fixturePage(p) && fixturePage(p).noteValue === 'product-sticky';
    }, 'scroll-observed', 12000);
    if (!scrolled.ok) return { ok: false, reason: 'scroll-not-observed', before: beforeScroll && beforeScroll.raw, scrolled };
    const touch = await ensureProductTouch(io);
    if (!touch.ok) return { ok: false, reason: touch.reason, touch };
    const focusTap = await io.tapFixtureField('note');
    if (!focusTap.ok) return { ok: false, reason: 'note-tap-failed', focusTap, scrolled: scrolled.value.raw };
    const focused = await waitFor(io, () => io.presentation(), (p) => {
      const page = fixturePage(p);
      const native = fixtureNative(p);
      const ime = p.fixture && isObject(p.fixture.outerime) ? p.fixture.outerime : null;
      return Boolean(page && page.noteFocused === true && page.noteValue === 'product-sticky' &&
        page.noteEnabled === true && page.noteReadOnly === false && page.noteTag === 'INPUT' &&
        isNum(page.noteSelectionStart) && isNum(page.noteSelectionEnd) &&
        page.noteSelectionStart >= 0 && page.noteSelectionEnd >= page.noteSelectionStart &&
        page.documentHasFocus === true && native && native.hasFocus === true &&
        ime && ime.shown === true);
    }, 'note-focused', 12000);
    if (!focused.ok) return { ok: false, reason: 'note-focus-timeout', scrolled: scrolled.value.raw, focused };
    const before = focused.value;
    const beforePage = fixturePage(before) || {};
    const beforeNative = fixtureNative(before) || {};
    const beforeMetrics2 = observeMetrics(before) || {};
    const savedSy = beforeMetrics2.metrics ? beforeMetrics2.metrics.sy : beforeMetrics2.sy;
    const composer = before.composer;
    const composerFrame = acceptedComposerRect(composer);
    const pageFrame = boundsOf(beforeNative.physicalFrame);
    if (!composerFrame || !pageFrame) return { ok: false, reason: 'composer-measurement-missing', composer, before: before.raw };
    if (intersects(composerFrame, pageFrame)) return { ok: false, reason: 'composer-overlaps-page', composer: composerFrame, page: pageFrame };
    const enlarge = await io.tapUi('Enlarge');
    if (!enlarge.ok) return { ok: false, reason: 'enlarge-failed', enlarge };
    const expanded = await waitFor(io, () => io.presentation(), (p) => isObject(p.ui) && isObject(p.ui.labels) && isObject(p.ui.labels.Restore), 'enlarged', 20000);
    if (!expanded.ok) return { ok: false, reason: 'enlarge-not-observed', before: before.raw };
    const restore = await io.tapUi('Restore');
    if (!restore.ok) return { ok: false, reason: 'restore-failed', restore };
    const restored = await waitFor(io, () => io.presentation(), (p) => isObject(p.ui) && isObject(p.ui.labels) && isObject(p.ui.labels.Enlarge), 'restored', 20000);
    if (!restored.ok) return { ok: false, reason: 'restore-not-observed', before: before.raw };
    const after = restored.value;
    const afterPage = fixturePage(after) || {};
    const afterNative = fixtureNative(after) || {};
    const afterMetrics = observeMetrics(after) || {};
    const afterSy = afterMetrics.metrics ? afterMetrics.metrics.sy : afterMetrics.sy;
    const imeAfter = after.fixture && isObject(after.fixture.outerime) ? after.fixture.outerime : null;
    const afterComposerFrame = acceptedComposerRect(after.composer);
    const afterPageFrame = boundsOf(afterNative.physicalFrame);
    if (!afterComposerFrame || !afterPageFrame) return { ok: false, reason: 'composer-after-measurement-missing', composer: after.composer, after: after.raw };
    if (intersects(afterComposerFrame, afterPageFrame)) return { ok: false, reason: 'composer-overlaps-page-after', composer: afterComposerFrame, page: afterPageFrame };
    if (!isNum(afterSy) || Math.abs(afterSy - savedSy) > 1 ||
        afterPage.noteValue !== 'product-sticky' || afterPage.noteFocused !== true ||
        afterPage.noteEnabled !== true || afterPage.noteReadOnly !== false || afterPage.noteTag !== 'INPUT' ||
        !isNum(afterPage.noteSelectionStart) || !isNum(afterPage.noteSelectionEnd) ||
        afterPage.noteSelectionStart < 0 || afterPage.noteSelectionEnd < afterPage.noteSelectionStart ||
        afterPage.documentHasFocus !== true ||
        afterPage.timeOrigin !== beforePage.timeOrigin || afterPage.loads !== beforePage.loads ||
        beforeNative.viewHandle !== afterNative.viewHandle || afterNative.hasFocus !== true ||
        !imeAfter || imeAfter.shown !== true) {
      return { ok: false, reason: 'focused-restore-identity', before: before.raw, after: after.raw, savedSy, afterSy };
    }
    return { ok: true, reason: 'completed', before: before.raw, scrolled: scrolled.value.raw, after: after.raw, savedSy, afterSy };
  })();
}

function desktopCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.focusUrl, 'desktop');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const presentation = await io.presentation();
    if (!presentation) return { ok: false, reason: 'presentation-missing' };
    const observe = observeMetrics(presentation) || {};
    const metrics = isObject(observe.metrics) ? observe.metrics : observe;
    const failures = [];
    if (metrics.ua !== 'desktop') failures.push('ua');
    if (metrics.clientWidth !== 1024) failures.push('clientWidth');
    if (metrics.media1000 !== true) failures.push('media1000');
    if (!isObject(metrics.visualViewport) && !isObject(observe.visualViewport)) failures.push('visualViewport');
    if (!isNum(metrics.dpr) && !isNum(observe.dpr)) failures.push('dpr');
    if (!fixtureNative(presentation) || !isObject(fixtureNative(presentation).physicalFrame)) failures.push('physical');
    if (failures.length) return { ok: false, reason: 'desktop-metrics-failed', failures, presentation: presentation.raw };
    return { ok: true, reason: 'completed', presentation: presentation.raw };
  })();
}

function mouseMoveClickCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.mouseUrl, 'desktop');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const mouse = await ensureProductMouse(io);
    if (!mouse.ok) return { ok: false, reason: mouse.reason, mouse };
    const frameWait = await waitFor(io, () => io.pageRect(), (state) => Boolean(state && isObject(state.pointerUi) &&
      state.pointerUi.geometryReady === true), 'mouse-geometry', 8000);
    if (!frameWait.ok) return { ok: false, reason: 'mouse-geometry-timeout', frameWait };
    const frame = frameWait.value;
    const pointer = frame && isObject(frame.pointerUi) ? frame.pointerUi : null;
    const physical = frame && isObject(frame.physical) ? frame.physical : null;
    const viewport = frame && isObject(frame.page) && isObject(frame.page.visualViewport) ? frame.page.visualViewport : null;
    if (!pointer || !physical || !viewport || pointer.geometryReady !== true || !isNum(pointer.pointerViewX) || !isNum(pointer.pointerViewY) ||
        !isNum(physical.width) || !isNum(viewport.width) || viewport.width <= 0) {
      return { ok: false, reason: 'mouse-geometry-missing', frame };
    }
    const scale = physical.width / viewport.width;
    const pointerCssX = viewport.offsetLeft + pointer.pointerViewX / scale;
    const pointerCssY = viewport.offsetTop + pointer.pointerViewY / scale;
    const initial = await io.fixtureTarget('box', pointerCssX, pointerCssY);
    if (!initial || !isObject(initial.box)) return { ok: false, reason: 'mouse-box-missing', initial };
    const box = initial.box;
    const centerX = (box.left + box.right) / 2;
    const centerY = (box.top + box.bottom) / 2;
    const startX = physical.left + pointer.pointerViewX;
    const startY = physical.top + pointer.pointerViewY;
    const dx = Math.round((centerX - pointerCssX) * scale);
    const dy = Math.round((centerY - pointerCssY) * scale);
    let moved = false;
    if (!(initial.hit === true) && Math.hypot(dx, dy) >= 1) {
      if (Math.hypot(dx, dy) <= 32) return { ok: false, reason: 'mouse-move-tiny', dx, dy, initial };
      if (!(await io.finger(`finger:${Math.round(startX)},${Math.round(startY)},${dx},${dy},0,4`))) {
        return { ok: false, reason: 'mouse-move-not-sent', dx, dy };
      }
      moved = true;
    }
    const settledFrame = await waitFor(io, () => io.pageRect(), (state) => Boolean(state &&
      isObject(state.pointerUi) && state.pointerUi.geometryReady === true &&
      (!moved || Math.abs(state.pointerUi.pointerViewX - pointer.pointerViewX) > 1 ||
        Math.abs(state.pointerUi.pointerViewY - pointer.pointerViewY) > 1)), 'mouse-after-geometry', 8000);
    if (!settledFrame.ok) return { ok: false, reason: 'mouse-after-geometry-timeout', settledFrame };
    const afterMoveFrame = settledFrame.value;
    const movedPointer = afterMoveFrame && isObject(afterMoveFrame.pointerUi) ? afterMoveFrame.pointerUi : null;
    const movedPhysical = afterMoveFrame && isObject(afterMoveFrame.physical) ? afterMoveFrame.physical : null;
    const movedViewport = afterMoveFrame && isObject(afterMoveFrame.page) && isObject(afterMoveFrame.page.visualViewport) ? afterMoveFrame.page.visualViewport : null;
    if (!movedPointer || !movedPhysical || !movedViewport || movedPointer.geometryReady !== true ||
        !isNum(movedPointer.pointerViewX) || !isNum(movedPointer.pointerViewY) || !isNum(movedPhysical.width) || !isNum(movedViewport.width) || movedViewport.width <= 0) {
      return { ok: false, reason: 'mouse-after-geometry-missing', afterMoveFrame };
    }
    if (isNum(pointer.viewHandle) && isNum(movedPointer.viewHandle) && pointer.viewHandle !== movedPointer.viewHandle) {
      return { ok: false, reason: 'mouse-pointer-view-changed', before: pointer, after: movedPointer };
    }
    const afterScale = movedPhysical.width / movedViewport.width;
    const currentCssX = movedViewport.offsetLeft + movedPointer.pointerViewX / afterScale;
    const currentCssY = movedViewport.offsetTop + movedPointer.pointerViewY / afterScale;
    const hovered = await io.fixtureTarget('box', currentCssX, currentCssY);
    if (!hovered || hovered.hit !== true) return { ok: false, reason: 'mouse-not-hit', dx, dy, moved, pointer: { x: currentCssX, y: currentCssY }, hovered };
    const before = await io.presentation();
    const beforeLog = before && isObject(before.observe) && Array.isArray(before.observe.log) ? before.observe.log : null;
    if (!beforeLog) return { ok: false, reason: 'mouse-log-missing', before: before && before.raw };
    const clickX = Math.round(movedPhysical.left + movedPointer.pointerViewX);
    const clickY = Math.round(movedPhysical.top + movedPointer.pointerViewY);
    const click = await io.finger(`finger:${clickX},${clickY},0,0,0,1`);
    if (!click) return { ok: false, reason: 'mouse-click-not-sent' };
    const after = await io.presentation();
    const afterLog = after && isObject(after.observe) && Array.isArray(after.observe.log) ? after.observe.log : null;
    if (!afterLog) return { ok: false, reason: 'mouse-after-log-missing', after: after && after.raw };
    // Pure hover under Android accessibility is a known limitation: a relative move may never reach
    // the page as a DOM pointermove. The observable proof of this case is the one real click landing
    // at the moved pointer, so a pointermove is recorded when present but never required.
    const isMouse = (entry) => Boolean(entry) && entry.p === 'mouse';
    const downs = afterLog.filter((entry) => isMouse(entry) && entry.t === 'pointerdown' && entry.bs === 1);
    const clicks = afterLog.filter((entry) => isMouse(entry) && entry.t === 'click');
    const moves = afterLog.filter((entry) => isMouse(entry) && entry.t === 'pointermove');
    const beforeDowns = beforeLog.filter((entry) => isMouse(entry) && entry.t === 'pointerdown' && entry.bs === 1).length;
    const beforeClicks = beforeLog.filter((entry) => isMouse(entry) && entry.t === 'click').length;
    const beforeMoves = beforeLog.filter((entry) => isMouse(entry) && entry.t === 'pointermove').length;
    const newMoves = moves.length - beforeMoves;
    if (clicks.length - beforeClicks !== 1) {
      return { ok: false, reason: 'mouse-click-count', before: before.raw, after: after.raw, beforeClicks, afterClicks: clicks.length, newMoves };
    }
    if (downs.length - beforeDowns !== 1) {
      return { ok: false, reason: 'mouse-pointerdown-count', before: before.raw, after: after.raw, beforeDowns, afterDowns: downs.length, newMoves };
    }
    const expectedX = moved ? pointerCssX + dx / scale : currentCssX;
    const expectedY = moved ? pointerCssY + dy / scale : currentCssY;
    // The newest entries are the ones this click appended; the page log is a bounded ring.
    const newDown = downs[downs.length - 1];
    const newClick = clicks[clicks.length - 1];
    if ([newDown, newClick].some((entry) => !isNum(entry.x) || !isNum(entry.y) ||
        Math.abs(entry.x - expectedX) > 2 || Math.abs(entry.y - expectedY) > 2)) {
      return { ok: false, reason: 'mouse-click-position', pointerdown: newDown, click: newClick, expectedX, expectedY, moved, dx, dy, after: after.raw };
    }
    // Informational only: with Android accessibility active Chromium consumes native hover, so a
    // missing pointermove is reported as unsupported rather than as a failed click case.
    const pointermoveObserved = moves.length > 0;
    return {
      ok: true,
      reason: 'completed',
      dx,
      dy,
      moved,
      hover: pointermoveObserved ? 'observed' : 'unsupported-a11y-on',
      pointermoveObserved,
      before: before.raw,
      after: after.raw,
    };
  })();
}

/**
 * Entries the page log gained during the gesture. The observer returns the newest 40 entries, so a
 * before slice must be a prefix of the after slice; anything else (a rolling-window overlap) is
 * reported, never guessed.
 */
function newLogEntries(before, after) {
  if (!Array.isArray(before) || !Array.isArray(after)) return null;
  if (after.length < before.length) return null;
  const same = (a, b) => Boolean(a) && Boolean(b) && a.t === b.t && a.p === b.p &&
    a.bs === b.bs && a.b === b.b && a.x === b.x && a.y === b.y;
  for (let index = 0; index < before.length; index += 1) {
    if (!same(before[index], after[index])) return null;
  }
  return after.slice(before.length);
}

/**
 * Approved hover exception (phase-3 contract): with accessibility disabled in the app process, one
 * real relative finger move in Mouse mode must reach the page as a DOM pointermove for a mouse with
 * no buttons, at the measured point, and must not press, release or click anything.
 */
function mouseHoverA11yOffCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.mouseUrl, 'desktop');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const mouse = await ensureProductMouse(io);
    if (!mouse.ok) return { ok: false, reason: mouse.reason, mouse };
    const frameWait = await waitFor(io, () => io.pageRect(), (state) => Boolean(state && isObject(state.pointerUi) &&
      state.pointerUi.geometryReady === true), 'hover-geometry', 8000);
    if (!frameWait.ok) return { ok: false, reason: 'hover-geometry-timeout', frameWait };
    const frame = frameWait.value;
    const pointer = frame && isObject(frame.pointerUi) ? frame.pointerUi : null;
    const physical = frame && isObject(frame.physical) ? frame.physical : null;
    const viewport = frame && isObject(frame.page) && isObject(frame.page.visualViewport) ? frame.page.visualViewport : null;
    if (!pointer || !physical || !viewport || pointer.geometryReady !== true || !isNum(pointer.pointerViewX) || !isNum(pointer.pointerViewY) ||
        !isNum(physical.left) || !isNum(physical.top) || !isNum(physical.width) || !isNum(viewport.width) || viewport.width <= 0) {
      return { ok: false, reason: 'hover-geometry-missing', frame };
    }
    const scale = physical.width / viewport.width;
    const pointerCssX = viewport.offsetLeft + pointer.pointerViewX / scale;
    const pointerCssY = viewport.offsetTop + pointer.pointerViewY / scale;
    const startX = physical.left + pointer.pointerViewX;
    const startY = physical.top + pointer.pointerViewY;
    const aim = await io.fixtureTarget('box', pointerCssX, pointerCssY);
    if (!aim || !isObject(aim.box)) return { ok: false, reason: 'hover-box-missing', aim };
    const box = aim.box;
    // The pointer persists across cases (move-click leaves it on the box centre), so aim at the
    // farthest inset box corner: that keeps the single relative move above the 32px hover threshold
    // while the target still hit-tests to the box.
    const inset = Math.min(10, Math.min((box.right - box.left) / 4, (box.bottom - box.top) / 4));
    const corners = [
      { x: box.left + inset, y: box.top + inset },
      { x: box.right - inset, y: box.top + inset },
      { x: box.left + inset, y: box.bottom - inset },
      { x: box.right - inset, y: box.bottom - inset },
    ];
    const distance = (point) => Math.hypot(point.x - pointerCssX, point.y - pointerCssY);
    const target = corners.reduce((best, corner) => (distance(corner) > distance(best) ? corner : best), corners[0]);
    const hover = await io.fixtureTarget('box', target.x, target.y);
    if (!hover || hover.hit !== true) return { ok: false, reason: 'hover-target-not-box', target, hover };
    const dx = Math.round((target.x - pointerCssX) * scale);
    const dy = Math.round((target.y - pointerCssY) * scale);
    // Same sizing rule as move-click: a correction at or below 32px is a tap-sized move, not a hover.
    if (Math.hypot(dx, dy) <= 32) return { ok: false, reason: 'hover-move-tiny', dx, dy, target, aim };
    const before = await io.presentation();
    const beforeLog = before && isObject(before.observe) && Array.isArray(before.observe.log) ? before.observe.log : null;
    if (!beforeLog) return { ok: false, reason: 'hover-log-missing', before: before && before.raw };
    const beforePage = fixturePage(before) || {};
    const beforeNative = fixtureNative(before) || {};
    // One accessibility-off window, never retried. The producer restores on its own failure paths;
    // the case also requests the restore in finally so a failed hover never breaks later cases.
    let restore = null;
    let failure = null;
    try {
      const begin = await io.a11yOffBegin();
      if (!begin.ok) {
        failure = { ok: false, reason: 'a11y-off-begin-failed', begin };
      } else {
        const gesture = await io.hoverFinger(`finger:${Math.round(startX)},${Math.round(startY)},${dx},${dy},0,4`);
        if (!gesture.ok) {
          failure = { ok: false, reason: 'hover-move-not-sent', begin, gesture };
        } else if (!isObject(gesture.data) || gesture.data.a11yEnabledDuringGesture !== false) {
          failure = { ok: false, reason: 'hover-a11y-enabled-during-gesture', begin, gesture };
        }
      }
    } finally {
      restore = await io.a11yOffEnd();
    }
    if (failure) return { ...failure, restore };
    if (!restore || !restore.ok) return { ok: false, reason: 'a11y-restore-failed', restore };
    const after = await io.presentation();
    const afterLog = after && isObject(after.observe) && Array.isArray(after.observe.log) ? after.observe.log : null;
    if (!afterLog) return { ok: false, reason: 'hover-after-log-missing', restore, after: after && after.raw };
    const afterPage = fixturePage(after) || {};
    const afterNative = fixtureNative(after) || {};
    const fresh = newLogEntries(beforeLog, afterLog);
    if (!fresh) {
      return { ok: false, reason: 'hover-log-window', restore, beforeLength: beforeLog.length, afterLength: afterLog.length, after: after.raw };
    }
    const moves = fresh.filter((entry) => Boolean(entry) && entry.t === 'pointermove' && entry.p === 'mouse' && entry.bs === 0);
    const forbidden = fresh.filter((entry) => Boolean(entry) &&
      ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].includes(entry.t));
    if (forbidden.length > 0) {
      return { ok: false, reason: 'hover-extra-pointer-events', restore, forbidden, fresh, after: after.raw };
    }
    if (moves.length < 1) {
      return { ok: false, reason: 'hover-pointermove-missing', restore, dx, dy, fresh, after: after.raw };
    }
    const last = moves[moves.length - 1];
    const expectedX = pointerCssX + dx / scale;
    const expectedY = pointerCssY + dy / scale;
    if (!isNum(last.x) || !isNum(last.y) ||
        Math.abs(last.x - expectedX) > 2 || Math.abs(last.y - expectedY) > 2) {
      return { ok: false, reason: 'hover-pointermove-position', restore, last, expectedX, expectedY, dx, dy, after: after.raw };
    }
    if (beforePage.timeOrigin !== afterPage.timeOrigin || beforePage.loads !== afterPage.loads ||
        beforeNative.viewHandle !== afterNative.viewHandle) {
      return {
        ok: false,
        reason: 'hover-source-changed',
        restore,
        before: { timeOrigin: beforePage.timeOrigin, loads: beforePage.loads, viewHandle: beforeNative.viewHandle },
        after: { timeOrigin: afterPage.timeOrigin, loads: afterPage.loads, viewHandle: afterNative.viewHandle },
      };
    }
    return {
      ok: true,
      reason: 'completed',
      dx,
      dy,
      hover: 'observed',
      pointermoveObserved: true,
      restore,
      moves: moves.length,
      lastMove: last,
      expectedX,
      expectedY,
      before: before.raw,
      after: after.raw,
    };
  })();
}

function mouseDragWheelCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.mouseUrl, 'desktop');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const mouse = await ensureProductMouse(io);
    if (!mouse.ok) return { ok: false, reason: mouse.reason, mouse };
    const frameWait = await waitFor(io, () => io.pageRect(), (state) => Boolean(state && isObject(state.pointerUi) &&
      state.pointerUi.geometryReady === true), 'drag-geometry', 8000);
    if (!frameWait.ok) return { ok: false, reason: 'drag-geometry-timeout', frameWait };
    const frame = frameWait.value;
    const pointer = frame && isObject(frame.pointerUi) ? frame.pointerUi : null;
    const physical = frame && isObject(frame.physical) ? frame.physical : null;
    const viewport = frame && isObject(frame.page) && isObject(frame.page.visualViewport) ? frame.page.visualViewport : null;
    if (!pointer || !physical || !viewport || pointer.geometryReady !== true || !isNum(pointer.pointerViewX) || !isNum(pointer.pointerViewY) ||
        !isNum(physical.width) || !isNum(viewport.width) || viewport.width <= 0) return { ok: false, reason: 'drag-geometry-missing', frame };
    const scale = physical.width / viewport.width;
    const pointerCssX = viewport.offsetLeft + pointer.pointerViewX / scale;
    const pointerCssY = viewport.offsetTop + pointer.pointerViewY / scale;
    const aim = await io.fixtureTarget('box', pointerCssX, pointerCssY);
    if (!aim || !isObject(aim.box)) return { ok: false, reason: 'drag-box-missing', aim };
    const centerX = (aim.box.left + aim.box.right) / 2;
    const centerY = (aim.box.top + aim.box.bottom) / 2;
    const startX = physical.left + pointer.pointerViewX;
    const startY = physical.top + pointer.pointerViewY;
    const moveX = Math.round((centerX - pointerCssX) * scale);
    const moveY = Math.round((centerY - pointerCssY) * scale);
    if (aim.hit !== true) {
      if (Math.hypot(moveX, moveY) <= 32) return { ok: false, reason: 'drag-move-tiny', moveX, moveY };
      if (!(await io.finger(`finger:${Math.round(startX)},${Math.round(startY)},${moveX},${moveY},0,4`))) return { ok: false, reason: 'drag-move-not-sent' };
    }
    const settledFrame = await waitFor(io, () => io.pageRect(), (state) => Boolean(state &&
      isObject(state.pointerUi) && state.pointerUi.geometryReady === true &&
      (aim.hit === true || Math.abs(state.pointerUi.pointerViewX - pointer.pointerViewX) > 1 ||
        Math.abs(state.pointerUi.pointerViewY - pointer.pointerViewY) > 1)), 'drag-after-geometry', 8000);
    if (!settledFrame.ok) return { ok: false, reason: 'drag-after-geometry-timeout', settledFrame };
    const afterMoveFrame = settledFrame.value;
    const movedPointer = afterMoveFrame && isObject(afterMoveFrame.pointerUi) ? afterMoveFrame.pointerUi : null;
    const movedPhysical = afterMoveFrame && isObject(afterMoveFrame.physical) ? afterMoveFrame.physical : null;
    const movedViewport = afterMoveFrame && isObject(afterMoveFrame.page) && isObject(afterMoveFrame.page.visualViewport) ? afterMoveFrame.page.visualViewport : null;
    if (!movedPointer || !movedPhysical || !movedViewport || movedPointer.geometryReady !== true ||
        !isNum(movedPointer.pointerViewX) || !isNum(movedPointer.pointerViewY) || !isNum(movedPhysical.width) || !isNum(movedViewport.width) || movedViewport.width <= 0) {
      return { ok: false, reason: 'drag-after-geometry-missing', afterMoveFrame };
    }
    const afterScale = movedPhysical.width / movedViewport.width;
    const currentCssX = movedViewport.offsetLeft + movedPointer.pointerViewX / afterScale;
    const currentCssY = movedViewport.offsetTop + movedPointer.pointerViewY / afterScale;
    const hovered = await io.fixtureTarget('box', currentCssX, currentCssY);
    if (!hovered || hovered.hit !== true) return { ok: false, reason: 'drag-not-hit', pointer: { x: currentCssX, y: currentCssY }, hovered };
    const before = await io.presentation();
    const beforeBox = before && isObject(before.observe) && isObject(before.observe.report) && Array.isArray(before.observe.report.box) ? before.observe.report.box : null;
    if (!beforeBox) return { ok: false, reason: 'drag-box-report-missing', before: before && before.raw };
    const dragStartX = Math.round(movedPhysical.left + movedPointer.pointerViewX);
    const dragStartY = Math.round(movedPhysical.top + movedPointer.pointerViewY);
    const dropX = Math.round(dragStartX + 40 * afterScale);
    const dropY = Math.round(dragStartY + 20 * afterScale);
    const drag = await io.finger(`finger:${dragStartX},${dragStartY},${dropX - dragStartX},${dropY - dragStartY},900,4`);
    if (!drag) return { ok: false, reason: 'drag-finger-not-sent' };
    const afterDrag = await io.presentation();
    const afterBox = afterDrag && isObject(afterDrag.observe) && isObject(afterDrag.observe.report) && Array.isArray(afterDrag.observe.report.box) ? afterDrag.observe.report.box : null;
    if (!afterBox) return { ok: false, reason: 'drag-after-report-missing', after: afterDrag && afterDrag.raw };
    const expectedX = (dropX - dragStartX) / afterScale;
    const expectedY = (dropY - dragStartY) / afterScale;
    if (Math.abs((afterBox[0] - beforeBox[0]) - expectedX) > 2 || Math.abs((afterBox[1] - beforeBox[1]) - expectedY) > 2) {
      return { ok: false, reason: 'drag-drop-mismatch', beforeBox, afterBox, expectedX, expectedY };
    }
    const dragLog = afterDrag && isObject(afterDrag.observe) && Array.isArray(afterDrag.observe.log) ? afterDrag.observe.log : null;
    if (!dragLog) return { ok: false, reason: 'drag-log-missing', after: afterDrag && afterDrag.raw };
    const downs = dragLog.filter((entry) => entry && entry.t === 'pointerdown' && entry.p === 'mouse' && entry.bs === 1);
    const moves = dragLog.filter((entry) => entry && entry.t === 'pointermove' && entry.p === 'mouse' && entry.bs === 1);
    const ups = dragLog.filter((entry) => entry && entry.t === 'pointerup' && entry.p === 'mouse' && entry.bs === 0);
    if (downs.length !== 1 || moves.length < 1 || ups.length !== 1) {
      return { ok: false, reason: 'drag-pointer-sequence', downs: downs.length, moves: moves.length, ups: ups.length, after: afterDrag.raw };
    }
    const beforeSy = isNum(before.observe.sy) ? before.observe.sy : null;
    const wheelGesture = await io.twoFinger(-240);
    if (!wheelGesture.ok) return { ok: false, reason: 'wheel-not-sent', gesture: wheelGesture };
    const wheel = await waitFor(io, () => io.presentation(), (p) => {
      const log = p && isObject(p.observe) && Array.isArray(p.observe.log) ? p.observe.log : [];
      const wheels = log.filter((entry) => entry && entry.t === 'wheel');
      return wheels.length > 0 && isNum(p.observe.sy) && beforeSy != null && p.observe.sy > beforeSy;
    }, 'wheel-observed', 10000);
    if (!wheel.ok) return { ok: false, reason: 'wheel-not-observed', drag: afterDrag && afterDrag.raw, wheel };
    return { ok: true, reason: 'completed', beforeBox, afterBox, wheel: wheel.value.raw };
  })();
}

function pinchCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.mouseUrl, 'mobile');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const touch = await ensureProductTouch(io);
    if (!touch.ok) return { ok: false, reason: touch.reason, touch };
    const before = await io.presentation();
    if (!before || !isObject(before.observe)) return { ok: false, reason: 'pinch-presentation-missing' };
    const beforeNative = fixtureNative(before);
    const beforeView = isObject(before.observe.visualViewport) ? before.observe.visualViewport
      : isObject(before.observe.report) && isObject(before.observe.report.geometry) ? before.observe.report.geometry.visualViewport : null;
    if (!beforeNative || !isNum(beforeNative.viewHandle) || !beforeView || !isNum(beforeView.scale) || !isNum(beforeView.width)) {
      return { ok: false, reason: 'pinch-viewport-missing', before: before.raw };
    }
    const pinchGesture = await io.pinch();
    if (!pinchGesture.ok) return { ok: false, reason: 'pinch-not-sent', gesture: pinchGesture };
    const changed = await waitFor(io, () => io.presentation(), (p) => {
      const view = p && isObject(p.observe) ? (isObject(p.observe.visualViewport) ? p.observe.visualViewport
        : isObject(p.observe.report) && isObject(p.observe.report.geometry) ? p.observe.report.geometry.visualViewport : null) : null;
      const native = fixtureNative(p);
      return Boolean(view && native && native.viewHandle === beforeNative.viewHandle && isNum(view.scale) && isNum(view.width) &&
        (view.scale !== beforeView.scale || view.width !== beforeView.width) &&
        p.observe.timeOrigin === before.observe.timeOrigin &&
        p.observe.loads === before.observe.loads);
    }, 'pinch-observed', 10000);
    if (!changed.ok) return { ok: false, reason: 'pinch-scale-not-changed', before: before.raw, changed };
    const frame = await waitFor(io, () => io.pageRect(), (state) => Boolean(state && isObject(state.pointerUi) &&
      state.pointerUi.geometryReady === true && state.viewHandle === beforeNative.viewHandle), 'pinch-geometry', 8000);
    if (!frame.ok) return { ok: false, reason: 'pinch-geometry-not-ready', frame };
    return { ok: true, reason: 'completed', before: before.raw, after: changed.value.raw };
  })();
}

function sessionStorageCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.focusUrl, 'mobile');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const marker = require('node:crypto').randomBytes(16).toString('hex');
    const written = await io.siteState('write', marker);
    if (!written || written.set !== true) return { ok: false, reason: 'site-write-failed', written };
    let result = null;
    try {
      result = await (async () => {
        const oldSnapshot = await io.snapshot();
        const oldElements = oldSnapshot.ok && oldSnapshot.data && Array.isArray(oldSnapshot.data.elements) ? oldSnapshot.data.elements : [];
        const oldRef = oldElements.find((item) => item && item.selector)?.selector || null;
        if (!oldRef) return { ok: false, reason: 'old-ref-missing', oldSnapshot };
        const beforeRead = await io.siteState('read');
        if (!beforeRead || beforeRead.cookiePresent !== true || beforeRead.storagePresent !== true ||
            !beforeRead.cookieHash || beforeRead.cookieHash !== beforeRead.storageHash || !beforeRead.profileName) {
          return { ok: false, reason: 'site-read-before-failed', beforeRead };
        }
        const created = await io.sessionNew();
        if (!created.ok || !isObject(created.data) || !isText(created.data.sessionId)) return { ok: false, reason: 'session-new-failed', created };
        const oldUse = await io.click(oldRef);
        if (oldUse.ok || !['AUTHORIZATION_REQUIRED', 'REF_EXPIRED', 'NO_SESSION'].includes(oldUse.code)) {
          return { ok: false, reason: 'old-ref-not-refused', oldUse, sessionId: created.data.sessionId };
        }
        const reopened = await io.open(PRODUCT.focusUrl, 'mobile');
        if (!reopened.ok) return { ok: false, reason: 'new-session-open-failed', reopened };
        const afterRead = await io.siteState('read');
        if (!afterRead || afterRead.cookiePresent !== true || afterRead.storagePresent !== true ||
            afterRead.cookieHash !== beforeRead.cookieHash || afterRead.storageHash !== beforeRead.storageHash ||
            afterRead.profileName !== beforeRead.profileName) {
          return { ok: false, reason: 'site-state-not-preserved', beforeRead, afterRead };
        }
        const diag = await io.readDiag();
        if (!diag || diag.grantForSelected !== false || diag.agentOn !== false) return { ok: false, reason: 'grant-inherited', diag };
        const allowB = await allowOnce(io);
        if (!allowB.ok) return { ok: false, reason: 'second-allow-failed', allowB };
        const fresh = await io.snapshot();
        if (!fresh.ok) return { ok: false, reason: 'fresh-snapshot-failed', fresh };
        return { ok: true, reason: 'completed', sessionId: created.data.sessionId, beforeRead, afterRead };
      })();
    } finally {
      const cleared = await io.siteState('clear');
      if (!cleared || cleared.cleared !== true) {
        if (!result || result.ok === true) result = { ok: false, reason: 'site-clear-failed', cleared, previous: result };
      }
    }
    return result;
  })();
}

function nativeEntryCase(io) {
  return (async () => {
    // A is only the precondition target: one tool open, one human Allow. It gives us the old native
    // tab/view to prove B and the final Open target do not reuse. No later tool.open is allowed.
    const opened = await baseOpen(io, PRODUCT.focusUrl, 'mobile');
    if (!opened.ok) return opened;
    const allow = await allowOnce(io);
    if (!allow.ok) return allow;
    const before = await io.presentation();
    const beforeNative = fixtureNative(before);
    const beforeRect = await io.pageRect();
    const beforeDiag = await io.readDiag();
    if (!before || !beforeNative || !beforeRect || !beforeDiag || !isText(beforeDiag.selectedTabId) ||
        !isNum(beforeRect.viewHandle) || !Array.isArray(beforeDiag.tabIds)) {
      return { ok: false, reason: 'native-entry-before-missing', before, beforeRect, beforeDiag };
    }
    const oldTab = beforeDiag.selectedTabId;
    const oldView = beforeRect.viewHandle;
    const oldTabs = [...beforeDiag.tabIds];
    const sessionA = beforeNative && isText(beforeNative.owner) ? beforeNative.owner : null;

    // Mobile pinned entry: the sessions button opens the drawer; New chat is then one real tap and
    // creates only a draft (no server Session, no native tab). The drawer failure must stop here.
    const sessions = await io.tapChat('Open sessions and projects');
    if (!sessions.ok) return { ok: false, reason: 'sessions-drawer-missing', sessions };
    const newChatTarget = await waitFor(io, () => io.chatTarget('New chat'), (target) => target.ok === true, 'native-entry-new-chat-target', 12000);
    if (!newChatTarget.ok) return { ok: false, reason: 'new-chat-target-missing', sessions, newChatTarget };
    const newChat = await io.tapChat('New chat');
    if (!newChat.ok) return { ok: false, reason: 'new-chat-ui-missing', newChat };
    const revoked = await waitFor(io, () => io.readDiag(), (state) => Boolean(state &&
      state.grantForSelected === false && state.agentOn === false), 'native-entry-revoke', 15000);
    if (!revoked.ok) return { ok: false, reason: 'new-chat-did-not-revoke', revoked };
    const draftDiag = revoked.value;
    if (!Array.isArray(draftDiag.tabIds) || draftDiag.tabIds.length !== oldTabs.length ||
        oldTabs.some((tab) => !draftDiag.tabIds.includes(tab))) {
      return { ok: false, reason: 'new-chat-created-native-tab', oldTabs, draft: draftDiag };
    }

    // Actual B: production create + OpenSessionLink UI select; no LLM prompt, no bridge mutation.
    const created = await io.sessionNew();
    if (!created.ok || !isObject(created.data) || !isText(created.data.sessionId)) {
      return { ok: false, reason: 'session-new-failed', created };
    }
    const sessionB = created.data.sessionId;
    const afterSession = await io.readDiag();
    if (!afterSession || !Array.isArray(afterSession.tabIds) || !afterSession.tabIds.includes(oldTab)) {
      return { ok: false, reason: 'session-b-old-tab-missing', oldTab, afterSession };
    }

    // Exactly one visible workspace label is chosen from the two fixed candidates; no failed tap
    // then fallback. Only then is the Browser tab tapped.
    const workspace = await waitFor(io, async () => {
      for (const label of ['Open workspace panel', 'Open workspace panel, uncommitted changes']) {
        const target = await io.chatTarget(label);
        if (target.ok) return { ok: true, label, target: target.target };
      }
      return { ok: false };
    }, (value) => value.ok === true, 'native-entry-workspace-target', 15000);
    if (!workspace.ok) return { ok: false, reason: 'workspace-panel-target-missing', workspace };
    const workspaceTap = await io.tapChat(workspace.value.label);
    if (!workspaceTap.ok) return { ok: false, reason: 'workspace-panel-tap-failed', workspace: workspace.value, workspaceTap };

    const browserTarget = await waitFor(io, () => io.chatTarget('Browser'), (target) => target.ok === true, 'native-entry-browser-tab', 12000);
    if (!browserTarget.ok) return { ok: false, reason: 'browser-tab-target-missing', browserTarget };
    const browserTap = await io.tapChat('Browser');
    if (!browserTap.ok) return { ok: false, reason: 'browser-tab-tap-failed', browserTap };

    const addressTarget = await waitFor(io, () => io.chatTarget('Browser address'), (target) => target.ok === true, 'native-entry-address-target', 12000);
    if (!addressTarget.ok) return { ok: false, reason: 'browser-address-target-missing', addressTarget };
    const addressTap = await io.tapChat('Browser address');
    if (!addressTap.ok) return { ok: false, reason: 'browser-address-tap-failed', addressTap };

    const focusedAddress = await waitFor(io, () => io.chatState(), (state) => Boolean(state && state.ok === true &&
      state.sessionId === sessionB && state.address.present === true && state.address.focused === true &&
      state.address.enabled === true && state.address.readOnly === false &&
      state.main.hasFocus === true && state.documentHasFocus === true &&
      (state.address.value === '' || state.address.value === PRODUCT.focusUrl)), 'native-entry-address-focus', 15000);
    if (!focusedAddress.ok) return { ok: false, reason: 'browser-address-not-focused', sessionB, focusedAddress };
    const addressView = focusedAddress.value.main.viewHandle;

    const typed = await io.typeChatAddress(PRODUCT.focusUrl);
    if (!typed.ok) return { ok: false, reason: typed.reason || 'browser-address-type-failed', typed, sessionB };
    const exact = await waitFor(io, () => io.chatState(), (state) => Boolean(state && state.ok === true &&
      state.sessionId === sessionB && state.main.viewHandle === addressView &&
      documentReadyOk(state.documentReady) && state.address.value === PRODUCT.focusUrl), 'native-entry-address-value', 12000);
    if (!exact.ok) return { ok: false, reason: 'browser-address-value-not-exact', typed, sessionB, exact };

    const openTarget = await waitFor(io, () => io.chatTarget('Open'), (target) => target.ok === true, 'native-entry-open-target', 12000);
    if (!openTarget.ok) return { ok: false, reason: 'chat-open-target-missing', openTarget };
    const open = await io.tapChat('Open');
    if (!open.ok) return { ok: false, reason: 'chat-open-tap-failed', open };

    const entered = await waitFor(io, () => io.readDiag(), (state) => Boolean(state &&
      isText(state.selectedTabId) && state.selectedTabId !== oldTab && state.selectedTabId !== draftDiag.selectedTabId &&
      state.grantForSelected === false && state.agentOn === false &&
      Array.isArray(state.tabIds) && state.tabIds.includes(oldTab)), 'native-entry-open-tab', 20000);
    if (!entered.ok) return { ok: false, reason: 'native-entry-tab-not-created', oldTab, entered };
    const afterRect = await waitFor(io, () => io.pageRect(), (state) => Boolean(state &&
      isNum(state.viewHandle) && state.viewHandle !== oldView), 'native-entry-view', 15000);
    if (!afterRect.ok) return { ok: false, reason: 'native-entry-same-view', beforeView: oldView, afterRect };
    const afterView = afterRect.value.viewHandle;
    if (afterView === oldView) return { ok: false, reason: 'native-entry-same-view', beforeView: oldView, afterRect };
    const afterDiag = await io.readDiag();
    if (!afterDiag || afterDiag.grantForSelected !== false || afterDiag.agentOn !== false ||
        !Array.isArray(afterDiag.tabIds) || !afterDiag.tabIds.includes(oldTab)) {
      return { ok: false, reason: 'native-entry-after-identity', afterDiag };
    }
    return {
      ok: true,
      reason: 'completed',
      sessionA,
      sessionB,
      oldTab,
      oldView,
      afterTab: afterDiag.selectedTabId,
      afterView,
      oldTabsStillListed: afterDiag.tabIds.includes(oldTab),
      draftTabIds: draftDiag.tabIds,
      workspaceLabel: workspace.value.label,
      address: exact.value.address,
      addressCommand: typed.text || null,
      addressAlreadyExact: typed.alreadyExact === true,
      commands: {
        sessionsTap: sessions.commandId || null,
        newChatTap: newChat.commandId || null,
        workspaceTap: workspaceTap.commandId || null,
        browserTap: browserTap.commandId || null,
        addressTap: addressTap.commandId || null,
        openTap: open.commandId || null,
      },
    };
  })();
}

function profileCase(io) {
  return (async () => {
    const opened = await baseOpen(io, PRODUCT.focusUrl, 'mobile');
    if (!opened.ok) return opened;
    const data = await io.profileIsolation();
    if (!data || data.supported !== true || !isObject(data.profiles) ||
        !isBool(data.cookieStoresDistinct) || !Array.isArray(data.checks)) {
      return { ok: false, reason: 'profile-isolation-reader-invalid', data };
    }
    if (!data.cookieStoresDistinct || data.cleaned !== true) return { ok: false, reason: 'profile-isolation-not-clean', data };
    const profileNames = ['browser', 'console', 'other'].map((name) => data.profiles[name]);
    if (profileNames.some((name) => !isText(name)) || new Set(profileNames).size !== 3) {
      return { ok: false, reason: 'profile-names-invalid', data };
    }
    if (data.checks.length !== 3) return { ok: false, reason: 'profile-checks-count', data };
    const writers = data.checks.map((check) => check && check.writer);
    if (new Set(writers).size !== 3 || !['browser', 'console', 'other'].every((name) => writers.includes(name))) {
      return { ok: false, reason: 'profile-writers-invalid', data };
    }
    for (const check of data.checks) {
      if (!check || !['browser', 'console', 'other'].includes(check.writer) ||
          check.ownPresent !== true || !Array.isArray(check.otherWriters) || !Array.isArray(check.otherPresent) ||
          check.otherWriters.length !== 2 || check.otherPresent.length !== 2 ||
          check.otherWriters.some((name) => name === check.writer || !['browser', 'console', 'other'].includes(name)) ||
          new Set(check.otherWriters).size !== 2 ||
          check.otherPresent.some((value) => value !== false)) {
        return { ok: false, reason: 'profile-isolation-check-failed', data, check };
      }
    }
    return { ok: true, reason: 'completed', data };
  })();
}

const PRODUCT_CASE_FUNCTIONS = ({
  'product-blocked-tight': blockedCase,
  'product-focused-restore': focusedCase,
  'product-desktop-1024': desktopCase,
  'product-mouse-move-click': mouseMoveClickCase,
  'product-mouse-hover-a11y-off': mouseHoverA11yOffCase,
  'product-mouse-drag-wheel': mouseDragWheelCase,
  'product-pinch': pinchCase,
  'product-session-storage': sessionStorageCase,
  'product-console-profile-isolation': profileCase,
  'product-native-entry': nativeEntryCase,
});

for (const name of PENDING_PRODUCT_CASES) {
  PRODUCT_CASE_FUNCTIONS[name] = async () => ({ ok: false, reason: 'case-not-implemented' });
}
Object.freeze(PRODUCT_CASE_FUNCTIONS);

module.exports = {
  PRODUCT,
  PRODUCT_CASE_NAMES,
  IMPLEMENTED_PRODUCT_CASES,
  PENDING_PRODUCT_CASES,
  parseProductCatalog,
  readProductCatalog,
  createProductIo,
  PRODUCT_CASE_FUNCTIONS,
  prepareProductCase,
  baseOpen,
  allowOnce,
  acceptedComposerRect,
  createCaseFence,
  waitFor,
};
