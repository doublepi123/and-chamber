const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');

const source = readFileSync(
  resolve(__dirname, '../../main/java/io/andchamber/app/ui/browser/BrowserDriverLogic.kt'),
  'utf8',
);
const marker = source.indexOf('fun desktopViewportScript(');
assert.ok(marker >= 0, 'desktop viewport generator is present');
const template = source.slice(marker).split('"""')[1];
assert.equal(typeof template, 'string');
const desktopScript = (width = 1920) => template.replace(/\$width\b/g, String(width));
const content = (width, dpr) => `width=${width}, initial-scale=${1 / dpr}, minimum-scale=${1 / dpr}`;

function install(width = 1920, dpr = 2.625) {
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
  const window = { MutationObserver, devicePixelRatio: dpr };
  vm.runInNewContext(desktopScript(width), { window, document, MutationObserver });
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

test('a late site viewport meta is pinned to the default desktop width and real DPR scale', () => {
  const page = install();
  page.flush();
  const site = page.document.createElement();
  site.name = 'viewport';
  site.setAttribute('name', 'viewport');
  site.setAttribute('content', 'width=device-width');
  page.document.head.appendChild(site);
  page.flush();
  assert.equal(site.getAttribute('content'), content(1920, 2.625));
  const before = page.mutations();
  site.setAttribute('content', 'width=device-width, initial-scale=1');
  page.flush();
  assert.equal(site.getAttribute('content'), content(1920, 2.625));
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
  const script = desktopScript();
  assert.equal(script.includes('user-scalable=no'), false);
  assert.equal(script.includes('maximum-scale'), false);
  assert.equal(script.includes('initial-scale=1'), false);
});

test('custom width updates preserve the captured real DPR without spoofing it', () => {
  const page = install(1366, 3);
  page.flush();
  assert.equal(page.nodes[0].getAttribute('content'), content(1366, 3));
  assert.equal(page.window.devicePixelRatio, 3);
  page.window.__acDesktopViewport.setWidth(1280);
  page.flush();
  assert.equal(page.nodes[0].getAttribute('content'), content(1280, 3));
  assert.equal(page.window.devicePixelRatio, 3);
});
