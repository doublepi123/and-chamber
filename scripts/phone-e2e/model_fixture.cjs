#!/usr/bin/env node
'use strict';

// Deterministic OpenAI-compatible model fixture for the AndChamber phone-control E2E.
//
// It serves POST /v1/chat/completions (JSON and SSE) for OpenCode 2.0.32's
// @opencode/ai/providers/openai-compatible route. It is not a model: it only answers a test
// sentinel embedded in the latest user message, and it only emits an andchamber_phone tool call
// when the request itself proves the tool was injected. Every real action is executed by OpenCode
// through the plugin; this process never calls the plugin, the loopback tool server or adb.
//
// The default listener is loopback only. An emulator guest reaches it through the standard host
// alias 10.0.2.2; the baseURL is a configuration parameter, never a hard-coded private address.
//
// Logs contain only request counts, sentinel case, tool name and fixed decision/case names. They
// never contain prompts, tool arguments, tool result bodies, headers, keys or screenshots.

const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const PRIVATE_ROOT = '/tmp/opencode';
const PHONE_TOOL = 'andchamber_phone';
const TARGET_PACKAGE = 'io.example.phonefixture';
const DEFAULT_INPUT_TEXT = 'andchamber-ascii-1';
const INPUT_ECHO_EMPTY = 'fixture-input-echo-empty';
const FINISH_TEXT = '报告结束，待核对';
// Must match PhoneStandingContinueSender.STANDING_CONTINUE_PROMPT. This is the real native wake-up,
// not a second test-sentinel prompt posted by the acceptance driver.
const NATIVE_CONTINUE_PROMPT = 'Continue the previous task. First observe the phone screen again; do not repeat any action that already happened.';
// Direct-start states accepted from the native standing starter. There is no PENDING_CONFIRMATION.
const START_STATES = ['RUNNING', 'READY', 'NEEDS_USER'];
const ACTIONS = [
  'status', 'request', 'apps', 'open_app', 'progress', 'need_user', 'finish',
  'snapshot', 'capture', 'tap', 'swipe', 'input', 'back',
];
const TOOL_MEDIA_PART_TYPES = new Set([
  'image_url',
  'input_image',
  'file',
  'image',
  'image-data',
  'image-url',
  'file-data',
  'file-url',
]);
const REQUIRED_TOOL_PROPERTIES = [
  'action', 'summary', 'packageName', 'observationId', 'ref', 'x', 'y', 'x2', 'y2', 'durationMs', 'text',
  'epoch', 'phase', 'message', 'reason',
];
const SENTINEL_RE = /\[\[andchamber-phone-e2e\s+(\{[\s\S]*?\})\]\]/g;
const MAX_BODY_BYTES = 16 * 1024 * 1024;

const SEQUENCES = Object.freeze({
  status: ['status'],
  apps: ['apps'],
  open_app: ['open_app', 'snapshot'],
  snapshot: ['snapshot'],
  input: ['status', 'snapshot', 'input', 'snapshot'],
  tap: ['status', 'snapshot', 'tap', 'snapshot'],
  tap_save: ['status', 'snapshot', 'tap', 'snapshot'],
  tap_next: ['status', 'snapshot', 'tap', 'snapshot'],
  swipe: ['status', 'snapshot', 'swipe', 'snapshot'],
  back: ['status', 'snapshot', 'back', 'snapshot'],
  capture: ['status', 'snapshot', 'capture', 'snapshot'],
  progress: ['status', 'progress'],
  need_user: ['status', 'need_user'],
  finish: ['status', 'finish'],
});

class PlanError extends Error {}

function planFail(reason) {
  throw new PlanError(reason);
}

function isInteger(value) {
  return Number.isInteger(value);
}

function messageText(message) {
  if (!message || typeof message !== 'object') return '';
  const content = message.content;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((part) => {
        if (!part || typeof part !== 'object') return '';
        if (typeof part.text === 'string') return part.text;
        return '';
      })
      .join('');
  }
  if (Array.isArray(message.parts)) {
    return message.parts
      .map((part) => (part && typeof part.text === 'string' ? part.text : ''))
      .join('');
  }
  if (typeof message.text === 'string') return message.text;
  return '';
}

/**
 * A tool result is either one string or a known multipart content array with exactly one text
 * part and only media/file parts beside it. Multiple text parts or unknown part types are refused:
 * the fixture never extracts a JSON prefix from an arbitrary wrapper, so a page value cannot be
 * mistaken for a successful tool envelope.
 */
function toolResultPayload(message) {
  const content = message && message.content;
  if (typeof content === 'string') return { text: content, valid: true };
  if (!Array.isArray(content)) return { text: '', valid: false };
  const textParts = content.filter((part) => part && part.type === 'text' && typeof part.text === 'string');
  if (textParts.length !== 1) return { text: '', valid: false };
  const otherParts = content.filter((part) => part && part.type !== 'text');
  if (otherParts.some((part) => !TOOL_MEDIA_PART_TYPES.has(part.type))) {
    return { text: '', valid: false };
  }
  return { text: textParts[0].text, valid: true };
}

function normalizeMessages(body) {
  if (Array.isArray(body.messages)) return body.messages;
  if (Array.isArray(body.prompt)) {
    return body.prompt
      .filter((item) => item && typeof item === 'object')
      .map((item) => ({
        role: typeof item.role === 'string' ? item.role : 'user',
        content: item.content !== undefined ? item.content : item.text,
      }));
  }
  return [];
}

function findSentinel(messages) {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!message || message.role !== 'user') continue;
    const text = messageText(message);
    SENTINEL_RE.lastIndex = 0;
    let match;
    let last = null;
    while ((match = SENTINEL_RE.exec(text)) !== null) last = match;
    if (!last) continue;
    let value;
    try {
      value = JSON.parse(last[1]);
    } catch {
      return { index, value: null, invalid: true };
    }
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
      return { index, value: null, invalid: true };
    }
    return { index, value, invalid: false };
  }
  return null;
}

function findNativeContinuation(messages, sentinel) {
  if (!sentinel) return null;
  for (let index = messages.length - 1; index > sentinel.index; index -= 1) {
    const message = messages[index];
    if (!message || message.role !== 'user') continue;
    if (messageText(message).trim() === NATIVE_CONTINUE_PROMPT) return { index };
  }
  return null;
}

function inspectTools(tools) {
  const list = Array.isArray(tools) ? tools : [];
  let phone = null;
  for (const tool of list) {
    if (!tool || typeof tool !== 'object') continue;
    const fn = tool.function && typeof tool.function === 'object' ? tool.function : tool;
    if (fn.name === PHONE_TOOL) {
      phone = fn;
      break;
    }
  }
  if (!phone) {
    return { count: list.length, present: false, valid: false, reasons: ['missing'] };
  }
  const reasons = [];
  const parameters = phone.parameters && typeof phone.parameters === 'object'
    ? phone.parameters
    : phone.inputSchema;
  if (!parameters || typeof parameters !== 'object') reasons.push('parameters');
  const properties = parameters && parameters.properties && typeof parameters.properties === 'object'
    ? parameters.properties
    : null;
  if (!properties) reasons.push('properties');
  for (const name of REQUIRED_TOOL_PROPERTIES) {
    if (!properties || !Object.prototype.hasOwnProperty.call(properties, name)) reasons.push(`property:${name}`);
  }
  const action = properties && properties.action && typeof properties.action === 'object' ? properties.action : null;
  if (!action || !Array.isArray(action.enum)) reasons.push('action-enum');
  else {
    for (const name of ACTIONS) {
      if (!action.enum.includes(name)) reasons.push(`action:${name}`);
    }
  }
  if (!Array.isArray(parameters && parameters.required) || !parameters.required.includes('action')) {
    reasons.push('required-action');
  }
  if (!parameters || parameters.additionalProperties !== false) reasons.push('additional-properties');
  return { count: list.length, present: true, valid: reasons.length === 0, reasons };
}

function parseEnvelope(text) {
  if (typeof text !== 'string') return null;
  const trimmed = text.trim();
  if (!trimmed.startsWith('{')) return null;
  let value;
  try {
    value = JSON.parse(trimmed);
  } catch {
    return null;
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  if (value.version !== 1) return null;
  if (typeof value.ok !== 'boolean') return null;
  if (typeof value.action !== 'string') return null;
  if (!isInteger(value.epoch)) return null;
  return value;
}

function actionMatches(envelope, step) {
  if (!envelope || typeof envelope.action !== 'string') return false;
  var name = envelope.action;
  if (name.startsWith('phone.')) name = name.slice('phone.'.length);
  return name === step;
}

function analyzeTurn(body) {
  const messages = normalizeMessages(body);
  const sentinel = findSentinel(messages);
  const nativeContinuation = findNativeContinuation(messages, sentinel);
  const tools = inspectTools(body && body.tools);
  const calls = [];
  const results = [];
  if (sentinel) {
    const callsAfter = nativeContinuation ? nativeContinuation.index : sentinel.index;
    for (let index = callsAfter + 1; index < messages.length; index += 1) {
      const message = messages[index];
      if (!message || typeof message !== 'object') continue;
      if (message.role === 'assistant' && Array.isArray(message.tool_calls)) {
        for (const call of message.tool_calls) {
          const fn = call && call.function && typeof call.function === 'object' ? call.function : null;
          if (!fn || fn.name !== PHONE_TOOL) continue;
          let args = null;
          try {
            args = typeof fn.arguments === 'string' ? JSON.parse(fn.arguments) : null;
          } catch {
            args = null;
          }
          calls.push({
            id: typeof call.id === 'string' ? call.id : '',
            args: args && typeof args === 'object' && !Array.isArray(args) ? args : null,
          });
        }
      } else if (message.role === 'tool') {
        const payload = toolResultPayload(message);
        results.push({
          id: typeof message.tool_call_id === 'string' ? message.tool_call_id : '',
          text: payload.text,
          valid: payload.valid,
          parsed: payload.valid ? parseEnvelope(payload.text) : null,
        });
      }
    }
  }

  const byId = new Map();
  for (const result of results) {
    if (result.id) byId.set(result.id, result);
  }
  let resultCursor = 0;
  for (const call of calls) {
    call.result = (call.id && byId.get(call.id)) || results[resultCursor] || null;
    resultCursor += 1;
  }

  let firstSnapshot = null;
  let latestSnapshot = null;
  let latestStatus = null;
  let latestCapture = null;
  let error = null;
  for (const call of calls) {
    const parsed = call.result && call.result.parsed;
    if (!parsed) continue;
    if (parsed.ok === false && !error) error = parsed;
    if (parsed.ok !== true) continue;
    if (actionMatches(parsed, 'snapshot') && parsed.data && typeof parsed.data === 'object') {
      if (!firstSnapshot) firstSnapshot = parsed;
      latestSnapshot = parsed;
    } else if (actionMatches(parsed, 'status')) {
      latestStatus = parsed;
    } else if (actionMatches(parsed, 'capture')) {
      latestCapture = parsed;
    }
  }

  const lastCall = calls.length ? calls[calls.length - 1] : null;
  const lastResult = lastCall ? lastCall.result : null;
  return {
    messages,
    sentinel,
    nativeContinuation,
    tools,
    calls,
    results,
    callCount: calls.length,
    lastCall,
    lastResult,
    firstSnapshot,
    latestSnapshot,
    latestStatus,
    latestCapture,
    error,
  };
}

function textPlan(text, caseName) {
  return { kind: 'text', text, case: caseName };
}

function toolPlan(args, callSeq, step, scenario) {
  return {
    kind: 'tool',
    action: args.action,
    scenario,
    case: `call-${step}`,
    toolCall: {
      id: `call_phone_e2e_${callSeq}`,
      type: 'function',
      function: { name: PHONE_TOOL, arguments: JSON.stringify(args) },
    },
  };
}

function sentinelLabel(sentinel) {
  if (!sentinel) return 'none';
  if (sentinel.invalid || !sentinel.value) return 'invalid';
  const value = sentinel.value;
  const mode = ['begin', 'continue', 'action'].includes(value.mode) ? value.mode : 'invalid-mode';
  const raw = typeof value.scenario === 'string'
    ? value.scenario
    : typeof value.action === 'string' ? value.action : 'none';
  const scenario = SEQUENCES[raw] ? raw : 'other';
  return `${mode}:${scenario}`;
}

function sequenceFor(scenario) {
  return SEQUENCES[scenario] ? SEQUENCES[scenario] : null;
}

function counterOf(snapshot) {
  if (!snapshot || !Array.isArray(snapshot.nodes)) return null;
  for (const node of snapshot.nodes) {
    const label = node && typeof node.label === 'string' ? node.label : '';
    const match = /^Saved (\d+) times$/.exec(label);
    if (match) return Number(match[1]);
  }
  return null;
}

function maxRowOf(snapshot) {
  if (!snapshot || !Array.isArray(snapshot.nodes)) return null;
  let max = null;
  for (const node of snapshot.nodes) {
    const label = node && typeof node.label === 'string' ? node.label : '';
    const match = /^Fixture row (\d+)$/.exec(label);
    if (match) max = Math.max(max === null ? 0 : max, Number(match[1]));
  }
  return max;
}

function labelsOf(snapshot) {
  return Array.isArray(snapshot && snapshot.nodes)
    ? snapshot.nodes.map((node) => (node && typeof node.label === 'string' ? node.label : ''))
    : [];
}

function nodesOf(snapshot) {
  return Array.isArray(snapshot && snapshot.nodes) ? snapshot.nodes : [];
}

function editableNode(snapshot) {
  const nodes = Array.isArray(snapshot && snapshot.nodes) ? snapshot.nodes : [];
  const editable = nodes.filter((node) => {
    if (!node || node.editable !== true) return false;
    const label = typeof node.label === 'string' ? node.label : '';
    return !/password/i.test(label);
  });
  return editable.find((node) => node.label === 'fixture-input') || editable[0] || null;
}

function clickableNode(snapshot, label) {
  const nodes = Array.isArray(snapshot && snapshot.nodes) ? snapshot.nodes : [];
  return nodes.find((node) => node && node.clickable === true &&
    typeof node.label === 'string' && node.label.toLowerCase() === label.toLowerCase()) || null;
}

function inputText(value) {
  const text = typeof value.text === 'string' && value.text.length > 0 ? value.text : DEFAULT_INPUT_TEXT;
  if (!/^[\x20-\x7e]{1,64}$/.test(text)) planFail('input-not-ascii-test-text');
  return text;
}

function snapshotBinding(state) {
  const latest = state.latestSnapshot;
  if (!latest || latest.ok !== true || !latest.data || typeof latest.data !== 'object') {
    planFail('snapshot-missing');
  }
  const data = latest.data;
  if (typeof data.observationId !== 'string' || data.observationId.length === 0) planFail('observation-id-missing');
  if (!Array.isArray(data.nodes)) planFail('snapshot-nodes-missing');
  if (data.packageName !== TARGET_PACKAGE) planFail('target-package-mismatch');
  if (!isInteger(data.displayWidth) || !isInteger(data.displayHeight)) planFail('snapshot-geometry-missing');
  return data;
}

function actionArgs(state, step, value, scenario) {
  if (step === 'status' || step === 'snapshot' || step === 'apps') return { action: step };
  if (step === 'open_app') return { action: step, packageName: TARGET_PACKAGE };
  if (!state.latestStatus || state.latestStatus.ok !== true) planFail('status-missing');
  if (step === 'progress' || step === 'need_user' || step === 'finish') {
    if (!isInteger(state.latestStatus.epoch)) planFail('status-epoch-missing');
    const epoch = state.latestStatus.epoch;
    const short = (text, fallback) =>
      typeof text === 'string' && text.length > 0 ? text.slice(0, 120) : fallback;
    if (step === 'progress') {
      return { action: 'progress', epoch, phase: short(value.phase || value.message, 'fixture progress') };
    }
    if (step === 'need_user') {
      return { action: 'need_user', epoch, reason: short(value.reason || value.message, 'fixture needs a person') };
    }
    return { action: 'finish', epoch, summary: short(value.summary, 'fixture reported the end') };
  }
  const data = snapshotBinding(state);
  const observationId = data.observationId;

  if (step === 'capture') return { action: 'capture', observationId };
  if (step === 'back') return { action: 'back', observationId };

  if (step === 'input') {
    const args = { action: 'input', observationId, text: inputText(value) };
    const ref = typeof value.ref === 'string' && value.ref.length > 0
      ? value.ref
      : (editableNode(data) || {}).ref;
    if (typeof ref === 'string' && ref.length > 0) args.ref = ref;
    else planFail('editable-target-missing');
    return args;
  }

  if (step === 'tap') {
    if (typeof value.ref === 'string' && value.ref.length > 0) {
      return { action: 'tap', observationId, ref: value.ref };
    }
    if (isInteger(value.x) && isInteger(value.y)) {
      return { action: 'tap', observationId, x: value.x, y: value.y };
    }
    const wanted = typeof value.label === 'string' && value.label.length > 0
      ? value.label
      : scenario === 'tap_next' ? 'Next page' : 'Save';
    const node = clickableNode(data, wanted);
    if (!node || typeof node.ref !== 'string' || node.ref.length === 0) planFail('tap-target-missing');
    return { action: 'tap', observationId, ref: node.ref };
  }

  if (step === 'swipe') {
    const x = isInteger(value.x) ? value.x : Math.round(data.displayWidth / 2);
    const y = isInteger(value.y) ? value.y : Math.round(data.displayHeight * 0.72);
    const x2 = isInteger(value.x2) ? value.x2 : x;
    const y2 = isInteger(value.y2) ? value.y2 : Math.round(data.displayHeight * 0.28);
    const durationMs = isInteger(value.durationMs) ? value.durationMs : 300;
    if (durationMs < 50 || durationMs > 5000) planFail('swipe-duration-invalid');
    return { action: 'swipe', observationId, x, y, x2, y2, durationMs };
  }

  planFail(`unsupported-step:${step}`);
}

function verifyScenario(state, scenario, action, value) {
  if (scenario === 'finish') {
    const text = state.lastResult && state.lastResult.text;
    return typeof text === 'string' && text.trim() === FINISH_TEXT
      ? { ok: true }
      : { ok: false, reason: 'finish-result-missing' };
  }
  if (scenario === 'progress' || scenario === 'need_user') {
    const parsed = state.lastResult && state.lastResult.parsed;
    if (!parsed || parsed.ok !== true || !actionMatches(parsed, scenario)) {
      return { ok: false, reason: `${scenario}-result-missing` };
    }
    const expected = scenario === 'progress' ? 'PROGRESS' : 'WAITING_USER';
    return parsed.data && parsed.data.state === expected && parsed.data.verified === false
      ? { ok: true }
      : { ok: false, reason: `${scenario}-state-invalid` };
  }
  if (scenario === 'status') {
    const data = state.latestStatus && state.latestStatus.data;
    return data && typeof data.enabled === 'boolean' && typeof data.connected === 'boolean'
      ? { ok: true } : { ok: false, reason: 'status-missing' };
  }
  if (scenario === 'apps') {
    const parsed = state.lastResult && state.lastResult.parsed;
    const apps = parsed && parsed.data && parsed.data.apps;
    return parsed && parsed.ok === true && actionMatches(parsed, 'apps') && Array.isArray(apps) &&
      apps.length > 0 && apps.every((app) => app && app.packageName === TARGET_PACKAGE)
      ? { ok: true }
      : { ok: false, reason: 'apps-scope-mismatch' };
  }
  const first = state.firstSnapshot && state.firstSnapshot.data;
  const last = state.latestSnapshot && state.latestSnapshot.data;
  if (!last || last.packageName !== TARGET_PACKAGE) return { ok: false, reason: 'target-not-foreground' };
  const labels = labelsOf(last);

  if (scenario === 'snapshot') return { ok: true };
  if (scenario === 'open_app') {
    const opened = state.calls.some((call) => {
      const parsed = call.result && call.result.parsed;
      return parsed && parsed.ok === true && actionMatches(parsed, 'open_app') &&
        parsed.data && parsed.data.state === 'OPENED';
    });
    return opened && labels.includes('AndChamber Phone Fixture')
      ? { ok: true }
      : { ok: false, reason: 'open-app-not-observed' };
  }
  if (scenario === 'capture') {
    const capture = state.latestCapture;
    if (!capture || capture.ok !== true || !capture.data || typeof capture.data !== 'object') {
      return { ok: false, reason: 'capture-missing' };
    }
    const data = capture.data;
    if (data.mime !== 'image/png') return { ok: false, reason: 'capture-not-png' };
    if (!isInteger(data.width) || !isInteger(data.height) || data.width <= 0 || data.height <= 0) {
      return { ok: false, reason: 'capture-size-invalid' };
    }
    if (typeof data.observationId !== 'string' || data.observationId.length === 0) {
      return { ok: false, reason: 'capture-observation-missing' };
    }
    return { ok: true };
  }
  if (scenario === 'input' || (action === 'input')) {
    const expected = inputText(value);
    const marker = nodesOf(first).some((node) => node && node.editable !== true && node.label === INPUT_ECHO_EMPTY);
    if (!marker) return { ok: false, reason: 'input-echo-marker-missing' };
    // The driver clears editable labels. Only the non-editable echo is accepted as the witness.
    const witness = nodesOf(last).some((node) => node && node.editable !== true && node.label === expected);
    return witness
      ? { ok: true }
      : { ok: false, reason: 'input-echo-missing' };
  }
  if (scenario === 'tap_next') {
    return labels.includes('Second page')
      ? { ok: true }
      : { ok: false, reason: 'second-page-missing' };
  }
  if (scenario === 'tap' || scenario === 'tap_save') {
    const before = counterOf(first);
    const after = counterOf(last);
    if (before === null || after === null) return { ok: false, reason: 'counter-missing' };
    return after > before
      ? { ok: true }
      : { ok: false, reason: 'counter-not-incremented' };
  }
  if (scenario === 'swipe') {
    const before = maxRowOf(first);
    const after = maxRowOf(last);
    if (before === null || after === null) return { ok: false, reason: 'row-missing' };
    return after > before
      ? { ok: true }
      : { ok: false, reason: 'list-not-scrolled' };
  }
  if (scenario === 'back') {
    if (labels.includes('Second page')) return { ok: false, reason: 'still-on-second-page' };
    return labels.includes('AndChamber Phone Fixture')
      ? { ok: true }
      : { ok: false, reason: 'first-page-missing' };
  }
  return { ok: false, reason: `unsupported-scenario:${scenario}` };
}

function epochOf(state) {
  if (state.latestStatus && isInteger(state.latestStatus.epoch)) return state.latestStatus.epoch;
  if (state.latestSnapshot && isInteger(state.latestSnapshot.epoch)) return state.latestSnapshot.epoch;
  return 0;
}

function planTurn(body, options = {}) {
  const callSeq = isInteger(options.callSeq) ? options.callSeq : 1;
  const state = analyzeTurn(body);
  if (!state.sentinel) {
    return textPlan('phone-e2e-fixture: no test sentinel; request ignored', 'ignored');
  }
  if (state.sentinel.invalid || !state.sentinel.value) {
    return textPlan('phone-e2e-fixture: invalid test sentinel', 'sentinel-invalid');
  }
  if (!state.tools.present) {
    return textPlan('phone-e2e-fixture: andchamber_phone tool not injected', 'tools-rejected');
  }
  if (!state.tools.valid) {
    return textPlan('phone-e2e-fixture: andchamber_phone schema rejected', 'tools-rejected');
  }
  if (state.error) {
    const code = state.error.error && typeof state.error.error.code === 'string'
      ? state.error.error.code
      : 'unknown';
    return textPlan(`phone-e2e-fixture: tool refused action=${state.error.action} code=${code}`, 'tool-refused');
  }
  if (state.lastCall && state.lastResult && !state.lastResult.parsed) {
    const lastAction = state.lastCall.args && state.lastCall.args.action;
    if (lastAction !== 'request' && lastAction !== 'finish') {
      return textPlan('phone-e2e-fixture: tool result is not an envelope', 'tool-result-invalid');
    }
  }

  const value = state.sentinel.value;
  let mode = typeof value.mode === 'string' ? value.mode : '';
  let scenario = typeof value.scenario === 'string'
    ? value.scenario
    : typeof value.action === 'string' ? value.action : 'input';
  if (state.nativeContinuation) {
    mode = 'continue';
    // A need_user scenario first proves the stop, then the real native prompt resumes with a real
    // mutation. Tests can choose another bounded follow-up explicitly; no second sentinel is posted.
    scenario = typeof value.resumeScenario === 'string'
      ? value.resumeScenario
      : scenario === 'need_user' ? 'input' : scenario;
  }

  let sequence;
  if (mode === 'begin') {
    const scenarioSteps = sequenceFor(scenario);
    sequence = scenarioSteps ? ['request', ...scenarioSteps] : null;
  } else if (mode === 'continue') sequence = sequenceFor(scenario);
  else if (mode === 'action') sequence = sequenceFor(scenario);
  else return textPlan('phone-e2e-fixture: unsupported sentinel mode', 'sentinel-invalid');
  if (!sequence) return textPlan('phone-e2e-fixture: unsupported scenario', 'sentinel-invalid');

  if (!state.nativeContinuation && mode === 'begin' && state.callCount === 1) {
    // The begin request is the real chat user story: the local plugin calls "request", the native
    // standing starter creates a fresh per-task epoch and launches the allowed app. Only a parsed
    // direct-start envelope continues; a plain text ack or a refusal stops the scenario.
    const result = state.calls[0] && state.calls[0].result;
    const parsed = result && result.parsed;
    const startState = parsed && parsed.ok === true && parsed.data && typeof parsed.data === 'object'
      ? parsed.data.state
      : null;
    if (!parsed || parsed.ok !== true || !isInteger(parsed.epoch) || parsed.epoch < 1 ||
        !START_STATES.includes(startState)) {
      return textPlan('phone-e2e-fixture: request-not-confirmed', 'request-not-confirmed');
    }
    // Fall through: the next turn continues the concrete observed scenario (status, snapshot, ...).
  }

  if (state.callCount < sequence.length) {
    const step = sequence[state.callCount];
    let args;
    try {
      if (step === 'request') {
        const summary = typeof value.summary === 'string' && value.summary.length > 0
          ? value.summary.slice(0, 200)
          : `AndChamber phone E2E ${scenario}`;
        args = {
          action: 'request',
          summary,
          packageName: typeof value.packageName === 'string' ? value.packageName : TARGET_PACKAGE,
        };
      } else {
        args = actionArgs(state, step, value, scenario);
      }
    } catch (error) {
      if (!(error instanceof PlanError)) throw error;
      return textPlan(`phone-e2e-fixture: plan rejected ${error.message}`, `plan-rejected:${error.message}`);
    }
    return toolPlan(args, callSeq, step, scenario);
  }

  if (!state.lastResult) {
    return textPlan('phone-e2e-fixture: awaiting tool result', 'awaiting-tool-result');
  }
  const lastAction = state.lastCall && state.lastCall.args && typeof state.lastCall.args.action === 'string'
    ? state.lastCall.args.action
    : 'unknown';
  const mutationStep =
    sequence.find((step) => step !== 'status' && step !== 'snapshot' && step !== 'request') ||
    sequence[sequence.length - 1];
  const verified = verifyScenario(state, scenario, lastAction, value);
  if (!verified.ok) {
    return textPlan(
      `phone-e2e-fixture: e2e-complete scenario=${scenario} action=${mutationStep} verify=fail:${verified.reason} epoch=${epochOf(state)}`,
      'e2e-verify-failed',
    );
  }
  return textPlan(
    `phone-e2e-fixture: e2e-complete scenario=${scenario} action=${mutationStep} verify=pass epoch=${epochOf(state)}`,
    'e2e-complete',
  );
}

function completionFor(body, plan, seq) {
  const id = `chatcmpl-phone-e2e-${seq}`;
  const model = typeof body.model === 'string' ? body.model : 'phone';
  const message = plan.kind === 'tool'
    ? { role: 'assistant', content: null, tool_calls: [plan.toolCall] }
    : { role: 'assistant', content: plan.text };
  return {
    id,
    object: 'chat.completion',
    created: 0,
    model,
    choices: [{
      index: 0,
      message,
      finish_reason: plan.kind === 'tool' ? 'tool_calls' : 'stop',
    }],
    usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
  };
}

function ssePayloads(body, plan, seq, options = {}) {
  const id = `chatcmpl-phone-e2e-${seq}`;
  const model = typeof body.model === 'string' ? body.model : 'phone';
  const base = { id, object: 'chat.completion.chunk', created: 0, model };
  const payloads = [
    { ...base, choices: [{ index: 0, delta: { role: 'assistant' }, finish_reason: null }] },
  ];
  if (plan.kind === 'tool') {
    payloads.push({
      ...base,
      choices: [{
        index: 0,
        delta: {
          tool_calls: [{
            index: 0,
            id: plan.toolCall.id,
            function: plan.toolCall.function,
          }],
        },
        finish_reason: null,
      }],
    });
  } else {
    payloads.push({
      ...base,
      choices: [{ index: 0, delta: { content: plan.text }, finish_reason: null }],
    });
  }
  payloads.push({
    ...base,
    choices: [{ index: 0, delta: {}, finish_reason: plan.kind === 'tool' ? 'tool_calls' : 'stop' }],
  });
  if (options.includeUsage === true) {
    payloads.push({
      ...base,
      choices: [],
      usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
    });
  }
  return payloads;
}

function withinRepo(file) {
  const resolved = path.resolve(file);
  return resolved === REPO_ROOT || resolved.startsWith(REPO_ROOT + path.sep);
}

function ensureTranscriptPath(file) {
  if (!file) return null;
  if (!path.isAbsolute(file)) throw new Error('--transcript must be absolute');
  if (withinRepo(file)) throw new Error('--transcript must be outside the repository');
  const resolved = path.resolve(file);
  fs.mkdirSync(path.dirname(resolved), { recursive: true, mode: 0o700 });
  return resolved;
}

function ensurePidPath(file) {
  if (!path.isAbsolute(file)) throw new Error('--pid-file must be absolute');
  const resolved = path.resolve(file);
  if (resolved !== PRIVATE_ROOT && !resolved.startsWith(PRIVATE_ROOT + path.sep)) {
    throw new Error(`--pid-file must stay under ${PRIVATE_ROOT}`);
  }
  fs.mkdirSync(path.dirname(resolved), { recursive: true, mode: 0o700 });
  return resolved;
}

function readPid(file) {
  try {
    const value = Number(String(fs.readFileSync(file, 'utf8')).trim());
    return Number.isInteger(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

function processAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error && error.code === 'EPERM';
  }
}

function isOwnFixture(pid) {
  try {
    const raw = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8');
    return raw.split('\u0000').some((part) => part.endsWith('model_fixture.cjs') || part.endsWith('model_fixture'));
  } catch {
    return false;
  }
}

function checkExistingPid(file) {
  if (!fs.existsSync(file)) return;
  const pid = readPid(file);
  if (pid === null) {
    fs.rmSync(file, { force: true });
    return;
  }
  if (processAlive(pid)) {
    if (isOwnFixture(pid)) throw new Error(`phone-e2e model fixture already running pid=${pid}`);
    throw new Error('pid file points to a live foreign process; refusing to replace it');
  }
  fs.rmSync(file, { force: true });
}

function writePid(file) {
  fs.writeFileSync(file, `${process.pid}\n`, { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

function removeOwnPid(file) {
  try {
    if (readPid(file) === process.pid) fs.rmSync(file, { force: true });
  } catch {
    // The file is advisory; cleanup must not fail the process exit.
  }
}

function stopFromPidFile(file) {
  const pid = readPid(file);
  if (pid === null) throw new Error('pid file is missing or invalid');
  if (!processAlive(pid)) {
    fs.rmSync(file, { force: true });
    return { pid, stopped: false, reason: 'not-running' };
  }
  if (!isOwnFixture(pid)) throw new Error('pid file does not name this fixture; refusing to signal it');
  process.kill(pid, 'SIGTERM');
  const sleepArray = new Int32Array(new SharedArrayBuffer(4));
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (!processAlive(pid)) break;
    Atomics.wait(sleepArray, 0, 0, 100);
  }
  if (processAlive(pid)) {
    if (!isOwnFixture(pid)) throw new Error('process identity changed; refusing SIGKILL');
    process.kill(pid, 'SIGKILL');
  }
  fs.rmSync(file, { force: true });
  return { pid, stopped: true };
}

function logEvent(event, emit, transcript) {
  const line = [
    'phone-e2e-fixture request',
    `seq=${event.seq}`,
    `stream=${event.stream}`,
    `messages=${event.messages}`,
    `tools=${event.tools}`,
    `phoneTool=${event.phoneTool}`,
    `sentinel=${event.sentinel}`,
    `call=${event.callCount}`,
    `decision=${event.decision}`,
    `tool=${event.tool || '-'}`,
    `action=${event.action || '-'}`,
    `case=${event.case}`,
  ].join(' ');
  if (emit) emit(line);
  if (transcript) {
    fs.appendFileSync(transcript, `${JSON.stringify(event)}\n`, { mode: 0o600 });
    try { fs.chmodSync(transcript, 0o600); } catch { /* best effort */ }
  }
}

async function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('body too large'), { statusCode: 413 }));
        request.destroy();
        return;
      }
      chunks.push(chunk);
    });
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function sendJson(response, status, value) {
  const body = Buffer.from(JSON.stringify(value));
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': body.length,
    connection: 'close',
  });
  response.end(body);
}

function sendSse(response, payloads) {
  response.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-cache',
    connection: 'close',
  });
  for (const payload of payloads) {
    response.write(`data: ${JSON.stringify(payload)}\n\n`);
  }
  response.write('data: [DONE]\n\n');
  response.end();
}

function startServer(options = {}) {
  const host = options.host || '127.0.0.1';
  if (host !== '127.0.0.1') return Promise.reject(new Error('fixture only listens on 127.0.0.1'));
  const requestedPort = options.port === undefined ? 32123 : Number(options.port);
  if (!Number.isInteger(requestedPort) || requestedPort < 0 || requestedPort > 65535) {
    return Promise.reject(new Error('invalid port'));
  }
  const transcript = ensureTranscriptPath(options.transcript || null);
  const emit = typeof options.emit === 'function' ? options.emit : null;
  const callPrefix = `call_phone_e2e_${require('node:crypto').randomBytes(8).toString('hex')}`;
  let sequence = 0;
  const server = http.createServer((request, response) => {
    handleRequest(request, response).catch((error) => {
      if (!response.headersSent) {
        sendJson(response, error && error.statusCode ? error.statusCode : 500, {
          error: { message: 'fixture-internal-error', type: 'fixture_error' },
        });
      } else {
        response.end();
      }
    });
  });

  async function handleRequest(request, response) {
    const url = new URL(request.url || '/', 'http://127.0.0.1');
    if (request.method === 'GET' && url.pathname === '/health') {
      sendJson(response, 200, { ok: true, model: 'phone' });
      return;
    }
    if (request.method === 'GET' && url.pathname === '/v1/models') {
      sendJson(response, 200, {
        object: 'list',
        data: [{ id: 'phone', object: 'model', created: 0, owned_by: 'andchamber-phone-e2e-fixture' }],
      });
      return;
    }
    if (request.method !== 'POST' || url.pathname !== '/v1/chat/completions') {
      sendJson(response, 404, { error: { message: 'not-found', type: 'invalid_request_error' } });
      return;
    }
    const raw = await readBody(request);
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      sendJson(response, 400, { error: { message: 'invalid-json', type: 'invalid_request_error' } });
      return;
    }
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      sendJson(response, 400, { error: { message: 'invalid-body', type: 'invalid_request_error' } });
      return;
    }
    if (typeof body.model !== 'string' || !/(^|\/)phone$/.test(body.model)) {
      sendJson(response, 400, { error: { message: 'unsupported-model', type: 'invalid_request_error' } });
      return;
    }
    if (!Array.isArray(body.messages)) {
      sendJson(response, 400, { error: { message: 'messages-required', type: 'invalid_request_error' } });
      return;
    }
    sequence += 1;
    const state = analyzeTurn(body);
    const plan = planTurn(body, { callSeq: sequence });
    if (plan.kind === 'tool') plan.toolCall.id = `${callPrefix}_${sequence}`;
    const sentinelText = state.nativeContinuation
      ? `native-continue:${sentinelLabel(state.sentinel)}`
      : sentinelLabel(state.sentinel);
    logEvent({
      seq: sequence,
      stream: body.stream === true,
      messages: body.messages.length,
      tools: state.tools.count,
      phoneTool: !state.tools.present ? 'missing' : state.tools.valid ? 'valid' : 'invalid',
      sentinel: sentinelText,
      callCount: state.callCount,
      decision: plan.kind,
      tool: plan.kind === 'tool' ? PHONE_TOOL : '',
      action: plan.kind === 'tool' ? plan.action : '',
      case: plan.case,
    }, emit, transcript);

    if (body.stream === true) {
      sendSse(response, ssePayloads(body, plan, sequence, {
        includeUsage: Boolean(body.stream_options && body.stream_options.include_usage === true),
      }));
    } else {
      sendJson(response, 200, completionFor(body, plan, sequence));
    }
  }

  return new Promise((resolve, reject) => {
    const failListen = (error) => reject(error);
    server.once('error', failListen);
    server.listen(requestedPort, host, () => {
      server.removeListener('error', failListen);
      const port = server.address().port;
      const pidFile = options.pidFile === false
        ? null
        : ensurePidPath(options.pidFile || path.join(PRIVATE_ROOT, 'andchamber-phone-e2e', `model-fixture-${port}.pid`));
      try {
        if (pidFile) {
          checkExistingPid(pidFile);
          writePid(pidFile);
        }
        if (transcript) {
          fs.writeFileSync(transcript, '', { mode: 0o600 });
          fs.chmodSync(transcript, 0o600);
        }
      } catch (error) {
        if (pidFile) removeOwnPid(pidFile);
        server.close();
        reject(error);
        return;
      }
      let closed = false;
      const close = () => new Promise((done) => {
        if (closed) {
          done();
          return;
        }
        closed = true;
        if (pidFile) removeOwnPid(pidFile);
        server.close(() => done());
      });
      resolve({
        server,
        host,
        port,
        url: `http://${host}:${port}`,
        pid: process.pid,
        pidFile,
        transcript,
        close,
      });
    });
  });
}

function parseArgs(argv) {
  const args = {
    port: 32123,
    host: '127.0.0.1',
    pidFile: '',
    noPidFile: false,
    transcript: '',
    stop: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!key.startsWith('--')) throw new Error(`unexpected argument: ${key}`);
    if (key === '--stop') {
      args.stop = true;
      continue;
    }
    if (key === '--no-pid-file') {
      args.noPidFile = true;
      continue;
    }
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new Error(`missing value for ${key}`);
    index += 1;
    if (key === '--port') args.port = Number(value);
    else if (key === '--host') args.host = value;
    else if (key === '--pid-file') args.pidFile = value;
    else if (key === '--transcript') args.transcript = value;
    else throw new Error(`unknown option: ${key}`);
  }
  if (args.host !== '127.0.0.1') throw new Error('--host must be 127.0.0.1');
  if (!Number.isInteger(args.port) || args.port < 0 || args.port > 65535) throw new Error('invalid --port');
  if (args.pidFile) args.pidFile = ensurePidPath(args.pidFile);
  return args;
}

async function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`phone-e2e-fixture: ${error.message}`);
    process.exitCode = 2;
    return;
  }
  if (args.stop) {
    try {
      const pidFile = args.pidFile || path.join(PRIVATE_ROOT, 'andchamber-phone-e2e', `model-fixture-${args.port}.pid`);
      const result = stopFromPidFile(ensurePidPath(pidFile));
      console.log(`phone-e2e-fixture stop pid=${result.pid} stopped=${result.stopped}`);
    } catch (error) {
      console.error(`phone-e2e-fixture: ${error.message}`);
      process.exitCode = 1;
    }
    return;
  }
  let handle;
  try {
    handle = await startServer({
      host: args.host,
      port: args.port,
      pidFile: args.noPidFile ? false : (args.pidFile || undefined),
      transcript: args.transcript || null,
      emit: (line) => console.log(line),
    });
  } catch (error) {
    console.error(`phone-e2e-fixture: ${error.message}`);
    process.exitCode = 1;
    return;
  }
  console.log(`phone-e2e-fixture listen ${handle.url}/v1 model=phone pid=${handle.pid} pidFile=${handle.pidFile || '-'}`);
  const shutdown = () => {
    handle.close().then(() => {
      process.exitCode = 0;
    });
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
}

if (require.main === module) main();

module.exports = {
  PHONE_TOOL,
  TARGET_PACKAGE,
  DEFAULT_INPUT_TEXT,
  INPUT_ECHO_EMPTY,
  ACTIONS,
  PlanError,
  analyzeTurn,
  parseEnvelope,
  inspectTools,
  findSentinel,
  planTurn,
  completionFor,
  ssePayloads,
  startServer,
  stopFromPidFile,
  parseArgs,
  verifyScenario,
};
