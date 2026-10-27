// AndChamber phone-control tool for OpenCode.
//
// OpenCode loads this file with a bare dynamic import from its own directory, so it must not import
// anything at module scope: nothing resolves from that directory (no node_modules). Node/Bun
// globals only.
//
// The endpoint file lives outside this directory and is rewritten by the App on every launch (the
// port and token rotate), so it is re-read on every call.
//
// This tool is not an authority. The person enables phone control and grants one target/action set
// in the AndChamber UI; nothing here can enable, pair, grant, resume or acknowledge anything.

const ENDPOINT_ENV = "ANDCHAMBER_PHONE_TOOL_FILE";
const MAX_ENDPOINT_BYTES = 8192;
const MAX_TOKEN_LENGTH = 512;
const CANCEL_TIMEOUT_MS = 2000;

// Plugin identity for the pinned v2 registration APIs. The tool name the host lists, and the RPC
// port the native side probes (POST /api/rpc/andchamber-phone/ready).
const TOOL_NAME = "andchamber_phone";
const RPC_ID = "andchamber-phone";
const RPC_READY = "ready";

// Raw JSON Schemas (no package import): the RPC takes no input and answers with exactly this shape.
const EMPTY_INPUT_SCHEMA = { type: "object", properties: {}, additionalProperties: false };
const READY_OUTPUT_SCHEMA = {
  type: "object",
  properties: { ok: { const: true }, id: { const: "andchamber_phone" } },
  required: ["ok", "id"],
  additionalProperties: false,
};

const UNAVAILABLE =
  "AndChamber phone control is not available. Open the AndChamber chat screen on the phone and try again.";

/** A model finish is only a report; the business result remains pending verification. */
const FINISH_REPORTED = "报告结束，待核对";

const DESCRIPTION = [
  "Control one app on the user's Android phone through the AndChamber phone-control channel.",
  'Start with "status": it reports whether phone control is enabled, its phase, epoch, capabilities, runtime readiness, whether a native task exists, and whether the person configured the standing scope. It needs no grant.',
  'Use "apps" to read only the person-preauthorized app list (label and package). It never exposes the installed-app catalogue and needs no grant.',
  'Use "request" with a summary (max 200) and one packageName from "apps" to start a task directly inside the person-configured standing scope. The native side checks the scope, creates a new task epoch and launches that allowed app; the reply is state RUNNING/READY (or NEEDS_USER) with the epoch. There is no PENDING_CONFIRMATION step in this mode, and the tool never creates authority itself.',
  'Use "open_app" with one packageName from "apps" to open that preauthorized app. Other packages are refused; it is not a general opener or command surface.',
  'For an already confirmed native task, "progress", "need_user" and "finish" need the current epoch from "status". They are the agent\'s own report, marked unverified: never native facts and never proof of business success. No event means no progress; do not invent percentages.',
  '"need_user" asks the person to take over. It only becomes waiting after native control confirms that new operations and late reads are blocked; until then it may fail with STOP_UNCONFIRMED. Native confirmation still does not roll back an action that was already sent. A need_user, take-over, unknown outcome, lock, connection loss or runtime change suspends the standing scope until the person resumes it on the phone.',
  '"finish" only reports that the agent is done. It answers with 报告结束，待核对 and never means success; the person or native verification must check the result. It also requires native stop/revoke confirmation first.',
  'A progress/need_user/finish call is refused with NO_TASK when no native task exists, STALE_TASK or TASK_NOT_ACTIVE when the task changed or ended, EPOCH_CHANGED for a stale epoch, and SESSION_BUSY for another chat session. Do not retry a stale epoch or another session\'s task in a loop.',
  'Every control action except "status", "apps", "request" and "open_app" requires a live task epoch from "request"/"status" and a native grant/lease. This tool can never enable, pair, grant, resume, acknowledge or open overlay permission; never claim that it can.',
  'Take "snapshot" first. It returns observationId plus accessibility nodes with refs, roles, labels and bounds in display pixels. Use that observationId for "capture", "tap", "swipe", "input" and "back"; a newer snapshot or any change to the window/geometry makes the old observationId stale, so take a new snapshot instead of guessing.',
  'One action per call. "tap" uses either ref or x/y; "swipe" uses x/y and x2/y2 in display pixels plus optional durationMs; "input" types text into the focused field, or focuses ref first; "back" presses the system back.',
  'Non-ASCII input may be refused with UNICODE_UNSUPPORTED. Do not silently replace it with an approximation.',
  'Only SENT means the driver injected the action; it does not prove the app did what was intended. An OUTCOME_UNKNOWN result does not prove failure. Never repeat the action to find out: take a fresh snapshot if possible, then ask the person to check the phone. Ask the person to resume control on the phone if needed.',
  'Only one chat session owns a grant epoch. After another session performs a real action, this session is refused with SESSION_BUSY; do not retry in a loop. Ask the person to grant phone control again if this chat should own it. "status" and task events never claim the epoch by themselves.',
  'A session label is routing only, not authentication; never claim that this tool proves which chat is speaking. Immediately after a native grant, actions may be refused with ROUTE_NOT_PUBLISHED until the phone publishes the confirmed route; wait for the person, do not retry in a loop.',
  '"snapshot", "capture" and "tap" may also fail with NOT_GRANTED, LEASE_EXPIRED, EPOCH_CHANGED, PAUSED, GRANT_ACTIVE, ROUTE_NOT_PUBLISHED, ACTION_NOT_GRANTED, RUNTIME_NOT_READY, DISABLED, NOT_CONNECTED or BUSY. These are fixed refusals, not retry hints.',
  'HIERARCHY_NOT_IDLE (also possible on "snapshot") means no stable hierarchy/page was observed. It does not mean the connection is down; do not retry in a loop, wait for the page to settle and ask the person for a new explicit request. READ_TIMEOUT means reads timed out and the current control connection is no longer used; the person must check the phone and reconnect explicitly. These codes never grant automatic recovery.',
].join(" ");

// Reading the endpoint file:
// OpenCode imports the plugin natively (`await import(entry)` in
// packages/opencode/src/plugin/loader.ts), with no bundling step, so a dynamic
// import of a Node builtin resolves the same way OpenCode's own code does.
// Bun also exposes `Bun.file`, which avoids module resolution entirely, so use
// it when it exists and fall back to node:fs/promises (Node, which also runs
// this file in scripts/tests/phone-tool-plugin.test.cjs).
const readTextFile = async (file) => {
  if (globalThis.Bun && typeof globalThis.Bun.file === "function") {
    return await globalThis.Bun.file(file).text();
  }
  const { readFile } = await import("node:fs/promises");
  return await readFile(file, "utf8");
};

const isLoopbackEndpoint = (value, path) => {
  if (typeof value !== "string") return false;
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    return false;
  }
  return (
    parsed.protocol === "http:" &&
    parsed.hostname === "127.0.0.1" &&
    parsed.username === "" &&
    parsed.password === "" &&
    parsed.pathname === path &&
    parsed.search === "" &&
    parsed.hash === ""
  );
};

const validToken = (value) =>
  typeof value === "string" &&
  value.length > 0 &&
  value.length <= MAX_TOKEN_LENGTH &&
  !/[\s\u0000-\u001f\u007f]/.test(value);

// Resolve the App-written endpoint descriptor for this call. Any problem
// (absent env, unreadable file, bad JSON, unexpected shape) is reported as the
// single user-facing unavailable message, never as a thrown error or a path.
const readEndpoint = async () => {
  const file = process.env[ENDPOINT_ENV];
  if (typeof file !== "string" || file.length === 0) return null;
  try {
    const raw = await readTextFile(file);
    if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_ENDPOINT_BYTES) return null;
    const endpoint = JSON.parse(raw);
    if (!endpoint || typeof endpoint !== "object") return null;
    if (endpoint.version !== 1) return null;
    if (!validToken(endpoint.token)) return null;
    if (!isLoopbackEndpoint(endpoint.url, "/v1/tool")) return null;
    if (!isLoopbackEndpoint(endpoint.cancelUrl, "/v1/cancel")) return null;
    return {
      url: endpoint.url,
      cancelUrl: endpoint.cancelUrl,
      token: endpoint.token,
    };
  } catch {
    return null;
  }
};

const textResult = (text) => ({ content: [{ type: "text", text }] });

const requestHeaders = (token) => ({
  authorization: `Bearer ${token}`,
  "content-type": "application/json",
});

// Best effort, and deliberately short: the caller already gave up, so nothing
// here may delay the cancelled result beyond CANCEL_TIMEOUT_MS or surface as an
// error. The request is awaited so it is not lost if the process stops next.
// The body names only this (session, call): no other session can cancel it.
const sendCancel = async (endpoint, sessionID, callID) => {
  try {
    await fetch(endpoint.cancelUrl, {
      method: "POST",
      headers: requestHeaders(endpoint.token),
      body: JSON.stringify({ version: 1, sessionID, callID }),
      signal: AbortSignal.timeout(CANCEL_TIMEOUT_MS),
      redirect: "error",
    });
  } catch {
    // Ignore: the cancellation is advisory.
  }
};

const isAbort = (error, context) =>
  context?.signal?.aborted === true ||
  (typeof error === "object" && error !== null && error.name === "AbortError");

const IMAGE_MIME = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/webp": "webp",
};

// The envelope travels as text because the tool declares no output schema.
// Strip only the base64 payload from the text copy: it is carried as a file
// part instead, and duplicating it would blow up the transcript.
const toContent = (payload) => {
  const envelope = { ...payload };
  if (payload.data && typeof payload.data === "object" && !Array.isArray(payload.data)) {
    const { base64, ...rest } = payload.data;
    envelope.data = rest;
  }
  const content = [{ type: "text", text: JSON.stringify(envelope) }];
  const data = payload.data;
  const extension = data && typeof data === "object" ? IMAGE_MIME[data.mime] : undefined;
  if (
    payload.ok === true &&
    data &&
    typeof data === "object" &&
    typeof data.base64 === "string" &&
    data.base64.length > 0 &&
    extension
  ) {
    content.push({
      type: "file",
      uri: data.base64,
      mime: data.mime,
      name: `andchamber-phone-capture.${extension}`,
    });
  }
  return { content };
};

export default {
  id: "andchamber-phone",
  setup: async (ctx) => {
    // 1) Register the tool exactly as before. `transform` resolves once the callback has run.
    await ctx.tool.transform((tools) => {
      tools.add({
        name: TOOL_NAME,
        description: DESCRIPTION,
        // Direct tool, never behind Code Mode: the model must see it in the
        // normal tool list.
        options: { codemode: false },
        input: {
          type: "object",
          properties: {
            action: {
              type: "string",
              enum: ["status", "request", "apps", "open_app", "progress", "need_user", "finish", "snapshot", "capture", "tap", "swipe", "input", "back"],
              description: "One phone action per call.",
            },
            epoch: {
              type: "integer",
              minimum: 0,
              description:
                'Current task epoch from "request" or "status". Required for "progress", "need_user" and "finish".',
            },
            phase: {
              type: "string",
              maxLength: 120,
              description:
                'Short, non-sensitive progress phase ("progress" only). Always an unverified model self-report.',
            },
            message: {
              type: "string",
              maxLength: 120,
              description:
                'Short, non-sensitive message for "progress" or "need_user". Use either message or phase/reason, not both.',
            },
            reason: {
              type: "string",
              maxLength: 120,
              description:
                'Short, non-sensitive reason for "need_user". It becomes waiting only after native stop confirmation.',
            },
            summary: {
              type: "string",
              maxLength: 200,
              description:
                'Task summary for "request" (max 200). For "finish" it is a bounded (max 120) end report, never success.',
            },
            packageName: {
              type: "string",
              maxLength: 200,
              description:
                'Exactly one packageName from "apps". Required for "request" and "open_app"; never an arbitrary package.',
            },
            observationId: {
              type: "string",
              description:
                'observationId from the latest "snapshot". Required for "capture", "tap", "swipe", "input" and "back".',
            },
            ref: {
              type: "string",
              description:
                'Node ref from the latest snapshot. For "tap" it selects the element; for "input" it focuses that editable field before typing.',
            },
            x: {
              type: "integer",
              minimum: 0,
              maximum: 10000,
              description: 'Display-pixel x for "tap" (with y) or the swipe start.',
            },
            y: {
              type: "integer",
              minimum: 0,
              maximum: 10000,
              description: 'Display-pixel y for "tap" (with x) or the swipe start.',
            },
            x2: {
              type: "integer",
              minimum: 0,
              maximum: 10000,
              description: 'Display-pixel x where a "swipe" ends.',
            },
            y2: {
              type: "integer",
              minimum: 0,
              maximum: 10000,
              description: 'Display-pixel y where a "swipe" ends.',
            },
            durationMs: {
              type: "integer",
              minimum: 50,
              maximum: 5000,
              description: 'Swipe duration in milliseconds ("swipe" only); defaults to 300.',
            },
            text: {
              type: "string",
              description: 'Text to type ("input" only). Non-ASCII may be refused.',
            },
          },
          required: ["action"],
          additionalProperties: false,
          // Conditional requirement keeps the flat tool schema compatible: only the three task
          // event actions must carry the epoch read from status.
          allOf: [
            {
              if: {
                properties: {
                  action: { enum: ["progress", "need_user", "finish"] },
                },
                required: ["action"],
              },
              then: { required: ["epoch"] },
            },
            {
              if: {
                properties: { action: { enum: ["request"] } },
                required: ["action"],
              },
              then: { required: ["summary", "packageName"] },
            },
            {
              if: {
                properties: { action: { enum: ["open_app"] } },
                required: ["action"],
              },
              then: { required: ["packageName"] },
            },
          ],
        },
        async execute(input, context) {
          const args = input && typeof input === "object" ? input : {};
          const sessionID = context?.sessionID;
          const callID = context?.id;

          const endpoint = await readEndpoint();
          if (!endpoint) return textResult(UNAVAILABLE);

          let response;
          try {
            response = await fetch(endpoint.url, {
              method: "POST",
              headers: requestHeaders(endpoint.token),
              body: JSON.stringify({
                version: 1,
                sessionID,
                callID,
                messageID: context?.messageID,
                agent: context?.agent,
                input: args,
              }),
              signal: context?.signal,
              // The token is scoped to this loopback listener; never hand it
              // to a redirect target.
              redirect: "error",
            });
          } catch (error) {
            if (isAbort(error, context)) {
              await sendCancel(endpoint, sessionID, callID);
              return textResult("Cancelled.");
            }
            return textResult("AndChamber phone request failed (network error).");
          }

          if (!response.ok) {
            // Status only: the body is not trusted and may echo request data.
            return textResult(`AndChamber phone request failed (HTTP ${response.status}).`);
          }

          let payload;
          try {
            payload = await response.json();
          } catch (error) {
            if (isAbort(error, context)) {
              await sendCancel(endpoint, sessionID, callID);
              return textResult("Cancelled.");
            }
            return textResult("AndChamber phone returned an invalid response.");
          }

          if (
            !payload ||
            typeof payload !== "object" ||
            payload.version !== 1 ||
            typeof payload.ok !== "boolean" ||
            typeof payload.action !== "string" ||
            !Number.isInteger(payload.epoch)
          ) {
            return textResult("AndChamber phone returned an invalid response.");
          }

          // A successful finish is only the agent's end report; never render it as success.
          if (payload.ok === true && payload.action === "phone.finish") {
            return textResult(FINISH_REPORTED);
          }

          return toContent(payload);
        },
      });
    });

    // 2) The host is the source of truth. A silently dropped add must never look like a working
    // channel, so the registration is read back and `setup` fails closed without it.
    const tools = await ctx.tool.list();
    if (!Array.isArray(tools) || !tools.some((tool) => tool && tool.id === TOOL_NAME)) {
      throw new Error(`${TOOL_NAME} was not registered`);
    }

    // 3) Read-only readiness port for the native side. It answers with a fixed body and grants
    // nothing; the App probes it as POST /api/rpc/andchamber-phone/ready.
    await ctx.rpc.register(
      {
        id: RPC_ID,
        methods: {
          [RPC_READY]: { input: EMPTY_INPUT_SCHEMA, output: READY_OUTPUT_SCHEMA },
        },
        events: {},
      },
      {
        [RPC_READY]: async () => ({ ok: true, id: TOOL_NAME }),
      },
    );
  },
};
