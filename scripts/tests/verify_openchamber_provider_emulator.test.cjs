'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const harness = require('../verify_openchamber_provider_emulator.cjs');

test('serial is emulator only', () => {
  assert.throws(() => harness.parseArgs(['--serial', 'device', '--out', '/tmp/out']), /emulator/);
});

test('agent-tool body requires a real session', () => {
  const body = JSON.parse(harness.agentToolBody('browser.snapshot', { selector: '[data-andchamber-ref="abc"]' }, 'ses_123'));
  assert.equal(body.tool, 'openchamber_web');
  assert.equal(body.sessionID, 'ses_123');
  assert.throws(() => harness.agentToolBody('browser.open', {}, 'nope'), /real session/);
});

test('child env lookup rejects a process outside the launched tree', () => {
  const files = {
    '/proc/77/status': 'PPid:\t42\n',
    '/proc/77/environ': Buffer.from('OPENCHAMBER_AGENT_TOOL_URL=http://127.0.0.1:9/api/openchamber/agent-tool\0OPENCHAMBER_AGENT_TOOL_TOKEN=secret-token\0'),
  };
  const read = (file) => files[String(file)];
  assert.equal(harness.findAgentTool(42, () => ['77'], read).token, 'secret-token');
  assert.throws(() => harness.findAgentTool(7, () => ['77'], read), /agent-tool env/);
});

test('click-count 10 is not accepted as 1 and scroll requires a larger offset', () => {
  const harness = require('../verify_openchamber_provider_emulator.cjs');
  assert.equal(harness.exactLabel('click-count:1', 'click-count'), 1);
  assert.equal(harness.exactLabel('click-count:10', 'click-count'), 10);
  assert.notEqual(harness.exactLabel('click-count:10', 'click-count'), 1);
  assert.equal(harness.exactLabel('scroll-y:0', 'scroll-y'), 0);
  assert.equal(harness.exactLabel('scroll-y:40', 'scroll-y') > harness.exactLabel('scroll-y:0', 'scroll-y'), true);
});

test('missing UI loop cannot pass and tokens are redacted', () => {
  const report = harness.result([{ name: 'ui-grant-actions', result: 'FAIL', detail: 'not run' }]);
  assert.equal(report.result, 'FAIL');
  assert.equal(harness.redact('Bearer secret-token', ['secret-token']).includes('secret-token'), false);
});
