-- CR-120: 대용량 입력 전수 분석 엔진 — job 상태 저장소
--
-- LARGE_INPUT 스텝 1회 실행 = job 1개. 자율주행 밖에서 결정론적으로 분해한 청크 목록과
-- 청크별 처리 결과, 계층 Reduce 트리, 누락 검증 리포트를 한 테이블 JSONB 컬럼에 적재한다.
--
-- 본질: 32MB(Anthropic API 요청 물리한계)를 애초에 안 치게 청크 단위로 전수 처리하고,
-- 누락을 숨기지 않는다(CR-119 철학) — coverage_report 에 처리/실패 청크를 정직하게 남긴다.
--
-- status 단방향: DECOMPOSING → PROCESSING → REDUCING → VERIFYING → COMPLETED.
--   어느 단계에서든 실패하면 FAILED 종착.

CREATE TABLE large_input_jobs (
    id              BIGSERIAL PRIMARY KEY,
    job_id          UUID NOT NULL UNIQUE,       -- 외부 식별자 (결과 Map 으로 반환, 추적용)
    run_id          UUID NOT NULL,              -- 워크플로우 run
    step_id         VARCHAR(100),               -- LARGE_INPUT 스텝 id
    analysis_action VARCHAR(64),                -- extract/summarize/verify/... (AnalysisActionRegistry key)
    source_ref      VARCHAR(255),               -- attachment_id 또는 인라인 입력 식별자
    status          VARCHAR(30) NOT NULL,       -- DECOMPOSING/PROCESSING/REDUCING/VERIFYING/COMPLETED/FAILED
    total_chunks    INT,
    completed       INT NOT NULL DEFAULT 0,
    failed          INT NOT NULL DEFAULT 0,
    chunks          JSONB,                      -- 분해된 청크 메타 목록
    chunk_results   JSONB,                      -- 청크별 처리 결과 (증분 적재)
    reduce_tree     JSONB,                      -- 계층 Reduce 트리
    coverage_report JSONB,                      -- 누락 검증 리포트
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lij_run    ON large_input_jobs(run_id);
CREATE INDEX idx_lij_status ON large_input_jobs(status);
