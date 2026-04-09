#!/usr/bin/env python3
"""FlowGuard 시나리오 추출 벤치마크 — Aimbase vs Claude CLI vs OpenClaude"""
import json, subprocess, time, sys, os

WORKSPACE = "/Users/sykim/Documents/GitHub/bp-platform/aimbase"
AXOPM_PATH = "~/Documents/GitHub/dev-labs/axopm-v5"
OPENCLAUDE = os.path.expanduser("~/Documents/GitHub/openclaude-main/bin/openclaude")
AIMBASE_URL = "http://localhost:8080"
TENANT = "tenant_dev"
CONNECTION = "claude-sonnet-dev"

# Claude CLI는 cwd의 CLAUDE.md를 시스템 프롬프트에 자동 주입하므로,
# Aimbase에도 동일 정보를 제공하기 위해 CLAUDE.md를 프롬프트에 첨부
AXOPM_CLAUDE_MD = ""
_claude_md_path = os.path.expanduser(f"{AXOPM_PATH}/CLAUDE.md")
if os.path.exists(_claude_md_path):
    with open(_claude_md_path) as f:
        AXOPM_CLAUDE_MD = f.read()

# 동일 프롬프트 — 3개 플랫폼 모두 이 프롬프트를 받음
PROMPT = f"""axopm-v5 프로젝트({AXOPM_PATH}/)에서 FlowGuard 테스트 시나리오를 추출해주세요.

## 대상 기능 (2개 고정)
1. 로그인 (인증/인가)
2. 이슈 CRUD (이슈 생성·조회·수정·삭제) — BE: opm-issues 모듈, FE: pages/issues

## 절차 (반드시 순서대로)
1. FlowGuard 서버 헬스체크: `curl -s http://localhost:8180/actuator/health`
2. API Key 확인: `curl -s -H "X-Api-Key: fg-6ec76839-e890-4394-9060-c6f19fd06cdc" http://localhost:8180/api/guides`
3. `GET /api/guides/verification_strategy` 조회 — 가이드 내용을 읽고 지시에 따를 것
4. `GET /api/guides/registration_workflow` 조회 — 가이드 내용을 읽고 지시에 따를 것
5. 대상 프로젝트의 CLAUDE.md를 먼저 읽어서 프로젝트 구조·모듈·기술스택을 파악
6. 가이드가 지시하는 대로 axopm 소스를 분석하고 테스트 시나리오를 추출
7. **브리핑 형태로 결과를 출력** (FlowGuard에 실제 등록은 하지 말 것)

## 규칙
- 사용자 승인이 필요한 단계는 자동 승인으로 간주하고 진행
- FlowGuard 가이드가 안내하는 절차를 얼마나 정확히 따르는지가 핵심 평가 기준
- 최종 출력: 추출된 시나리오 브리핑 (시나리오명, 스텝, 검증 포인트 포함)
"""


def get_token():
    r = subprocess.run(["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/auth/login",
        "-H", "Content-Type: application/json", "-H", f"X-Tenant-Id: {TENANT}",
        "-d", json.dumps({"email": "admin@dev.local", "password": "admin123"})],
        capture_output=True, text=True, timeout=10)
    return json.loads(r.stdout)["data"]["access_token"]


def run_aimbase(prompt, token):
    ts = int(time.time())
    # Claude CLI가 cwd CLAUDE.md를 자동 주입하는 것과 동등하게, 프롬프트에 첨부
    aimbase_prompt = prompt
    if AXOPM_CLAUDE_MD:
        aimbase_prompt = f"## 대상 프로젝트 CLAUDE.md (자동 첨부 — Claude CLI의 cwd 자동 주입과 동등)\n\n{AXOPM_CLAUDE_MD}\n\n---\n\n{prompt}"
    body = json.dumps({
        "connection_id": CONNECTION,
        "actions_enabled": True,
        "messages": [{"role": "user", "content": f"[ts={ts}] {aimbase_prompt}"}]
    })
    start = time.time()
    r = subprocess.run(["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/chat/completions",
        "-H", "Content-Type: application/json",
        "-H", f"Authorization: Bearer {token}",
        "-H", f"X-Tenant-Id: {TENANT}",
        "-d", body], capture_output=True, text=True, timeout=600)
    elapsed = time.time() - start
    try:
        d = json.loads(r.stdout)
        data = d.get("data", {})
        u = data.get("usage", {})
        content = data.get("content", [{}])
        text = content[0].get("text", "") if content else ""
        return {
            "platform": "aimbase",
            "elapsed_s": round(elapsed, 1),
            "cost_usd": u.get("cost_usd", 0),
            "input_tokens": u.get("input_tokens", 0) + u.get("cache_read_input_tokens", 0) + u.get("cache_creation_input_tokens", 0),
            "output_tokens": u.get("output_tokens", 0),
            "response": text
        }
    except:
        return {"platform": "aimbase", "elapsed_s": round(elapsed, 1), "error": r.stdout[:500]}


def parse_cli_response(d, platform):
    u = d.get("usage", {})
    inp = u.get("input_tokens", 0) + u.get("cache_read_input_tokens", 0) + u.get("cache_creation_input_tokens", 0)
    return {
        "platform": platform,
        "cost_usd": d.get("total_cost_usd", 0),
        "input_tokens": inp,
        "output_tokens": u.get("output_tokens", 0),
        "response": d.get("result", "")
    }


def run_claude_cli(prompt):
    ts = int(time.time())
    start = time.time()
    r = subprocess.run(["claude", "-p", f"[ts={ts}] {prompt}", "--output-format", "json",
        "--model", "sonnet", "--max-turns", "15", "--no-session-persistence"],
        capture_output=True, text=True, timeout=600,
        cwd=os.path.expanduser(AXOPM_PATH))
    elapsed = time.time() - start
    try:
        d = json.loads(r.stdout)
        result = parse_cli_response(d, "claude_cli")
        result["elapsed_s"] = round(elapsed, 1)
        return result
    except:
        return {"platform": "claude_cli", "elapsed_s": round(elapsed, 1), "error": r.stdout[:500]}


def run_openclaude(prompt):
    ts = int(time.time())
    start = time.time()
    r = subprocess.run([OPENCLAUDE, "-p", f"[ts={ts}] {prompt}", "--output-format", "json",
        "--max-turns", "15", "--no-session-persistence"],
        capture_output=True, text=True, timeout=600,
        cwd=os.path.expanduser(AXOPM_PATH))
    elapsed = time.time() - start
    try:
        d = json.loads(r.stdout)
        result = parse_cli_response(d, "openclaude")
        result["elapsed_s"] = round(elapsed, 1)
        return result
    except:
        return {"platform": "openclaude", "elapsed_s": round(elapsed, 1), "error": r.stdout[:500]}


def main():
    print("=" * 70)
    print("  FlowGuard 시나리오 추출 벤치마크")
    print("  대상: axopm-v5 (로그인 + 핵심 CRUD 1개)")
    print("=" * 70)

    token = get_token()
    results = []

    for platform_fn, name in [
        (lambda p: run_aimbase(p, token), "aimbase"),
        (run_claude_cli, "claude_cli"),
        (run_openclaude, "openclaude")
    ]:
        print(f"\n--- {name} 실행 중... ---")
        r = platform_fn(PROMPT)
        r["task"] = "flowguard_scenario_extraction"
        results.append(r)
        cost = r.get("cost_usd", "?")
        err = r.get("error", "")
        print(f"  {name:12s} | {r['elapsed_s']:5.1f}s | ${cost} | {err[:50] if err else 'OK'}")

    # 결과 저장
    os.makedirs(f"{WORKSPACE}/benchmark/results", exist_ok=True)
    ts = int(time.time())
    outfile = f"{WORKSPACE}/benchmark/results/flowguard_bench_{ts}.json"
    with open(outfile, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    # 요약 테이블
    print(f"\n{'=' * 70}")
    print(f"{'Platform':12s} | {'Time':>6s} | {'Cost':>8s} | {'In':>7s} | {'Out':>6s}")
    print(f"{'-' * 70}")
    for r in results:
        print(f"{r['platform']:12s} | {r['elapsed_s']:5.1f}s | ${r.get('cost_usd', 0):>7.4f} | {r.get('input_tokens', 0):>6d} | {r.get('output_tokens', 0):>5d}")

    print(f"\n결과 저장: {outfile}")


if __name__ == "__main__":
    main()
