'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const FIXTURES = path.join(
  __dirname, '..', '..', 'app', 'src', 'androidTest', 'java',
  'io', 'andchamber', 'app', 'browser', 'OpenChamberProviderFixtures.kt',
);
const SUPPORT = path.join(
  __dirname, '..', '..', 'app', 'src', 'androidTest', 'java',
  'io', 'andchamber', 'app', 'browser', 'LocalProviderRegressionSupport.kt',
);
const SOURCE = fs.readFileSync(FIXTURES, 'utf8');
const SUPPORT_SOURCE = fs.readFileSync(SUPPORT, 'utf8');

function kotlinConst(name) {
  const match = new RegExp(`const val ${name} = "([^"]+)"`).exec(SOURCE);
  assert.ok(match, `missing ${name}`);
  return match[1];
}

const LOGIN_URL = kotlinConst('LOGIN_URL');
const ORIGIN = kotlinConst('ORIGIN');

const SCRIPT_MATCH = /private const val FIXTURE_STATE_SCRIPT = """([\s\S]*?)"""/.exec(SUPPORT_SOURCE);
assert.ok(SCRIPT_MATCH, 'FIXTURE_STATE_SCRIPT must stay extractable');
const SCRIPT = SCRIPT_MATCH[1];

function input(overrides = {}) {
  return {
    tagName: 'INPUT',
    value: 'fixture-user',
    disabled: false,
    readOnly: false,
    selectionStart: 2,
    selectionEnd: 9,
    ...overrides,
  };
}

function run(overrides = {}) {
  const win = {
    __posts: overrides.posts === undefined ? 0 : overrides.posts,
    __loads: overrides.loads === undefined ? 1 : overrides.loads,
    __clicks: overrides.clicks === undefined ? undefined : overrides.clicks,
    clicks: overrides.clickCount === undefined ? 0 : overrides.clickCount,
  };
  const sandbox = {
    location: { href: overrides.href || LOGIN_URL },
    document: {
      readyState: 'complete',
      hasFocus: overrides.hasFocus === undefined ? () => true : overrides.hasFocus,
      activeElement: overrides.activeElement === undefined ? { tagName: 'BODY' } : overrides.activeElement,
      querySelector(selector) {
        if (selector.includes('login-user')) return overrides.input === undefined ? input() : overrides.input;
        if (selector === 'form') return overrides.form === undefined ? {} : overrides.form;
        return null;
      },
    },
    window: win,
    sessionStorage: {
      getItem: (key) => (overrides.storage && Object.prototype.hasOwnProperty.call(overrides.storage, key)
        ? overrides.storage[key]
        : null),
    },
    performance: { timeOrigin: overrides.timeOrigin === undefined ? 42.5 : overrides.timeOrigin },
    JSON,
    Array,
    String,
    Number,
    isFinite,
  };
  win.location = sandbox.location;
  const value = vm.runInNewContext(SCRIPT, sandbox);
  assert.equal(typeof value, 'string');
  return JSON.parse(value);
}

test('document focus or native focus is not DOM input focus', () => {
  const out = run({
    href: LOGIN_URL,
    hasFocus: () => true,
    activeElement: { tagName: 'BODY' },
    input: input({ value: 'fixture-user' }),
  });
  assert.equal(out.documentHasFocus, true);
  assert.equal(out.inputFocused, false);
  assert.equal(out.inputValue, 'fixture-user');
  assert.equal(out.inputEnabled, true);
  assert.equal(out.inputReadOnly, false);
  assert.equal(out.inputTag, 'INPUT');
});

test('focused input keeps the real value, selection and finite timeOrigin', () => {
  const field = input({
    value: 'fixture-user',
    selectionStart: 3,
    selectionEnd: 7,
  });
  const out = run({
    href: LOGIN_URL,
    hasFocus: () => true,
    activeElement: field,
    input: field,
    timeOrigin: 1234.5,
  });
  assert.equal(out.inputFocused, true);
  assert.equal(out.inputValue, 'fixture-user');
  assert.equal(out.selectionStart, 3);
  assert.equal(out.selectionEnd, 7);
  assert.equal(out.timeOrigin, 1234.5);
});

test('readonly and disabled are reported as independent real fields', () => {
  const out = run({
    href: LOGIN_URL,
    input: input({ readOnly: true, disabled: true, value: 'fixture-user' }),
  });
  assert.equal(out.inputEnabled, false);
  assert.equal(out.inputReadOnly, true);
  assert.equal(out.inputValue, 'fixture-user');
});

test('a missing login input is null, not false or zero', () => {
  const out = run({
    href: LOGIN_URL,
    input: null,
    activeElement: { tagName: 'BODY' },
  });
  assert.equal(out.inputValue, null);
  assert.equal(out.inputEnabled, null);
  assert.equal(out.inputReadOnly, null);
  assert.equal(out.inputTag, null);
  assert.equal(out.selectionStart, null);
  assert.equal(out.selectionEnd, null);
  assert.equal(out.inputFocused, false);
});

test('only the exact LOGIN_URL may expose a login input', () => {
  const out = run({
    href: ORIGIN,
    input: input({ value: 'must-not-leak' }),
    activeElement: input(),
  });
  assert.equal(out.inputValue, null);
  assert.equal(out.inputEnabled, null);
  assert.equal(out.inputReadOnly, null);
  assert.equal(out.inputTag, null);
  assert.equal(out.selectionStart, null);
  assert.equal(out.selectionEnd, null);
  assert.equal(out.inputFocused, false);
});

test('non-finite timeOrigin and missing hasFocus stay null', () => {
  const out = run({
    href: LOGIN_URL,
    hasFocus: null,
    timeOrigin: Infinity,
  });
  assert.equal(out.documentHasFocus, null);
  assert.equal(out.timeOrigin, null);
});

test('real posts/loads are still the actual page values', () => {
  const out = run({ href: LOGIN_URL, posts: 2, loads: 5, clickCount: 4 });
  assert.equal(out.posts, 2);
  assert.equal(out.loads, 5);
  assert.equal(out.clicks, 4);
});
