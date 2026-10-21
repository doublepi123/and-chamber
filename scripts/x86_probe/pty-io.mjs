// Real PTY I/O with strict evidence: the marker must appear as a STANDALONE output line, not only in the echoed command.
// The command builds markers with shell concatenation so the echoed text never equals the marker:
//   printf '%s\n' X86_UPDATE_''OK   -> prints "X86_UPDATE_OK" (the echo shows the quotes, so it differs)
// Requirements: version lines from git/node/opencode and separate completed marker line.
import fs from 'node:fs';
import { openWs } from './ws-min.mjs';
// Usage: node pty-io.mjs <marker> <outDir> [jarFile] [port]   (port defaults to 3000 forwarded to 127.0.0.1)

const port = Number(process.argv[5] || process.env.OC_PORT || 3000);
const BASE = `http://127.0.0.1:${port}`;
const tag = process.argv[2] || 'X86_UPDATE';
const outDir = process.argv[3] || '.';
const jarPath = process.argv[4] || process.env.OC_JAR;
if (!jarPath) { console.error('need cookie jar path (argv[4] or OC_JAR)'); process.exit(64); }
const cookie = fs.readFileSync(jarPath, 'utf8').split('\n')
  .map((l) => (l.startsWith('#HttpOnly_') ? l.slice('#HttpOnly_'.length) : l))
  .filter((l) => l && !l.startsWith('#')).map((l) => l.split('\t')).filter((f) => f.length >= 7)
  .map((f) => `${f[5]}=${f[6]}`).join('; ');
const headers = { 'content-type': 'application/json', cookie };

// Marker split so the typed command never contains the complete marker line.
const half = tag.slice(0, Math.floor(tag.length / 2));
const rest = tag.slice(half.length);
const command = [
  `printf '%s\\n' ${half}''${rest}`,
  `printf '%s\\n' PTY_MARK_''GIT_BEGIN; git --version; printf '%s\\n' PTY_MARK_''GIT_END`,
  `printf '%s\\n' PTY_MARK_''NODE_BEGIN; node --version; printf '%s\\n' PTY_MARK_''NODE_END`,
  `printf '%s\\n' PTY_MARK_''OC_BEGIN; opencode --version; printf '%s\\n' PTY_MARK_''OC_END`,
  `printf '%s\\n' ${half}''${rest}_''DONE`,
].join('; ');

const create = await fetch(`${BASE}/api/terminal/create`, { method: 'POST', headers, body: JSON.stringify({ cwd: '/root', cols: 120, rows: 40 }) });
const created = await create.json();
if (!created.sessionId) { console.log('CREATE_FAILED', create.status); process.exit(2); }
const sid = created.sessionId;
console.log('CREATE', create.status, 'status=' + created.status, 'mode=' + created.mode);

const ws = openWs({ host: '127.0.0.1', port, path: '/api/terminal/ws', cookie });
console.log('WS_UPGRADE', (await ws.ready).status);
let raw = '';
ws.onMessage(({ data }) => {
  if (data[0] !== 0x01) return;
  const msg = JSON.parse(data.subarray(1).toString('utf8'));
  if (msg.t === 'output' && typeof msg.d === 'string') raw += msg.d;
  if (msg.t === 'error') console.log('WS_ERROR', JSON.stringify(msg).slice(0, 160));
});
ws.sendControl({ t: 'attach', v: 3, s: sid });
await new Promise((r) => setTimeout(r, 1500));
ws.sendControl({ t: 'write', v: 3, s: sid, d: command + '\n' });

// Only standalone lines count. Strip ANSI and CRs; a line must equal the expected text exactly.
const lines = () => raw.replace(/\x1b\[[0-9;?]*[ -\/]*[@-~]/g, '').replace(/\r/g, '').split('\n').map((l) => l.trim());
const need = ['GIT_BEGIN', 'GIT_END', 'NODE_BEGIN', 'NODE_END', 'OC_BEGIN', 'OC_END'].map((k) => `PTY_MARK_${k}`);
const deadline = Date.now() + 90000;
let ok = false;
while (Date.now() < deadline) {
  const L = lines();
  const seen = need.every((m) => L.includes(m));
  const done = L.includes(`${half}${rest}_DONE`.replace(/_DONE$/, '') + '_DONE') || L.includes(`${tag}_DONE`);
  if (seen && L.includes(tag) && done) { ok = true; break; }
  await new Promise((r) => setTimeout(r, 400));
}
const L = lines();
const report = {
  tag, sessionId: sid, standaloneMarkerLine: L.includes(tag),
  gitVersion: L.find((l) => /^git version /.test(l)) || null,
  nodeVersion: L.find((l) => /^v\d+\.\d+\.\d+$/.test(l)) || null,
  opencodeVersion: L.find((l) => /^opencode v\d+\.\d+\.\d+/.test(l)) || null,
  markersComplete: need.every((m) => L.includes(m)),
  doneLine: L.includes(`${tag}_DONE`),
  ok,
};
console.log('REPORT ' + JSON.stringify(report));
fs.writeFileSync(`${outDir}/pty-transcript.txt`, raw.replace(/\x1b\[[0-9;?]*[ -\/]*[@-~]/g, ''));
fs.writeFileSync(`${outDir}/pty-report.json`, JSON.stringify(report, null, 2) + '\n');
ws.close();
const del = await fetch(`${BASE}/api/terminal/${encodeURIComponent(sid)}`, { method: 'DELETE', headers });
console.log('DELETE_OWN_SESSION', del.status);
process.exit(ok ? 0 : 3);
