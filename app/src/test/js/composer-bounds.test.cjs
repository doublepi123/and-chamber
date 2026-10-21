// Fixed Android UI observer only: no browser, credentials or input content are needed.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const script = readFileSync(resolve(__dirname, '../../main/java/io/andchamber/app/ui/web/ComposerBoundsScript.kt'), 'utf8').split('"""')[1];

function fixture({ frame = false } = {}) {
  const reports = [], pending = [], events = {};
  const rect = { top: 600, bottom: 780, left: 0, right: 400, width: 400, height: 180 };
  const children = [];
  const node = { getBoundingClientRect: () => rect, querySelectorAll: selector => {
    assert.equal(selector, '[data-composer-box]'); return children;
  } };
  Object.defineProperty(node, 'innerText', { get: () => { throw Error('must not read text'); } });
  const viewport = { width: 400, height: 800, offsetTop: 0, offsetLeft: 0, addEventListener: (n, cb) => { events['visual-' + n] = cb; } };
  const document = { documentElement: {}, querySelectorAll: selector => {
    assert.equal(selector, '[data-andchamber-chat-composer]'); return [node];
  }, addEventListener: (n, cb) => { events[n] = cb; } };
  const window = { visualViewport: viewport, addEventListener: (n, cb) => { events[n] = cb; } };
  window.top = frame ? {} : window;
  class Observer { constructor(cb) { events.observe = cb; } observe() {} unobserve() {} disconnect() {} }
  vm.runInNewContext(script, { window, document, visualViewport: viewport, innerWidth: 400, innerHeight: 800,
    bridge: { composerBounds: (...args) => reports.push(args.slice(1)) }, token: 'document-proof',
    ResizeObserver: Observer, MutationObserver: Observer, getComputedStyle: () => ({display:'flex', visibility:'visible', opacity:'1'}),
    setTimeout: cb => pending.push(cb), requestAnimationFrame: cb => pending.push(cb),
  });
  function flush() { let count = 0; while (pending.length) { assert.ok(++count < 10); pending.shift()(); } }
  return { reports, pending, events, rect, viewport, flush, children };
}

test('multiline growth changes top and includes the footer without reading text', () => {
  const f = fixture(); f.flush(); assert.deepEqual(f.reports[0], [600,780,400,800,true]);
  f.rect.top = 400; f.rect.height = 380; f.events.observe(); f.flush();
  assert.deepEqual(f.reports[1], [400,780,400,800,true]);
});
test('visual viewport offset is subtracted once and resize bursts coalesce', () => {
  const f = fixture(); f.viewport.offsetTop = 100; f.viewport.height = 700;
  for (let i=0;i<100;i++) f.events['visual-resize']();
  assert.equal(f.pending.length,1); f.flush();
  assert.deepEqual(f.reports[0], [500,680,400,700,true]);
  f.events.observe(); f.flush(); assert.equal(f.reports.length,1);
});
test('subframes and invalid viewport cannot report', () => {
  const child = fixture({frame:true}); child.flush(); assert.equal(child.reports.length,0);
  const f = fixture(); f.viewport.width = Infinity; f.flush(); assert.equal(f.reports.length,0);
});
test('pagehide stops reports and host rebind forces a fresh report', () => {
  const f = fixture(); f.flush(); f.events['andchamber-browser-host-ready'](); f.flush();
  assert.equal(f.reports.length,2); f.events.pagehide(); f.events.observe(); f.flush();
  assert.equal(f.reports.length,2);
});
test('native resize can request a fresh measurement with unchanged CSS bounds', () => {
  const f = fixture(); f.flush(); f.events['andchamber-composer-measure'](); f.flush();
  assert.equal(f.reports.length,2); assert.deepEqual(f.reports[0],f.reports[1]);
});
test('expanded mobile composer measures its fixed child when the marked slot has zero height', () => {
  const f = fixture();
  f.rect.top = f.rect.bottom = 800; f.rect.height = 0;
  const child = {top: 400, bottom: 780, left: 12, right: 388, width: 376, height: 380};
  f.children.push({getBoundingClientRect: () => child}); f.flush();
  assert.deepEqual(f.reports[0], [400,780,400,800,true]);
  child.top = 200; child.height = 580; f.events.observe(); f.flush();
  assert.deepEqual(f.reports[1], [200,780,400,800,true]);
});

test('source marker wraps the common ChatInput, not just the editable field', {skip: !process.env.OPENCHAMBER_SOURCE}, () => {
  const source = readFileSync(resolve(process.env.OPENCHAMBER_SOURCE, 'packages/ui/src/components/chat/ChatContainer.tsx'), 'utf8');
  assert.match(source, /ref=\{attachComposerSlot\}\s+data-andchamber-chat-composer=""/);
  const marker = source.indexOf('data-andchamber-chat-composer');
  assert.ok(source.indexOf('<ChatInput', marker) > marker);
});
