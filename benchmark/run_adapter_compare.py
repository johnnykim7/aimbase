#!/usr/bin/env python3
"""
Aimbase 두 어댑터 비교: anthropic (API) vs anthropic-cli (CLI).
같은 REST 엔드포인트 + connection_id 만 바꿔서 도구 호출 행동 비교.
"""
import json, subprocess, time, os

WORKSPACE = "/Users/sykim/Documents/GitHub/bp-platform/aimbase"
AIMBASE_URL = "http://localhost:8080"
TENANT = "tenant_dev"
CONN_API = "claude-sonnet-dev"   # anthropic 어댑터
CONN_CLI = "claude-cli-dev"      # anthropic-cli 어댑터

# 시나리오: workspace whitelist 안의 경로(~/aimbase-workspace 하위)
TASKS = {
    "T_inside_concrete": (
        "claudecli-src 디렉토리의 .java 파일 6개를 분석하여 1~2문단 짧은 요약을 작성하세요.\n"
        "절차:\n"
        "1. 도구로 디렉토리의 .java 파일 목록을 확인 (1번 호출)\n"
        "2. 각 파일을 도구로 읽기 (6번 호출)\n"
        "3. 짧게 종합 요약 작성 — 각 클래스 한 줄, 협력 흐름 2~3줄.\n"
        "중요: 같은 도구를 같은 인자로 반복 호출하지 마세요. 추측하지 말고 도구 결과만 사용하세요. 응답은 500자 이내로 짧게."
    ),
    "T_inside_abstract": (
        "워크스페이스 루트의 java 파일들을 분석하여 1~2문단 짧은 요약을 작성하세요.\n"
        "절차:\n"
        "1. builtin_glob 으로 워크스페이스의 .java 파일 목록 확인 (도구 호출 필수)\n"
        "2. 발견된 각 파일을 builtin_file_read 로 읽기 (도구 호출 필수)\n"
        "3. 짧게 종합 요약 작성 — 각 클래스 한 줄, 협력 흐름 2~3줄.\n"
        "중요: 도구 호출 없이 답변 작성 금지. 추측 금지, 도구 결과로만 작성. 응답은 500자 이내."
    ),
}

WORKING_DIR = os.path.expanduser("~/aimbase-workspace/claudecli-src")


def get_token():
    r = subprocess.run(
        ["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/auth/login",
         "-H", "Content-Type: application/json", "-H", f"X-Tenant-Id: {TENANT}",
         "-d", json.dumps({"email": "admin@dev.local", "password": "admin123"})],
        capture_output=True, text=True, timeout=10)
    return json.loads(r.stdout)["data"]["access_token"]


def run(connection_id, prompt, token):
    ts = int(time.time())
    body = json.dumps({
        "connection_id": connection_id,
        "actions_enabled": True,
        "working_directory": WORKING_DIR,
        "messages": [{"role": "user", "content": f"[ts={ts}] {prompt}"}],
    })
    start = time.time()
    r = subprocess.run(
        ["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/chat/completions",
         "-H", "Content-Type: application/json",
         "-H", f"Authorization: Bearer {token}",
         "-H", f"X-Tenant-Id: {TENANT}",
         "-d", body], capture_output=True, text=True, timeout=600)
    elapsed = time.time() - start
    out = {"connection_id": connection_id, "elapsed_s": round(elapsed, 1)}
    try:
        d = json.loads(r.stdout)
        data = d.get("data", {})
        u = data.get("usage", {}) or {}
        actions = data.get("actions_executed", []) or []
        text_blocks = [b.get("text", "") for b in (data.get("content") or []) if b.get("type") == "text"]
        out.update({
            "tool_calls": len(actions),
            "tool_names": [a.get("name") for a in actions],
            "input_tokens": u.get("input_tokens", 0),
            "cache_read": u.get("cache_read_input_tokens", 0),
            "cache_creation": u.get("cache_creation_input_tokens", 0),
            "output_tokens": u.get("output_tokens", 0),
            "cost_usd": data.get("cost_usd") or u.get("cost_usd", 0),
            "response": "".join(text_blocks),
        })
    except Exception as e:
        out["error"] = f"{e}: {r.stdout[:300]}"
    return out


def main():
    token = get_token()
    results = []
    for task_id, prompt in TASKS.items():
        print(f"\n{'='*70}\n  {task_id}\n{'='*70}")
        for cid in (CONN_API, CONN_CLI):
            r = run(cid, prompt, token)
            r["task"] = task_id
            results.append(r)
            err = r.get("error", "")
            tools = r.get("tool_calls", "?")
            print(f"  {cid:25s} | {r['elapsed_s']:6.1f}s | tools={tools} | "
                  f"in={r.get('input_tokens', '?')} out={r.get('output_tokens', '?')} "
                  f"${r.get('cost_usd', '?')} | {err[:50] if err else 'OK'}")

    # 저장
    os.makedirs(f"{WORKSPACE}/benchmark/results", exist_ok=True)
    ts = int(time.time())
    outfile = f"{WORKSPACE}/benchmark/results/adapter_compare_{ts}.json"
    with open(outfile, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    # 요약 + 응답
    print(f"\n{'='*70}\n결과 저장: {outfile}\n{'='*70}")
    for r in results:
        print(f"\n--- {r['task']} / {r['connection_id']} ---")
        print(f"elapsed={r['elapsed_s']}s, tools={r.get('tool_calls', '?')}, "
              f"in={r.get('input_tokens', '?')}, out={r.get('output_tokens', '?')}, "
              f"cost=${r.get('cost_usd', '?')}")
        if r.get("tool_names"):
            print(f"tool_names={r['tool_names']}")
        text = r.get("response") or r.get("error") or ""
        print(f"response: {text[:600]}")


if __name__ == "__main__":
    main()
