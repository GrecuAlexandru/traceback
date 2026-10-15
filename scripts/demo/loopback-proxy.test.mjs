import net from 'node:net';
import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { proxy } from './loopback-proxy.mjs';
test('forwarder preserves HTTP Host and payload bytes on the loopback hop', async () => {
  const backend = net.createServer(socket => socket.once('data', data => socket.end(data)));
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const forwarder = proxy({ host: '127.0.0.1', port: 0, targetPort: backend.address().port });
  await once(forwarder, 'listening');
  try {
    const client = net.connect(forwarder.address().port, '127.0.0.1');
    await once(client, 'connect');
    const bytes = Buffer.from('POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:18081\r\nContent-Length: 2\r\n\r\n{}');
    const chunks = []; client.on('data', b => chunks.push(b));
    client.write(bytes); await once(client, 'end');
    assert.deepEqual(Buffer.concat(chunks), bytes);
    assert.equal(forwarder.maxConnections, 64);
  } finally { forwarder.close(); backend.close(); }
});
