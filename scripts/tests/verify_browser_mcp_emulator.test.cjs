'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const verifier = require('../verify_browser_mcp_emulator.cjs');

test('serial is emulator only and out is absolute', () => {
  assert.throws(() => verifier.parseArgs(['--serial', 'device', '--out', '/tmp/out']), /emulator/);
  assert.equal(verifier.parseArgs(['--serial', 'emulator-5580', '--out', '/tmp/opencode/embedded-browser-mcp-e2e']).serial, 'emulator-5580');
});

test('adb shell command is one quoted run-as argument', () => {
  const argv = verifier.writeCommandArgv('emulator-5580');
  assert.deepEqual(argv, [
    '-s',
    'emulator-5580',
    'shell',
    "run-as io.andchamber.app sh -c 'tmp=$(mktemp files/browser-mcp-e2e/command.tmp.XXXXXX) && cat > \"$tmp\" && mv -f \"$tmp\" files/browser-mcp-e2e/command.json'",
  ]);
  assert.deepEqual(verifier.credentialExistsArgv('emulator-5580'), [
    '-s',
    'emulator-5580',
    'shell',
    'run-as io.andchamber.app test -f files/browser-mcp-e2e/credential.json',
  ]);
  assert.deepEqual(verifier.statusArgv('emulator-5580').slice(2), [
    'exec-out',
    'run-as',
    'io.andchamber.app',
    'cat',
    'files/browser-mcp-e2e/status.json',
  ]);
});

test('endpoint mismatch never spawns or exposes the token', () => {
  let spawned = false;
  assert.throws(() => verifier.openIfEndpoint(
    { endpoint: 'http://127.0.0.1:9/v1/command', token: 'secret-token' },
    10,
    () => { spawned = true; },
  ), (error) => {
    assert.equal(error.message.includes('secret-token'), false);
    return /endpoint mismatch/.test(error.message);
  });
  assert.equal(spawned, false);
  assert.equal(verifier.openIfEndpoint({ endpoint: 'http://127.0.0.1:10/v1/command' }, 10, () => 'opened'), 'opened');
});

test('navigation ref comes from the post-scroll reset snapshot', async () => {
  const reset = { url: verifier.SECOND_URL, elements: [{ role: 'button', name: 'Apply', ref: 'after-reset' }, { role: 'input', name: 'note', ref: 'new-note' }] };
  const used = [];
  await verifier.proveReplacement({
    read: async () => reset,
    refFrom: (snapshot) => {
      const ref = snapshot.elements[0].ref;
      used.push(ref);
      return ref;
    },
    staleRef: (ref) => ref === 'before-scroll',
    navigate: async () => {},
    oldRefFails: async (ref) => ref === 'after-reset',
    type: async (ref) => used.push(`type:${ref}`),
  });
  assert.equal(used[0], 'after-reset');
  assert.equal(used.at(-1), 'type:after-reset');
  assert.equal(used.includes('before-scroll'), false);
  await assert.rejects(() => verifier.proveReplacement({
    read: async () => ({ url: verifier.SECOND_URL, elements: [{ ref: 'before-scroll' }] }),
    refFrom: (snapshot) => snapshot.elements[0].ref,
    staleRef: (ref) => ref === 'before-scroll',
    navigate: async () => { throw new Error('navigated with stale ref'); },
    oldRefFails: async () => true,
    type: async () => {},
  }), /stale before navigation/);
});

test('replacement proof rejects the old ref before reading the new snapshot', async () => {
  const order = [];
  await verifier.proveReplacement({
    read: async () => {
      order.push('read');
      return order.filter((item) => item === 'read').length === 1
        ? { url: 'https://example.com/browser-mcp-e2e', elements: [{ role: 'button', name: 'Apply', ref: 'old' }] }
        : { url: verifier.SECOND_URL, elements: [{ role: 'input', name: 'note', ref: 'new' }] };
    },
    refFrom: (snapshot) => snapshot.elements[0].ref,
    navigate: async () => { order.push('navigate'); },
    oldRefFails: async (ref) => {
      order.push(`reject:${ref}`);
      return true;
    },
    type: async (ref) => { order.push(`type:${ref}`); },
  });
  assert.deepEqual(order, ['read', 'navigate', 'reject:old', 'read', 'type:new']);
});

test('click-count 10 is not accepted as 1 and scroll-y is numeric', () => {
  assert.equal(verifier.labeledInt('click-count:10', 'click-count'), 10);
  assert.notEqual(verifier.labeledInt('click-count:10', 'click-count'), 1);
  assert.equal(verifier.labeledInt('click-count:1', 'click-count'), 1);
  assert.equal(verifier.snapshotLabel({ text: 'scroll-y:12' }, 'scroll-y') > 0, true);
  assert.equal(verifier.snapshotLabel({ text: 'scrollY:12' }, 'scroll-y'), null);
  assert.equal(verifier.noteWithValue({ elements: [{ name: 'note', value: 'emulator-marker' }] }, 'emulator-marker').name, 'note');
  assert.equal(verifier.noteWithValue({ elements: [{ name: 'other', value: 'emulator-marker' }] }, 'emulator-marker'), undefined);
});

test('deadline exhaustion fails fast and cleanup nonzero fails', () => {
  assert.throws(() => verifier.boundedTimeout(8000, 0), /deadline exhausted/);
  assert.equal(verifier.boundedTimeout(8000, 100), 100);
  assert.equal(verifier.remainingMs(0, 100000, 110000, 10000), 0);
  const report = { checks: [], add(name, ok, detail) { this.checks.push({ name, result: ok ? 'PASS' : 'FAIL', detail }); } };
  verifier.noteCleanup(report, 1);
  assert.equal(report.checks[0].result, 'FAIL');
  verifier.noteCleanup(report, 0);
  assert.equal(report.checks[1].result, 'PASS');
});

test('missing status is pending before runId and fatal after it', () => {
  const missing = { code: 0, stdout: 'cat: files/browser-mcp-e2e/status.json: No such file or directory\n' };
  assert.equal(verifier.interpretStatus(missing, ''), null);
  assert.throws(() => verifier.interpretStatus(missing, 'run-1'), /missing after runId/);
  const ready = verifier.interpretStatus({
    code: 0,
    stdout: JSON.stringify({ phase: 'disabled', runId: 'run-1', credentialFile: verifier.CREDENTIAL_REMOTE, port: null }),
  }, '');
  assert.equal(ready.phase, 'disabled');
  assert.throws(() => verifier.interpretStatus({ code: 0, stdout: 'not-json' }, 'run-1'), /malformed/);
  assert.equal(verifier.interpretStatus({ code: 0, stdout: 'not-json' }, ''), null);
});

test('tool failure accepts rpc error and redacts tokens', () => {
  assert.match(verifier.toolFailed({ error: { message: 'denied' } }), /denied/);
  assert.equal(verifier.toolFailed({ result: { isError: true } }).length > 0, true);
  assert.equal(verifier.redact('Bearer secret-token failed', 'secret-token').includes('secret-token'), false);
});
