/**
 * Aimbase Chat Widget — 샘플 소비앱 BFF (Node.js)
 *
 * 이 파일은 소비앱 서버 역할을 한다. 브라우저가 직접 Aimbase API Key 를 만지지 못하도록
 * BFF 가 API Key 를 env 에만 보관하고, 브라우저가 /my-bff/aimbase-token 을 호출하면
 * Aimbase 에 서버간 호출로 단기 JWT(type=widget)를 대리 발급받아 전달한다.
 *
 * 실행:
 *   AIMBASE_URL=http://localhost:8181 \
 *   AIMBASE_API_KEY=plat-... \
 *   AIMBASE_TENANT_ID=tenant_dev \
 *   ALLOWED_ORIGIN=http://localhost:3999 \
 *   node server.js
 */

const http = require("http");
const fs = require("fs");
const path = require("path");

const AIMBASE_URL = process.env.AIMBASE_URL ?? "http://localhost:8181";
const AIMBASE_API_KEY = process.env.AIMBASE_API_KEY;
const AIMBASE_TENANT_ID = process.env.AIMBASE_TENANT_ID;
const ALLOWED_ORIGIN = process.env.ALLOWED_ORIGIN ?? "http://localhost:3999";
const PORT = Number(process.env.PORT ?? 3999);

if (!AIMBASE_API_KEY || !AIMBASE_TENANT_ID) {
  console.error("[BFF] AIMBASE_API_KEY and AIMBASE_TENANT_ID env vars are required");
  process.exit(1);
}

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
  if (!res.ok) {
    throw new Error(`Aimbase token issue failed: ${res.status} ${text}`);
  }
  const json = JSON.parse(text);
  return json.data ?? json;
}

const server = http.createServer(async (req, res) => {
  // 정적 파일 (샘플 consumer 페이지 같이 서빙)
  if (req.method === "GET" && (req.url === "/" || req.url === "/index.html")) {
    const html = fs.readFileSync(path.join(__dirname, "../sample-consumer/index.html"), "utf8");
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(html);
    return;
  }
  if (req.method === "GET" && req.url === "/aimbase-chat.umd.js") {
    try {
      const js = fs.readFileSync(
        path.join(__dirname, "../dist/aimbase-chat.umd.global.js"),
        "utf8",
      );
      res.writeHead(200, { "Content-Type": "application/javascript; charset=utf-8" });
      res.end(js);
    } catch {
      res.writeHead(404);
      res.end("위젯 번들이 아직 빌드되지 않았습니다. `npm run build` 를 먼저 실행하세요.");
    }
    return;
  }

  // 토큰 프록시
  if (req.method === "POST" && req.url === "/my-bff/aimbase-token") {
    try {
      // TODO: 실제 운영에서는 여기서 소비앱 세션/로그인 검증 수행
      const token = await issueWidgetToken();
      res.writeHead(200, {
        "Content-Type": "application/json",
        "Access-Control-Allow-Origin": "*",
      });
      res.end(JSON.stringify(token));
    } catch (e) {
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: e.message }));
    }
    return;
  }

  res.writeHead(404);
  res.end("not found");
});

server.listen(PORT, () => {
  console.log(`[BFF] http://localhost:${PORT} (Aimbase=${AIMBASE_URL}, tenant=${AIMBASE_TENANT_ID})`);
});
