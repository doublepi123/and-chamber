const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

const source = readFileSync(
  resolve(__dirname, '../../main/java/io/andchamber/app/ui/browser/BrowserDriverLogic.kt'),
  'utf8',
);
const marker = source.indexOf('fun desktopViewportScript(): String = """');
const script = source.slice(marker).split('"""')[1];

function install() {
  const queue = [];
  const nodes = [];
  let observer = null;
  let mutations = 0;
  function Meta() {
    this.attributes = {};
    this.isConnected = false;
    this.parent = null;
  }
  Meta.prototype.getAttribute = function (name) { return this.attributes[name] ?? null; };
  Meta.prototype.setAttribute = function (name, value) {
    this.attributes[name] = value;
    mutations += 1;
    if (observer) queue.push(() => observer([this]));
  };
  Meta.prototype.remove = function () {
    this.isConnected = false;
    const index = nodes.indexOf(this);
    if (index >= 0) nodes.splice(index, 1);
  };
  const document = {
    documentElement: { nodeName: 'HTML' },
    head: { appendChild(node) { node.isConnected = true; node.parent = this; nodes.push(node); } },
    createElement() { return new Meta(); },
    querySelectorAll(selector) {
      assert.equal(selector, 'meta[name="viewport"]');
      return nodes.filter(node => node.getAttribute('name') === 'viewport');
    },
  };
  function MutationObserver(callback) { this.callback = callback; observer = callback; }
  MutationObserver.prototype.observe = function () {};
  MutationObserver.prototype.disconnect = function () { if (observer === this.callback) observer = null; };
  const window = { MutationObserver };
  vm.runInNewContext(script, { window, document, MutationObserver });
  function flush(limit = 20) {
    let steps = 0;
    while (queue.length) {
      assert.ok(++steps <= limit, 'observer did not settle');
      queue.shift()();
    }
    return steps;
  }
  return { document, window, nodes, flush, mutations: () => mutations };
}

test('a late site viewport meta is pinned to width 1024 and then the observer stops', () => {
  const page = install();
  page.flush();
  const site = page.document.createElement();
  site.name = 'viewport';
  site.setAttribute('name', 'viewport');
  site.setAttribute('content', 'width=device-width');
  page.document.head.appendChild(site);
  page.flush();
  assert.equal(site.getAttribute('content'), 'width=1024');
  const before = page.mutations();
  site.setAttribute('content', 'width=device-width, initial-scale=1');
  page.flush();
  assert.equal(site.getAttribute('content'), 'width=1024');
  const after = page.mutations();
  page.flush();
  assert.equal(page.mutations(), after);
  assert.ok(after > before);
});

test('stop disconnects the observer and removes only the owned meta', () => {
  const page = install();
  page.flush();
  const site = page.document.createElement();
  site.setAttribute('name', 'viewport');
  site.setAttribute('content', 'width=device-width');
  page.document.head.appendChild(site);
  page.flush();
  page.window.__acDesktopViewport.stop();
  assert.equal(page.window.__acDesktopViewport, null);
  assert.equal(page.nodes.includes(site), true);
  assert.equal(page.nodes.some(node => node.getAttribute('data-ac-viewport') === 'desktop'), false);
  const before = page.mutations();
  site.setAttribute('content', 'width=device-width');
  page.flush();
  assert.equal(site.getAttribute('content'), 'width=device-width');
  assert.equal(page.mutations(), before + 1);
});

test('the script does not lock pinch scale', () => {
  assert.equal(script.includes('user-scalable=no'), false);
  assert.equal(script.includes('maximum-scale'), false);
  assert.equal(script.includes('initial-scale=1'), false);
});
