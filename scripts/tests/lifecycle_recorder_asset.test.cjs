const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../app/src/androidTest/assets/lifecycle-recorder.js'), 'utf8');

/**
 * One document. [bridge] is the native per-view object: installed/record are the only entry points,
 * and it records what the native side accepted. The fake native side is a stand-in for the Kotlin
 * LifecycleEventRecorder.Bridge, so these checks run without a device.
 */
function documentFor(origin, bridgeOverride) {
  const calls = { installed: [], record: [] };
  const listeners = {};
  const bridge = bridgeOverride || {
    installed(timeOrigin) { calls.installed.push(timeOrigin); return true; },
    record(timeOrigin, target) { calls.record.push({ timeOrigin, target }); return true; },
  };
  const context = {
    window: {},
    document: { addEventListener(type, listener) { listeners[type] = listener; } },
    performance: { timeOrigin: origin },
    LifecycleEventRecorder: bridge,
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  return { context, listeners, calls };
}

test('the script reports its own document to the native handshake exactly once', () => {
  const doc = documentFor(10);
  assert.deepEqual(doc.calls.installed, ['10']);
  assert.equal(doc.context.window.__lifecycleRecorderInstalled, true);
});

test('a repeated script run in the same document does not install twice', () => {
  const doc = documentFor(10);
  vm.runInContext(source, doc.context);
  assert.deepEqual(doc.calls.installed, ['10']);
  assert.equal(Object.keys(doc.listeners).length, 1);
});

test('without the native bridge the script installs nothing and reports nothing', () => {
  const context = { window: {}, document: { addEventListener() { throw new Error('no listener expected'); } }, performance: { timeOrigin: 5 }, LifecycleEventRecorder: {} };
  vm.createContext(context);
  vm.runInContext(source, context);
  assert.equal(context.window.__lifecycleRecorderInstalled, undefined);
});

test('a click reports only the short tag list, never arbitrary text', () => {
  const doc = documentFor(10);
  doc.listeners.click({ target: { id: 'next', textContent: 'secret text' } });
  doc.listeners.click({ target: { id: 'something-else' } });
  doc.listeners.click({ target: { id: 42 } });
  assert.deepEqual(doc.calls.record, [
    { timeOrigin: '10', target: 'next' },
    { timeOrigin: '10', target: null },
    { timeOrigin: '10', target: null },
  ]);
});

test('two documents each report their own origin and their clicks are separate', () => {
  const first = documentFor(10);
  first.listeners.click({ target: { id: 'next' } });
  const second = documentFor(20);
  second.listeners.click({ target: { id: 'box' } });
  assert.deepEqual(second.calls.installed, ['20']);
  assert.deepEqual(first.calls.record, [{ timeOrigin: '10', target: 'next' }]);
  assert.deepEqual(second.calls.record, [{ timeOrigin: '20', target: 'box' }]);
});
