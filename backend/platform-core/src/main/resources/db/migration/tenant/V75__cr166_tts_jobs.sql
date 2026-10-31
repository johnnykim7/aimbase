-- CR-166: 같은 목소리 음성 생성 작업.
--
-- 생성기(tts-sidecar)가 CPU 로 한 문장에 15 ~ 35초 걸려 바로 답할 수 없으므로 맡기기와 조회로 나눈다.
-- 차례는 created_at 순이고 서버 전체에서 한 번에 하나만 만든다(BIZ-133).
-- (voice, text) 인덱스는 같은 글을 다시 맡겼을 때 앞 작업을 찾는 데 쓴다(BIZ-134).
-- V74 는 CR-164 몫이다.

CREATE TABLE tts_jobs (
    id             BIGSERIAL PRIMARY KEY,
    job_id         UUID NOT NULL UNIQUE,
    voice          VARCHAR(100) NOT NULL,
    text           TEXT NOT NULL,
    status         VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    audio          BYTEA,
    mime_type      VARCHAR(50),
    duration_sec   DOUBLE PRECISION,
    elapsed_sec    DOUBLE PRECISION,
    error_message  TEXT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ
);

CREATE INDEX idx_tts_jobs_voice_text ON tts_jobs(voice, text);
CREATE INDEX idx_tts_jobs_status     ON tts_jobs(status, created_at);
CREATE INDEX idx_tts_jobs_finished   ON tts_jobs(finished_at);
