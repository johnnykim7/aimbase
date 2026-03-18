import http from 'node:http';

const PORT = 4000;
const COLORS = {
  log: '\x1b[37m',    // white
  warn: '\x1b[33m',   // yellow
  error: '\x1b[31m',  // red
  info: '\x1b[36m',   // cyan
  debug: '\x1b[90m',  // gray
  reset: '\x1b[0m',
};

const server = http.createServer((req, res) => {
  // CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === 'POST' && req.url === '/log') {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      try {
        const { level = 'log', args = [], timestamp } = JSON.parse(body);
        const color = COLORS[level] || COLORS.log;
        const time = timestamp ? new Date(timestamp).toLocaleTimeString() : new Date().toLocaleTimeString();
        const msg = args.map((a) => (typeof a === 'object' ? JSON.stringify(a, null, 2) : a)).join(' ');
        console.log(`${COLORS.debug}[${time}]${COLORS.reset} ${color}[${level.toUpperCase()}]${COLORS.reset} ${msg}`);
      } catch {
        console.log(`[LOG-SERVER] parse error: ${body}`);
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
    });
    return;
  }

  // Health check
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200);
    res.end('ok');
    return;
  }

  res.writeHead(404);
  res.end();
});

server.listen(PORT, () => {
  console.log(`\x1b[32m[LOG-SERVER]\x1b[0m Listening on http://localhost:${PORT}/log`);
  console.log(`\x1b[32m[LOG-SERVER]\x1b[0m Waiting for frontend console.log messages...`);
});
