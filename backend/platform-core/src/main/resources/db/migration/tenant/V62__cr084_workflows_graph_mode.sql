-- CR-084 P3: 워크플로우 그래프 실행 모드 (graph_mode)
--
-- 'dag'    = 기존 동작. Kahn 위상정렬 후 단일 1-pass 실행 (하위호환 기본값).
-- 'cyclic' = 워크리스트 스케줄러. 임의 노드 간 순환 + ROUTER N-way 분기 추종,
--            run 당 글로벌 step budget 으로 무한루프 방어.
--
-- 기존 워크플로우는 NULL → 애플리케이션에서 'dag' 로 처리하므로 동작 변화 없음.
-- (DEFAULT 'dag' 로 신규 행은 명시 지정, 기존 행은 NULL=dag 동등 취급)

ALTER TABLE workflows ADD COLUMN IF NOT EXISTS graph_mode VARCHAR(20) DEFAULT 'dag';

COMMENT ON COLUMN workflows.graph_mode IS
    'CR-084 워크플로우 실행 모드: dag(기존 1-pass, 기본) | cyclic(워크리스트 스케줄러 + step budget). NULL=dag.';
