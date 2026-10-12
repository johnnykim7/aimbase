-- CR-147: 테넌트 전용 스킬 묶음 (Claude Code 스킬 폴더 zip 을 그대로 보관)
-- 플랫폼 공용은 Master V68 의 platform_ 테이블이 같은 모양으로 가진다.
--
-- 파일 내용은 BYTEA 로 DB 에 둔다. 판과 파일이 한 트랜잭션으로 묶이고 저장소 종류(로컬, S3)를 타지 않는다.
-- 묶음 상한은 global_config skills.bundle-max-bytes 로 막는다.
-- 판은 고치거나 지우지 않는다(BIZ-119). 삭제는 skill_bundles.is_active = false.

CREATE TABLE skill_bundles (
    id              UUID PRIMARY KEY,
    name            VARCHAR(64) NOT NULL UNIQUE,
    description     TEXT NOT NULL,
    latest_version  INT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE skill_bundle_versions (
    id              UUID PRIMARY KEY,
    bundle_id       UUID NOT NULL REFERENCES skill_bundles(id),
    version         INT NOT NULL,
    description     TEXT NOT NULL,
    frontmatter     JSONB NOT NULL,
    instructions    TEXT NOT NULL,
    file_count      INT NOT NULL,
    total_bytes     BIGINT NOT NULL,
    content_sha256  CHAR(64) NOT NULL,
    warnings        JSONB NOT NULL DEFAULT '[]',
    uploaded_by     VARCHAR(100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_skill_bundle_versions_bundle_version UNIQUE (bundle_id, version)
);

CREATE TABLE skill_bundle_files (
    version_id  UUID NOT NULL REFERENCES skill_bundle_versions(id),
    path        VARCHAR(512) NOT NULL,
    size_bytes  BIGINT NOT NULL,
    sha256      CHAR(64) NOT NULL,
    is_text     BOOLEAN NOT NULL,
    content     BYTEA NOT NULL,
    PRIMARY KEY (version_id, path)
);

CREATE INDEX idx_skill_bundles_active ON skill_bundles(is_active) WHERE is_active = true;
CREATE INDEX idx_skill_bundle_versions_bundle ON skill_bundle_versions(bundle_id, version DESC);
