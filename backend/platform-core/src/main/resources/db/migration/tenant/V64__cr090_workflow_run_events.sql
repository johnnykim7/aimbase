-- CR-090: 워크플로우 실행 가시성
--
-- workflow_run_events — 시간순 흐름 이벤트 저장소.
-- "어떤 도구를 어떤 input 으로 불렀고 무엇을 받았다" 한 줄 흐름을 사후 재구성하기 위함.
--
-- 원칙: 얇은 버전 — prompt/response 원본은 저장 안 함. 메타만.
--   - 깊이 분석 필요 시 trace_id → traces, subagent_run_id → subagent_runs 로 join.
--   - payload 는 input_keys / preview ≤100자 / finish_reason 등 메타만.
--
-- event_type 6종:
--   STEP_START      WorkflowEngine 스텝 시작
--   TOOL_USE        도구 호출 직전 (TOOL_CALL 직접 + AGENT_CALL 루프 회차)
--   TOOL_RESULT     도구 결과 (위 두 경로)
--   LLM_RESPONSE    LLM_CALL 응답 / AGENT_CALL 루프 회차 응답
--   STEP_END        스텝 정상 종료
--   STEP_FAILED     스텝 retry 후 최종 실패

CREATE TABLE workflow_run_events (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL,
    step_id VARCHAR(100),
    iteration INT,                              -- AGENT_CALL 도구 루프 회차 (nullable)
    event_type VARCHAR(40) NOT NULL,
    tool_name VARCHAR(100),                     -- TOOL_USE/TOOL_RESULT 일 때
    duration_ms BIGINT,                         -- TOOL_RESULT/LLM_RESPONSE/STEP_END/STEP_FAILED 일 때
    payload JSONB,                              -- 메타만
    trace_id VARCHAR(100),                      -- LLM_RESPONSE → traces 조인 키 (nullable)
    subagent_run_id UUID,                       -- AGENT_CALL 안 이벤트 → subagent_runs 조인 키 (nullable)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 주 조회 패턴: 특정 run 의 시간순 이벤트
CREATE INDEX idx_wfre_run_created ON workflow_run_events(run_id, created_at);

-- trace 조인 키 부분 인덱스 (대부분 row 는 null)
CREATE INDEX idx_wfre_trace ON workflow_run_events(trace_id) WHERE trace_id IS NOT NULL;
