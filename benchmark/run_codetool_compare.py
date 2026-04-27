#!/usr/bin/env python3
"""
ClaudeCodeTool (워크플로우 도구, CR-044) vs ClaudeCli LLM 어댑터 (CR-050) 비교.

같은 시나리오 + 같은 'Aimbase MCP 도구만 사용' 정책 (tool_bridge=aimbase-mcp-only / ToolMode=AIMBASE)
으로 두 호출 경로의 응답·시간·도구 호출 동등성 검증 — CR-069 잠금 정책 통일 확인.
"""
import json, subprocess, time, os

WORKSPACE = "/Users/sykim/Documents/GitHub/bp-platform/aimbase"
AIMBASE_URL = "http://localhost:8080"
TENANT = "tenant_dev"
WORKING_DIR = os.path.expanduser("~/aimbase-workspace/claudecli-src")

PROMPT = (
    "claudecli-src 디렉토리의 .java 파일 6개를 분석하여 1~2문단 짧은 요약을 작성하세요.\n"
    "절차:\n"
    "1. 도구로 디렉토리의 .java 파일 목록을 확인 (1번 호출)\n"
    "2. 각 파일을 도구로 읽기 (6번 호출)\n"
    "3. 짧게 종합 요약 작성 — 각 클래스 한 줄, 협력 흐름 2~3줄.\n"
    "중요: 같은 도구를 같은 인자로 반복 호출하지 마세요. 추측하지 말고 도구 결과만 사용하세요. 응답은 500자 이내로 짧게."
)


def get_token():
    r = subprocess.run(
        ["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/auth/login",
         "-H", "Content-Type: application/json", "-H", f"X-Tenant-Id: {TENANT}",
         "-d", json.dumps({"email": "admin@dev.local", "password": "admin123"})],
        capture_output=True, text=True, timeout=10)
    return json.loads(r.stdout)["data"]["access_token"]


def run_cli_adapter(prompt, token, ts_suffix):
    """CR-050 ClaudeCli LLM 어댑터 (chat/completions)."""
    body = json.dumps({
        "connection_id": "claude-cli-dev",
        "actions_enabled": True,
        "session_id": f"codetool-cmp-cli-{ts_suffix}",
        "working_directory": WORKING_DIR,
        "messages": [{"role": "user", "content": f"[ts={ts_suffix}] {prompt}"}],
    })
    start = time.time()
    r = subprocess.run(
        ["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/chat/completions",
         "-H", "Content-Type: application/json",
         "-H", f"Authorization: Bearer {token}",
         "-H", f"X-Tenant-Id: {TENANT}",
         "-d", body], capture_output=True, text=True, timeout=600)
    elapsed = time.time() - start
    out = {"path": "ClaudeCli (CR-050)", "elapsed_s": round(elapsed, 1)}
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
            "output_tokens": u.get("output_tokens", 0),
            "cost_usd": data.get("cost_usd") or u.get("cost_usd", 0),
            "response": "".join(text_blocks),
        })
    except Exception as e:
        out["error"] = f"{e}: {r.stdout[:300]}"
    return out


def run_codetool(prompt, token, ts_suffix):
    """CR-044 ClaudeCodeTool — tool_bridge=aimbase-mcp-only 로 같은 정책 강제 + 모델/턴 통일."""
    body = json.dumps({
        "input": {
            "prompt": f"[ts={ts_suffix}] {prompt}",
            "tool_bridge": "aimbase-mcp-only",
            "model": "claude-sonnet-4-6",       # CLI 어댑터와 동일 모델
            "max_turns": 20,                     # 6 파일 + 분석 충분
            "working_directory": WORKING_DIR,
            "session_id": f"codetool-cmp-tool-{ts_suffix}",
            "output_format": "json",
        }
    })
    start = time.time()
    r = subprocess.run(
        ["curl", "-s", "-X", "POST", f"{AIMBASE_URL}/api/v1/tools/claude_code/execute",
         "-H", "Content-Type: application/json",
         "-H", f"Authorization: Bearer {token}",
         "-H", f"X-Tenant-Id: {TENANT}",
         "-d", body], capture_output=True, text=True, timeout=600)
    elapsed = time.time() - start
    out = {"path": "ClaudeCodeTool (CR-044)", "elapsed_s": round(elapsed, 1)}
    try:
        d = json.loads(r.stdout)
        data = d.get("data", {})
        # ToolResult 구조: { success, output, summary, ... }
        # output 안에 ClaudeCodeTool 의 raw stdout (JSON 또는 텍스트)
        out["raw"] = data
        # summary 파싱 시도
        tool_output = data.get("output") if isinstance(data, dict) else None
        if isinstance(tool_output, str):
            try:
                inner = json.loads(tool_output)
                out["response"] = inner.get("result") or inner.get("text") or str(inner)[:1000]
                if "usage" in inner:
                    u = inner["usage"]
                    out["input_tokens"] = (u.get("input_tokens", 0)
                                            + u.get("cache_read_input_tokens", 0)
                                            + u.get("cache_creation_input_tokens", 0))
                    out["output_tokens"] = u.get("output_tokens", 0)
                if "total_cost_usd" in inner:
                    out["cost_usd"] = inner["total_cost_usd"]
                if "num_turns" in inner:
                    out["num_turns"] = inner["num_turns"]
            except Exception:
                out["response"] = tool_output[:1000]
        else:
            out["response"] = str(tool_output)[:1000]
        out["summary"] = data.get("summary") if isinstance(data, dict) else None
    except Exception as e:
        out["error"] = f"{e}: {r.stdout[:500]}"
    return out


def main():
    token = get_token()
    suffix = int(time.time())
    print(f"\n{'=' * 70}\n  ClaudeCli LLM Adapter vs ClaudeCodeTool — 같은 시나리오\n{'=' * 70}\n")

    cli = run_cli_adapter(PROMPT, token, suffix)
    print(f"  ClaudeCli  | {cli['elapsed_s']:6.1f}s | tools={cli.get('tool_calls', '?')} | "
          f"in={cli.get('input_tokens', '?')} out={cli.get('output_tokens', '?')} "
          f"${cli.get('cost_usd', '?')} | {cli.get('error', 'OK')[:50]}")

    code = run_codetool(PROMPT, token, suffix)
    print(f"  CodeTool   | {code['elapsed_s']:6.1f}s | turns={code.get('num_turns', '?')} | "
          f"in={code.get('input_tokens', '?')} out={code.get('output_tokens', '?')} "
          f"${code.get('cost_usd', '?')} | {code.get('error', 'OK')[:80]}")

    out_path = f"{WORKSPACE}/benchmark/results/codetool_vs_cliadapter_{suffix}.json"
    with open(out_path, "w") as f:
        json.dump([cli, code], f, indent=2, ensure_ascii=False)

    print(f"\n결과 저장: {out_path}")
    print(f"\n{'-' * 70}\n[CLI Adapter 응답]\n{'-' * 70}")
    print((cli.get("response") or cli.get("error") or "")[:1500])
    print(f"\n{'-' * 70}\n[CodeTool 응답]\n{'-' * 70}")
    print((code.get("response") or code.get("error") or "")[:1500])


if __name__ == "__main__":
    main()
