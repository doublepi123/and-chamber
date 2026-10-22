const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

const source = readFileSync(
  resolve(__dirname, '../../main/java/io/andchamber/app/ui/browser/BrowserDriverLogic.kt'),
  'utf8',
);

/**
 * Builds the restore script exactly as BrowserDriverLogic.scrollRestoreScript does: the Kotlin template is
 * instantiated with the same placeholders, so the script that runs is the one the app generates, not a copy.
 */
function restoreScript({ documentId, x, y, targetHeightPx, nativeWidthPx, tolerance = 2.0 }) {
  const marker = source.indexOf('fun scrollRestoreScript(');
  const start = source.indexOf('"""(function(){', marker);
  const end = source.indexOf('"""', start + 3);
  let body = source.slice(start + 3, end);
  // Kotlin string templates in the body: $x $y $targetHeightPx $nativeWidthPx ${jsonQuote(documentId)} and the tolerance constant.
  body = body
    .replace('${jsonQuote(documentId)}', JSON.stringify(documentId))
    .replace('$x', String(x))
    .replace('$y', String(y))
    .replace('$targetHeightPx', String(targetHeightPx))
    .replace('$nativeWidthPx', String(nativeWidthPx))
    .replace('${WindowRestoreTolerance.PROJECTION_PX}', String(tolerance));
  assert.ok(!/\$\{|\$[a-zA-Z]/.test(body), `unresolved Kotlin template in generated script: ${body.match(/\$[^,)]*/g)}`);
  return body;
}

/**
 * A page: a witness, a visual viewport, and a scroll position. `scrollTo` is a TOP-LEVEL global of the context, so
 * the bare `scrollTo(...)` in the generated script resolves to this spy. It clamps the requested offset to the
 * scroll range, as a browser does, so the readback is the real position and not the requested value.
 */
function page({ witness = 'abcdef01', visualWidth = 1024, visualHeight = 315.0769, scrollMax = 489, scrollY = 0 }) {
  const state = { scrollTo: 0, scrollY, last: null };
  const context = {
    __acWitness: undefined,
    visualViewport: undefined,
    scrollX: 0,
    innerWidth: 1024,
    innerHeight: 1912,
    isFinite,
    Math,
    scrollTo(options) {
      state.scrollTo += 1;
      state.last = options;
      context.scrollY = Math.min(options.top, scrollMax);
    },
  };
  context.window = {
    __acWitness: { documentId: witness },
    visualViewport: { width: visualWidth, height: visualHeight, offsetLeft: 0, offsetTop: 0, scale: 1 },
  };
  context.scrollY = scrollY;
  vm.createContext(context);
  return { context, state };
}

function run(pageState, script) {
  // The script is an IIFE expression: evaluate it in the page context and return its value.
  return vm.runInContext(script, pageState.context);
}

test('the generated restore script is the app template with the real placeholders', () => {
  const script = restoreScript({ documentId: 'abcdef01', x: 0, y: 893, targetHeightPx: 252, nativeWidthPx: 819 });
  assert.ok(script.includes('witness!=="abcdef01"'));
  assert.ok(script.includes('819/v.width'));
  assert.equal(script.match(/scrollTo\(/g).length, 1, 'exactly one scrollTo in the template');
});

test('mismatched visual projection: zero scrollTo calls and an honest refusal', () => {
  // Native 819 wide, visual 1024 x 1911.97: projection is ~1529, not the 252 target. Must not scroll.
  const script = restoreScript({ documentId: 'abcdef01', x: 0, y: 893, targetHeightPx: 252, nativeWidthPx: 819 });
  const p = page({ visualWidth: 1024, visualHeight: 1911.9666 });
  const result = run(p, script);
  assert.equal(p.state.scrollTo, 0, 'no scroll was performed');
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'projection');
});

test('matching visual projection: exactly one instant scroll, and the readback is the actual position', () => {
  // Visual 1024 x 315.0769 projects 819 native to the 252 target. One scroll, then the real readback.
  const script = restoreScript({ documentId: 'abcdef01', x: 0, y: 893, targetHeightPx: 252, nativeWidthPx: 819 });
  const p = page({ visualWidth: 1024, visualHeight: 315.0769, scrollMax: 489 });
  const result = run(p, script);
  assert.equal(p.state.scrollTo, 1, 'exactly one scroll');
  assert.equal(p.state.last.behavior, 'instant');
  assert.equal(result.ok, true);
  assert.equal(result.scrollY, 489, 'the readback is the page actual position, clamped by the range');
  assert.notEqual(result.scrollY, 893, 'the requested value is not echoed as the actual');
});

test('wrong witness: refused before any projection or scroll', () => {
  const script = restoreScript({ documentId: 'deadbeef', x: 0, y: 893, targetHeightPx: 252, nativeWidthPx: 819 });
  const p = page({ witness: 'abcdef01', visualHeight: 315.0769 });
  const result = run(p, script);
  assert.equal(p.state.scrollTo, 0);
  assert.equal(result.reason, 'witness');
});

test('the returned object carries no witness echo and no diagnostic field', () => {
  const script = restoreScript({ documentId: 'abcdef01', x: 0, y: 893, targetHeightPx: 252, nativeWidthPx: 819 });
  const p = page({ visualHeight: 315.0769 });
  const result = run(p, script);
  assert.deepEqual(Object.keys(result).sort(), ['documentId', 'ok', 'scrollX', 'scrollY']);
  assert.equal(result.documentId, 'abcdef01', 'the witness is checked, the returned id equals the expected one');
});
