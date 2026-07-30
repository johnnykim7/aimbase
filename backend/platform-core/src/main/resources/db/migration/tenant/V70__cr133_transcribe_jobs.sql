-- CR-133: 회의녹음 배치 전사 — job 상태 저장소
--
-- 위젯 STT(CR-060)와 경로를 분리하는 이유:
--   위젯은 60초/120초 제한을 "일부러" 걸어 둔 짧은 발화 전용이다(ChatSttController, WhisperSpeechService).
--   1시간 회의는 전사에만 ~4분이 걸려 동기 응답이 불가능하므로 job + 폴링 구조로 간다.
--   CR-120 large_input_jobs 와 같은 패턴.
--
-- status 단방향: PENDING → RUNNING → COMPLETED.
--   어느 단계에서든 실패하면 FAILED 종착.

CREATE TABLE transcribe_jobs (
    id             BIGSERIAL PRIMARY KEY,
    job_id         UUID NOT NULL UNIQUE,        -- 외부 식별자 (API 로 노출)
    connection_id  VARCHAR(100),                -- 사용한 STT 커넥션 (NULL 이면 기본 해석)
    filename       VARCHAR(500),
    mime_type      VARCHAR(100),
    size_bytes     BIGINT,
    language       VARCHAR(20),                 -- 요청 언어 (ko / auto)
    status         VARCHAR(30) NOT NULL,        -- PENDING/RUNNING/COMPLETED/FAILED
    text           TEXT,                        -- 전사 전문
    segments       JSONB,                       -- [{start,end,text}, ...]
    detected_lang  VARCHAR(20),
    duration_sec   DOUBLE PRECISION,            -- 오디오 길이
    elapsed_sec    DOUBLE PRECISION,            -- 전사 소요 시간
    error_message  TEXT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tj_status  ON transcribe_jobs(status);
CREATE INDEX idx_tj_created ON transcribe_jobs(created_at DESC);
