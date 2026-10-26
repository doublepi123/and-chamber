#!/usr/bin/env node
'use strict';

// Focused offline checks for the phone E2E support lane:
//  - deterministic OpenAI-compatible JSON and SSE rendering
//  - andchamber_phone tool-schema rejection
//  - envelope/result parsing and one real action per scenario
//  - the test-only target APK builds with Android SDK/JDK tools only

const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const fixture = require('../phone-e2e/model_fixture.cjs');
const buildScript = require('../phone-e2e/build_target_apk.cjs');
const providerOverlay = require('../phone-e2e/provider-overlay.example.json');

const PACKAGE = 'io.example.phonefixture';
const TEST_TEXT = 'andchamber-ascii-1';
const NATIVE_CONTINUE_PROMPT = 'Continue the previous task. First observe the phone screen again; do not repeat any action that already happened.';

test('provider overlay declares text and image input for the capture attachment', () => {
  assert.deepEqual(
    providerOverlay.providers.fixture.models.phone.capabilities.input,
    ['text', 'image'],
  );
});

function toolDefinition() {
  return {
    type: 'function',
    function: {
      name: 'andchamber_phone',
      description: 'test fixture',
      parameters: {
        type: 'object',
        properties: {
          action: {
            type: 'string',
            enum: ['status', 'request', 'apps', 'open_app', 'progress', 'need_user', 'finish', 'snapshot', 'capture', 'tap', 'swipe', 'input', 'back'],
          },
          summary: { type: 'string' },
          packageName: { type: 'string' },
          epoch: { type: 'integer' },
          phase: { type: 'string' },
          message: { type: 'string' },
          reason: { type: 'string' },
          observationId: { type: 'string' },
          ref: { type: 'string' },
          x: { type: 'integer' },
          y: { type: 'integer' },
          x2: { type: 'integer' },
          y2: { type: 'integer' },
          durationMs: { type: 'integer' },
          text: { type: 'string' },
        },
        required: ['action'],
        additionalProperties: false,
        allOf: [
          {
            if: {
              properties: {
                action: { enum: ['progress', 'need_user', 'finish'] },
              },
              required: ['action'],
            },
            then: { required: ['epoch'] },
          },
          {
            if: {
              properties: { action: { enum: ['request'] } },
              required: ['action'],
            },
            then: { required: ['summary', 'packageName'] },
          },
          {
            if: {
              properties: { action: { enum: ['open_app'] } },
              required: ['action'],
            },
            then: { required: ['packageName'] },
          },
        ],
      },
    },
  };
}

function sentinel(value) {
  return `[[andchamber-phone-e2e ${JSON.stringify(value)}]]`;
}

function body(messages, options = {}) {
  return {
    model: 'phone',
    messages,
    tools: options.tools === undefined ? [toolDefinition()] : options.tools,
    stream: options.stream === true,
  };
}

function node(ref, label, extra = {}) {
  return {
    ref,
    role: extra.role || 'TextView',
    label,
    clickable: extra.clickable === true,
    editable: extra.editable === true,
    focused: false,
    bounds: { left: 0, top: 0, right: 100, bottom: 100 },
  };
}

function snapshot(observationId, nodes) {
  return {
    version: 1,
    ok: true,
    action: 'phone.snapshot',
    epoch: 7,
    data: {
      observationId,
      generation: 1,
      packageName: PACKAGE,
      rotation: 0,
      displayWidth: 1080,
      displayHeight: 2400,
      nodes,
    },
  };
}

function status() {
  return {
    version: 1,
    ok: true,
    action: 'phone.status',
    epoch: 7,
    data: { enabled: true, connected: true, phase: 'READY', runtimeReady: true },
  };
}

function envelope(action, data = {}) {
  return { version: 1, ok: true, action, epoch: 7, data };
}

function addToolTurn(messages, id, args, result) {
  return addToolTurnContent(
    messages,
    id,
    args,
    typeof result === 'string' ? result : JSON.stringify(result),
  );
}

function addToolTurnContent(messages, id, args, content) {
  return [
    ...messages,
    {
      role: 'assistant',
      content: null,
      tool_calls: [{
        id,
        type: 'function',
        function: { name: 'andchamber_phone', arguments: JSON.stringify(args) },
      }],
    },
    {
      role: 'tool',
      tool_call_id: id,
      content,
    },
  ];
}

function mainNodes(extra = {}) {
  return [
    node('n0', 'AndChamber Phone Fixture'),
    node('n1', 'Saved 0 times'),
    node('n1a', fixture.INPUT_ECHO_EMPTY, { role: 'TextView' }),
    node('n2', 'fixture-input', { role: 'EditText', editable: true }),
    node('n3', 'fixture-password', { role: 'EditText' }),
    node('n4', 'Save', { role: 'Button', clickable: true }),
    node('n5', 'Next page', { role: 'Button', clickable: true }),
    ...Array.from({ length: 6 }, (_, index) => node(`n${index + 6}`, `Fixture row 0${index + 1}`)),
    ...(extra.nodes || []),
  ];
}

test('no sentinel is ignored without a tool call', () => {
  const plan = fixture.planTurn(body([{ role: 'user', content: 'ordinary prompt' }]), { callSeq: 1 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'ignored');
  assert.doesNotMatch(plan.text, /tool_calls/);
});

test('begin proposes request only with the exact target package', () => {
  const messages = [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }];
  const plan = fixture.planTurn(body(messages), { callSeq: 1 });
  assert.equal(plan.kind, 'tool');
  const args = JSON.parse(plan.toolCall.function.arguments);
  assert.deepEqual(args, {
    action: 'request',
    summary: 'AndChamber phone E2E input',
    packageName: PACKAGE,
  });
});

test('missing or malformed andchamber_phone schema is rejected, not faked', () => {
  const messages = [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }];
  const missing = fixture.planTurn(body(messages, { tools: [] }), { callSeq: 1 });
  assert.equal(missing.kind, 'text');
  assert.equal(missing.case, 'tools-rejected');
  assert.match(missing.text, /not injected/);

  const wrong = toolDefinition();
  wrong.function.parameters.properties.action.enum = ['status', 'snapshot'];
  const malformed = fixture.planTurn(body(messages, { tools: [wrong] }), { callSeq: 1 });
  assert.equal(malformed.kind, 'text');
  assert.equal(malformed.case, 'tools-rejected');
  assert.equal(JSON.parse(JSON.stringify(malformed)).toolCall, undefined);
});

test('progress/need_user/finish carry the status epoch and bounded text', () => {
  const cases = [
    ['progress', 'phase', 'fixture progress'],
    ['need_user', 'reason', 'fixture needs a person'],
    ['finish', 'summary', 'fixture reported the end'],
  ];
  for (const [scenario, field, expected] of cases) {
    const messages = addToolTurn(
      [{ role: 'user', content: sentinel({ mode: 'continue', scenario }) }],
      'call_phone_e2e_1',
      { action: 'status' },
      status(),
    );
    const plan = fixture.planTurn(body(messages), { callSeq: 2 });
    assert.equal(plan.kind, 'tool');
    const args = JSON.parse(plan.toolCall.function.arguments);
    assert.equal(args.action, scenario);
    assert.equal(args.epoch, 7);
    assert.equal(args[field], expected);
  }
});

test('an accepted direct start continues the observed scenario without PENDING', () => {
  const messages = addToolTurn(
    [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'status' }) }],
    'call_phone_e2e_1',
    { action: 'request', summary: 'x', packageName: PACKAGE },
    envelope('phone.request', { state: 'RUNNING' }),
  );
  const plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.kind, 'tool');
  assert.equal(plan.action, 'status');
  assert.equal(plan.scenario, 'status');
  assert.equal(plan.case, 'call-status');
});

test('begin=input starts directly, then runs the observed input scenario without PENDING', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }];
  let plan = fixture.planTurn(body(messages), { callSeq: 1 });
  assert.equal(plan.kind, 'tool');
  assert.equal(plan.action, 'request');
  assert.doesNotMatch(JSON.stringify(plan), /PENDING/);
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    { action: 'request', summary: 'x', packageName: PACKAGE },
    envelope('phone.request', { state: 'RUNNING' }),
  );

  plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.action, 'status');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'status' }, status());

  plan = fixture.planTurn(body(messages), { callSeq: 3 });
  assert.equal(plan.action, 'snapshot');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'snapshot' }, snapshot('obs-1', mainNodes()));

  plan = fixture.planTurn(body(messages), { callSeq: 4 });
  assert.equal(plan.action, 'input');
  const inputArgs = JSON.parse(plan.toolCall.function.arguments);
  assert.equal(inputArgs.observationId, 'obs-1');
  assert.equal(inputArgs.text, TEST_TEXT);
  messages = addToolTurn(messages, plan.toolCall.id, inputArgs, envelope('phone.input'));

  plan = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.equal(plan.action, 'snapshot');
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    { action: 'snapshot' },
    snapshot('obs-2', mainNodes({ nodes: [node('n21', TEST_TEXT, { role: 'TextView' })] })),
  );

  plan = fixture.planTurn(body(messages), { callSeq: 6 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'e2e-complete');
  assert.match(plan.text, /scenario=input action=input verify=pass epoch=7/);
});

test('the real native continuation resumes the original need_user task without a second sentinel', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'need_user' }) }];
  let plan = fixture.planTurn(body(messages), { callSeq: 1 });
  assert.equal(plan.action, 'request');
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    { action: 'request', summary: 'x', packageName: PACKAGE },
    envelope('phone.request', { state: 'RUNNING' }),
  );

  plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.action, 'status');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'status' }, status());
  plan = fixture.planTurn(body(messages), { callSeq: 3 });
  assert.equal(plan.action, 'need_user');
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    JSON.parse(plan.toolCall.function.arguments),
    envelope('phone.need_user', { state: 'WAITING_USER', verified: false }),
  );
  assert.equal(fixture.planTurn(body(messages), { callSeq: 4 }).case, 'e2e-complete');

  // This is the production native prompt, not another acceptance-driver scenario POST.
  messages = [...messages, { role: 'user', content: NATIVE_CONTINUE_PROMPT }];
  assert.equal(messages.filter((message) => message.role === 'user' && message.content.includes('[[andchamber-phone-e2e')).length, 1);
  plan = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.equal(plan.action, 'status');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'status' }, status());

  plan = fixture.planTurn(body(messages), { callSeq: 6 });
  assert.equal(plan.action, 'snapshot');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'snapshot' }, snapshot('obs-resume-1', mainNodes()));
  plan = fixture.planTurn(body(messages), { callSeq: 7 });
  assert.equal(plan.action, 'input');
  const inputArgs = JSON.parse(plan.toolCall.function.arguments);
  assert.equal(inputArgs.observationId, 'obs-resume-1');
  messages = addToolTurn(messages, plan.toolCall.id, inputArgs, envelope('phone.input'));

  plan = fixture.planTurn(body(messages), { callSeq: 8 });
  assert.equal(plan.action, 'snapshot');
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    { action: 'snapshot' },
    snapshot('obs-resume-2', mainNodes({ nodes: [node('n30', TEST_TEXT, { role: 'TextView' })] })),
  );
  plan = fixture.planTurn(body(messages), { callSeq: 9 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'e2e-complete');
  assert.match(plan.text, /scenario=input action=input verify=pass/);
});

test('the native continuation alone is not a synthetic task sentinel', () => {
  const plan = fixture.planTurn(body([{ role: 'user', content: NATIVE_CONTINUE_PROMPT }]), { callSeq: 1 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'ignored');
});

test('a plain text acknowledgement is not a direct start', () => {
  const messages = addToolTurn(
    [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }],
    'call_phone_e2e_1',
    { action: 'request', summary: 'x', packageName: PACKAGE },
    'request accepted',
  );
  const plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'request-not-confirmed');
});

test('a direct-start envelope with PENDING or a stale epoch does not continue', () => {
  for (const reply of [
    envelope('phone.request', { state: 'PENDING_CONFIRMATION' }),
    { version: 1, ok: true, action: 'phone.request', epoch: 0, data: { state: 'RUNNING' } },
  ]) {
    const messages = addToolTurn(
      [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }],
      'call_phone_e2e_1',
      { action: 'request', summary: 'x', packageName: PACKAGE },
      reply,
    );
    const plan = fixture.planTurn(body(messages), { callSeq: 2 });
    assert.equal(plan.kind, 'text');
    assert.equal(plan.case, 'request-not-confirmed');
  }
});

test('an unavailable plain request result is not reported as pending confirmation', () => {
  const messages = addToolTurn(
    [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }],
    'call_phone_e2e_1',
    { action: 'request', summary: 'x', packageName: PACKAGE },
    'AndChamber phone control is not available. Open the AndChamber chat screen on the phone and try again.',
  );
  const plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'request-not-confirmed');
});

test('status verifies minimal information without requiring a screen observation', () => {
  const messages = addToolTurn(
    [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'status' }) }],
    'status-only', { action: 'status' }, status(),
  );
  assert.match(fixture.planTurn(body(messages), { callSeq: 2 }).text, /scenario=status action=status verify=pass/);
});

test('apps returns only the preauthorized fixture list', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'action', action: 'apps' }) }];
  const plan = fixture.planTurn(body(messages), { callSeq: 1 });
  assert.equal(plan.action, 'apps');
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    { action: 'apps' },
    envelope('phone.apps', { apps: [{ label: 'Fixture', packageName: PACKAGE }], actions: ['INPUT'] }),
  );
  assert.match(fixture.planTurn(body(messages), { callSeq: 2 }).text, /scenario=apps action=apps verify=pass/);
});

test('open_app requires both the Opened reply and a real foreground observation', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'action', action: 'open_app' }) }];
  let plan = fixture.planTurn(body(messages), { callSeq: 1 });
  assert.deepEqual(JSON.parse(plan.toolCall.function.arguments), { action: 'open_app', packageName: PACKAGE });
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    { action: 'open_app', packageName: PACKAGE },
    envelope('phone.open_app', { state: 'OPENED' }),
  );
  plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.action, 'snapshot');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'snapshot' }, snapshot('obs-opened', mainNodes()));
  plan = fixture.planTurn(body(messages), { callSeq: 3 });
  assert.equal(plan.kind, 'text');
  assert.match(plan.text, /scenario=open_app action=open_app verify=pass/);
});

test('input scenario runs status, snapshot, input, snapshot and verifies the text', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'input' }) }];
  let plan = fixture.planTurn(body(messages), { callSeq: 1 });
  assert.equal(plan.kind, 'tool');
  assert.equal(plan.action, 'status');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'status' }, status());

  plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.action, 'snapshot');
  messages = addToolTurn(messages, plan.toolCall.id, { action: 'snapshot' }, snapshot('obs-1', mainNodes()));

  plan = fixture.planTurn(body(messages), { callSeq: 3 });
  assert.equal(plan.kind, 'tool');
  assert.equal(plan.action, 'input');
  const inputArgs = JSON.parse(plan.toolCall.function.arguments);
  assert.equal(inputArgs.observationId, 'obs-1');
  assert.equal(inputArgs.text, TEST_TEXT);
  assert.equal(inputArgs.ref, 'n2');
  messages = addToolTurn(messages, plan.toolCall.id, inputArgs, envelope('phone.input'));

  plan = fixture.planTurn(body(messages), { callSeq: 4 });
  assert.equal(plan.action, 'snapshot');
  messages = addToolTurn(
    messages,
    plan.toolCall.id,
    { action: 'snapshot' },
    snapshot('obs-2', mainNodes({ nodes: [node('n21', TEST_TEXT, { role: 'TextView' })] })),
  );

  plan = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'e2e-complete');
  assert.match(plan.text, /scenario=input action=input verify=pass epoch=7/);
});

test('a snapshot that does not show the typed text fails verification', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'input' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  messages = addToolTurn(messages, 'c2', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
  messages = addToolTurn(messages, 'c3', { action: 'input', observationId: 'obs-1', ref: 'n2', text: TEST_TEXT }, envelope('phone.input'));
  messages = addToolTurn(messages, 'c4', { action: 'snapshot' }, snapshot('obs-2', mainNodes()));
  const plan = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'e2e-verify-failed');
  assert.match(plan.text, /verify=fail:input-echo-missing/);
});

test('an editable label is not accepted as the input witness', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'input' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  messages = addToolTurn(messages, 'c2', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
  messages = addToolTurn(messages, 'c3', { action: 'input', observationId: 'obs-1', ref: 'n2', text: TEST_TEXT }, envelope('phone.input'));
  messages = addToolTurn(
    messages,
    'c4',
    { action: 'snapshot' },
    snapshot('obs-2', mainNodes({ nodes: [node('n21', TEST_TEXT, { role: 'EditText', editable: true })] })),
  );
  const plan = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.equal(plan.kind, 'text');
  assert.match(plan.text, /verify=fail:input-echo-missing/);
});

test('an input snapshot without the initial echo marker fails', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'input' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  messages = addToolTurn(
    messages,
    'c2',
    { action: 'snapshot' },
    snapshot('obs-1', [node('n2', 'fixture-input', { role: 'EditText', editable: true })]),
  );
  messages = addToolTurn(messages, 'c3', { action: 'input', observationId: 'obs-1', ref: 'n2', text: TEST_TEXT }, envelope('phone.input'));
  messages = addToolTurn(
    messages,
    'c4',
    { action: 'snapshot' },
    snapshot('obs-2', [node('n21', TEST_TEXT, { role: 'TextView' })]),
  );
  const plan = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.equal(plan.kind, 'text');
  assert.match(plan.text, /verify=fail:input-echo-marker-missing/);
});

test('the target mirrors only the ordinary input and never the password', () => {
  const source = fs.readFileSync(
    path.join(__dirname, '..', 'phone-e2e', 'target', 'java', 'io', 'example', 'phonefixture', 'MainActivity.java'),
    'utf8',
  );
  assert.match(source, /R\.id\.fixture_input_echo/);
  assert.match(source, /INPUT_ECHO_EMPTY/);
  assert.match(source, /input\.addTextChangedListener/);
  assert.doesNotMatch(source, /password\.addTextChangedListener/);
});

test('tap_save taps the stable Save label and requires a counter increase', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'tap_save' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  const nodes = mainNodes().map((n) => n.label === 'Save' ? { ...n, label: 'SAVE' } : n);
  messages = addToolTurn(messages, 'c2', { action: 'snapshot' }, snapshot('obs-1', nodes));
  const tap = fixture.planTurn(body(messages), { callSeq: 3 });
  assert.equal(tap.action, 'tap');
  assert.deepEqual(JSON.parse(tap.toolCall.function.arguments), {
    action: 'tap',
    observationId: 'obs-1',
    ref: 'n4',
  });
  messages = addToolTurn(messages, 'c3', JSON.parse(tap.toolCall.function.arguments), envelope('phone.tap'));
  messages = addToolTurn(
    messages,
    'c4',
    { action: 'snapshot' },
    snapshot('obs-2', [node('n0', 'AndChamber Phone Fixture'), node('n1', 'Saved 1 times')]),
  );
  const done = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.match(done.text, /scenario=tap_save action=tap verify=pass/);
});

test('swipe uses snapshot geometry and requires later visible rows', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'swipe' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  messages = addToolTurn(messages, 'c2', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
  const swipe = fixture.planTurn(body(messages), { callSeq: 3 });
  const args = JSON.parse(swipe.toolCall.function.arguments);
  assert.equal(args.action, 'swipe');
  assert.equal(args.observationId, 'obs-1');
  assert.equal(args.x, 540);
  assert.equal(args.y, 1728);
  assert.equal(args.y2, 672);
  messages = addToolTurn(messages, 'c3', args, envelope('phone.swipe'));
  messages = addToolTurn(
    messages,
    'c4',
    { action: 'snapshot' },
    snapshot('obs-2', [
      node('n0', 'AndChamber Phone Fixture'),
      node('n1', 'Fixture row 20'),
      node('n2', 'Fixture row 30'),
    ]),
  );
  const done = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.match(done.text, /scenario=swipe action=swipe verify=pass/);
});

test('back requires the second page to be gone and the first page visible', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'back' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  messages = addToolTurn(
    messages,
    'c2',
    { action: 'snapshot' },
    snapshot('obs-1', [node('n0', 'Second page'), node('n1', 'Page two ready')]),
  );
  const back = fixture.planTurn(body(messages), { callSeq: 3 });
  assert.deepEqual(JSON.parse(back.toolCall.function.arguments), {
    action: 'back',
    observationId: 'obs-1',
  });
  messages = addToolTurn(messages, 'c3', { action: 'back', observationId: 'obs-1' }, envelope('phone.back'));
  messages = addToolTurn(
    messages,
    'c4',
    { action: 'snapshot' },
    snapshot('obs-2', [node('n0', 'AndChamber Phone Fixture'), node('n1', 'Saved 0 times')]),
  );
  const done = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.match(done.text, /scenario=back action=back verify=pass/);
});

test('capture verifies the text envelope without requiring base64', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'capture' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  messages = addToolTurn(messages, 'c2', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
  const capture = fixture.planTurn(body(messages), { callSeq: 3 });
  assert.equal(capture.action, 'capture');
  messages = addToolTurn(
    messages,
    'c3',
    { action: 'capture', observationId: 'obs-1' },
    envelope('phone.capture', { observationId: 'obs-1', mime: 'image/png', width: 1080, height: 2400 }),
  );
  messages = addToolTurn(messages, 'c4', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
  const done = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.match(done.text, /scenario=capture action=capture verify=pass/);
});

test('capture accepts a known multipart tool result and ignores the following image attachment', () => {
  let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'capture' }) }];
  messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
  messages = addToolTurn(messages, 'c2', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
  const captureEnvelope = envelope('phone.capture', {
    observationId: 'obs-1',
    generation: 1,
    mime: 'image/png',
    width: 1080,
    height: 2400,
  });
  messages = addToolTurnContent(
    messages,
    'c3',
    { action: 'capture', observationId: 'obs-1' },
    [
      { type: 'text', text: JSON.stringify(captureEnvelope) },
      { type: 'image_url', image_url: { url: 'data:image/png;base64,AAAA' } },
    ],
  );
  // OpenCode puts the image in a following user attachment, not in the tool text.
  messages = [
    ...messages,
    {
      role: 'user',
      content: [
        { type: 'text', text: '' },
        { type: 'image_url', image_url: { url: 'data:image/png;base64,BBBB' } },
      ],
    },
  ];

  const next = fixture.planTurn(body(messages), { callSeq: 4 });
  assert.equal(next.kind, 'tool');
  assert.equal(next.action, 'snapshot');
  messages = addToolTurn(messages, 'c4', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
  const done = fixture.planTurn(body(messages), { callSeq: 5 });
  assert.match(done.text, /scenario=capture action=capture verify=pass/);
});

test('tool result wrappers are rejected unless they are one exact envelope', () => {
  const captureEnvelope = JSON.stringify(
    envelope('phone.capture', {
      observationId: 'obs-1',
      generation: 1,
      mime: 'image/png',
      width: 1080,
      height: 2400,
    }),
  );
  for (const content of [
    [
      { type: 'text', text: captureEnvelope },
      { type: 'text', text: 'ERROR: Cannot read image (this model does not support image input). Inform the user.' },
    ],
    [
      { type: 'text', text: captureEnvelope },
      { type: 'mystery-part', text: 'not a known media part' },
    ],
    `${captureEnvelope}\nERROR: Cannot read image (this model does not support image input). Inform the user.`,
  ]) {
    let messages = [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'capture' }) }];
    messages = addToolTurn(messages, 'c1', { action: 'status' }, status());
    messages = addToolTurn(messages, 'c2', { action: 'snapshot' }, snapshot('obs-1', mainNodes()));
    messages = addToolTurnContent(
      messages,
      'c3',
      { action: 'capture', observationId: 'obs-1' },
      content,
    );
    const plan = fixture.planTurn(body(messages), { callSeq: 4 });
    assert.equal(plan.kind, 'text');
    assert.equal(plan.case, 'tool-result-invalid');
    assert.doesNotMatch(plan.text, /e2e-complete/);
  }
});

test('a refused tool envelope stops the scenario without retrying', () => {
  const messages = addToolTurn(
    [{ role: 'user', content: sentinel({ mode: 'continue', scenario: 'input' }) }],
    'c1',
    { action: 'status' },
    { version: 1, ok: false, action: 'phone.status', epoch: 7, error: { code: 'NOT_GRANTED', message: 'fixed' } },
  );
  const plan = fixture.planTurn(body(messages), { callSeq: 2 });
  assert.equal(plan.kind, 'text');
  assert.equal(plan.case, 'tool-refused');
  assert.match(plan.text, /action=phone\.status code=NOT_GRANTED/);
  assert.equal(plan.toolCall, undefined);
});

test('non-stream completion returns the exact tool call', () => {
  const messages = [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }];
  const plan = fixture.planTurn(body(messages), { callSeq: 1 });
  const completion = fixture.completionFor(body(messages), plan, 1);
  assert.equal(completion.choices[0].finish_reason, 'tool_calls');
  assert.equal(completion.choices[0].message.tool_calls[0].function.name, 'andchamber_phone');
  const args = JSON.parse(completion.choices[0].message.tool_calls[0].function.arguments);
  assert.equal(args.action, 'request');
});

test('SSE payloads carry role, one tool delta and a finish reason', () => {
  const messages = [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }];
  const plan = fixture.planTurn(body(messages), { callSeq: 1 });
  const payloads = fixture.ssePayloads(body(messages), plan, 1, { includeUsage: true });
  const toolDelta = payloads
    .flatMap((payload) => (payload.choices || []).flatMap((choice) => (choice.delta && choice.delta.tool_calls) || []));
  assert.equal(toolDelta.length, 1);
  assert.equal(toolDelta[0].function.name, 'andchamber_phone');
  assert.ok(payloads.some((payload) => (payload.choices || []).some((choice) => choice.finish_reason === 'tool_calls')));
  assert.ok(payloads.some((payload) => Array.isArray(payload.choices) && payload.choices.length === 0 && payload.usage));
});

test('HTTP fixture serves JSON, SSE and model listing over loopback', async (t) => {
  const parent = path.join('/tmp/opencode', 'andchamber-phone-e2e');
  fs.mkdirSync(parent, { recursive: true, mode: 0o700 });
  const dir = fs.mkdtempSync(path.join(parent, 'http-test-'));
  const transcript = path.join(dir, 'transcript.jsonl');
  const handle = await fixture.startServer({ port: 0, pidFile: false, transcript });
  t.after(async () => {
    await handle.close();
    fs.rmSync(dir, { recursive: true, force: true });
  });

  const models = await fetch(`${handle.url}/v1/models`);
  assert.equal(models.status, 200);
  assert.equal((await models.json()).data[0].id, 'phone');

  const messages = [{ role: 'user', content: sentinel({ mode: 'begin', scenario: 'input' }) }];
  const jsonResponse = await fetch(`${handle.url}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body(messages)),
  });
  assert.equal(jsonResponse.status, 200);
  const completion = await jsonResponse.json();
  assert.equal(completion.choices[0].message.tool_calls[0].function.name, 'andchamber_phone');
  const firstCallId = completion.choices[0].message.tool_calls[0].id;
  assert.match(firstCallId, /^call_phone_e2e_[0-9a-f]{16}_1$/);
  const restarted = await fixture.startServer({ port: 0, pidFile: false });
  try {
    const reply = await fetch(`${restarted.url}/v1/chat/completions`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body(messages)),
    });
    assert.notEqual((await reply.json()).choices[0].message.tool_calls[0].id, firstCallId);
  } finally {
    await restarted.close();
  }

  const streamResponse = await fetch(`${handle.url}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body(messages, { stream: true })),
  });
  assert.equal(streamResponse.status, 200);
  const text = await streamResponse.text();
  const events = text.split('\n\n').filter((line) => line.startsWith('data: '));
  assert.ok(events.some((line) => line.includes('"tool_calls"')));
  assert.equal(events[events.length - 1], 'data: [DONE]');

  const ignored = await fetch(`${handle.url}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body([{
      role: 'user',
      content: [
        { type: 'text', text: 'ordinary prompt' },
        { type: 'image_url', image_url: { url: 'data:image/png;base64,PRIVATESENTINEL' } },
      ],
    }])),
  });
  assert.equal((await ignored.json()).choices[0].message.tool_calls, undefined);

  const invalid = await fetch(`${handle.url}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: '{',
  });
  assert.equal(invalid.status, 400);

  const lines = fs.readFileSync(transcript, 'utf8').trim().split('\n').map((line) => JSON.parse(line));
  assert.equal(lines.length, 3);
  for (const line of lines) {
    assert.match(line.case, /^(call-request|ignored)$/);
    if (line.decision === 'tool') assert.equal(line.tool, 'andchamber_phone');
    else assert.equal(line.tool, '');
  }
  const raw = fs.readFileSync(transcript, 'utf8');
  assert.doesNotMatch(raw, /andchamber-phone-e2e/);
  assert.doesNotMatch(raw, /andchamber-ascii/);
  assert.doesNotMatch(raw, /PRIVATESENTINEL/);
});

test('target APK builds offline with SDK/JDK tools only', { timeout: 240000 }, (t) => {
  let sdk;
  try {
    sdk = buildScript.resolveSdk('');
  } catch (error) {
    t.skip(error.message);
    return;
  }
  if (!fs.existsSync(path.join(sdk, 'platforms', 'android-35', 'android.jar'))) {
    t.skip('Android SDK platform 35 is not available');
    return;
  }
  const parent = path.join('/tmp/opencode', 'andchamber-phone-e2e');
  fs.mkdirSync(parent, { recursive: true, mode: 0o700 });
  const out = path.join(parent, `apk-test-${process.pid}-${Date.now()}`);
  const result = spawnSync(process.execPath, [path.join(__dirname, '..', 'phone-e2e', 'build_target_apk.cjs'), '--out', out, '--sdk', sdk], {
    encoding: 'utf8',
    timeout: 240000,
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const summary = JSON.parse(fs.readFileSync(path.join(out, 'build-result.json'), 'utf8'));
  assert.equal(summary.packageName, PACKAGE);
  assert.equal(summary.label, 'Phone Fixture');
  assert.equal(summary.launchableActivity, `${PACKAGE}.MainActivity`);
  assert.match(summary.sha256, /^[0-9a-f]{64}$/);
  assert.ok(summary.sizeBytes > 0);
  assert.ok(fs.existsSync(summary.apk));

  const tools = buildScript.resolveTools({ sdk, api: '35', buildTools: '', javaHome: '' });
  const badging = spawnSync(tools.aapt2, ['dump', 'badging', summary.apk], { encoding: 'utf8' });
  assert.equal(badging.status, 0, badging.stderr);
  assert.match(badging.stdout, /package: name='io\.example\.phonefixture'/);
  assert.match(badging.stdout, /application-label:'Phone Fixture'/);
  assert.doesNotMatch(badging.stdout, /android\.permission\.INTERNET/);

  const zip = spawnSync(tools.jar, ['tf', summary.apk], { encoding: 'utf8' });
  assert.equal(zip.status, 0, zip.stderr);
  assert.match(zip.stdout, /^classes\.dex$/m);
  assert.match(zip.stdout, /AndroidManifest\.xml/);

  const resources = spawnSync(tools.aapt2, ['dump', 'resources', summary.apk], { encoding: 'utf8' });
  assert.equal(resources.status, 0, resources.stderr);
  assert.match(resources.stdout, /id\/fixture_input_echo/);
  t.diagnostic(`target APK: ${summary.apk}`);
});
