// Minimal RFC 6455 client over node:http so the auth cookie can be sent (built-in WebSocket cannot set headers).
import http from 'node:http';
import crypto from 'node:crypto';

export function openWs({ host, port, path, cookie }) {
  const key = crypto.randomBytes(16).toString('base64');
  const req = http.request({
    host, port, path, method: 'GET',
    headers: { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': key, Cookie: cookie, Origin: `http://${host}:${port}` },
  });
  const listeners = { message: [], close: [], error: [] };
  let sock = null; let buf = Buffer.alloc(0);
  const ready = new Promise((resolve, reject) => {
    req.on('upgrade', (res, socket, head) => {
      if (res.headers['sec-websocket-accept'] !== crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')) {
        return reject(new Error('bad Sec-WebSocket-Accept'));
      }
      sock = socket; buf = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
      socket.on('data', (d) => { buf = Buffer.concat([buf, d]); drain(); });
      socket.on('close', () => listeners.close.forEach((f) => f({})));
      socket.on('error', (e) => listeners.error.forEach((f) => f(e)));
      resolve({ status: res.statusCode });
    });
    req.on('response', (res) => { res.resume(); reject(new Error(`upgrade refused: HTTP ${res.statusCode}`)); });
    req.on('error', reject);
    req.end();
  });
  function drain() {
    while (buf.length >= 2) {
      const op = buf[0] & 0x0f; let len = buf[1] & 0x7f; let off = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
      if (buf.length < off + len) return;
      const payload = buf.subarray(off, off + len);
      buf = buf.subarray(off + len);
      if (op === 0x1 || op === 0x2) listeners.message.forEach((f) => f({ data: Buffer.from(payload) }));
      else if (op === 0x9) sendFrame(0xA, payload);
      else if (op === 0x8) { listeners.close.forEach((f) => f({})); sock.end(); }
    }
  }
  function sendFrame(op, payload) {
    const mask = crypto.randomBytes(4);
    const header = payload.length < 126 ? Buffer.from([0x80 | op, 0x80 | payload.length])
      : Buffer.from([0x80 | op, 0x80 | 126, (payload.length >> 8) & 255, payload.length & 255]);
    const masked = Buffer.alloc(payload.length);
    for (let i = 0; i < payload.length; i++) masked[i] = payload[i] ^ mask[i % 4];
    sock.write(Buffer.concat([header, mask, masked]));
  }
  return {
    ready,
    onMessage: (f) => listeners.message.push(f),
    onClose: (f) => listeners.close.push(f),
    onError: (f) => listeners.error.push(f),
    sendText: (s) => sendFrame(0x1, Buffer.from(s, 'utf8')),
    sendControl: (obj) => sendFrame(0x2, Buffer.concat([Buffer.from([0x01]), Buffer.from(JSON.stringify(obj), 'utf8')])),
    close: () => { try { sendFrame(0x8, Buffer.alloc(0)); sock.end(); } catch {} },
  };
}
