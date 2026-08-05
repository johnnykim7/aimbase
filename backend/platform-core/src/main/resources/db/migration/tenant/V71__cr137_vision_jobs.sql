-- CR-137: 모바일 사진/영상 업로드 → 서버 프레임 추출 → VLM 판독 job
--
-- 왜 job(비동기)인가:
--   영상 업로드(~100MB) + ffmpeg 프레임 추출 + VLM 6프레임 판독 = 수십 초~수 분.
--   동기 HTTP 는 게이트웨이 타임아웃에 걸린다. CR-133 transcribe_jobs / CR-120
--   large_input_jobs 와 같은 패턴.
--
-- 사진 1장은 이 테이블을 타지 않는다 — 기존 chat_attachments(CR-061) +
--   ChatController.resolveImageBlock 경로가 이미 동작한다(CR-136 e2e 검증 완료).
--   CR-137 이 새로 여는 것은 영상뿐이다.
--
-- status 단방향: PENDING → RUNNING → COMPLETED.
--   어느 단계에서든 실패하면 FAILED 종착.
--
-- 타임스탬프는 TIMESTAMPTZ — V70(transcribe_jobs) 관례와 맞춘다.
--   같은 프로젝트에서 TIMESTAMP 와 섞으면 드라이버가 LocalDateTime 변환을 못 해 조회가 깨진다.

CREATE TABLE vision_jobs (
    id             BIGSERIAL PRIMARY KEY,
    job_id         UUID NOT NULL UNIQUE,        -- 외부 식별자 (API 로 노출)
    connection_id  VARCHAR(100),                -- 판독에 쓸 LLM 커넥션 (예: qwen-vl-7b-mac-001)
    filename       VARCHAR(500),
    mime_type      VARCHAR(100),                -- video/mp4 | video/quicktime | video/webm | video/x-msvideo
    size_bytes     BIGINT,
    duration_sec   DOUBLE PRECISION,            -- 원본 영상 길이 (ffprobe)
    frame_count    INTEGER,                     -- 실제 추출된 프레임 수 (BIZ-112, 기본 6)
    source_path    TEXT,                        -- 원본 영상 저장 경로 (재판독·감사용 보존)
    frames_path    TEXT,                        -- 추출 프레임 디렉토리
    prompt         TEXT,                        -- 판독 지시 (NULL 이면 기본 프롬프트)
    status         VARCHAR(30) NOT NULL,        -- PENDING/RUNNING/COMPLETED/FAILED
    result         TEXT,                        -- VLM 판독 결과 (평문)
    result_json    JSONB,                       -- structured output 사용 시 (CR-007)
    elapsed_sec    DOUBLE PRECISION,            -- 추출+판독 총 소요
    error_message  TEXT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_vj_status  ON vision_jobs(status);
CREATE INDEX idx_vj_created ON vision_jobs(created_at DESC);
