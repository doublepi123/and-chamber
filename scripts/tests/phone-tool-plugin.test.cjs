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

test.before(async () => {
  plugin = (await import(pathToFileURL(PLUGIN_PATH).href)).default;
  let added;
  const fakeContext = {
    tool: {
      transform: async (fn) => {
        await fn({
          add: (definition) => {
            added = definition;
          },
        });
      },
    },
  };
  await plugin.setup(fakeContext);
  tool = added;
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
  for (const key of ['phase', 'message', 'reason']) {
    assert.equal(tool.input.properties[key].maxLength, 120, key);
  }
  assert.equal(tool.input.properties.epoch.type, 'integer');
  assert.equal(tool.input.properties.epoch.minimum, 0);
  assert.equal(Array.isArray(tool.input.allOf), true);
  assert.deepEqual(tool.input.allOf[0].then.required, ['epoch']);
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
  assert.match(tool.description, /请在AndChamber手机控制中确认/);
  assert.match(tool.description, /read-only preparation/);
  assert.match(tool.description, /never enables, pairs, grants, resumes, acknowledges or opens a screen/);
  assert.match(tool.description, /another chat session cannot replace it/);
  assert.match(tool.description, /refused while a grant is active/);
  assert.match(tool.description, /never enable, pair, grant, resume, acknowledge/);
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
  assert.doesNotMatch(tool.description, /shell|intent|package list/i);
});

test('posts client identity from the V2 context and never a model-chosen session', async (t) => {
  const payload = { version: 1, ok: true, action: 'phone.status', epoch: 0, data: { enabled: false } };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'status' }, context());

  assert.deepEqual(result, { content: [{ type: 'text', text: JSON.stringify(payload) }] });
  assert.equal(server.requests.length, 1);
  const request = server.requests[0];
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
    assert.deepEqual(JSON.parse(server.requests.at(-1).body).input, input);
  }
  assert.equal(server.requests.length, cases.length);
});

test('a successful request returns the fixed native-confirmation text only', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'phone.request',
    epoch: 3,
    data: { state: 'PENDING_CONFIRMATION', confirmation: '请在AndChamber手机控制中确认' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute(
    { action: 'request', summary: 'Order groceries', packageName: 'com.example.target' },
    context(),
  );

  assert.deepEqual(result, {
    content: [{ type: 'text', text: '请在AndChamber手机控制中确认' }],
  });
  assert.equal(JSON.stringify(result).includes('Order groceries'), false);
  assertNoEndpoint(result, server);
  assert.deepEqual(JSON.parse(server.requests[0].body).input, {
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
  assert.deepEqual(JSON.parse(server.requests[0].body).input, {
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

  await waitFor(() => server.requests.length === 1);
  controller.abort();
  const result = await pending;

  assert.deepEqual(result, { content: [{ type: 'text', text: 'Cancelled.' }] });
  await waitFor(() => server.requests.length === 2);
  const cancel = server.requests[1];
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
  assert.equal(first.requests[0].headers.authorization, `Bearer ${first.token}`);

  dirs.write(second, 'rotated.json');
  const secondResult = await tool.execute({ action: 'status' }, context());
  assert.deepEqual(JSON.parse(secondResult.content[0].text).data, { n: 2 });
  assert.equal(second.requests[0].headers.authorization, `Bearer ${second.token}`);
  assert.equal(first.requests.length, 1);
  assertNoEndpoint(firstResult, first);
  assertNoEndpoint(secondResult, second);
});

test('never enables, grants or resumes through the tool input schema', () => {
  const properties = Object.keys(tool.input.properties);
  for (const forbidden of ['enable', 'grant', 'pair', 'resume', 'acknowledge', 'targetPackage', 'package', 'shell', 'intent']) {
    assert.equal(properties.includes(forbidden), false, forbidden);
  }
});
