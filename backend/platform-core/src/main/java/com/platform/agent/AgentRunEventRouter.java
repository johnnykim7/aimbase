package com.platform.agent;

import com.platform.orchestrator.stream.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * CR-070 Phase B: Agent → 서버 진행 이벤트 라우팅 테이블.
 *
 * 사용자 SSE 스트림이 Agent 측 ClaudeCodeTool 실행 진행상황을 받기 위한 매핑:
 * - 사용자 SSE 진입점(예: ChatController.streamResponse)이 runId 를 발급하고 sink 등록
 * - Agent 가 NDJSON 이벤트를 `POST /api/v1/agents/{id}/runs/{runId}/events` 로 push
 * - 라우터가 runId → sink 로 dispatch
 *
 * 정책:
 * - in-memory ConcurrentHashMap (인스턴스 스코프, 다중 인스턴스 시 sticky session 또는 별도 dispatcher 필요)
 * - 손실 허용 (UX 보강 목적, ACK/재전송 없음)
 * - TTL 만료 자동 GC — 등록 후 30분 미수신 시 폐기
 * - 테넌트 격리 — 등록 시 tenantId 함께 보관, push 시 mismatch 거부
 */
@Component
public class AgentRunEventRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRunEventRouter.class);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final Map<String, Registration> registry = new ConcurrentHashMap<>();

    /**
     * 새 runId 발급 + sink 등록.
     *
     * @param tenantId 사용자 SSE 진입점의 테넌트 ID (검증용, null 허용 — 멀티테넌시 비활성 환경)
     * @param sink     이벤트 도착 시 전달할 싱크 (보통 사용자 SSE emitter wrapper)
     * @return 발급된 runId
     */
    public String register(String tenantId, Consumer<StreamEvent> sink) {
        String runId = "run-" + UUID.randomUUID();
        registry.put(runId, new Registration(tenantId, sink, Instant.now()));
        log.debug("AgentRunEventRouter register runId={} tenantId={}", runId, tenantId);
        return runId;
    }

    /**
     * 등록 해제 (사용자 SSE 종료 시 finally 에서 호출).
     */
    public void unregister(String runId) {
        Registration r = registry.remove(runId);
        if (r != null) {
            log.debug("AgentRunEventRouter unregister runId={}", runId);
        }
    }

    /**
     * Agent 가 push 한 이벤트를 사용자 sink 로 전달.
     *
     * @return true = 전달 성공, false = runId 없음 또는 테넌트 mismatch
     */
    public boolean dispatch(String runId, String tenantId, StreamEvent event) {
        gcExpired();
        Registration r = registry.get(runId);
        if (r == null) {
            log.debug("AgentRunEventRouter dispatch miss runId={}", runId);
            return false;
        }
        if (r.tenantId != null && tenantId != null && !r.tenantId.equals(tenantId)) {
            log.warn("AgentRunEventRouter tenant mismatch runId={} expected={} actual={}",
                    runId, r.tenantId, tenantId);
            return false;
        }
        try {
            r.sink.accept(event);
            return true;
        } catch (Exception e) {
            log.warn("AgentRunEventRouter sink emit 실패 runId={}: {}", runId, e.getMessage());
            return false;
        }
    }

    /**
     * 테스트/모니터링용 — 등록 수.
     */
    public int size() {
        return registry.size();
    }

    private void gcExpired() {
        Instant cutoff = Instant.now().minus(DEFAULT_TTL);
        Iterator<Map.Entry<String, Registration>> it = registry.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Registration> e = it.next();
            if (e.getValue().registeredAt.isBefore(cutoff)) {
                it.remove();
                log.debug("AgentRunEventRouter GC expired runId={}", e.getKey());
            }
        }
    }

    private record Registration(String tenantId, Consumer<StreamEvent> sink, Instant registeredAt) {}
}
