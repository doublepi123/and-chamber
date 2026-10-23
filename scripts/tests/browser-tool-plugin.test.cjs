'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const { pathToFileURL } = require('node:url');

const PLUGIN_PATH = path.resolve(__dirname, '../../app/src/main/assets/browser-tool/index.js');
const PACKAGE_PATH = path.resolve(__dirname, '../../app/src/main/assets/browser-tool/package.json');
const ENV = 'ANDCHAMBER_BROWSER_TOOL_FILE';
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
        await fn({ add: (definition) => { added = definition; } });
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
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'andchamber-plugin-'));
  const previous = process.env[ENV];
  t.after(() => {
    if (previous === undefined) delete process.env[ENV];
    else process.env[ENV] = previous;
    fs.rmSync(dir, { recursive: true, force: true });
  });
  return {
    dir,
    write(server, name = 'browser-tool.json') {
      const file = path.join(dir, name);
      fs.writeFileSync(file, JSON.stringify(server.endpoint), { mode: 0o600 });
      process.env[ENV] = file;
      return file;
    },
    writeRaw(content, name = 'browser-tool.json') {
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
    name: 'andchamber-browser-tool',
    version: '1.0.0',
    private: true,
    type: 'module',
    exports: { '.': './index.js' },
  });

  assert.equal(plugin.id, 'andchamber-browser');
  assert.equal(typeof plugin.setup, 'function');
  assert.equal(tool.name, 'andchamber_web');
  assert.deepEqual(tool.options, { codemode: false });
  assert.equal(typeof tool.execute, 'function');

  assert.equal(tool.input.type, 'object');
  assert.equal(tool.input.additionalProperties, false);
  assert.deepEqual(tool.input.required, ['action']);
  assert.deepEqual(tool.input.properties.action.enum, [
    'open',
    'snapshot',
    'click',
    'type',
    'scroll',
    'capture',
    'resize',
  ]);
  assert.deepEqual(tool.input.properties.viewport.enum, ['mobile', 'desktop', 'fill']);
  assert.deepEqual(tool.input.properties.direction.enum, ['up', 'down']);
  assert.equal(tool.input.properties.submit.type, 'boolean');
  for (const key of ['url', 'tabId', 'selector', 'text', 'value']) {
    assert.equal(tool.input.properties[key].type, 'string', key);
  }

  assert.match(tool.description, /defaults to agent control/);
  assert.match(tool.description, /drivable:false/);
  assert.match(tool.description, /drivable:true/);
  assert.match(tool.description, /about a minute/);
  assert.match(tool.description, /hand control back/i);
  assert.match(tool.description, /never grants by itself/i);
  assert.match(tool.description, /does not end control/i);
  assert.match(tool.description, /not taken back by ordinary agent actions/i);
  assert.match(tool.description, /wake a hidden window/i);
  assert.doesNotMatch(tool.description, /touch/i);
  assert.match(tool.description, /latest snapshot/);
  assert.match(tool.description, /one action per call/i);
  assert.match(tool.description, /AUTHORIZATION_REQUIRED/);
  assert.match(tool.description, /REF_EXPIRED/);
  assert.match(tool.description, /OUTCOME_UNKNOWN/);
  assert.match(tool.description, /Never repeat a click, typing or submission/);
  assert.match(tool.description, /snapshot or capture to observe/);
  assert.match(tool.description, /observation does not confirm/i);
  assert.match(tool.description, /BROWSER_PAGE_UNAVAILABLE/);
  assert.match(tool.description, /do not retry in a loop/i);
  assert.doesNotMatch(tool.description, /must tap Allow/i);
  assert.doesNotMatch(tool.description, /tap Allow/i);
});

test('only open asks the server to wait for the automatic default grant', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'browser.open',
    data: { opened: true, tabId: 'tab-1', drivable: false, reason: 'authorization_required' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  await tool.execute({ action: 'open', url: 'https://example.com' }, context());
  await tool.execute({ action: 'snapshot' }, context({ id: 'call_def' }));

  const [opened, snapshot] = server.requests.map((request) => JSON.parse(request.body));
  assert.equal(opened.allowWaitMs, 60000);
  assert.deepEqual(opened.input, { action: 'open', url: 'https://example.com' });
  assert.equal('allowWaitMs' in opened, true);
  assert.equal('allowWaitMs' in snapshot, false);
  assert.deepEqual(snapshot.input, { action: 'snapshot' });
  assert.deepEqual(Object.keys(opened).sort(), [
    'agent',
    'allowWaitMs',
    'callID',
    'input',
    'messageID',
    'sessionID',
    'version',
  ]);
});

test('posts the call and returns the success envelope as text', async (t) => {
  const payload = { version: 1, ok: true, action: 'browser.snapshot', data: { snapshot: 'button "Go"' } };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'snapshot' }, context());

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
    input: { action: 'snapshot' },
  });
  assertNoEndpoint(result, server);
});

test('type preserves the submit boolean and the confirmed submission result without retry', async (t) => {
  const selector = '[data-andchamber-ref="ref-1"]';
  const payload = { version: 1, ok: true, action: 'browser.type',
    data: { typed: selector, enterDispatched: true, url: 'https://example.com/search?q=opencode', drivable: true } };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);
  for (const submit of [true, false]) {
    const input = { action: 'type', selector, text: 'opencode', submit };
    const result = await tool.execute(input, context({ id: submit ? 'submit_true' : 'submit_false' }));
    const request = JSON.parse(server.requests.at(-1).body);
    assert.deepEqual(request.input, input);
    assert.equal('allowWaitMs' in request, false);
    assert.deepEqual(JSON.parse(result.content[0].text), payload);
    assertNoEndpoint(result, server);
  }
  assert.equal(server.requests.length, 2);
  assert.match(tool.input.properties.submit.description, /same-tab GET/);
  assert.match(tool.input.properties.submit.description, /POST/);
});

test('agents can choose fixed desktop resolution for open and resize', async (t) => {
  const dir = endpointDir(t);
  for (const [action, width, height] of [['open', 1920, 1080], ['resize', 1366, 768]]) {
    const payload = { version: 1, ok: true, action: `browser.${action}`, data: { drivable: true } };
    const server = await useServer(t, json(payload));
    dir.write(server);
    const input = { action, viewport: 'desktop', width, height,
      ...(action === 'open' ? { url: 'https://example.com/' } : {}) };
    await tool.execute(input, context({ id: `desktop_${action}` }));
    assert.equal(server.requests.length, 1);
    assert.deepEqual(JSON.parse(server.requests[0].body).input, input);
  }
  assert.equal(tool.input.properties.width.type, 'integer');
  assert.equal(tool.input.properties.height.type, 'integer');
  assert.deepEqual([tool.input.properties.width.minimum, tool.input.properties.width.maximum], [640, 3840]);
  assert.deepEqual([tool.input.properties.height.minimum, tool.input.properties.height.maximum], [360, 2160]);
  assert.match(tool.description, /Choose.*mobile.*desktop.*yourself/);
  assert.match(tool.description, /1920x1080/);
});

test('returns a refused action envelope as text', async (t) => {
  const payload = {
    version: 1,
    ok: false,
    action: 'browser.click',
    error: { code: 'AUTHORIZATION_REQUIRED', message: 'Tap Allow in the AndChamber window.' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'click', selector: '#go' }, context());

  assert.equal(result.content.length, 1);
  assert.equal(result.content[0].type, 'text');
  assert.deepEqual(JSON.parse(result.content[0].text), payload);
  assertNoEndpoint(result, server);
});

test('accepts a server error envelope that carries the action', async (t) => {
  const payload = {
    version: 1,
    ok: false,
    action: 'browser.snapshot',
    error: { code: 'DUPLICATE_CALL', message: 'This call was already handled.' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'snapshot' }, context());

  assert.equal(result.content.length, 1);
  assert.equal(result.content[0].type, 'text');
  assert.deepEqual(JSON.parse(result.content[0].text), payload);
  assertNoEndpoint(result, server);
});

test('maps a png capture to a file part and keeps base64 out of the text', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'browser.capture',
    data: { base64: PNG_BASE64, mime: 'image/png', note: 'captured' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'capture' }, context());

  assert.equal(result.content.length, 2);
  assert.deepEqual(result.content[1], {
    type: 'file',
    uri: PNG_BASE64,
    mime: 'image/png',
    name: 'andchamber-capture.png',
  });
  assert.deepEqual(JSON.parse(result.content[0].text), {
    version: 1,
    ok: true,
    action: 'browser.capture',
    data: { mime: 'image/png', note: 'captured' },
  });
  assert.equal(result.content[0].text.includes(PNG_BASE64), false);
  assertNoEndpoint(result, server);
});

test('keeps a non-png capture text-only', async (t) => {
  const payload = {
    version: 1,
    ok: true,
    action: 'browser.capture',
    data: { base64: 'AAAA', mime: 'image/jpeg' },
  };
  const server = await useServer(t, json(payload));
  endpointDir(t).write(server);

  const result = await tool.execute({ action: 'capture' }, context());

  assert.equal(result.content.length, 1);
  assert.equal(JSON.parse(result.content[0].text).data.base64, undefined);
  assertNoEndpoint(result, server);
});

test('aborting posts to cancelUrl and returns Cancelled.', { timeout: 10000 }, async (t) => {
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
    { action: 'click', selector: '#go' },
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
        text: 'AndChamber browser is not available. Open the AndChamber chat screen on the phone and try again.',
      },
    ],
  };

  delete process.env[ENV];
  assert.deepEqual(await tool.execute({ action: 'snapshot' }, context()), expected);

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'andchamber-plugin-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  process.env[ENV] = path.join(dir, 'absent.json');
  assert.deepEqual(await tool.execute({ action: 'snapshot' }, context()), expected);
});

test('bad JSON, wrong version, missing token or non-loopback url fail closed', async (t) => {
  const dirs = endpointDir(t);
  const expectedText =
    'AndChamber browser is not available. Open the AndChamber chat screen on the phone and try again.';
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
    JSON.stringify({ ...base, cancelUrl: 'http://127.0.0.1:9/other' }),
    JSON.stringify({ ...base, token: `bad token ${token}` }),
  ];

  for (const [index, raw] of cases.entries()) {
    dirs.writeRaw(raw, `case-${index}.json`);
    const result = await tool.execute({ action: 'snapshot' }, context());
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

    const result = await tool.execute({ action: 'snapshot' }, context());

    assert.equal(result.content.length, 1);
    assert.equal(result.content[0].text, `AndChamber browser request failed (HTTP ${status}).`);
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

  const result = await tool.execute({ action: 'snapshot' }, context());

  assert.deepEqual(result, {
    content: [{ type: 'text', text: 'AndChamber browser request failed (network error).' }],
  });
  assert.equal(JSON.stringify(result).includes(token), false);
});

test('an unparseable or invalid envelope returns a fixed error', async (t) => {
  for (const body of ['not json', JSON.stringify({ version: 1, ok: 'yes', action: 'browser.snapshot' })]) {
    const server = await useServer(t, (req, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(body);
    });
    endpointDir(t).write(server);

    const result = await tool.execute({ action: 'snapshot' }, context());

    assert.deepEqual(result, {
      content: [{ type: 'text', text: 'AndChamber browser returned an invalid response.' }],
    });
    assertNoEndpoint(result, server);
  }
});

test('re-reads the endpoint file on every call', async (t) => {
  const first = await useServer(t, json({ version: 1, ok: true, action: 'browser.snapshot', data: { n: 1 } }));
  const second = await useServer(t, json({ version: 1, ok: true, action: 'browser.snapshot', data: { n: 2 } }));
  const dirs = endpointDir(t);

  dirs.write(first);
  const firstResult = await tool.execute({ action: 'snapshot' }, context());
  assert.deepEqual(JSON.parse(firstResult.content[0].text).data, { n: 1 });
  assert.equal(first.requests.length, 1);
  assert.equal(first.requests[0].headers.authorization, `Bearer ${first.token}`);

  dirs.write(second, 'rotated.json');
  const secondResult = await tool.execute({ action: 'snapshot' }, context());
  assert.deepEqual(JSON.parse(secondResult.content[0].text).data, { n: 2 });
  assert.equal(second.requests.length, 1);
  assert.equal(second.requests[0].headers.authorization, `Bearer ${second.token}`);
  assert.equal(first.requests.length, 1);
  assertNoEndpoint(firstResult, first);
  assertNoEndpoint(secondResult, second);
});
