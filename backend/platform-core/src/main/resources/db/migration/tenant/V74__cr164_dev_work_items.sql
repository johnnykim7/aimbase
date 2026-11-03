-- CR-164: 개발 작업 상태 원천.
--
-- dev_work_items 는 작업 하나의 지금 상태, dev_work_item_changes 는 상태가 바뀔 때마다 한 줄이다.
-- 바깥에 알릴 때 보내는 바뀐 시각(reported_at)은 변경 줄의 changed_at 이다. 다시 보내도 같은 값을
-- 써야 받는 쪽이 중복을 거를 수 있어서 변경을 줄로 남긴다.

CREATE TABLE dev_work_items (
    work_item_id          VARCHAR(100)  PRIMARY KEY,
    feature_name          VARCHAR(300)  NOT NULL,
    stage                 VARCHAR(50)   NOT NULL,
    status                VARCHAR(20)   NOT NULL,
    evidence_url          VARCHAR(2000),
    status_changed_at     TIMESTAMPTZ   NOT NULL,
    last_workflow_run_id  UUID,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_dev_work_items_status_changed_at ON dev_work_items(status_changed_at DESC);

CREATE TABLE dev_work_item_changes (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    work_item_id     VARCHAR(100)  NOT NULL REFERENCES dev_work_items(work_item_id),
    stage            VARCHAR(50)   NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    evidence_url     VARCHAR(2000),
    changed_at       TIMESTAMPTZ   NOT NULL,
    source           VARCHAR(20)   NOT NULL,
    workflow_run_id  UUID
);

CREATE INDEX idx_dev_work_item_changes_work_item ON dev_work_item_changes(work_item_id, changed_at DESC);
