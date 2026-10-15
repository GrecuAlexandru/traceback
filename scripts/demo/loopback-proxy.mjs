// Docker's published 127.0.0.1:18081 -> namespace :8080 -> backend loopback :18081.
// Preserve bytes (including Host), never inspect/log bodies, headers or credentials.
import net from 'node:net';
import { pathToFileURL } from 'node:url';
export function proxy({ host = '0.0.0.0', port = 8080, targetPort = 18081 } = {}) {
  const server = net.createServer(client => {
    const upstream = net.connect({ host: '127.0.0.1', port: targetPort });
    const close = () => { client.destroy(); upstream.destroy(); };
    for (const socket of [client, upstream]) {
      socket.setTimeout(15000, close);
      socket.on('error', close);
      socket.on('close', close);
    }
    client.pipe(upstream); upstream.pipe(client);
  });
  server.maxConnections = 64;
  return server.listen(port, host);
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) proxy();
