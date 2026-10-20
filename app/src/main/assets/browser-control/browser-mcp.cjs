#!/usr/bin/env node
// Zero-dependency stdio MCP bridge for AndChamber's embedded browser.
// Reads a private credential file named by ANDCHAMBER_BROWSER_ENDPOINT_FILE
// on every call. Never prints the token. Never follows redirects. Never retries.
'use strict';

const fs = require('fs');
const http = require('http');
const { randomUUID } = require('crypto');

const PROTOCOL_VERSIONS = ['2024-11-05', '2025-03-26', '2025-06-18'];
const DEFAULT_PROTOCOL = '2025-06-18';
const SERVER_NAME = 'andchamber-browser';
const SERVER_VERSION = '1.0.0';
const MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
const MAX_CREDENTIAL_BYTES = 8192;
const MAX_TOKEN_LENGTH = 512;
const MAX_SESSION_ID_LENGTH = 128;
const MAX_ERROR_MESSAGE = 500;
const MAX_URL_LENGTH = 2048;
const MAX_REF_LENGTH = 256;
const MAX_TEXT_LENGTH = 8192;
const MAX_RESULT_TEXT = 64 * 1024;
const DEFAULT_TIMEOUT_MS = 5000;
const NAVIGATE_TIMEOUT_MS = 15000;
const ENDPOINT_PATH = '/v1/command';
const ENV_NAME = 'ANDCHAMBER_BROWSER_ENDPOINT_FILE';

const DISABLED_MESSAGE =
  'Browser control is not available. Open AndChamber, open its standalone browser, and enable browser control.';

const TOOLS = [
  {
    name: 'browser_navigate',
    description: 'Navigate the embedded browser to an https URL.',
    timeoutMs: NAVIGATE_TIMEOUT_MS,
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      properties: {
        url: { type: 'string', minLength: 1, maxLength: MAX_URL_LENGTH },
      },
      required: ['url'],
    },
  },
  {
    name: 'browser_snapshot',
    description: 'Return a text snapshot of the embedded browser page.',
    timeoutMs: DEFAULT_TIMEOUT_MS,
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      properties: {},
    },
  },
  {
    name: 'browser_click',
    description: 'Click an element identified by a snapshot ref.',
    timeoutMs: DEFAULT_TIMEOUT_MS,
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      properties: {
        ref: { type: 'string', minLength: 1, maxLength: MAX_REF_LENGTH },
      },
      required: ['ref'],
    },
  },
  {
    name: 'browser_type',
    description: 'Type text into an element identified by a snapshot ref.',
    timeoutMs: DEFAULT_TIMEOUT_MS,
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      properties: {
        ref: { type: 'string', minLength: 1, maxLength: MAX_REF_LENGTH },
        text: { type: 'string', maxLength: MAX_TEXT_LENGTH },
      },
      required: ['ref', 'text'],
    },
  },
  {
    name: 'browser_scroll',
    description: 'Scroll the embedded browser page.',
    timeoutMs: DEFAULT_TIMEOUT_MS,
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      properties: {
        direction: { type: 'string', enum: ['up', 'down', 'top', 'bottom'] },
      },
      required: ['direction'],
    },
  },
  {
    name: 'browser_capture',
    description: 'Capture a PNG screenshot of the embedded browser page.',
    timeoutMs: DEFAULT_TIMEOUT_MS,
    inputSchema: {
      type: 'object',
      additionalProperties: false,
      properties: {},
    },
  },
];

const TOOL_BY_NAME = new Map(TOOLS.map((tool) => [tool.name, tool]));
const WIRE_METHOD = {
  browser_navigate: 'NAVIGATE',
  browser_snapshot: 'SNAPSHOT',
  browser_click: 'CLICK',
  browser_type: 'TYPE',
  browser_scroll: 'SCROLL',
  browser_capture: 'CAPTURE',
};
const STRUCTURED_ERROR_STATUSES = new Set([400, 401, 403, 409, 411, 413, 415]);

function boundedString(value, max) {
  return typeof value === 'string' && value.length > 0 && value.length <= max;
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function redact(text, secret) {
  let value = String(text == null ? '' : text);
  value = value
    .replace(/Bearer\s+\S+/gi, 'Bearer [redacted]')
    .replace(/("token"\s*:\s*")[^"]*(")/gi, '$1[redacted]$2');
  if (typeof secret === 'string' && secret.length > 0) {
    value = value.split(secret).join('[redacted]');
  }
  return value.slice(0, MAX_ERROR_MESSAGE);
}

function logError(message) {
  process.stderr.write(`${redact(message)}\n`);
}

function fail(message) {
  const error = new Error(redact(message));
  error.safe = true;
  return error;
}

function readCredentialFile() {
  const filePath = process.env[ENV_NAME];
  if (typeof filePath !== 'string' || filePath.length === 0) {
    throw fail(DISABLED_MESSAGE);
  }
  let stat;
  try {
    stat = fs.lstatSync(filePath);
  } catch (_error) {
    throw fail(DISABLED_MESSAGE);
  }
  if (!stat.isFile() || stat.isSymbolicLink()) {
    throw fail('Browser control credential file must be a regular file, not a symlink.');
  }
  if (stat.size <= 0 || stat.size > MAX_CREDENTIAL_BYTES) {
    throw fail('Browser control credential file has an invalid size.');
  }
  let raw;
  try {
    raw = fs.readFileSync(filePath);
  } catch (_error) {
    throw fail(DISABLED_MESSAGE);
  }
  if (raw.length !== stat.size || raw.length > MAX_CREDENTIAL_BYTES) {
    throw fail('Browser control credential file has an invalid size.');
  }
  let parsed;
  try {
    parsed = JSON.parse(raw.toString('utf8'));
  } catch (_error) {
    throw fail('Browser control credential file is not valid JSON.');
  }
  return validateCredential(parsed);
}

function validateCredential(value) {
  if (!isPlainObject(value)) {
    throw fail('Browser control credential file is invalid.');
  }
  const keys = Object.keys(value);
  const allowed = new Set(['version', 'endpoint', 'token', 'sessionId', 'expiresAt']);
  if (keys.length !== allowed.size || keys.some((key) => !allowed.has(key))) {
    throw fail('Browser control credential file has unexpected fields.');
  }
  if (value.version !== 1) {
    throw fail('Browser control credential file has an unsupported version.');
  }
  if (!boundedString(value.token, MAX_TOKEN_LENGTH) || /[\r\n\0]/.test(value.token)) {
    throw fail('Browser control credential file has an invalid token.');
  }
  if (!boundedString(value.sessionId, MAX_SESSION_ID_LENGTH) || /[\r\n\0]/.test(value.sessionId)) {
    throw fail('Browser control credential file has an invalid session.');
  }
  if (!Number.isInteger(value.expiresAt) || value.expiresAt <= Date.now()) {
    throw fail('Browser control credentials have expired. Enable browser control again in AndChamber.');
  }
  validateEndpoint(value.endpoint);
  return {
    endpoint: value.endpoint,
    token: value.token,
    sessionId: value.sessionId,
    expiresAt: value.expiresAt,
  };
}

function validateEndpoint(endpoint) {
  const match = typeof endpoint === 'string'
    ? /^http:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})\/v1\/command$/.exec(endpoint)
    : null;
  const port = match ? Number(match[1]) : 0;
  if (!match || !Number.isInteger(port) || port < 1 || port > 65535) {
    throw fail('Browser control endpoint must be http://127.0.0.1:<port>/v1/command.');
  }
  return { hostname: '127.0.0.1', port, pathname: ENDPOINT_PATH };
}

function validateArguments(tool, args) {
  if (args === undefined) {
    args = {};
  }
  if (!isPlainObject(args)) {
    throw fail(`Invalid arguments for ${tool.name}.`);
  }
  const schema = tool.inputSchema;
  const properties = schema.properties || {};
  const required = schema.required || [];
  const unknown = Object.keys(args).filter((key) => !Object.prototype.hasOwnProperty.call(properties, key));
  if (unknown.length > 0) {
    throw fail(`Unexpected argument for ${tool.name}.`);
  }
  for (const key of required) {
    if (!Object.prototype.hasOwnProperty.call(args, key)) {
      throw fail(`Missing required argument for ${tool.name}.`);
    }
  }
  for (const [key, spec] of Object.entries(properties)) {
    if (!Object.prototype.hasOwnProperty.call(args, key)) {
      continue;
    }
    const value = args[key];
    if (spec.type === 'string' && typeof value !== 'string') {
      throw fail(`Invalid argument type for ${tool.name}.`);
    }
    if (typeof spec.minLength === 'number' && value.length < spec.minLength) {
      throw fail(`Invalid argument length for ${tool.name}.`);
    }
    if (typeof spec.maxLength === 'number' && value.length > spec.maxLength) {
      throw fail(`Invalid argument length for ${tool.name}.`);
    }
    if (Array.isArray(spec.enum) && !spec.enum.includes(value)) {
      throw fail(`Invalid argument value for ${tool.name}.`);
    }
  }
  if (tool.name === 'browser_navigate') {
    validateNavigationUrl(args.url);
  }
  return args;
}

function validateNavigationUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch (_error) {
    throw fail('Navigation URL is invalid.');
  }
  if (url.protocol !== 'https:' || url.username || url.password) {
    throw fail('Navigation URL must be https without embedded credentials.');
  }
}

function postCommand(credential, method, params, timeoutMs) {
  const endpoint = validateEndpoint(credential.endpoint);
  const wireMethod = WIRE_METHOD[method];
  if (!wireMethod) {
    return Promise.reject(fail(`Unknown tool: ${method}`));
  }
  const requestId = randomUUID();
  const body = JSON.stringify({
    id: requestId,
    sessionId: credential.sessionId,
    method: wireMethod,
    params,
    timeoutMs,
  });
  const payload = Buffer.from(body, 'utf8');
  if (payload.length > MAX_MESSAGE_BYTES) {
    return Promise.reject(fail('Browser command is too large.'));
  }
  const port = endpoint.port;
  const hostname = endpoint.hostname;
  const options = {
    protocol: 'http:',
    hostname,
    port,
    method: 'POST',
    path: ENDPOINT_PATH,
    headers: {
      Authorization: `Bearer ${credential.token}`,
      'Content-Type': 'application/json',
      'Content-Length': payload.length,
      Accept: 'application/json',
      Connection: 'close',
    },
    timeout: timeoutMs,
    agent: false,
  };
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error, value) => {
      if (settled) {
        return;
      }
      settled = true;
      if (error) {
        reject(error);
      } else {
        resolve(value);
      }
    };
    const request = http.request(options, (response) => {
      const chunks = [];
      let size = 0;
      response.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_MESSAGE_BYTES) {
          request.destroy();
          finish(fail('Browser control response is too large.'));
          return;
        }
        chunks.push(chunk);
      });
      response.on('end', () => {
        const status = response.statusCode;
        const raw = Buffer.concat(chunks, size).toString('utf8');
        if (status >= 300 && status < 400) {
          finish(fail('Browser control refused a redirect. The command result is unknown; it was not retried.'));
          return;
        }
        if (status === 200 || STRUCTURED_ERROR_STATUSES.has(status)) {
          finish(null, { raw, requestId, status });
          return;
        }
        finish(fail(`Browser control returned HTTP ${status}. The command result is unknown; it was not retried.`));
      });
      response.on('error', () => {
        finish(fail('Browser control connection failed. The command result is unknown; it was not retried.'));
      });
    });
    request.on('timeout', () => {
      request.destroy();
      finish(fail('Browser control timed out. The command result is unknown; it was not retried.'));
    });
    request.on('error', () => {
      finish(fail('Browser control connection failed. The command result is unknown; it was not retried.'));
    });
    request.end(payload);
  });
}

function assertStringMap(result) {
  if (!isPlainObject(result)) {
    throw fail('Browser control result is invalid.');
  }
  for (const value of Object.values(result)) {
    if (typeof value !== 'string') {
      throw fail('Browser control result is invalid.');
    }
  }
  return result;
}

function parseCommandResponse(payload, token) {
  const raw = payload && payload.raw;
  const requestId = payload && payload.requestId;
  if (typeof raw !== 'string' || Buffer.byteLength(raw, 'utf8') > MAX_MESSAGE_BYTES) {
    throw fail('Browser control response is too large.');
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (_error) {
    throw fail('Browser control returned malformed JSON.');
  }
  if (!isPlainObject(parsed) || typeof parsed.id !== 'string' || typeof parsed.ok !== 'boolean') {
    throw fail('Browser control returned an invalid response.');
  }
  if (parsed.id !== requestId) {
    throw fail('Browser control response id does not match. The command result is unknown; it was not retried.');
  }
  if (payload.status !== 200 && parsed.ok !== false) {
    throw fail('Browser control returned an invalid response. The command result is unknown; it was not retried.');
  }
  if (parsed.ok === true) {
    if (payload.status !== 200) {
      throw fail('Browser control returned an invalid response. The command result is unknown; it was not retried.');
    }
    return assertStringMap(parsed.result);
  }
  const error = isPlainObject(parsed.error) ? parsed.error : {};
  const code = boundedString(error.code, 64) ? error.code : 'browser_error';
  const message = boundedString(error.message, MAX_ERROR_MESSAGE)
    ? error.message
    : 'Browser control reported an error.';
  const wrapped = fail(`${code}: ${redact(message, token)}`);
  wrapped.code = code;
  throw wrapped;
}

function toToolResult(toolName, result) {
  if (toolName === 'browser_capture') {
    const keys = Object.keys(result);
    if (
      !isPlainObject(result) ||
      keys.length !== 2 ||
      result.mimeType !== 'image/png' ||
      !boundedString(result.data, MAX_MESSAGE_BYTES) ||
      !/^[A-Za-z0-9+/]+={0,2}$/.test(result.data)
    ) {
      throw fail('Browser capture result is invalid.');
    }
    return {
      content: [{ type: 'image', mimeType: 'image/png', data: result.data }],
    };
  }
  let text;
  if (typeof result === 'string') {
    text = result;
  } else {
    try {
      text = JSON.stringify(result);
    } catch (_error) {
      throw fail('Browser control result could not be encoded.');
    }
  }
  if (text == null) {
    text = 'null';
  }
  if (text.length > MAX_RESULT_TEXT) {
    throw fail('Browser control result is too large.');
  }
  return { content: [{ type: 'text', text }] };
}

async function callTool(name, args) {
  const tool = TOOL_BY_NAME.get(name);
  if (!tool) {
    throw fail(`Unknown tool: ${name}`);
  }
  const params = validateArguments(tool, args);
  const credential = readCredentialFile();
  let payload;
  try {
    payload = await postCommand(credential, name, params, tool.timeoutMs);
  } catch (error) {
    throw fail(redact(
      error && error.message ? error.message : 'Browser control connection failed. The command result is unknown; it was not retried.',
      credential.token,
    ));
  }
  return toToolResult(name, parseCommandResponse(payload, credential.token));
}

function toolListResult() {
  return {
    tools: TOOLS.map((tool) => ({
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema,
    })),
  };
}

function initializeResult(requested) {
  const protocolVersion = PROTOCOL_VERSIONS.includes(requested) ? requested : DEFAULT_PROTOCOL;
  return {
    protocolVersion,
    capabilities: { tools: { listChanged: false } },
    serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
    instructions: DISABLED_MESSAGE,
  };
}

function rpcError(id, code, message) {
  return { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message: redact(message) } };
}

function rpcResult(id, result) {
  return { jsonrpc: '2.0', id, result };
}

async function handleMessage(message) {
  if (!isPlainObject(message) || message.jsonrpc !== '2.0' || typeof message.method !== 'string') {
    return rpcError(isPlainObject(message) ? message.id : null, -32600, 'Invalid Request');
  }
  const hasId = Object.prototype.hasOwnProperty.call(message, 'id');
  const id = message.id;
  if (hasId && id !== null && typeof id !== 'string' && typeof id !== 'number') {
    return rpcError(null, -32600, 'Invalid Request');
  }
  if (!hasId) {
    return null;
  }
  try {
    switch (message.method) {
      case 'initialize': {
        const params = isPlainObject(message.params) ? message.params : {};
        return rpcResult(id, initializeResult(params.protocolVersion));
      }
      case 'ping':
        return rpcResult(id, {});
      case 'tools/list':
        return rpcResult(id, toolListResult());
      case 'tools/call': {
        const params = message.params;
        if (!isPlainObject(params) || typeof params.name !== 'string') {
          return rpcError(id, -32602, 'Invalid params');
        }
        const result = await callTool(params.name, params.arguments);
        return rpcResult(id, result);
      }
      default:
        return rpcError(id, -32601, 'Method not found');
    }
  } catch (error) {
    const messageText = error && error.safe ? error.message : 'Browser control request failed.';
    return rpcError(id, -32000, messageText);
  }
}

function writeMessage(message) {
  const line = `${JSON.stringify(message)}\n`;
  if (Buffer.byteLength(line, 'utf8') > MAX_MESSAGE_BYTES) {
    const fallback = rpcError(message.id, -32000, 'Response is too large.');
    process.stdout.write(`${JSON.stringify(fallback)}\n`);
    return;
  }
  process.stdout.write(line);
}

function start() {
  let buffer = Buffer.alloc(0);
  let closed = false;
  process.stdin.on('data', (chunk) => {
    if (closed) {
      return;
    }
    buffer = Buffer.concat([buffer, chunk]);
    if (buffer.length > MAX_MESSAGE_BYTES) {
      closed = true;
      buffer = Buffer.alloc(0);
      writeMessage(rpcError(null, -32000, 'Input is too large.'));
      process.exitCode = 1;
      process.stdin.pause();
      return;
    }
    let newline = buffer.indexOf(0x0a);
    while (newline !== -1) {
      const raw = buffer.subarray(0, newline).toString('utf8').replace(/\r$/, '');
      buffer = buffer.subarray(newline + 1);
      if (raw.length > 0) {
        let parsed = null;
        let response;
        try {
          parsed = JSON.parse(raw);
          response = handleMessage(parsed);
        } catch (_error) {
          response = Promise.resolve(rpcError(null, -32700, 'Parse error'));
        }
        Promise.resolve(response).then((value) => {
          if (value) {
            writeMessage(value);
          }
        }).catch(() => {
          writeMessage(rpcError(isPlainObject(parsed) ? parsed.id : null, -32603, 'Internal error'));
        });
      }
      newline = buffer.indexOf(0x0a);
    }
  });
  process.stdin.on('end', () => {
    if (closed || buffer.length === 0) {
      return;
    }
    writeMessage(rpcError(null, -32700, 'Parse error'));
  });
  process.stdin.on('error', () => {
    logError('stdin failed');
  });
}

if (require.main === module) {
  start();
}

module.exports = {
  DISABLED_MESSAGE,
  ENDPOINT_PATH,
  ENV_NAME,
  MAX_CREDENTIAL_BYTES,
  MAX_MESSAGE_BYTES,
  PROTOCOL_VERSIONS,
  TOOLS,
  WIRE_METHOD,
  callTool,
  handleMessage,
  readCredentialFile,
  redact,
  start,
  validateCredential,
  validateEndpoint,
};
