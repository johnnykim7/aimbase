#!/usr/bin/env python3
"""Aimbase vs Claude CLI vs OpenClaude 3자 벤치마크"""
import json, subprocess, time, sys, os

WORKSPACE = "/Users/sykim/Documents/GitHub/bp-platform/aimbase"
OPENCLAUDE = os.path.expanduser("~/Documents/GitHub/openclaude-main/bin/openclaude")
AIMBASE_URL = "http://localhost:8080"
TENANT = "tenant_dev"
CONNECTION = "claude-sonnet-dev"

TASKS = {
    "T1_simple_qa": "Java Virtual Thread와 Platform Thread의 차이를 3줄로 설명해줘",
    "T2_file_read": f"파일 {WORKSPACE}/CLAUDE.md 를 읽고 현재 Sprint 번호와 프로젝트 이름만 알려줘",
    "T3_multi_search": f"{WORKSPACE}/backend/platform-core/src/main/java/com/platform/session/ContextWindowManager.java 의 trim 메서드가 호출하는 메서드 3개와 각각의 역할을 정리해줘",
    "T4_code_gen": "Spring Boot에서 /api/health 엔드포인트를 만들어줘. DB ping + Redis ping 결과를 JSON으로 반환하는 코드. 코드만 짧게."
}

def get_token():
    r = subprocess.run(["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/auth/login",
        "-H", "Content-Type: application/json", "-H", f"X-Tenant-Id: {TENANT}",
        "-d", json.dumps({"email":"admin@dev.local","password":"admin123"})],
        capture_output=True, text=True, timeout=10)
    return json.loads(r.stdout)["data"]["access_token"]

def run_aimbase(prompt, token, actions=False):
    ts = int(time.time())
    body = json.dumps({
        "connection_id": CONNECTION,
        "actions_enabled": actions,
        "messages": [{"role":"user","content":f"[ts={ts}] {prompt}"}]
    })
    start = time.time()
    r = subprocess.run(["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/chat/completions",
        "-H", "Content-Type: application/json",
        "-H", f"Authorization: Bearer {token}",
        "-H", f"X-Tenant-Id: {TENANT}",
        "-d", body], capture_output=True, text=True, timeout=120)
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
        return {"platform": "aimbase", "elapsed_s": round(elapsed, 1), "error": r.stdout[:200]}

def parse_cli_response(d, platform):
    """Claude CLI / OpenClaude 공통 JSON 파싱"""
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
        "--model", "sonnet", "--max-turns", "3", "--no-session-persistence"],
        capture_output=True, text=True, timeout=120, cwd=WORKSPACE)
    elapsed = time.time() - start
    try:
        d = json.loads(r.stdout)
        result = parse_cli_response(d, "claude_cli")
        result["elapsed_s"] = round(elapsed, 1)
        return result
    except:
        return {"platform": "claude_cli", "elapsed_s": round(elapsed, 1), "error": r.stdout[:200]}

def run_openclaude(prompt):
    ts = int(time.time())
    start = time.time()
    r = subprocess.run([OPENCLAUDE, "-p", f"[ts={ts}] {prompt}", "--output-format", "json",
        "--max-turns", "3", "--no-session-persistence"],
        capture_output=True, text=True, timeout=120, cwd=WORKSPACE)
    elapsed = time.time() - start
    try:
        d = json.loads(r.stdout)
        result = parse_cli_response(d, "openclaude")
        result["elapsed_s"] = round(elapsed, 1)
        return result
    except:
        return {"platform": "openclaude", "elapsed_s": round(elapsed, 1), "error": r.stdout[:200]}

def main():
    token = get_token()
    results = []

    for task_id, prompt in TASKS.items():
        actions = task_id in ("T2_file_read", "T3_multi_search")
        print(f"\n{'='*60}")
        print(f"  {task_id}")
        print(f"{'='*60}")

        for platform_fn, name in [
            (lambda p: run_aimbase(p, token, actions), "aimbase"),
            (run_claude_cli, "claude_cli"),
            (run_openclaude, "openclaude")
        ]:
            r = platform_fn(prompt)
            r["task"] = task_id
            results.append(r)
            cost = r.get("cost_usd", "?")
            err = r.get("error", "")
            print(f"  {name:12s} | {r['elapsed_s']:5.1f}s | ${cost} | {err[:30] if err else 'OK'}")

    # 결과 저장
    os.makedirs(f"{WORKSPACE}/benchmark/results", exist_ok=True)
    ts = int(time.time())
    outfile = f"{WORKSPACE}/benchmark/results/benchmark_{ts}.json"
    with open(outfile, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    # 요약 테이블
    print(f"\n{'='*70}")
    print(f"{'Task':20s} | {'Platform':12s} | {'Time':>6s} | {'Cost':>8s} | {'In':>6s} | {'Out':>5s}")
    print(f"{'-'*70}")
    for r in results:
        print(f"{r.get('task',''):20s} | {r['platform']:12s} | {r['elapsed_s']:5.1f}s | ${r.get('cost_usd',0):>7.4f} | {r.get('input_tokens',0):>5d} | {r.get('output_tokens',0):>4d}")

    print(f"\n결과 저장: {outfile}")

if __name__ == "__main__":
    main()
