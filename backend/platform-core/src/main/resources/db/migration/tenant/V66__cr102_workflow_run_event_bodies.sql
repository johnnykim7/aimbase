-- CR-102: 워크플로우 실행 본문 전문 적재 (Quality Inspector)
--
-- CR-090 의 workflow_run_events 는 "얇은 버전" — prompt/response 원본 미저장, 메타(preview ≤100자)만.
-- 품질 분석은 단계의 입력→출력 본문을 끝까지 정독해야 의미 있으므로(절단하면 분석 불가),
-- 본문 전문(절단 없음) 적재 컬럼을 추가한다.
--
-- 적재 대상 (사용자 결정: 전문 적재):
--   LLM_RESPONSE  → prompt_text  (system+prompt 입력) / response_text (응답 본문)
--   TOOL_USE      → input_json   (도구 input 전문)
--   TOOL_RESULT   → output_text  (도구 결과 전문)
--   STEP_END      → output_text  (단계 결과 본문, 단계 간 데이터 전달 검토용)
--
-- 모두 nullable — 기존 행/메타 전용 이벤트(STEP_START 등)는 비워둔다.
-- 절단 없는 전문 저장 → run 당 용량 증가. 보관기간(TTL) 정책은 후속 CR 에서 별도.

ALTER TABLE workflow_run_events
    ADD COLUMN prompt_text   TEXT,   -- LLM_RESPONSE: system + prompt 입력 본문
    ADD COLUMN response_text TEXT,   -- LLM_RESPONSE: 응답 본문 (content text)
    ADD COLUMN input_json    JSONB,  -- TOOL_USE: 도구 input 전문
    ADD COLUMN output_text   TEXT;   -- TOOL_RESULT / STEP_END: 결과 본문 전문
