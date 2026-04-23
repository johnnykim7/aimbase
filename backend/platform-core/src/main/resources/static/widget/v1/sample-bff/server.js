/**
 * Aimbase Chat Widget — 소비앱 BFF 샘플 (Node.js, 외부 의존 0)
 *
 * 공개 다운로드 URL: https://aimbase.company.com/widget/v1/sample-bff/server.js
 *
 * 이 파일을 소비앱 프로젝트로 복사해서 사용하세요. 핵심은 `issueWidgetToken()` 과
 * `/my-bff/aimbase-token` 핸들러 두 곳입니다. 그 외는 자유롭게 수정하세요.
 *
 * 실행:
 *   AIMBASE_URL=https://aimbase.company.com \
 *   AIMBASE_API_KEY=plat-xxx \
 *   AIMBASE_TENANT_ID=your_tenant \
 *   ALLOWED_ORIGIN=https://your-app.com \
 *   PORT=3000 \
 *   node server.js
 */

const http = require("http");

const AIMBASE_URL = process.env.AIMBASE_URL;
const AIMBASE_API_KEY = process.env.AIMBASE_API_KEY;
const AIMBASE_TENANT_ID = process.env.AIMBASE_TENANT_ID;
const ALLOWED_ORIGIN = process.env.ALLOWED_ORIGIN;
const PORT = Number(process.env.PORT ?? 3000);

if (!AIMBASE_URL || !AIMBASE_API_KEY || !AIMBASE_TENANT_ID || !ALLOWED_ORIGIN) {
  console.error(
    "[BFF] Required env: AIMBASE_URL, AIMBASE_API_KEY, AIMBASE_TENANT_ID, ALLOWED_ORIGIN",
  );
  process.exit(1);
}

/**
 * Aimbase 에서 단기 위젯 JWT 를 대리 발급.
 * 브라우저는 이 BFF 를 거쳐서만 토큰을 얻는다 — API Key 는 브라우저에 절대 전달 금지.
 */
async function issueWidgetToken() {
  const res = await fetch(`${AIMBASE_URL}/api/v1/sessions/issue-widget-token`, {
    method: "POST",
    headers: {
      "X-API-Key": AIMBASE_API_KEY,
      "X-Tenant-Id": AIMBASE_TENANT_ID,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      origin: ALLOWED_ORIGIN,
      scopes: ["chat:stream", "workflow:subscribe", "rag:read"],
      ttl_seconds: 1800,
    }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`Aimbase ${res.status}: ${text.slice(0, 200)}`);
  const json = JSON.parse(text);
  return json.data ?? json; // { token, expires_at, refresh_after, scopes }
}

const server = http.createServer(async (req, res) => {
  if (req.method === "POST" && req.url === "/my-bff/aimbase-token") {
    try {
      // TODO: 여기서 소비앱 자체 사용자 세션/로그인 검증 수행.
      // 인증되지 않은 요청은 401 반환하고 토큰을 내주지 않는다.

      const token = await issueWidgetToken();
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(token));
    } catch (e) {
      res.writeHead(502, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: e.message }));
    }
    return;
  }

  res.writeHead(404, { "Content-Type": "text/plain" });
  res.end("not found");
});

server.listen(PORT, () => {
  console.log(`[BFF] listening on :${PORT} (Aimbase=${AIMBASE_URL}, tenant=${AIMBASE_TENANT_ID})`);
});
