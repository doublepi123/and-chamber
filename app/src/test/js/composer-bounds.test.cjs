// Fixed Android UI observer only: no browser, credentials or input content are needed.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const script = readFileSync(resolve(__dirname, '../../main/java/io/andchamber/app/ui/web/ComposerBoundsScript.kt'), 'utf8').split('"""')[1];

const SLOTS = '[data-chat-column="main"] [data-composer-slot]';
const INPUT = '[data-chat-column="main"] [data-chat-input="true"]';
const FOOTER = '[data-chat-input-footer="true"]';

function box({ top = 600, bottom = 780, left = 0, right = 400, width = 400, height = 180 } = {}) {
  return { top, bottom, left, right, width, height };
}

function node(rect, style = {}) {
  const element = {
    rect,
    style: { display: 'flex', visibility: 'visible', opacity: '1', ...style },
    getBoundingClientRect: () => element.rect,
  };
  Object.defineProperty(element, 'innerText', { get: () => { throw Error('must not read text'); } });
  return element;
}

function fixture({ frame = false, withSlot = true } = {}) {
  const reports = [], pending = [], events = {}, inputs = [], footers = [], children = [];
  const rect = box();
  const slot = node(rect);
  slot.querySelectorAll = selector => {
    assert.equal(selector, '[data-composer-box]'); return children;
  };
  const document = {
    documentElement: {},
    querySelectorAll: selector => {
      if (selector === SLOTS) return withSlot ? [slot] : [];
      if (selector === INPUT) return inputs;
      if (selector === FOOTER) return footers;
      throw Error('unexpected selector ' + selector);
    },
    addEventListener: (name, callback) => { events[name] = callback; },
  };
  const viewport = { width: 400, height: 800, offsetTop: 0, offsetLeft: 0, addEventListener: (n, cb) => { events['visual-' + n] = cb; } };
  const window = { visualViewport: viewport, addEventListener: (n, cb) => { events[n] = cb; } };
  window.top = frame ? {} : window;
  class Observer { constructor(cb) { events.observe = cb; } observe() {} unobserve() {} disconnect() {} }
  vm.runInNewContext(script, { window, document, visualViewport: viewport, innerWidth: 400, innerHeight: 800,
    bridge: { composerBounds: (...args) => reports.push(args.slice(1)) }, token: 'document-proof',
    ResizeObserver: Observer, MutationObserver: Observer,
    getComputedStyle: element => element.style,
    setTimeout: cb => pending.push(cb), requestAnimationFrame: cb => pending.push(cb),
  });
  function flush() { let count = 0; while (pending.length) { assert.ok(++count < 10); pending.shift()(); } }
  return { reports, pending, events, rect, viewport, flush, children, inputs, footers, slot };
}

test('targets stock composer markup, never the removed patch marker', () => {
  assert.match(script, /\[data-chat-column="main"\] \[data-composer-slot\]/);
  assert.match(script, /\[data-chat-column="main"\] \[data-chat-input="true"\]/);
  assert.match(script, /\[data-chat-input-footer="true"\]/);
  assert.doesNotMatch(script, /data-andchamber-chat-composer/);
  assert.doesNotMatch(script, /data-chat-input="comment"/);
});

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
test('pagehide stops reports and a native request forces a fresh report', () => {
  const f = fixture(); f.flush(); f.events['andchamber-composer-measure'](); f.flush();
  assert.equal(f.reports.length,2); assert.deepEqual(f.reports[0],f.reports[1]);
  f.events.pagehide(); f.events.observe(); f.flush();
  assert.equal(f.reports.length,2);
});
test('expanded mobile composer measures its fixed box when the marked slot has zero height', () => {
  const f = fixture();
  f.rect.top = f.rect.bottom = 800; f.rect.height = 0;
  const child = box({ top: 400, bottom: 780, left: 12, right: 388, width: 376, height: 380 });
  f.children.push(node(child)); f.flush();
  assert.deepEqual(f.reports[0], [400,780,400,800,true]);
  child.top = 200; child.height = 580; f.events.observe(); f.flush();
  assert.deepEqual(f.reports[1], [200,780,400,800,true]);
});
test('a missing slot falls back to the main chat input, not the comment box', () => {
  const f = fixture({ withSlot: false });
  f.inputs.push(node(box({ top: 520, bottom: 700 })));
  f.footers.push(node(box({ top: 700, bottom: 780 })));
  f.flush();
  assert.deepEqual(f.reports, [[520,700,400,800,true]]);
});
test('a hidden slot falls back to the main chat input', () => {
  const f = fixture(); f.slot.style.display = 'none';
  f.inputs.push(node(box({ top: 520, bottom: 700 })));
  f.flush();
  assert.deepEqual(f.reports, [[520,700,400,800,true]]);
});
test('the footer is the last resort when neither slot nor input is present', () => {
  const f = fixture({ withSlot: false });
  f.footers.push(node(box({ top: 700, bottom: 780 })));
  f.flush();
  assert.deepEqual(f.reports, [[700,780,400,800,true]]);
});
test('nothing measurable reports an invisible composer exactly once', () => {
  const f = fixture({ withSlot: false }); f.flush();
  assert.deepEqual(f.reports, [[0,0,400,800,false]]);
  f.events['andchamber-composer-measure'](); f.flush();
  assert.equal(f.reports.length,2);
});

test('stock ChatContainer carries the composer slot and no patch marker', {skip: !process.env.OPENCHAMBER_SOURCE}, () => {
  const source = readFileSync(resolve(process.env.OPENCHAMBER_SOURCE, 'packages/ui/src/components/chat/ChatContainer.tsx'), 'utf8');
  assert.match(source, /data-composer-slot/);
  assert.doesNotMatch(source, /data-andchamber-chat-composer/);
});
