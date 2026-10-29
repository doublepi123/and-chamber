'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { pathToFileURL } = require('node:url');

const PLUGIN_PATH = path.resolve(__dirname, '../../app/src/main/assets/phone-tool/index.js');
const PACKAGE_PATH = path.resolve(__dirname, '../../app/src/main/assets/phone-tool/package.json');
const ENV = 'ANDCHAMBER_PHONE_TOOL_FILE';
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

let plugin;
let tool;
let rpc;

test.before(async () => {
  plugin = (await import(pathToFileURL(PLUGIN_PATH).href)).default;
  const added = [];
  const fakeContext = {
    tool: {
      transform: async (fn) => {
        await fn({
          add: (definition) => {
            added.push(definition);
          },
        });
        return { dispose: async () => {} };
      },
      list: async () => added.map((definition) => ({ ...definition, id: definition.name })),
    },
    rpc: {
      register: async (definition, handlers) => {
        rpc = { definition, handlers };
        return { dispose: async () => {} };
      },
    },
  };
  await plugin.setup(fakeContext);
  tool = added[0];
});

function startServer(respond) {
  const token = crypto.randomBytes(32).toString('hex');
  const requests = [];
  const sockets = new Set();
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const record = {
        method: req.method,
        url: req.url,
        headers: req.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      };
      requests.push(record);
      respond(req, res, record, token);
    });
  });
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({
        token,
        requests,
        endpoint: {
          version: 1,
          url: `http://127.0.0.1:${port}/v1/tool`,
          cancelUrl: `http://127.0.0.1:${port}/v1/cancel`,
          token,
        },
        close: () =>
          new Promise((done) => {
            for (const socket of sockets) socket.destroy();
            server.close(done);
          }),
      });
    });
  });
}

async function useServer(t, respond) {
  const server = await startServer(respond);
  t.after(() => server.close());
  return server;
}

function endpointDir(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'andchamber-phone-plugin-'));
  const previous = process.env[ENV];
  t.after(() => {
    if (previous === undefined) delete process.env[ENV];
    else process.env[ENV] = previous;
    fs.rmSync(dir, { recursive: true, force: true });
  });
  return {
    dir,
    write(server, name = 'phone-tool.json') {
      const file = path.join(dir, name);
      fs.writeFileSync(file, JSON.stringify(server.endpoint), { mode: 0o600 });
      process.env[ENV] = file;
      return file;
    },
    writeRaw(content, name = 'phone-tool.json') {
      const file = path.join(dir, name);
      fs.writeFileSync(file, content, { mode: 0o600 });
      process.env[ENV] = file;
      return file;
    },
  };
}

function json(handler) {
  return (req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(handler));
  };
}

function context(overrides = {}) {
  return {
    sessionID: 'ses_test_session',
    id: 'call_abc',
    messageID: 'msg_abc',
    agent: 'build',
    signal: new AbortController().signal,
    ...overrides,
  };
}

function assertNoEndpoint(result, server) {
  const rendered = JSON.stringify(result);
  assert.equal(rendered.includes(server.token), false, 'returned token');
  assert.equal(rendered.includes(server.endpoint.url), false, 'returned tool url');
  assert.equal(rendered.includes(server.endpoint.cancelUrl), false, 'returned cancel url');
}

function waitFor(predicate, timeoutMs = 2000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const tick = () => {
      if (predicate()) return resolve();
      if (Date.now() - started > timeoutMs) return reject(new Error('timed out waiting for request'));
      setTimeout(tick, 10);
    };
    tick();
  });
}

const EMPTY_INPUT_SCHEMA = { type: 'object', properties: {}, additionalProperties: false };
const READY_OUTPUT_SCHEMA = {
  type: 'object',
  properties: { ok: { const: true }, id: { const: 'andchamber_phone' } },
  required: ['ok', 'id'],
  additionalProperties: false,
};

/**
 * Stand-in for the pinned v2 plugin context. It records the order the real host uses
 * (transform, then list, then rpc.register) and lets one test replace any single step.
 */
async function setupPlugin(options = {}) {
  const order = [];
  const added = [];
  const registrations = [];
  const transform = async (callback) => {
    await callback({
      add: (definition) => {
        added.push(definition);
      },
    });
    return { dispose: async () => {} };
  };
  let error = null;
  try {
    await plugin.setup({
      tool: {
        transform: async (callback) => {
          order.push('transform');
          return (options.transform ?? transform)(callback);
        },
        list: async () => {
          order.push('list');
          if (options.list) return options.list(added);
          return added.map((definition) => ({ ...definition, id: definition.name }));
        },
      },
      rpc: {
        register: async (definition, handlers) => {
          order.push('register');
          if (options.register) return options.register(definition, handlers);
          registrations.push({ definition, handlers });
          return { dispose: async () => {} };
        },
      },
    });
  } catch (failure) {
    error = failure;
  }
  return { order, added, registrations, error, tool: added[0] };
}

/** Minimal JSON Schema check for the two closed schemas this plugin declares. */
function schemaAccepts(schema, value) {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
  if (schema.type !== 'object') return false;
  const keys = Object.keys(value);
  if (schema.additionalProperties === false && keys.some((key) => !(key in schema.properties))) return false;
  if ((schema.required ?? []).some((key) => !(key in value))) return false;
  for (const [key, property] of Object.entries(schema.properties)) {
    if (!(key in value)) continue;
    if ('const' in property && value[key] !== property.const) return false;
  }
  return true;
}

function toolRequests(server) {
  return server.requests.filter((request) => request.url === '/v1/tool');
}

test('package.json and tool registration shape', () => {
  const pkg = JSON.parse(fs.readFileSync(PACKAGE_PATH, 'utf8'));
  assert.deepEqual(pkg, {
    name: 'andchamber-phone-tool',
    version: '1.0.0',
    private: true,
    type: 'module',
    exports: { '.': './index.js' },
  });

  assert.equal(plugin.id, 'andchamber-phone');
  assert.equal(typeof plugin.setup, 'function');
  assert.equal(tool.name, 'andchamber_phone');
  assert.deepEqual(tool.options, { codemode: false });
  assert.equal(typeof tool.execute, 'function');

  assert.equal(tool.input.type, 'object');
  assert.equal(tool.input.additionalProperties, false);
  assert.deepEqual(tool.input.required, ['action']);
  assert.deepEqual(tool.input.properties.action.enum, [
    'status',
    'request',
    'apps',
    'open_app',
    'progress',
    'need_user',
    'finish',
    'snapshot',
    'capture',
    'tap',
    'swipe',
    'input',
    'back',
  ]);
  for (const key of [
    'observationId', 'ref', 'text', 'summary', 'packageName',
    'phase', 'message', 'reason',
  ]) {
    assert.equal(tool.input.properties[key].type, 'string', key);
  }
  assert.equal(tool.input.properties.summary.maxLength, 200);
  assert.equal(tool.input.properties.packageName.maxLength, 200);
  assert.match(tool.input.properties.packageName.description, /any launchable allowed app in the all-apps scope/);
  for (const key of ['phase', 'message', 'reason']) {
    assert.equal(tool.input.properties[key].maxLength, 120, key);
  }
  assert.equal(tool.input.properties.epoch.type, 'integer');
  assert.equal(tool.input.properties.epoch.minimum, 0);
  assert.equal(Array.isArray(tool.input.allOf), true);
  assert.deepEqual(tool.input.allOf[0].then.required, ['epoch']);
  assert.deepEqual(tool.input.allOf[1].then.required, ['summary', 'packageName']);
  assert.deepEqual(tool.input.allOf[2].then.required, ['packageName']);
  for (const key of ['x', 'y', 'x2', 'y2', 'durationMs']) {
    assert.equal(tool.input.properties[key].type, 'integer', key);
  }
  assert.deepEqual(
    [tool.input.properties.x.minimum, tool.input.properties.x.maximum],
    [0, 10000],
  );
  assert.deepEqual(
    [tool.input.properties.durationMs.minimum, tool.input.properties.durationMs.maximum],
    [50, 5000],
  );

  assert.match(tool.description, /status/);
  assert.match(tool.description, /needs no grant/);
  assert.match(tool.description, /"request"/);
  assert.match(tool.description, /"apps"/);
  assert.match(tool.description, /"open_app"/);
  assert.match(tool.description, /standing scope/);
  assert.match(tool.description, /There is no PENDING_CONFIRMATION step/);
  assert.match(tool.description, /never exposes the installed-app catalogue/);
  // The standing "all apps" choice lists launchable apps only; it stays a picker, not authority.
  assert.match(tool.description, /standing "all apps" choice/);
  assert.match(tool.description, /launchable right now/);
  assert.match(tool.description, /answered with allApps true/);
  assert.match(tool.description, /it is a picker, never authority/);
  assert.match(tool.description, /An explicit scope accepts only its listed package/);
  assert.match(tool.description, /An all-apps scope accepts any currently launchable app/);
  assert.match(tool.description, /secure surfaces \(system UI and keyguard\)/);
  assert.match(tool.description, /an app whose launcher is gone, or one that is not launchable, is refused/);
  assert.match(tool.description, /PHONE_SCREEN_OPEN/);
  assert.match(tool.description, /the native phone-control page is open/);
  assert.match(tool.description, /must not be brought forward because it stops control/);
  assert.match(tool.description, /leave the phone-control page \(Home or chat\) and send the request again/);
  // All-apps follow mode: a handoff to another allowed app is part of the same task.
  assert.match(tool.description, /FOLLOWS the foreground when the target app hands off to another allowed app/);
  assert.match(tool.description, /take a fresh snapshot after any app switch/);
  assert.match(tool.description, /An explicit scope never follows another package/);
  assert.match(tool.description, /a secure surface still ends the task/);
  // Explicit scope: one target; all-apps open_app keeps the same task/epoch for an allowed app.
  assert.match(tool.description, /In an explicit scope the task has exactly one target for its whole life/);
  assert.match(tool.description, /may bring any launchable allowed app forward inside the SAME task and epoch/);
  assert.match(tool.description, /never enable, pair, grant, resume, acknowledge or open overlay permission/);
  assert.match(tool.description, /Take "snapshot" first/);
  assert.match(tool.description, /observationId/);
  assert.match(tool.description, /One action per call/);
  assert.match(tool.description, /OUTCOME_UNKNOWN/);
  assert.match(tool.description, /Never repeat the action/);
  assert.match(tool.description, /SESSION_BUSY/);
  assert.match(tool.description, /"progress"/);
  assert.match(tool.description, /"need_user"/);
  assert.match(tool.description, /"finish"/);
  assert.match(tool.description, /报告结束，待核对/);
  assert.match(tool.description, /STOP_UNCONFIRMED/);
  assert.match(tool.description, /NO_TASK/);
  assert.match(tool.description, /TASK_NOT_ACTIVE/);
  assert.match(tool.description, /unverified/);
  assert.match(tool.description, /GRANT_ACTIVE/);
  assert.match(tool.description, /UNICODE_UNSUPPORTED/);
  assert.match(tool.description, /granted on the phone|native grant/);
  // need_user is limited to real human-only steps, never transient page/observation noise.
  assert.match(tool.description, /"need_user" is only for a real human-only step/);
  assert.match(tool.description, /Signing in and authorizing are not such steps/);
  assert.match(tool.description, /tap one-tap\/quick login/);
  assert.match(tool.description, /tap Agree\/Allow on a third-party authorization page/);
  assert.match(tool.description, /this tool cannot type into password fields/);
  assert.match(tool.description, /do not pick an account the person did not name/);
  assert.match(tool.description, /verification code/);
  assert.match(tool.description, /payment or other money confirmation/);
  assert.match(tool.description, /permission decision/);
  assert.match(tool.description, /input the device cannot type/);
  assert.match(tool.description, /choice that belongs to the person/);
  assert.match(tool.description, /Ordinary chat questions and answer choices use the normal question\/form interaction/);
  assert.match(tool.description, /Do not call "need_user" merely while waiting for a chat answer or an OpenCode permission reply/);
  assert.match(tool.description, /Do not call it for page loading/);
  assert.match(tool.description, /brief focus or window change/);
  assert.match(tool.description, /stale observation/);
  assert.match(tool.description, /ordinary HIERARCHY_NOT_IDLE/);
  assert.match(tool.description, /do not revoke an existing task or the saved scope/);

  // Copy assertions only: they lock the wording, not real model compliance.
  assert.match(tool.description, /taskPresent means a task is bound to the live epoch/);
  assert.match(tool.description, /taskRetained may be true while no grant exists/);
  assert.match(tool.description, /taskState is the current PhoneTaskState name or null/);
  assert.match(
    tool.description,
    /continuationState is IDLE, SENDING, DELIVERED, FAILED, NOT_RESUMED or null/,
  );
  assert.match(tool.description, /continuationFailure is a fixed enum or null/);
  assert.match(tool.description, /A missing grant \(NO_GRANT\) is not a lost standing configuration/);
  assert.match(tool.description, /saved scope and a retained waiting task may still exist/);
  assert.match(tool.description, /When taskState is WAITING_USER,/);
  assert.match(tool.description, /taskRetained alone does not mean waiting/);
  assert.match(tool.description, /ACTIVE task with a live grant may continue and finish normally/);
  assert.match(tool.description, /do not send a new "request" to replace that task/);
  assert.match(tool.description, /do not "finish" it/);
  assert.match(tool.description, /native Continue entry/);
  assert.match(tool.description, /new current epoch/);
  assert.match(tool.description, /fresh snapshot and continue from that fresh observation/);
  assert.match(tool.description, /cannot press Continue, wake, resume or roll back/);
  assert.match(
    tool.description,
    /NOT_RESUMED means a technical failure happened before a wake, with either no recovery grant created or an exact rollback confirmed/,
  );
  assert.match(tool.description, /person may confirm Continue again/);
  assert.match(tool.description, /FAILED means the wake is unconfirmed/);
  assert.match(tool.description, /never resend automatically/);
  assert.match(tool.description, /do not assume a retry is safe merely because there is currently no grant/);
  assert.match(tool.description, /Neither state runs or resumes the task automatically/);

  // A transient read-only busy state keeps the task and uses bounded new observations only.
  assert.match(tool.description, /and the live grant is still present/);
  assert.match(tool.description, /do not call "need_user" for this/);
  assert.match(
    tool.description,
    /If a start already failed and no grant remains, do not fake a grant with "snapshot" or resume in place/,
  );
  assert.match(tool.description, /keep the same live task/);
  assert.match(tool.description, /waiting for the page to stabilize/);
  assert.match(tool.description, /small bounded number of NEW snapshots/);
  assert.match(tool.description, /Only those fixed read-only refusals get this treatment/);
  assert.match(tool.description, /READ_TIMEOUT, OUTCOME_UNKNOWN and security refusals are not ordinary busy states/);
  assert.match(tool.description, /A read-only BUSY follows the bounded observation rules above only while the same live grant remains/);
  assert.match(tool.description, /unbounded busy loop/);
  assert.match(tool.description, /reuse old refs/);
  assert.match(tool.description, /failed capture as success/);
  assert.match(tool.description, /resend a SENT\/UNKNOWN mutating action/);
  assert.match(tool.description, /15-second per-call hard timeout/);
  assert.match(tool.description, /technical limitation honestly/);

  // HIERARCHY_NOT_IDLE is not NeedUser and never becomes a manual new-task demand.
  assert.match(tool.description, /HIERARCHY_NOT_IDLE/);
  assert.match(tool.description, /does not mean the connection is down/);
  assert.match(tool.description, /does not by itself revoke an existing task/);
  assert.match(tool.description, /not a reason to call "need_user"/);
  assert.match(
    tool.description,
    /Use "status" to tell whether the original task is still live or a startup was rolled back/,
  );
  assert.match(tool.description, /do not fake a grant with "snapshot" or resume in place/);
  assert.match(tool.description, /never authorises automatic recovery of stopped control/);
  assert.match(tool.description, /retries are bounded/);
  assert.doesNotMatch(tool.description, /ask the person for a new explicit request/);
  assert.doesNotMatch(tool.description, /native control will wait and continue/);

  // Unknown outcomes never invite a repeat or an automatic resume/clear.
  assert.match(tool.description, /Never repeat the action to find out/);
  assert.match(tool.description, /do not auto-resume or clear a standing block or unknown/);

  // READ_TIMEOUT: one bounded automatic reconnect, then a status check and a new request.
  assert.match(tool.description, /READ_TIMEOUT/);
  assert.match(tool.description, /bounded automatic reconnect in the background/);
  assert.match(tool.description, /Check "status" for connected/);
  assert.doesNotMatch(tool.description, /reconnect explicitly/);
  assert.doesNotMatch(tool.description, /shell|intent|package list/i);
});

test('posts client identity from the V2 context and never a model-chosen session', async (t) => {
  const payload = { version: 1, ok: true, action: 'phone.status', epoch: 0, data: { enabled: false } };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'status' }, context());

  assert.deepEqual(result, { content: [{ type: 'text', text: JSON.stringify(payload) }] });
  const calls = toolRequests(server);
  assert.equal(calls.length, 1);
  const request = calls[0];
  assert.equal(request.method, 'POST');
  assert.equal(request.url, '/v1/tool');
  assert.equal(request.headers.authorization, `Bearer ${server.token}`);
  assert.equal(request.headers['content-type'], 'application/json');
  assert.deepEqual(JSON.parse(request.body), {
    version: 1,
    sessionID: 'ses_test_session',
    callID: 'call_abc',
    messageID: 'msg_abc',
    agent: 'build',
    input: { action: 'status' },
  });
  assert.equal('allowWaitMs' in JSON.parse(request.body), false);
  assertNoEndpoint(result, server);
});

test('sends action-specific fields without adding or dropping input', async (t) => {
  const payload = { version: 1, ok: true, action: 'phone.snapshot', epoch: 2, data: { observationId: 'obs-1' } };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const cases = [
    { action: 'request', summary: 'Book a table for two', packageName: 'com.example.target' },
    { action: 'request', summary: 'Book a table for two' },
    { action: 'progress', epoch: 2, phase: 'checking the form' },
    { action: 'progress', epoch: 2, message: 'checking the form' },
    { action: 'need_user', epoch: 2, reason: 'a person must enter the code' },
    { action: 'finish', epoch: 2, summary: 'I have reported the end' },
    { action: 'snapshot' },
    { action: 'capture', observationId: 'obs-1' },
    { action: 'tap', observationId: 'obs-1', ref: 'n1' },
    { action: 'tap', observationId: 'obs-1', x: 10, y: 20 },
    { action: 'swipe', observationId: 'obs-1', x: 10, y: 20, x2: 30, y2: 40, durationMs: 250 },
    { action: 'input', observationId: 'obs-1', text: 'hello', ref: 'n2' },
    { action: 'back', observationId: 'obs-1' },
  ];
  for (const [index, input] of cases.entries()) {
    await tool.execute(input, context({ id: `call_${index}` }));
    assert.deepEqual(JSON.parse(toolRequests(server).at(-1).body).input, input);
  }
  assert.equal(toolRequests(server).length, cases.length);
});

test('a successful request returns the direct-start envelope without PENDING', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'phone.request',
    epoch: 3,
    data: { state: 'RUNNING' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute(
    { action: 'request', summary: 'Order groceries', packageName: 'com.example.target' },
    context(),
  );

  const envelope = JSON.parse(result.content[0].text);
  assert.equal(envelope.ok, true);
  assert.equal(envelope.action, 'phone.request');
  assert.equal(envelope.epoch, 3);
  assert.equal(envelope.data.state, 'RUNNING');
  assert.equal(JSON.stringify(result).includes('PENDING'), false);
  assert.equal(JSON.stringify(result).includes('请在AndChamber手机控制中确认'), false);
  assert.equal(JSON.stringify(result).includes('Order groceries'), false);
  assertNoEndpoint(result, server);
  assert.deepEqual(JSON.parse(toolRequests(server)[0].body).input, {
    action: 'request',
    summary: 'Order groceries',
    packageName: 'com.example.target',
  });
});

test('a refused request keeps the fixed error envelope', async (t) => {
  const payload = {
    version: 1,
    ok: false,
    action: 'phone.request',
    epoch: 4,
    error: {
      code: 'GRANT_ACTIVE',
      message:
        'Phone control already has an active grant. Finish or revoke it on the phone before requesting another task.',
    },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'request', summary: 'Order groceries' }, context());

  assert.equal(result.content.length, 1);
  assert.deepEqual(JSON.parse(result.content[0].text), payload);
  assertNoEndpoint(result, server);
});

test('a typed start failure keeps its structured error and fixed stage, and HTTP bodies are not echoed', async (t) => {
  // The first native cause (READ_TIMEOUT here) must reach the model as a structured envelope,
  // never rewritten as generic START_FAILED and never merged with request content.
  const payload = {
    version: 1,
    ok: false,
    action: 'phone.request',
    epoch: 4,
    error: {
      code: 'READ_TIMEOUT',
      message:
        'Reading the phone timed out. This control connection is no longer used. Ask the person to check the phone and reconnect explicitly; this does not prove the network is down or that the physical socket has closed.',
    },
    data: { startFailureStage: 'OBSERVATION' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute(
    { action: 'request', summary: 'Order 4111-secret', packageName: 'com.example.target' },
    context(),
  );

  assert.equal(result.content.length, 1);
  assert.deepEqual(JSON.parse(result.content[0].text), payload);
  assert.equal(JSON.parse(result.content[0].text).data.startFailureStage, 'OBSERVATION');
  assert.equal(result.content[0].text.includes('START_FAILED'), false);
  assert.equal(JSON.stringify(result).includes('4111-secret'), false);
  assertNoEndpoint(result, server);

  // A non-2xx body may echo request data: only the status is reported.
  const echo = await useServer(t, (req, res, record) => {
    res.writeHead(500, { 'content-type': 'application/json' });
    res.end(
      JSON.stringify({
        ...payload,
        echoedRequest: record.body,
      }),
    );
  });
  endpointDir(t).write(echo);
  const failed = await tool.execute(
    { action: 'request', summary: 'Order 4111-secret', packageName: 'com.example.target' },
    context(),
  );
  assert.deepEqual(failed, {
    content: [{ type: 'text', text: 'AndChamber phone request failed (HTTP 500).' }],
  });
  assert.equal(JSON.stringify(failed).includes('4111-secret'), false);
  assertNoEndpoint(failed, echo);
});

test('a hierarchy refusal stays a snapshot refusal and never becomes need_user', async (t) => {
  // Fixed example only: this locks the wire copy, not real LLM behaviour.
  const payload = {
    version: 1,
    ok: false,
    action: 'phone.snapshot',
    epoch: 4,
    error: {
      code: 'HIERARCHY_NOT_IDLE',
      message:
        'The phone did not provide a valid hierarchy or page. This does not mean the connection is down, and it does not by itself revoke an existing task. Do not call need_user for this.',
    },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'snapshot' }, context());

  assert.equal(result.content.length, 1);
  assert.deepEqual(JSON.parse(result.content[0].text), payload);
  assert.equal(JSON.parse(result.content[0].text).action, 'phone.snapshot');
  assert.equal(JSON.parse(result.content[0].text).error.code, 'HIERARCHY_NOT_IDLE');
  assert.equal(result.content[0].text.includes('phone.need_user'), false);
  assertNoEndpoint(result, server);
});

test('returns a refused action envelope as text without the token', async (t) => {
  const payload = {
    version: 1,
    ok: false,
    action: 'phone.tap',
    epoch: 2,
    error: { code: 'SESSION_BUSY', message: 'Another chat session owns phone control for the current grant.' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'tap', observationId: 'obs-1', ref: 'n1' }, context());

  assert.equal(result.content.length, 1);
  assert.deepEqual(JSON.parse(result.content[0].text), payload);
  assertNoEndpoint(result, server);
});

test('finish answers with the fixed pending-verification text and never success', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'phone.finish',
    epoch: 5,
    data: { state: 'ENDED_PENDING_CHECK', seq: 9, verified: false, sentActionsMayStillApply: true },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute(
    { action: 'finish', epoch: 5, summary: 'I have reported the end' },
    context(),
  );

  assert.deepEqual(result, {
    content: [{ type: 'text', text: '报告结束，待核对' }],
  });
  assert.equal(JSON.stringify(result).includes('reported the end'), false);
  assert.deepEqual(JSON.parse(toolRequests(server)[0].body).input, {
    action: 'finish',
    epoch: 5,
    summary: 'I have reported the end',
  });
  assertNoEndpoint(result, server);
});

test('need_user keeps the typed waiting envelope and unconfirmed stops are refused', async (t) => {
  const waiting = {
    version: 1,
    ok: true,
    action: 'phone.need_user',
    epoch: 5,
    data: { state: 'WAITING_USER', seq: 8, verified: false, sentActionsMayStillApply: true },
  };
  const server = await useServer(t, json(waiting));
  endpointDir(t).write(server);
  const result = await tool.execute(
    { action: 'need_user', epoch: 5, reason: 'a person must enter the code' },
    context(),
  );
  assert.equal(result.content.length, 1);
  assert.deepEqual(JSON.parse(result.content[0].text), waiting);

  const refused = {
    version: 1,
    ok: false,
    action: 'phone.need_user',
    epoch: 5,
    error: {
      code: 'STOP_UNCONFIRMED',
      message:
        'The phone has not confirmed that control stopped. Do not claim it stopped; ask the person to check the phone.',
    },
  };
  const refusedServer = await useServer(t, json(refused));
  endpointDir(t).write(refusedServer);
  const refusedResult = await tool.execute(
    { action: 'need_user', epoch: 5, reason: 'a person must enter the code' },
    context(),
  );
  assert.deepEqual(JSON.parse(refusedResult.content[0].text), refused);
});

test('maps a png capture to a file part and keeps base64 out of the text', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'phone.capture',
    epoch: 3,
    data: { observationId: 'obs-1', base64: PNG_BASE64, mime: 'image/png', width: 1080, height: 2400 },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'capture', observationId: 'obs-1' }, context());

  assert.equal(result.content.length, 2);
  assert.deepEqual(result.content[1], {
    type: 'file',
    uri: PNG_BASE64,
    mime: 'image/png',
    name: 'andchamber-phone-capture.png',
  });
  const textEnvelope = JSON.parse(result.content[0].text);
  assert.equal(textEnvelope.data.base64, undefined);
  assert.equal(textEnvelope.data.observationId, 'obs-1');
  assert.equal(textEnvelope.data.mime, 'image/png');
  assert.equal(result.content[0].text.includes(PNG_BASE64), false);
  assertNoEndpoint(result, server);
});

test('maps jpeg and webp captures to file parts with a matching extension', async (t) => {
  for (const [mime, extension] of [
    ['image/jpeg', 'jpg'],
    ['image/webp', 'webp'],
  ]) {
    const payload = {
      version: 1,
      ok: true,
      action: 'phone.capture',
      epoch: 1,
      data: { observationId: 'obs-1', base64: 'AAAA', mime },
    };
    const server = await useServer(t, json(payload));
    endpointDir(t).write(server);
    const result = await tool.execute({ action: 'capture', observationId: 'obs-1' }, context());
    assert.deepEqual(result.content[1], {
      type: 'file',
      uri: 'AAAA',
      mime,
      name: `andchamber-phone-capture.${extension}`,
    });
  }
});

test('keeps a non-image payload text-only', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'phone.capture',
    epoch: 1,
    data: { base64: 'AAAA', mime: 'application/octet-stream' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'capture', observationId: 'obs-1' }, context());

  assert.equal(result.content.length, 1);
  assert.equal(JSON.parse(result.content[0].text).data.base64, undefined);
  assertNoEndpoint(result, server);
});

test('aborting posts a cancel for this session and call only', { timeout: 10000 }, async (t) => {
  const server = await useServer(t, (req, res) => {
    if (req.url === '/v1/cancel') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"version":1,"ok":true,"cancelled":true}');
    }
    // /v1/tool stays pending until the client aborts.
  });
  endpointDir(t).write(server);

  const controller = new AbortController();
  const pending = tool.execute(
    { action: 'tap', observationId: 'obs-1', ref: 'n1' },
    context({ signal: controller.signal }),
  );

  await waitFor(() => toolRequests(server).length === 1);
  controller.abort();
  const result = await pending;

  assert.deepEqual(result, { content: [{ type: 'text', text: 'Cancelled.' }] });
  await waitFor(() => server.requests.some((request) => request.url === '/v1/cancel'));
  const cancel = server.requests.find((request) => request.url === '/v1/cancel');
  assert.equal(cancel.method, 'POST');
  assert.equal(cancel.url, '/v1/cancel');
  assert.equal(cancel.headers.authorization, `Bearer ${server.token}`);
  assert.deepEqual(JSON.parse(cancel.body), {
    version: 1,
    sessionID: 'ses_test_session',
    callID: 'call_abc',
  });
  assertNoEndpoint(result, server);
});

test('missing endpoint env or file yields the unavailable text without throwing', async (t) => {
  const previous = process.env[ENV];
  t.after(() => {
    if (previous === undefined) delete process.env[ENV];
    else process.env[ENV] = previous;
  });

  const expected = {
    content: [
      {
        type: 'text',
        text: 'AndChamber phone control is not available. Open the AndChamber chat screen on the phone and try again.',
      },
    ],
  };

  delete process.env[ENV];
  assert.deepEqual(await tool.execute({ action: 'status' }, context()), expected);

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'andchamber-phone-plugin-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  process.env[ENV] = path.join(dir, 'absent.json');
  assert.deepEqual(await tool.execute({ action: 'status' }, context()), expected);
});

test('bad JSON, wrong version, missing token or non-loopback url fail closed', async (t) => {
  const dirs = endpointDir(t);
  const expectedText =
    'AndChamber phone control is not available. Open the AndChamber chat screen on the phone and try again.';
  const token = crypto.randomBytes(32).toString('hex');
  const base = {
    version: 1,
    url: 'http://127.0.0.1:9/v1/tool',
    cancelUrl: 'http://127.0.0.1:9/v1/cancel',
    token,
  };

  const cases = [
    'not json',
    JSON.stringify({ ...base, version: 2 }),
    JSON.stringify({ ...base, token: '' }),
    JSON.stringify({ ...base, url: 'https://example.com/v1/tool' }),
    JSON.stringify({ ...base, url: 'http://192.0.2.1:9/v1/tool' }),
    JSON.stringify({ ...base, cancelUrl: 'http://127.0.0.1:9/other' }),
    JSON.stringify({ ...base, token: `bad token ${token}` }),
    JSON.stringify({ ...base, url: `${base.url}?x=1` }),
  ];

  for (const [index, raw] of cases.entries()) {
    dirs.writeRaw(raw, `case-${index}.json`);
    const result = await tool.execute({ action: 'status' }, context());
    assert.deepEqual(result, { content: [{ type: 'text', text: expectedText }] });
    assert.equal(JSON.stringify(result).includes(token), false);
  }
});

test('HTTP 401 and 500 return the status only', async (t) => {
  for (const status of [401, 500]) {
    const server = await useServer(t, (req, res, record, token) => {
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ version: 1, ok: false, error: { message: token } }));
    });
    endpointDir(t).write(server);

    const result = await tool.execute({ action: 'status' }, context());

    assert.equal(result.content.length, 1);
    assert.equal(result.content[0].text, `AndChamber phone request failed (HTTP ${status}).`);
    assertNoEndpoint(result, server);
  }
});

test('network failure returns a fixed error without the endpoint', async (t) => {
  const probe = http.createServer();
  await new Promise((resolve) => probe.listen(0, '127.0.0.1', resolve));
  const { port } = probe.address();
  await new Promise((resolve) => probe.close(resolve));

  const token = crypto.randomBytes(32).toString('hex');
  endpointDir(t).writeRaw(
    JSON.stringify({
      version: 1,
      url: `http://127.0.0.1:${port}/v1/tool`,
      cancelUrl: `http://127.0.0.1:${port}/v1/cancel`,
      token,
    }),
  );

  const result = await tool.execute({ action: 'status' }, context());

  assert.deepEqual(result, {
    content: [{ type: 'text', text: 'AndChamber phone request failed (network error).' }],
  });
  assert.equal(JSON.stringify(result).includes(token), false);
});

test('an unparseable or invalid envelope returns a fixed error', async (t) => {
  const bodies = [
    'not json',
    JSON.stringify({ version: 1, ok: 'yes', action: 'phone.status', epoch: 0 }),
    JSON.stringify({ version: 1, ok: true, action: 'phone.status' }),
    JSON.stringify({ version: 1, ok: true, action: 'phone.status', epoch: '0' }),
    JSON.stringify({ version: 2, ok: true, action: 'phone.status', epoch: 0 }),
  ];
  for (const body of bodies) {
    const server = await useServer(t, (req, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(body);
    });
    endpointDir(t).write(server);

    const result = await tool.execute({ action: 'status' }, context());

    assert.deepEqual(result, {
      content: [{ type: 'text', text: 'AndChamber phone returned an invalid response.' }],
    });
    assertNoEndpoint(result, server);
  }
});

test('re-reads the endpoint file on every call so a launch rotation is picked up', async (t) => {
  const first = await useServer(t, json({ version: 1, ok: true, action: 'phone.status', epoch: 1, data: { n: 1 } }));
  const second = await useServer(t, json({ version: 1, ok: true, action: 'phone.status', epoch: 2, data: { n: 2 } }));
  const dirs = endpointDir(t);

  dirs.write(first);
  const firstResult = await tool.execute({ action: 'status' }, context());
  assert.deepEqual(JSON.parse(firstResult.content[0].text).data, { n: 1 });
  assert.equal(toolRequests(first)[0].headers.authorization, `Bearer ${first.token}`);

  dirs.write(second, 'rotated.json');
  const secondResult = await tool.execute({ action: 'status' }, context());
  assert.deepEqual(JSON.parse(secondResult.content[0].text).data, { n: 2 });
  assert.equal(toolRequests(second)[0].headers.authorization, `Bearer ${second.token}`);
  assert.equal(toolRequests(first).length, 1);
  assertNoEndpoint(firstResult, first);
  assertNoEndpoint(secondResult, second);
});

test('never enables, grants or resumes through the tool input schema', () => {
  const properties = Object.keys(tool.input.properties);
  for (const forbidden of ['enable', 'grant', 'pair', 'resume', 'acknowledge', 'targetPackage', 'package', 'shell', 'intent']) {
    assert.equal(properties.includes(forbidden), false, forbidden);
  }
});

// Pinned v2 registration: transform -> list -> rpc.register. Readiness is the RPC probe itself now;
// there is no `/v1/ready` handshake, no retry window and no announcement in execute().

test('setup registers the tool, verifies the host list, then declares the ready RPC', async () => {
  const run = await setupPlugin();

  assert.equal(run.error, null);
  assert.deepEqual(run.order, ['transform', 'list', 'register']);
  assert.equal(run.added.length, 1);
  assert.equal(run.added[0].name, 'andchamber_phone');
  assert.equal(run.registrations.length, 1);

  const { definition, handlers } = run.registrations[0];
  assert.equal(definition.id, 'andchamber-phone');
  assert.deepEqual(definition.events, {});
  assert.deepEqual(Object.keys(definition.methods), ['ready']);
  assert.deepEqual(definition.methods.ready.input, EMPTY_INPUT_SCHEMA);
  assert.deepEqual(definition.methods.ready.output, READY_OUTPUT_SCHEMA);

  // Raw JSON Schema only: no package-provided schema or codec wrapper travels with the plugin.
  for (const schema of [definition.methods.ready.input, definition.methods.ready.output]) {
    assert.equal(Array.isArray(schema), false);
    assert.equal('~standard' in schema, false);
    assert.equal('_tag' in schema, false);
    assert.deepEqual(Object.keys(schema).filter((key) => !['type', 'properties', 'required', 'additionalProperties'].includes(key)), []);
  }

  const output = await handlers.ready({}, { signal: new AbortController().signal });
  assert.deepEqual(output, { ok: true, id: 'andchamber_phone' });
  assert.deepEqual(Object.keys(output), ['ok', 'id']);
});

test('the ready RPC takes no input and answers with exactly the declared output', async () => {
  // POST /api/rpc/andchamber-phone/ready carries {"input":{}} and returns {"output":{...}}.
  assert.equal(schemaAccepts(EMPTY_INPUT_SCHEMA, {}), true);
  assert.equal(schemaAccepts(EMPTY_INPUT_SCHEMA, { action: 'status' }), false);
  assert.equal(schemaAccepts(EMPTY_INPUT_SCHEMA, { input: {} }), false);
  assert.equal(schemaAccepts(EMPTY_INPUT_SCHEMA, { sessionID: 'ses-a' }), false);

  assert.equal(schemaAccepts(READY_OUTPUT_SCHEMA, { ok: true, id: 'andchamber_phone' }), true);
  assert.equal(schemaAccepts(READY_OUTPUT_SCHEMA, { ok: false, id: 'andchamber_phone' }), false);
  assert.equal(schemaAccepts(READY_OUTPUT_SCHEMA, { ok: true }), false);
  assert.equal(schemaAccepts(READY_OUTPUT_SCHEMA, { ok: true, id: 'other_tool' }), false);
  assert.equal(schemaAccepts(READY_OUTPUT_SCHEMA, { ok: true, id: 'andchamber_phone', extra: 1 }), false);

  const output = await rpc.handlers.ready({}, { signal: new AbortController().signal });
  assert.deepEqual(JSON.parse(JSON.stringify({ output })), {
    output: { ok: true, id: 'andchamber_phone' },
  });
  assert.equal(schemaAccepts(READY_OUTPUT_SCHEMA, output), true);
});

test('setup fails closed when the host does not list the registered tool', async () => {
  for (const list of [() => [], () => [{ id: 'other_tool' }]]) {
    const run = await setupPlugin({ list });
    assert.notEqual(run.error, null);
    assert.match(run.error.message, /andchamber_phone was not registered/);
    assert.deepEqual(run.order, ['transform', 'list']);
    assert.equal(run.registrations.length, 0, 'a missing tool must not get a ready RPC');
  }
});

test('setup fails closed when the ready RPC registration fails', async () => {
  const run = await setupPlugin({
    register: () => {
      throw new Error('rpc refused');
    },
  });
  assert.notEqual(run.error, null);
  assert.equal(run.error.message, 'rpc refused');
  assert.deepEqual(run.order, ['transform', 'list', 'register']);
  assert.equal(run.registrations.length, 0, 'a failed RPC must never look ready');
});

test('a failed tool registration never reaches the list or the ready RPC', async () => {
  const run = await setupPlugin({
    transform: async (callback) => {
      await callback({
        add: () => {
          throw new Error('registration refused');
        },
      });
    },
  });
  assert.notEqual(run.error, null);
  assert.equal(run.error.message, 'registration refused');
  assert.deepEqual(run.order, ['transform']);
  assert.equal(run.registrations.length, 0);
});

test('setup completes without an endpoint file and the tool still answers unavailable', async (t) => {
  const dirs = endpointDir(t);
  process.env[ENV] = path.join(dirs.dir, 'phone-tool.json'); // deliberately not written

  const run = await setupPlugin();
  assert.equal(run.error, null);
  assert.deepEqual(run.order, ['transform', 'list', 'register']);
  assert.equal(run.tool.name, 'andchamber_phone');

  const result = await run.tool.execute({ action: 'status' }, context());
  assert.equal(result.content.length, 1);
  assert.match(result.content[0].text, /not available/);
  assert.equal(JSON.stringify(result).includes(dirs.dir), false);
});
