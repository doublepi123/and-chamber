# app/src/main/java/io/andchamber/app/browser/tool/

## Responsibility

Local plugin transport for browser actions. It hosts a small HTTP/1.x server bound exclusively to IPv4 loopback on an ephemeral port, authenticates requests using a per-launch random bearer token, parses the plugin protocol, and delegates to a `ProviderExecutor`. It also writes/removes the private endpoint descriptor consumed by the runtime plugin.

Files:
- `BrowserToolServer.kt`: transport lifecycle, bounded socket admission, HTTP validation, token check, tool/cancel dispatch, deadlines and per-launch request/cancel tracking. `BrowserToolTransport` is the lifecycle/connection interface.
- `BrowserToolProtocol.kt`: strict JSON request parsing, parameter conversion, provider request construction and fixed-message response envelopes. Page-derived text is not included in errors.
- `BrowserToolEndpoint.kt`: endpoint document, token generation, atomic private file write and safe deletion.
- `TrustedPath.kt`: lexical containment and no-symlink path helpers for endpoint storage.

## Transport flow and controls

`start()` binds `127.0.0.1` only and returns the ephemeral port. Accepted peers must also be loopback. At most eight concurrent socket reads are admitted; header/body sizes and socket read time are bounded. Only POST to `/v1/tool` or `/v1/cancel` is accepted, with exact Host/port, no Origin, a bounded Content-Length body (transfer-encoding rejected), and a bearer token compared in constant time. The token is never logged or echoed. When `setConnection(null)` indicates host ineligibility, all active jobs are cancelled; stopping clears tracker state and closes sockets/server.

Tool input must have the supported protocol version, bounded session/call identifiers, a supported action and bounded scalar parameters. Call IDs are preserved only when safe request IDs, otherwise SHA-256-derived. Each launch tracks claims to refuse duplicates and cancellation tombstones so cancel-before-call and cancel/registration races cannot re-admit work. Both deduplication and cancellation are keyed by session plus request ID, so equal call IDs in different sessions stay independent; deadlines are enforced both through coroutine timeout and the provider deadline. The current connection ID is required before execution. Error envelopes use fixed text to avoid reflecting webpage content.

The endpoint file is under private runtime home `.local/state/andchamber/browser-tool.json`, with a 32-byte random lowercase-hex token and loopback tool/cancel URLs. Parent directories are owner-only, the file mode is `0600`, and replacement uses a temporary file plus atomic rename where available. Lexical containment and symlink checks fail closed; deletion unlinks a leaf symlink rather than following it. The file is written after the server listens and removed by its launch owner during teardown.

## Integration

- `runtime/process/LocalBrowserProviderBinding` owns start, endpoint publication, connection eligibility and teardown per runtime launch.
- The runtime plugin reads the descriptor and POSTs to the loopback endpoint; the transport is not exposed on a LAN interface and has no browser/CDP endpoint.
- `ProviderExecutor` (implemented by the presenter-connected page adapter) is the only page execution boundary. Transport authentication and connection eligibility do not replace page grant checks.
