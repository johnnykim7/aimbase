const http = require('http');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.PORT || '9100', 10);
const AIMBASE_URL = process.env.AIMBASE_URL || '';
const AGENT_ACCOUNT_ID = process.env.AGENT_ACCOUNT_ID || '';

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    return handleHealth(req, res);
  }
  if (req.method === 'POST' && req.url === '/execute') {
    return handleExecute(req, res);
  }
  res.writeHead(404);
  res.end(JSON.stringify({ error: 'Not found' }));
});

/**
 * 헬스체크 — Claude CLI 버전 + 인증 상태 반환.
 * 인증은 환경변수(CLAUDE_CODE_OAUTH_TOKEN 또는 ANTHROPIC_API_KEY)로 판단.
 */
function handleHealth(_req, res) {
  const authenticated = !!(process.env.CLAUDE_CODE_OAUTH_TOKEN || process.env.ANTHROPIC_API_KEY);

  const child = spawn('claude', ['--version'], { timeout: 5000 });
  let version = '';
  child.stdout.on('data', d => version += d);
  child.on('close', () => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: authenticated ? 'ok' : 'auth_required',
      authenticated,
      authType: process.env.CLAUDE_CODE_OAUTH_TOKEN ? 'oauth_token' : process.env.ANTHROPIC_API_KEY ? 'api_key' : 'none',
      version: version.trim(),
      pid: process.pid,
      message: authenticated ? null : '인증 토큰이 설정되지 않았습니다. CLAUDE_CODE_OAUTH_TOKEN 또는 ANTHROPIC_API_KEY 환경변수를 설정하세요.'
    }));
  });
  child.on('error', () => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'error',
      authenticated: false,
      version: 'unknown',
      pid: process.pid,
      message: 'Claude CLI를 찾을 수 없습니다.'
    }));
  });
}

function handleExecute(req, res) {
  let body = '';
  req.on('data', chunk => body += chunk);
  req.on('end', () => {
    let params;
    try {
      params = JSON.parse(body);
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: 'Invalid JSON' }));
    }

    const { command, env, workingDirectory, timeoutSeconds, inputFile } = params;
    if (!command || !Array.isArray(command) || command.length === 0) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: 'command array is required' }));
    }

    const [cmd, ...args] = command;
    const spawnEnv = { ...process.env, ...(env || {}) };
    // 중첩 세션 방지
    delete spawnEnv.CLAUDECODE;

    const opts = {
      env: spawnEnv,
      cwd: workingDirectory || '/data/workspace',
      stdio: ['pipe', 'pipe', 'pipe'],
    };

    const timeoutMs = (timeoutSeconds || 600) * 1000;
    const child = spawn(cmd, args, opts);

    // stdin 처리
    if (inputFile) {
      const fs = require('fs');
      try {
        const stream = fs.createReadStream(inputFile);
        stream.pipe(child.stdin);
      } catch {
        child.stdin.end();
      }
    } else {
      child.stdin.end();
    }

    let stdout = '';
    let stderr = '';
    child.stdout.on('data', d => stdout += d);
    child.stderr.on('data', d => stderr += d);

    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      stderr += '\n[sidecar] Process killed: timeout exceeded';
    }, timeoutMs);

    child.on('close', exitCode => {
      clearTimeout(timer);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ exitCode: exitCode ?? -1, stdout, stderr }));
    });

    child.on('error', err => {
      clearTimeout(timer);
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ exitCode: -1, stdout: '', stderr: err.message }));
    });
  });
}

/**
 * 기동 시 Aimbase에서 인증 토큰을 가져와 환경변수로 세팅.
 * AIMBASE_URL + AGENT_ACCOUNT_ID가 설정되어 있고, 토큰 환경변수가 없을 때만 동작.
 */
function fetchTokenFromAimbase(callback) {
  if (!AIMBASE_URL || !AGENT_ACCOUNT_ID) {
    return callback();
  }
  if (process.env.CLAUDE_CODE_OAUTH_TOKEN || process.env.ANTHROPIC_API_KEY) {
    console.log('[auth] 토큰 환경변수 이미 설정됨 — Aimbase 조회 건너뜀');
    return callback();
  }

  console.log(`[auth] Aimbase에서 토큰 조회 중... (${AIMBASE_URL})`);
  const url = `${AIMBASE_URL}/api/v1/platform/agent-accounts/${AGENT_ACCOUNT_ID}/token`;

  http.get(url, { timeout: 10000 }, (res) => {
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => {
      if (res.statusCode !== 200) {
        console.error(`[auth] 토큰 조회 실패 (${res.statusCode}) — UI에서 토큰을 등록하세요.`);
        return callback();
      }
      try {
        const { auth_type, auth_token } = JSON.parse(data);
        if (auth_type === 'oauth_token') {
          process.env.CLAUDE_CODE_OAUTH_TOKEN = auth_token;
          console.log('[auth] OAuth Token 세팅 완료 (구독)');
        } else if (auth_type === 'api_key') {
          process.env.ANTHROPIC_API_KEY = auth_token;
          console.log('[auth] API Key 세팅 완료 (종량제)');
        }
      } catch (err) {
        console.error('[auth] 토큰 파싱 실패:', err.message);
      }
      callback();
    });
  }).on('error', (err) => {
    console.error(`[auth] Aimbase 연결 실패: ${err.message}`);
    callback();
  });
}

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Claude sidecar listening on port ${PORT}`);

  fetchTokenFromAimbase(() => {
    const authType = process.env.CLAUDE_CODE_OAUTH_TOKEN ? 'OAuth Token (구독)' :
                     process.env.ANTHROPIC_API_KEY ? 'API Key (종량제)' : '미설정';
    console.log(`[auth] 인증 방식: ${authType}`);
  });
});
