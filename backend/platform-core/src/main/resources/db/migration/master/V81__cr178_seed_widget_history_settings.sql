-- CR-178: 위젯 지난 대화 목록과 프로젝트 런타임 설정.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('widget.history-page-size', '30', '위젯 대화 목록 한 쪽 줄 수. 최대 100 (CR-178, BIZ-170)', false, 'system', NOW()),
    ('widget.history-turns-max', '100', '위젯 이어보기로 보이는 질문 묶음 최대 수 (CR-178, BIZ-174)', false, 'system', NOW()),
    ('widget.project-instructions-max-chars', '4000', '위젯 프로젝트 지시문 최대 글자 수 (CR-178, BIZ-172)', false, 'system', NOW()),
    ('widget.project-files-max-count', '20', '위젯 프로젝트 하나의 자료 최대 개수 (CR-178, BIZ-173)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
