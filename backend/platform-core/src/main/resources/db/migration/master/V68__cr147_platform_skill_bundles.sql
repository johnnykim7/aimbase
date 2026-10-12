-- CR-147: 플랫폼 공용 스킬 묶음. 모든 테넌트가 같이 쓴다.
-- 테넌트 전용(Tenant V72 skill_bundles)과 같은 모양이고 이름 앞에 platform_ 만 붙는다.
-- 이름이 겹치면 테넌트 전용이 먼저다(BIZ-120).

CREATE TABLE platform_skill_bundles (
    id              UUID PRIMARY KEY,
    name            VARCHAR(64) NOT NULL UNIQUE,
    description     TEXT NOT NULL,
    latest_version  INT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE platform_skill_bundle_versions (
    id              UUID PRIMARY KEY,
    bundle_id       UUID NOT NULL REFERENCES platform_skill_bundles(id),
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
    CONSTRAINT uq_platform_skill_bundle_versions_bundle_version UNIQUE (bundle_id, version)
);

CREATE TABLE platform_skill_bundle_files (
    version_id  UUID NOT NULL REFERENCES platform_skill_bundle_versions(id),
    path        VARCHAR(512) NOT NULL,
    size_bytes  BIGINT NOT NULL,
    sha256      CHAR(64) NOT NULL,
    is_text     BOOLEAN NOT NULL,
    content     BYTEA NOT NULL,
    PRIMARY KEY (version_id, path)
);

CREATE INDEX idx_platform_skill_bundles_active ON platform_skill_bundles(is_active) WHERE is_active = true;
CREATE INDEX idx_platform_skill_bundle_versions_bundle ON platform_skill_bundle_versions(bundle_id, version DESC);
