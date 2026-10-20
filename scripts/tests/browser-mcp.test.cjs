'use strict';

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const MCP_PATH = path.resolve(__dirname, '../../app/src/main/assets/browser-control/browser-mcp.cjs');
const mcp = require(MCP_PATH);
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'utf8',
).toString('utf8');

function credential(overrides = {}) {
  return {
    version: 1,
    endpoint: overrides.endpoint || 'http://127.0.0.1:9/v1/command',
    token: overrides.token || 'test-token-value',
    sessionId: overrides.sessionId || 'session-1',
    expiresAt: overrides.expiresAt == null ? Date.now() + 60_000 : overrides.expiresAt,
  };
}

function writeCredential(dir, value, name = 'endpoint.json') {
  const file = path.join(dir, name);
  fs.writeFileSync(file, JSON.stringify(value), { mode: 0o600 });
  return file;
}

const NATIVE_METHODS = new Set(['NAVIGATE', 'SNAPSHOT', 'CLICK', 'TYPE', 'SCROLL', 'CAPTURE']);

function assertNativeRequest(record) {
  const body = JSON.parse(record.body);
  assert.equal(NATIVE_METHODS.has(body.method), true, body.method);
  assert.equal(body.method.startsWith('browser_'), false);
  assert.equal(record.headers['content-type'], 'application/json');
  assert.equal(Number(record.headers['content-length']), Buffer.byteLength(record.body));
  assert.match(record.headers.authorization || '', /^Bearer \S+$/);
  return body;
}

function startFakeServer(handler) {
  const requests = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8');
      const record = {
        method: req.method,
        url: req.url,
        headers: req.headers,
        body,
      };
      requests.push(record);
      if (record.body.length > 0 && record.body.length < 8192 && record.body.startsWith('{')) {
        const parsed = JSON.parse(record.body);
        assert.equal(NATIVE_METHODS.has(parsed.method), true, parsed.method);
      }
      handler(req, res, record);
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      resolve({
        server,
        requests,
        endpoint: `http://127.0.0.1:${address.port}/v1/command`,
        close: () => new Promise((done) => server.close(done)),
      });
    });
  });
}

function runServer(env, messages, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [MCP_PATH], {
      env: { ...process.env, ...env },
      stdio: ['pipe', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`mcp timed out\nstdout=${stdout}\nstderr=${stderr}`));
    }, options.timeoutMs || 8000);
    child.stdout.setEncoding('utf8');
    child.stderr.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    if (typeof messages === 'string' || Buffer.isBuffer(messages)) {
      child.stdin.write(messages);
    } else {
      child.stdin.write(messages.map((message) => JSON.stringify(message)).join('\n') + '\n');
    }
    if (options.keepOpen !== true) {
      child.stdin.end();
    }
    child.on('close', () => {
      clearTimeout(timer);
      const lines = stdout.split('\n').filter((line) => line.length > 0);
      resolve({
        stdout,
        stderr,
        messages: lines.map((line) => JSON.parse(line)),
      });
    });
  });
}

test('handshake, protocol negotiation, ping, and tool list', async () => {
  const result = await runServer({}, [
    { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05' } },
    { jsonrpc: '2.0', method: 'notifications/initialized' },
    { jsonrpc: '2.0', id: 2, method: 'ping' },
    { jsonrpc: '2.0', id: 3, method: 'tools/list' },
    { jsonrpc: '2.0', id: 4, method: 'initialize', params: { protocolVersion: '2099-01-01' } },
  ]);
  assert.equal(result.messages.length, 4);
  assert.equal(result.messages[0].result.protocolVersion, '2024-11-05');
  assert.equal(result.messages[1].result && Object.keys(result.messages[1].result).length, 0);
  assert.deepEqual(result.messages[2].result.tools.map((tool) => tool.name), [
    'browser_navigate',
    'browser_snapshot',
    'browser_click',
    'browser_type',
    'browser_scroll',
    'browser_capture',
  ]);
  for (const tool of result.messages[2].result.tools) {
    assert.equal(tool.inputSchema.additionalProperties, false);
  }
  assert.equal(result.messages[3].result.protocolVersion, '2025-06-18');
  assert.equal(result.stderr.includes('test-token'), false);
});

test('missing, symlink, oversized, and expired credentials fail closed', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const missing = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: path.join(dir, 'missing.json') }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.match(missing.messages[0].error.message, /Open AndChamber/);
    assert.equal(missing.stderr.includes('token'), false);

    const target = writeCredential(dir, credential());
    const link = path.join(dir, 'link.json');
    fs.symlinkSync(target, link);
    const linked = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: link }, [
      { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.match(linked.messages[0].error.message, /regular file/i);

    const huge = path.join(dir, 'huge.json');
    fs.writeFileSync(huge, 'x'.repeat(mcp.MAX_CREDENTIAL_BYTES + 1));
    const oversized = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: huge }, [
      { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.match(oversized.messages[0].error.message, /invalid size/i);

    const expired = writeCredential(dir, credential({ expiresAt: Date.now() - 1000 }), 'expired.json');
    const expiredResult = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: expired }, [
      { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.match(expiredResult.messages[0].error.message, /expired/i);
    assert.equal(expiredResult.stderr.includes('test-token-value'), false);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('endpoint is strictly http://127.0.0.1:<port>/v1/command', () => {
  assert.doesNotThrow(() => mcp.validateCredential(credential({ endpoint: 'http://127.0.0.1:9/v1/command' })));
  for (const endpoint of [
    'http://localhost:9/v1/command',
    'http://[::1]:9/v1/command',
    'http://127.0.0.1:9/v1/command/',
    'http://127.0.0.1:9/v1/command?x=1',
    'http://127.0.0.1:9/v1/command#x',
    'http://user:pass@127.0.0.1:9/v1/command',
    'https://127.0.0.1:9/v1/command',
    'http://127.0.0.1/v1/command',
    'http://example.com/v1/command',
  ]) {
    assert.throws(() => mcp.validateCredential(credential({ endpoint })), /127\.0\.0\.1/, endpoint);
  }
  const extra = credential();
  extra.note = 'no';
  assert.throws(() => mcp.validateCredential(extra), /unexpected fields/i);
});

test('invalid tool arguments never reach the endpoint', async () => {
  const fake = await startFakeServer((_req, res) => {
    res.writeHead(500);
    res.end('should not be called');
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint }));
    const result = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_click', arguments: {} } },
      { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'browser_scroll', arguments: { direction: 'left' } } },
      { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'browser_type', arguments: { ref: 'e1', text: 'ok', extra: true } } },
      { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'browser_navigate', arguments: { url: 'javascript:alert(1)' } } },
      { jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'browser_navigate', arguments: { url: 'http://example.com/' } } },
      { jsonrpc: '2.0', id: 6, method: 'tools/call', params: { name: 'browser_navigate', arguments: { url: 'https://user:secret@example.com/' } } },
      { jsonrpc: '2.0', id: 7, method: 'tools/call', params: { name: 'browser_eval', arguments: { code: '1' } } },
    ]);
    assert.equal(fake.requests.length, 0);
    assert.equal(result.messages.length, 7);
    assert.equal(result.messages.every((message) => message.error), true);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('forwards auth and body once, does not follow redirects, and does not retry', async () => {
  let calls = 0;
  const fake = await startFakeServer((req, res, record) => {
    calls += 1;
    if (calls === 1) {
      res.writeHead(302, { Location: 'http://127.0.0.1:9/stolen' });
      res.end('');
      return;
    }
    const body = assertNativeRequest(record);
    res.writeHead(409, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ id: body.id, ok: false, error: { code: 'closed', message: 'page closed' } }));
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint, token: 'secret-token' }));
    const redirected = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_click', arguments: { ref: 'e1' } } },
    ]);
    assert.equal(fake.requests.length, 1);
    assert.equal(fake.requests[0].headers.authorization, 'Bearer secret-token');
    assert.equal(fake.requests[0].headers['content-type'], 'application/json');
    assert.equal(Number(fake.requests[0].headers['content-length']), Buffer.byteLength(fake.requests[0].body));
    const forwarded = assertNativeRequest(fake.requests[0]);
    assert.equal(forwarded.method, 'CLICK');
    assert.equal(forwarded.sessionId, 'session-1');
    assert.equal(forwarded.timeoutMs, 5000);
    assert.deepEqual(forwarded.params, { ref: 'e1' });
    assert.match(redirected.messages[0].error.message, /redirect/i);
    assert.equal(redirected.stdout.includes('secret-token'), false);
    assert.equal(redirected.stderr.includes('secret-token'), false);

    const failed = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'browser_type', arguments: { ref: 'e2', text: 'hello' } } },
    ]);
    assert.equal(fake.requests.length, 2);
    assert.equal(assertNativeRequest(fake.requests[1]).method, 'TYPE');
    assert.match(failed.messages[0].error.message, /closed/);
    assert.equal(failed.stdout.includes('secret-token'), false);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('connection failure is not retried and reports an unknown result', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  const server = http.createServer((_req, res) => res.end(''));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  try {
    const file = writeCredential(dir, credential({ endpoint: `http://127.0.0.1:${port}/v1/command` }));
    const result = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_navigate', arguments: { url: 'https://example.com/' } } },
    ]);
    assert.match(result.messages[0].error.message, /unknown/i);
    assert.match(result.messages[0].error.message, /not retried/i);
    assert.equal(result.stdout.includes('test-token-value'), false);
    assert.equal(result.stderr.includes('test-token-value'), false);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('each tool posts the native enum method and keeps a structured HTTP error', async () => {
  const seen = [];
  const fake = await startFakeServer((_req, res, record) => {
    const body = assertNativeRequest(record);
    seen.push(body.method);
    res.writeHead(409, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: body.id,
      ok: false,
      error: { code: 'BUSY', message: `Bearer ${record.headers.authorization.split(' ')[1]} is busy` },
    }));
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint, token: 'wire-token' }));
    const calls = [
      ['browser_navigate', { url: 'https://example.com/path' }, 'NAVIGATE'],
      ['browser_snapshot', {}, 'SNAPSHOT'],
      ['browser_click', { ref: 'e1' }, 'CLICK'],
      ['browser_type', { ref: 'e2', text: 'hi' }, 'TYPE'],
      ['browser_scroll', { direction: 'down' }, 'SCROLL'],
      ['browser_capture', {}, 'CAPTURE'],
    ];
    const result = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, calls.map(([name, args], index) => ({
      jsonrpc: '2.0',
      id: index + 1,
      method: 'tools/call',
      params: { name, arguments: args },
    })));
    assert.deepEqual(seen, calls.map((entry) => entry[2]));
    assert.equal(fake.requests.length, 6);
    assert.equal(result.messages.length, 6);
    for (const message of result.messages) {
      assert.match(message.error.message, /^BUSY: /);
      assert.match(message.error.message, /\[redacted\]/);
      assert.equal(message.error.message.includes('wire-token'), false);
    }
    assert.equal(result.stdout.includes('wire-token'), false);
    assert.equal(result.stderr.includes('wire-token'), false);
    assert.equal(fake.requests[0].body.includes('"timeoutMs":15000'), true);
    assert.equal(fake.requests[1].body.includes('"timeoutMs":5000'), true);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('400, 401, and 403 JSON errors keep code and message without retrying', async () => {
  const statuses = [400, 401, 403];
  let index = 0;
  const fake = await startFakeServer((_req, res, record) => {
    const body = assertNativeRequest(record);
    const status = statuses[index];
    index += 1;
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: body.id,
      ok: false,
      error: { code: status === 401 ? 'UNAUTHORIZED' : 'BAD_REQUEST', message: 'refused once' },
    }));
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint }));
    const result = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [0, 1, 2].map((id) => ({
      jsonrpc: '2.0',
      id,
      method: 'tools/call',
      params: { name: 'browser_snapshot', arguments: {} },
    })));
    assert.equal(fake.requests.length, 3);
    assert.match(result.messages[0].error.message, /BAD_REQUEST: refused once/);
    assert.match(result.messages[1].error.message, /UNAUTHORIZED: refused once/);
    assert.match(result.messages[2].error.message, /BAD_REQUEST: refused once/);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('capture becomes MCP image content and text results stay text', async () => {
  const fake = await startFakeServer((_req, res, record) => {
    const body = assertNativeRequest(record);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    if (body.method === 'CAPTURE') {
      res.end(JSON.stringify({ id: body.id, ok: true, result: { mimeType: 'image/png', data: PNG } }));
      return;
    }
    res.end(JSON.stringify({ id: body.id, ok: true, result: { snapshot: '{"role":"document"}' } }));
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint }));
    const result = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_capture', arguments: {} } },
      { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.equal(result.messages[0].result.content[0].type, 'image');
    assert.equal(result.messages[0].result.content[0].mimeType, 'image/png');
    assert.equal(result.messages[0].result.content[0].data, PNG);
    assert.equal(result.messages[1].result.content[0].type, 'text');
    assert.match(result.messages[1].result.content[0].text, /"snapshot":"\{\\"role\\":\\"document\\"\}"/);
    assert.equal(fake.requests[1].body.includes('"timeoutMs":5000'), true);
    assert.equal(fake.requests.length, 2);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('malformed JSON-RPC and unknown methods return protocol errors without extra stdout', async () => {
  const child = spawn(process.execPath, [MCP_PATH], { stdio: ['pipe', 'pipe', 'pipe'] });
  let stdout = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  child.stdin.write('not-json\n');
  child.stdin.write(JSON.stringify({ jsonrpc: '1.0', id: 9, method: 'ping' }) + '\n');
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 8, method: 'unknown/method' }) + '\n');
  child.stdin.end();
  await new Promise((resolve) => child.on('close', resolve));
  const messages = stdout.split('\n').filter(Boolean).map((line) => JSON.parse(line));
  assert.equal(messages.length, 3);
  assert.equal(messages[0].error.code, -32700);
  assert.equal(messages[1].error.code, -32600);
  assert.equal(messages[2].error.code, -32601);
  assert.equal(stdout.trim().split('\n').every((line) => line.startsWith('{')), true);
});

test('disconnect and timeout each send one request and do not retry', async () => {
  const dropped = await startFakeServer((req, res) => {
    req.socket.destroy();
    res.destroy();
  });
  const stalled = await startFakeServer(() => {});
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const droppedFile = writeCredential(dir, credential({ endpoint: dropped.endpoint, token: 'drop-token' }), 'drop.json');
    const droppedResult = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: droppedFile }, [
      { jsonrpc: '2.0', id: 'drop', method: 'tools/call', params: { name: 'browser_click', arguments: { ref: 'e1' } } },
    ]);
    assert.equal(dropped.requests.length, 1);
    assert.match(droppedResult.messages[0].error.message, /unknown/i);
    assert.match(droppedResult.messages[0].error.message, /not retried/i);
    assert.equal(droppedResult.stdout.includes('drop-token'), false);
    assert.equal(droppedResult.stderr.includes('drop-token'), false);

    const stalledFile = writeCredential(dir, credential({ endpoint: stalled.endpoint, token: 'stall-token' }), 'stall.json');
    const child = spawn(process.execPath, ['-e', `
      const mcp = require(${JSON.stringify(MCP_PATH)});
      process.env.ANDCHAMBER_BROWSER_ENDPOINT_FILE = ${JSON.stringify(stalledFile)};
      mcp.callTool('browser_snapshot', {}).then(
        () => process.exit(0),
        (error) => { process.stderr.write(error.message + '\\n'); process.exit(2); }
      );
    `], { stdio: ['ignore', 'pipe', 'pipe'] });
    let stderr = '';
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    const code = await new Promise((resolve) => child.on('close', resolve));
    assert.equal(code, 2);
    assert.equal(stalled.requests.length, 1);
    assert.match(stderr, /timed out/i);
    assert.match(stderr, /not retried/i);
    assert.equal(stderr.includes('stall-token'), false);
  } finally {
    await dropped.close();
    await stalled.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('oversized line and response are truncated without leaking the token', async () => {
  const fake = await startFakeServer((_req, res, record) => {
    const body = JSON.parse(record.body);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(`${'x'.repeat(mcp.MAX_MESSAGE_BYTES + 8)}\n`);
    void body;
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint, token: 'overflow-token' }));
    const huge = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.equal(fake.requests.length, 1);
    assert.match(huge.messages[0].error.message, /too large/i);
    assert.equal(huge.stdout.includes('overflow-token'), false);
    assert.equal(huge.stderr.includes('overflow-token'), false);

    const line = `${'y'.repeat(mcp.MAX_MESSAGE_BYTES + 16)}\n`;
    const overflow = await runServer({}, line);
    assert.equal(overflow.messages.length, 1);
    assert.match(overflow.messages[0].error.message, /too large/i);
    assert.equal(overflow.stdout.includes('y'.repeat(100)), false);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('mismatched command response id is not accepted or retried', async () => {
  const fake = await startFakeServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ id: 'not-the-request', ok: true, result: { snapshot: 'secret' } }));
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint, token: 'mismatch-token' }));
    const result = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.equal(fake.requests.length, 1);
    assert.match(result.messages[0].error.message, /does not match/i);
    assert.match(result.messages[0].error.message, /not retried/i);
    assert.equal(result.stdout.includes('mismatch-token'), false);
    assert.equal(result.stdout.includes('secret'), false);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('environment proxy variables are left untouched and not used', async () => {
  const fake = await startFakeServer((_req, res, record) => {
    const body = assertNativeRequest(record);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ id: body.id, ok: true, result: { snapshot: 'ok' } }));
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint }));
    const result = await runServer({
      ANDCHAMBER_BROWSER_ENDPOINT_FILE: file,
      HTTP_PROXY: 'http://127.0.0.1:9',
      HTTPS_PROXY: 'http://127.0.0.1:9',
      ALL_PROXY: 'http://127.0.0.1:9',
      NO_PROXY: '',
    }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.equal(fake.requests.length, 1);
    assert.equal(result.messages[0].result.content[0].type, 'text');
    const { spawnSync } = require('node:child_process');
    const preserved = spawnSync(process.execPath, ['-e', `
      require(${JSON.stringify(MCP_PATH)});
      if (process.env.HTTP_PROXY !== 'http://proxy.example:9') process.exit(2);
      if (process.env.http_proxy !== 'http://proxy.example:9') process.exit(3);
    `], {
      env: { ...process.env, HTTP_PROXY: 'http://proxy.example:9', http_proxy: 'http://proxy.example:9' },
      encoding: 'utf8',
    });
    assert.equal(preserved.status, 0);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('stderr and tool errors redact bearer tokens', async () => {
  const fake = await startFakeServer((_req, res, record) => {
    const body = assertNativeRequest(record);
    res.writeHead(409, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: body.id,
      ok: false,
      error: { code: 'denied', message: 'Bearer super-secret-token was rejected' },
    }));
  });
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'browser-mcp-'));
  try {
    const file = writeCredential(dir, credential({ endpoint: fake.endpoint, token: 'super-secret-token' }));
    const result = await runServer({ ANDCHAMBER_BROWSER_ENDPOINT_FILE: file }, [
      { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'browser_snapshot', arguments: {} } },
    ]);
    assert.match(result.messages[0].error.message, /\[redacted\]/);
    assert.equal(result.stdout.includes('super-secret-token'), false);
    assert.equal(result.stderr.includes('super-secret-token'), false);
    assert.equal(mcp.redact('Authorization: Bearer super-secret-token').includes('super-secret-token'), false);
  } finally {
    await fake.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
