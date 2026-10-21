const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync(require('node:path').join(__dirname, '../../app/src/androidTest/assets/tap-label.js'), 'utf8');

function run(label, document, viewport) {
  const context = {
    document,
    window: { visualViewport: viewport },
    innerWidth: viewport.width,
    innerHeight: viewport.height,
    JSON,
    Math,
    Number,
  };
  vm.createContext(context);
  vm.runInContext(`${source}; result = tapLabelPoint(${JSON.stringify(label)});`, context);
  return context.result;
}

test('desktop button uses the visible intersection center', () => {
  const button = { disabled: false, contains: () => false, getBoundingClientRect: () => ({ left: 12, top: 59.42857360839844, right: 912, bottom: 106.85714721679688, width: 900, height: 47.42857360839844 }) };
  const document = {
    querySelector: () => button,
    elementFromPoint: (x, y) => Math.abs(x - 285.0476145744324) < 0.001 && Math.abs(y - 83.14286041259766) < 0.001 ? button : { tag: 'other' },
  };
  const result = run('Sign in', document, { offsetLeft: 11.809523582458496, offsetTop: 0, width: 546.2857055664062, height: 1020 });
  assert.equal(Math.abs(result.x - 285.0476145744324) < 0.001, true);
  assert.equal(result.fullyVisible, false);
  assert.equal(result.pointVisible, true);
  assert.equal(result.hit, true);
  assert.equal(Math.round(result.x), 285);
  assert.equal(Math.abs(result.y - 83.14286041259766) < 0.001, true);
});

test('outside, covered, disabled, empty, and non-finite targets miss', () => {
  const outside = run('Sign in', {
    querySelector: () => ({ disabled: false, getBoundingClientRect: () => ({ left: 900, top: 10, right: 980, bottom: 40, width: 80, height: 30 }) }),
    elementFromPoint: () => null,
  }, { offsetLeft: 0, offsetTop: 0, width: 500, height: 500 });
  assert.equal(outside.pointVisible, false);
  assert.equal(outside.hit, false);
  const covered = run('Sign in', {
    querySelector: () => ({ disabled: false, contains: () => false, getBoundingClientRect: () => ({ left: 10, top: 10, right: 40, bottom: 40, width: 30, height: 30 }) }),
    elementFromPoint: () => ({ tag: 'overlay' }),
  }, { offsetLeft: 0, offsetTop: 0, width: 500, height: 500 });
  assert.equal(covered.hit, false);
  const disabled = run('Sign in', {
    querySelector: () => ({ disabled: true, contains: () => false, getBoundingClientRect: () => ({ left: 10, top: 10, right: 40, bottom: 40, width: 30, height: 30 }) }),
    elementFromPoint: (x, y) => x && y && ({ disabled: true }),
  }, { offsetLeft: 0, offsetTop: 0, width: 500, height: 500 });
  assert.equal(disabled.hit, false);
  const empty = run('Sign in', {
    querySelector: () => ({ disabled: false, contains: () => false, getBoundingClientRect: () => ({ left: 10, top: 10, right: 10, bottom: 40, width: 0, height: 30 }) }),
    elementFromPoint: () => null,
  }, { offsetLeft: 0, offsetTop: 0, width: 500, height: 500 });
  assert.equal(empty.pointVisible, false);
  const broken = run('Sign in', {
    querySelector: () => ({ disabled: false, contains: () => false, getBoundingClientRect: () => ({ left: 10, top: Number.NaN, right: 40, bottom: 40, width: 30, height: 30 }) }),
    elementFromPoint: () => null,
  }, { offsetLeft: 0, offsetTop: 0, width: 500, height: 500 });
  assert.equal(broken.pointVisible, false);
});
