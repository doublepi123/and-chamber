'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('the actual local fixture observer reads visible text without embedded script source', () => {
  const kotlin = fs.readFileSync(path.join(__dirname, '../../app/src/androidTest/java/io/andchamber/app/browser/LocalProviderRuntimeSupport.kt'), 'utf8');
  const script = kotlin.match(/private const val FIXTURE_DOCUMENT_SCRIPT = """([\s\S]*?)"""/)[1];
  const nodes = { '#note': { tagName: 'INPUT' }, 'button#apply': { tagName: 'BUTTON' }, '#framed': { innerText: 'framed:top', textContent: "framed:document.write(window.top===window.self?'top':'nested')top" } };
  const observed = JSON.parse(vm.runInNewContext(script, { window: { __acWitness: { documentId: 'a'.repeat(32) } }, location: { href: 'https://example.com/openchamber-provider' }, document: { readyState: 'complete', querySelector: selector => nodes[selector] } }));
  assert.equal(observed.framedText, 'framed:top');
  assert.equal(observed.notePresent, true);
  assert.equal(observed.applyPresent, true);
});
