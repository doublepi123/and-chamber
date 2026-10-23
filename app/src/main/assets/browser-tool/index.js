// AndChamber in-app browser tool for OpenCode.
//
// OpenCode loads this file with a bare dynamic import from its own directory,
// so it must not import anything at module scope: nothing resolves from that
// directory (no node_modules). Node/Bun globals only.
//
// The endpoint file lives outside this directory and is rewritten by the App on
// every launch (the port and token rotate), so it is re-read on every call.

const ENDPOINT_ENV = "ANDCHAMBER_BROWSER_TOOL_FILE";
const MAX_ENDPOINT_BYTES = 8192;
const MAX_TOKEN_LENGTH = 512;
const CANCEL_TIMEOUT_MS = 2000;
// "open" may wait this long for the page to become ready and for the presenter's default agent
// control to be granted for it. It is a transport field, not part of the tool's input schema. The
// wait is for automatic readiness, never for a person's per-action tap.
const OPEN_ALLOW_WAIT_MS = 60000;

const UNAVAILABLE =
  "AndChamber browser is not available. Open the AndChamber chat screen on the phone and try again.";

const DESCRIPTION = [
  "Drive the in-app browser window on the user's Android phone.",
  'An explicit foreground "open" defaults to agent control once that exact page is ready: it still passes the presenter\'s existing grant checks, and "open" reports drivable:true.',
  '"open" waits about a minute for that automatic grant; if it reports drivable:false, ask the person to hand control back with Resume agent in the window. Opening again does not override an explicit takeover.',
  "Control ends only when the person takes over explicitly, hides or closes the window, the app leaves the foreground, or the chat owner changes; scrolling the page, dragging the window, resizing it or using full screen does not end control. A foreground agent action may wake a hidden window and ask for control again, but an explicit takeover is not taken back by ordinary agent actions. Restoring the window or opening it manually never grants by itself; the person can hand control back explicitly.",
  "Take one action per call.",
  'Choose "viewport":"mobile" or "desktop" yourself to suit the site and task; do not ask the person merely to choose the layout. Desktop uses a fixed 1920x1080 CSS viewport by default. Supply both "width" and "height" to choose another desktop resolution for "open" or "resize". Page layout is independent of the small-window/full-screen display; take a fresh snapshot after changing the page viewport.',
  'Take "snapshot" first and use refs from the latest snapshot; navigation or DOM changes invalidate old refs, so snapshot again instead of guessing.',
  'For a search, "type" with "submit":true enters the value and dispatches Enter once. Success requires confirmed same-tab GET navigation; POST forms, redirects and sensitive fields are not supported. An unknown outcome must not be retried automatically.',
  'An OUTCOME_UNKNOWN result does not prove that the action failed. Never repeat a click, typing or submission to find out. If the same document remains verifiable, use snapshot or capture to observe it; observation does not confirm the previous action completed. Ask the person to check the page before Resume agent. If the app instead shows Reopen, the document must be reopened explicitly; do not replay the previous action.',
  '"click", "type", "scroll", "capture" and "resize" may be refused with AUTHORIZATION_REQUIRED (agent control is absent, was taken over or revoked, or the window is not in front), REF_EXPIRED (take a new snapshot) or BROWSER_PAGE_UNAVAILABLE (the AndChamber chat screen is not open).',
  "When that happens, ask the person to hand control back with Resume agent in the window; do not expect a tap before every action, and do not retry in a loop.",
].join(" ");

// Reading the endpoint file:
// OpenCode imports the plugin natively (`await import(entry)` in
// packages/opencode/src/plugin/loader.ts), with no bundling step, so a dynamic
// import of a Node builtin resolves the same way OpenCode's own code does
// (`await import("fs/promises")` in packages/opencode/src/worktree/index.ts).
// Bun also exposes `Bun.file`, which avoids module resolution entirely, so use
// it when it exists and fall back to node:fs/promises (Node, which also runs
// this file in scripts/tests/browser-tool-plugin.test.cjs).
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
  if (
    payload.ok === true &&
    data &&
    typeof data === "object" &&
    typeof data.base64 === "string" &&
    data.base64.length > 0 &&
    data.mime === "image/png"
  ) {
    content.push({
      type: "file",
      uri: data.base64,
      mime: "image/png",
      name: "andchamber-capture.png",
    });
  }
  return { content };
};

export default {
  id: "andchamber-browser",
  setup: async (ctx) => {
    await ctx.tool.transform((tools) => {
      tools.add({
        name: "andchamber_web",
        description: DESCRIPTION,
        // Direct tool, never behind Code Mode: the model must see it in the
        // normal tool list.
        options: { codemode: false },
        input: {
          type: "object",
          properties: {
            action: {
              type: "string",
              enum: ["open", "snapshot", "click", "type", "scroll", "capture", "resize"],
              description: "One action to perform in the in-app browser window.",
            },
            url: {
              type: "string",
              description: 'Public HTTPS URL to open in the window ("open" only).',
            },
            tabId: {
              type: "string",
              description: "Tab id from the latest snapshot, when several tabs are open.",
            },
            viewport: {
              type: "string",
              enum: ["mobile", "desktop", "fill"],
              description: 'Page layout for "open" or "resize": choose mobile or desktop for the task. Desktop defaults to 1920x1080; fill keeps the current layout. This is not the floating window size.',
            },
            width: {
              type: "integer",
              minimum: 640,
              maximum: 3840,
              description: 'Desktop CSS viewport width. Supply together with height, and only with viewport="desktop" for "open" or "resize".',
            },
            height: {
              type: "integer",
              minimum: 360,
              maximum: 2160,
              description: 'Desktop CSS viewport height. Supply together with width, and only with viewport="desktop" for "open" or "resize".',
            },
            selector: {
              type: "string",
              description: 'Ref selector from the latest snapshot ("click" or "type"), not an arbitrary CSS selector.',
            },
            text: {
              type: "string",
              description: 'Text to enter ("type").',
            },
            value: {
              type: "string",
              description: 'Value for selector-based actions ("type").',
            },
            submit: {
              type: "boolean",
              description: 'After typing into a non-sensitive single-line input, press Enter once and wait for confirmed same-tab GET navigation. POST forms and redirects are unsupported ("type" only).',
            },
            direction: {
              type: "string",
              enum: ["up", "down"],
              description: 'Scroll direction ("scroll").',
            },
          },
          required: ["action"],
          additionalProperties: false,
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
                // Only "open" waits for the page's automatic default control; every other action
                // keeps its immediate answer.
                ...(args.action === "open" ? { allowWaitMs: OPEN_ALLOW_WAIT_MS } : {}),
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
            return textResult("AndChamber browser request failed (network error).");
          }

          if (!response.ok) {
            // Status only: the body is not trusted and may echo request data.
            return textResult(`AndChamber browser request failed (HTTP ${response.status}).`);
          }

          let payload;
          try {
            payload = await response.json();
          } catch (error) {
            if (isAbort(error, context)) {
              await sendCancel(endpoint, sessionID, callID);
              return textResult("Cancelled.");
            }
            return textResult("AndChamber browser returned an invalid response.");
          }

          if (
            !payload ||
            typeof payload !== "object" ||
            payload.version !== 1 ||
            typeof payload.ok !== "boolean" ||
            typeof payload.action !== "string"
          ) {
            return textResult("AndChamber browser returned an invalid response.");
          }

          return toContent(payload);
        },
      });
    });
  },
};
