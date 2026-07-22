package com.platform.llm.adapter;

import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ToolCall;
import com.platform.service.AgentEndpoint;
import com.platform.service.AgentRegistryService;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * CR-071 Phase 4: LLMAdapter 구현 — ClaudeCliRunner HTTP 호출 경로.
 *
 * <p>호출 흐름 (CR-075 — 라우팅 우선순위 폴백):
 * <ol>
 *   <li>헤더 {@code X-Aimbase-Agent-Id} 명시 → {@link AgentRegistryService#resolveActiveRunner(String)}</li>
 *   <li>위젯 토큰 user_ref 클레임 → {@link AgentRegistryService#resolveActiveByUserRef(String)} (정상 흐름)</li>
 *   <li>둘 다 없으면 400</li>
 *   <li>{@link ClaudeCliRunnerClient} 로 Runner 호출</li>
 * </ol>
 *
 * <p>Connection 레벨 옵션 (생성자 주입):
 * <ul>
 *   <li>{@code defaultModel} — connection.config.model</li>
 *   <li>{@code toolMode} — AIMBASE / NATIVE / HYBRID (CR-069)</li>
 *   <li>{@code configDir} — Runner 측 CLAUDE_CONFIG_DIR (정액 플랜 OAuth 디렉토리)</li>
 *   <li>{@code runnerApiKey} — connection.config.runner_api_key (X-Api-Key 헤더로 송신)</li>
 * </ul>
 *
 * <p>도구 정의는 transformToolDefs 에서 null 반환 — 도구 노출은 Runner 측 MCP 채널을 통한다 (CR-044).
 */
public class ClaudeCliAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliAdapter.class);
    public static final String PROVIDER = "anthropic-cli";

    private final ClaudeCliRunnerClient runnerClient;
    private final AgentRegistryService agentRegistry;
    private final String defaultModel;
    private final String toolMode;
    private final String configDir;
    private final String systemPromptOverride;
    private final String runnerApiKey;
    /** CR-103: 커넥터 config.agent_name — 워크플로우 경로 라우팅 폴백 키 (null 허용). */
    private final String routingAgentName;
    /**
     * CR-117: CLI 본체 {@code Agent} 서브에이전트 허용 여부 (connection.config.subagent_enabled).
     * true(기본) = 현행(자율 서브에이전트 허용). false 면 Runner 에 disallow_subagent 신호를 보내
     * {@code --disallowedTools Agent} 가 주입된다. CLI subagent 는 제어 불가(관찰만)이므로 OFF 는
     * "spawn 자체 차단" 용도 — depth 가드/정책 적용이 아니라 도구 노출 제거다.
     */
    private final boolean subagentEnabled;

    public ClaudeCliAdapter(ClaudeCliRunnerClient runnerClient,
                             AgentRegistryService agentRegistry,
                             String defaultModel,
                             String toolMode,
                             String configDir,
                             String systemPromptOverride,
                             String runnerApiKey,
                             String routingAgentName,
                             boolean subagentEnabled) {
        this.runnerClient = runnerClient;
        this.agentRegistry = agentRegistry;
        this.defaultModel = defaultModel;
        this.toolMode = toolMode;
        this.configDir = configDir;
        this.systemPromptOverride = systemPromptOverride;
        this.runnerApiKey = runnerApiKey;
        this.routingAgentName = routingAgentName;
        this.subagentEnabled = subagentEnabled;
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public List<String> getSupportedModels() {
        // CLI 가 지원하는 모델은 계정 플랜에 따라 달라진다 — 공식 목록 없음.
        return List.of();
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        try {
            AgentEndpoint endpoint = resolveEndpoint();
            LLMRequest effective = ensureModel(request);
            try {
                LLMResponse resp = runnerClient.chat(
                        endpoint, effective, toolMode, configDir, systemPromptOverride, runnerApiKey,
                        subagentEnabled); // CR-117
                return CompletableFuture.completedFuture(resp);
            } catch (RuntimeException re) {
                // CR-082: runner 호출 자체 실패 — 사유를 식별 가능한 prefix 로 감싸 RuntimeException 전파.
                // ResponseStatusException 을 던지면 Spring 이 ASYNC dispatch 를 통해 SecurityContext 재검사를 일으켜
                // 위젯에 "network error" 로 보이는 부수효과 발생 (운영 검증). 따라서 단순 RuntimeException 으로.
                String classified = classifyRunnerFailure(re);
                log.warn("CLI runner unreachable: {} ({})", classified, re.getMessage());
                cancelIfTimeout(endpoint, effective, classified); // CR-109
                throw new RuntimeException("cli_runner_unreachable:" + classified + ":" + re.getMessage(), re);
            }
        } catch (ResponseStatusException rse) {
            return CompletableFuture.failedFuture(rse);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        AgentEndpoint endpoint = resolveEndpoint();
        LLMRequest effective = ensureModel(request);
        try {
            runnerClient.chatStream(
                    endpoint, effective, toolMode, configDir, systemPromptOverride, runnerApiKey,
                    chunkConsumer, subagentEnabled); // CR-117
        } catch (RuntimeException re) {
            // CR-082: stream 경로도 동일 — RuntimeException 으로 일관.
            String classified = classifyRunnerFailure(re);
            log.warn("CLI runner unreachable (stream): {} ({})", classified, re.getMessage());
            cancelIfTimeout(endpoint, effective, classified); // CR-109
            throw new RuntimeException("cli_runner_unreachable:" + classified + ":" + re.getMessage(), re);
        }
    }

    /**
     * CR-109: BE 측 HTTP timeout(또는 broken pipe) 으로 runner 호출이 끊겼을 때, Runner 에 남아있는
     * 워커(claude CLI 프로세스)가 좀비로 누수되지 않도록 명시적 cancel 신호를 보낸다.
     *
     * <p>AGENT_OFFLINE/연결 거부 같은 "애초에 Runner 에 닿지 못한" 실패는 보낼 대상이 없으므로 제외.
     * timeout/broken-pipe 처럼 "요청은 갔는데 응답 전에 끊긴" 경우에만 정리 신호를 보낸다.
     * cancel 호출 자체가 실패해도(베스트에포트) 원래 예외 전파를 막지 않는다.
     */
    private void cancelIfTimeout(AgentEndpoint endpoint, LLMRequest request, String classified) {
        if (!("AGENT_TIMEOUT".equals(classified) || "TURN_BROKEN_PIPE".equals(classified))) {
            return;
        }
        String runId = request.sessionId();
        if (runId == null || runId.isBlank()) return;
        try {
            log.info("CR-109: timeout({}) — Runner cancel 신호 전송 (runId={})", classified, runId);
            runnerClient.cancel(endpoint, runId, runnerApiKey);
        } catch (RuntimeException ce) {
            log.warn("CR-109: Runner cancel 전송 실패 (runId={}): {}", runId, ce.getMessage());
        }
    }

    /**
     * CR-114: AGENT_CALL(CLI 자율주행) 이 <b>정상 완료</b>된 뒤, Runner pool 에 남는 워커(claude CLI 프로세스)를
     * 결정적으로 닫는다. CR-109 의 {@link #cancelIfTimeout}(timeout/실패 catch 경로)와 같은 {@code runnerClient.cancel}
     * 신호를 공유하되, 이쪽은 예외 없이 끝난 success 경로에서 호출된다. completed run 은 같은 sessionId 로 다시 호출될 일이
     * 없으므로 워커를 닫지 않으면 pool 에 영구 좀비로 남는다(운영 8시간 생존 0cb2ae03 사례). best-effort.
     */
    @Override
    public void cleanupSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            AgentEndpoint endpoint = resolveEndpoint();
            log.info("CR-114: 정상 완료 — Runner cancel 신호 전송 (runId={})", sessionId);
            runnerClient.cancel(endpoint, sessionId, runnerApiKey);
        } catch (RuntimeException e) {
            log.warn("CR-114: 정상 완료 후 worker 정리 실패 (runId={}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * CR-121: 부모 runId 접두사로 시작하는 모든 워커 세션을 Runner 에 일괄 종료 요청한다.
     * LARGE_INPUT 청크/재시도 sessionId 가 제각각이라 단일 cleanupSession 으로 못 잡는 잔여 좀비 회수용.
     */
    @Override
    public void cleanupSessionsByPrefix(String runIdPrefix) {
        if (runIdPrefix == null || runIdPrefix.isBlank()) return;
        try {
            AgentEndpoint endpoint = resolveEndpoint();
            log.info("CR-121: run 종료 — Runner prefix cancel 신호 전송 (prefix={})", runIdPrefix);
            runnerClient.cancel(endpoint, runIdPrefix, runnerApiKey, true);
        } catch (RuntimeException e) {
            log.warn("CR-121: prefix cancel 실패 (prefix={}): {}", runIdPrefix, e.getMessage());
        }
    }

    /**
     * CR-082: runner 호출 실패의 원인을 진단 로그/에러 메시지에 식별 가능한 사유 코드로 분류.
     * 정확한 사유보다 "어디서 끊겼는지" 단서 하나가 운영 진단을 빠르게 한다.
     */
    private static String classifyRunnerFailure(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 5 && cur != null; i++) {
            String msg = cur.getMessage();
            String type = cur.getClass().getSimpleName();
            if (cur instanceof java.net.ConnectException) return "AGENT_OFFLINE";
            if (cur instanceof java.net.SocketTimeoutException) return "AGENT_TIMEOUT";
            if (cur instanceof java.net.http.HttpTimeoutException) return "AGENT_TIMEOUT";
            if (cur instanceof java.net.NoRouteToHostException) return "TURN_RELAY_DEAD";
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("broken pipe") || lower.contains("connection reset")) return "TURN_BROKEN_PIPE";
                if (lower.contains("connection refused")) return "AGENT_OFFLINE";
            }
            // 마지막 fallback — 클래스명 노출
            if (cur.getCause() == null) return type;
            cur = cur.getCause();
        }
        return "UNKNOWN";
    }

    /**
     * Claude CLI 는 Anthropic API 의 {@code tools} 파라미터가 없으며, 도구 통합의 정식 채널은
     * Runner 가 spawn 시 연결하는 MCP 서버 ({@code aimbase-agent --mcp-stdio}, CR-042/044) 이다.
     * @return 항상 null — 도구는 MCP 채널로 전달됨.
     */
    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        return null;
    }

    @Override
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
        if (nativeResponse instanceof LLMResponse resp) {
            return resp.toolCalls() != null ? resp.toolCalls() : List.of();
        }
        return List.of();
    }

    // ─── 내부 ─────────────────────────────────────────────────────────────

    /**
     * 라우팅 우선순위
     *   1) {@code X-Aimbase-Agent-Id} 헤더 명시 (디버깅/특수 케이스 호환, CR-071)
     *   2) 위젯 토큰 {@code user_ref} 클레임 (위젯 정상 흐름, CR-075)
     *   3) 커넥터 config {@code agent_name} (워크플로우 정상 흐름, CR-103)
     *   4) 모두 없으면 400
     *
     * <p>헤더/user_ref 가 커넥터 기본값(agent_name)보다 우선 — 호출자가 명시한 라우팅이
     * 커넥터에 박힌 기본값을 덮는다. 워크플로우 경로는 1·2 가 비어 있으므로 3 이 동작한다.
     */
    private AgentEndpoint resolveEndpoint() {
        // 1) 헤더 명시 우선
        String agentId = RequestContext.getAgentId();
        if (agentId != null && !agentId.isBlank()) {
            Optional<AgentEndpoint> ep = agentRegistry.resolveActiveRunner(agentId);
            if (ep.isEmpty()) {
                // CR-082: 인프라 문제(agent down/STALE) — RuntimeException 으로 일관.
                // ResponseStatusException 을 SSE catch 흐름에 던지면 Spring 이 ASYNC dispatch 로
                // SecurityContext 권한 재검사를 일으켜 위젯에 "network error" 발생 (운영 검증).
                throw new RuntimeException("cli_agent_offline:agent-id=" + agentId
                        + " (agent inactive 또는 runner_capability=false)");
            }
            return ep.get();
        }

        // 2) user_ref 자동 라우팅 (CR-075)
        String userRef = RequestContext.getUserRef();
        if (userRef != null && !userRef.isBlank()) {
            Optional<AgentEndpoint> ep = agentRegistry.resolveActiveByUserRef(userRef);
            if (ep.isEmpty()) {
                throw new RuntimeException("cli_agent_offline:user=" + userRef
                        + " (사용자 PC 의 aimbase-agent 가 기동되지 않았거나 runner_capability=false)");
            }
            return ep.get();
        }

        // 3) 커넥터 config agent_name 자동 라우팅 (CR-103 — 워크플로우 LLM_CALL/AGENT_CALL 경로)
        if (routingAgentName != null && !routingAgentName.isBlank()) {
            Optional<AgentEndpoint> ep = agentRegistry.resolveActiveByAgentName(routingAgentName);
            if (ep.isEmpty()) {
                // CR-082 와 동일 — 인프라 문제는 RuntimeException 으로 일관 (SSE ASYNC dispatch 회피).
                throw new RuntimeException("cli_agent_offline:agent-name=" + routingAgentName
                        + " (해당 이름의 agent 가 inactive 또는 runner_capability=false)");
            }
            return ep.get();
        }

        // 4) 모두 없으면 400 — 클라이언트가 토큰/헤더 자체를 안 보냄. 컨트롤러 진입 전 검증과 동일 의미.
        // 이 케이스는 stream:false sync 핸들러에서 자연스럽게 400 매핑되는 게 의도. SSE 진입 전이라 ASYNC dispatch 위험 없음.
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "cli_routing_missing: X-Aimbase-Agent-Id 헤더 / 위젯 토큰 user_ref / 커넥터 config.agent_name 중 하나가 필요합니다");
    }

    private LLMRequest ensureModel(LLMRequest request) {
        LLMRequest req = stripBuiltinToolGuidance(request);  // CR-117: CLI 경로 도구 카탈로그 제거
        if (req.model() != null && !req.model().isBlank()) return req;
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ClaudeCliAdapter: model 미지정 + connection defaultModel 도 없음");
        }
        return req.withModel(defaultModel);
    }

    /** CR-117: builtin_* 도구 카탈로그 구간 시작 마커(CLI 경로엔 불필요·해로움). */
    private static final String TOOL_GUIDANCE_START = "## Tool-specific guidance";
    /** CR-117: 카탈로그 구간 끝 마커 — 이 헤더부터는 일반 지침이라 유지한다(자르기 구간의 끝). */
    private static final String TOOL_GUIDANCE_END = "# Output efficiency";

    /**
     * CR-117: CLI 경로 SYSTEM 프롬프트에서 builtin_* 도구 카탈로그 구간만 제거한다
     * ("## Tool-specific guidance" ~ "# Output efficiency" 직전).
     *
     * <p>이 카탈로그는 SDK agent 기준으로 쓰여 builtin_file_read/glob/grep 같은 이름을 안내하는데,
     * Claude CLI 어댑터 경로에선 그 이름이 CLI MCP 에 없어 "No such tool available: builtin_file_read" 로 실패하고
     * Read 로 폴백하는 중복·재시도가 생긴다. 또 "read ALL in ONE turn" 안내가 한 turn 페이지 누적(32MB)도 부추긴다.
     * CLI 는 자기 네이티브 도구(Read/Glob/Grep)를 이미 알므로 도구 카탈로그가 불필요 → CLI 경로에서만 잘라낸다.
     *
     * <p>카탈로그 앞의 일반 작업 지침(# Doing tasks 등)과 뒤의 # Output efficiency/# Tone and style 은 유지한다.
     * END 마커가 없으면(프롬프트 변형) 안전하게 START 이후 전부 제거. assemble/SDK agent 경로는 본 어댑터를 안 타므로 영향 없다.
     */
    private LLMRequest stripBuiltinToolGuidance(LLMRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) return request;
        boolean changed = false;
        java.util.List<com.platform.llm.model.UnifiedMessage> out =
                new java.util.ArrayList<>(request.messages().size());
        for (com.platform.llm.model.UnifiedMessage m : request.messages()) {
            if (m.role() != com.platform.llm.model.UnifiedMessage.Role.SYSTEM || m.content() == null) {
                out.add(m);
                continue;
            }
            java.util.List<com.platform.llm.model.ContentBlock> newBlocks =
                    new java.util.ArrayList<>(m.content().size());
            for (com.platform.llm.model.ContentBlock b : m.content()) {
                if (b instanceof com.platform.llm.model.ContentBlock.Text t
                        && t.text() != null && t.text().contains(TOOL_GUIDANCE_START)) {
                    newBlocks.add(new com.platform.llm.model.ContentBlock.Text(stripCatalog(t.text())));
                    changed = true;
                } else {
                    newBlocks.add(b);
                }
            }
            out.add(new com.platform.llm.model.UnifiedMessage(m.role(), newBlocks));
        }
        if (changed) {
            log.debug("CR-117: stripped builtin_* tool catalog from CLI system prompt");
        }
        return changed ? request.withMessages(out) : request;
    }

    /** START~END 구간만 제거하고 앞뒤를 잇는다. END 없으면 START 이후 전부 제거. */
    private static String stripCatalog(String text) {
        int start = text.indexOf(TOOL_GUIDANCE_START);
        if (start < 0) return text;
        int end = text.indexOf(TOOL_GUIDANCE_END, start);
        String before = text.substring(0, start).stripTrailing();
        if (end < 0) return before;
        String after = text.substring(end);
        return before + "\n\n" + after;
    }
}
