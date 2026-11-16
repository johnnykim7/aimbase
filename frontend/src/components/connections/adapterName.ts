import type { Connection } from "../../types/connection";

const CLI_ADAPTER_VALUES = ["anthropic-cli", "claude-cli", "claude cli", "anthropic cli", "claude-max", "claude-pro"];
const OPENAI_COMPATIBLE_HINTS = ["deepseek", "groq", "mistral", "openrouter", "together", "fireworks", "lm studio", "localai", "vllm"];

// 저장된 어댑터 값은 서버 이름(anthropic-cli 등)이고 수정 창은 표시명으로 전용 칸을 고른다.
// 서버 ConnectionAdapterFactory.normalizeAdapterType 과 같은 순서로 표시명을 찾고, 못 찾으면 저장값을 그대로 쓴다.
export function displayAdapter(type: string, stored: string): string {
  if (type !== "llm") return stored;
  const s = stored.toLowerCase();
  if (CLI_ADAPTER_VALUES.includes(s)) return "Claude CLI";
  if (s.includes("anthropic") || s.includes("claude")) return "Claude (Anthropic)";
  if (s === "openai_compatible" || s === "openai compatible" || OPENAI_COMPATIBLE_HINTS.some((h) => s.includes(h))) return "OpenAI Compatible";
  if (s.includes("openai") || s.includes("gpt")) return "OpenAI";
  if (s.includes("ollama")) return "Ollama";
  if (s.includes("bedrock") || s.includes("aws")) return "AWS Bedrock";
  if (s.includes("vertex") || s.includes("google") || s.includes("gemini")) return "Vertex AI";
  return stored;
}

// CR-196: 1년 토큰 칸과 상태 칩을 두는 연결. 서버 CliAccountTokenService 와 같이 Claude CLI 이고 실행기 이름이 있는 연결이다.
export function isServerRunner(conn: Connection): boolean {
  const agent = conn.config?.agent_name;
  return displayAdapter(conn.type ?? "llm", conn.adapter ?? "") === "Claude CLI" && typeof agent === "string" && agent.trim() !== "";
}
