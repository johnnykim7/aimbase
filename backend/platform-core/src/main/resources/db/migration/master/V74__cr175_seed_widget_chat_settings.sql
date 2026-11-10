-- CR-175: 채팅 위젯 대화 기본기 런타임 설정과, 코드에 박혀 있던 기본 모델을 설정으로 옮긴다.
-- llm.default-model 처음 값은 지금 코드 값과 같게 넣어 동작이 바뀌지 않는다.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('llm.default-model', 'anthropic/claude-sonnet-4-5', '연결도 모델도 정해지지 않았을 때 쓰는 모델 (CR-175, BIZ-150)', false, 'system', NOW()),
    ('widget.feedback-reasons', '[{"code":"WRONG_INFO","label":"틀린 정보"},{"code":"NO_LOOKUP","label":"조회를 안 함"},{"code":"TOO_LONG","label":"너무 김"},{"code":"OFF_TOPIC","label":"질문과 다름"},{"code":"OTHER","label":"기타"}]', '위젯 답 평가의 아쉬워요 이유. 저장은 code 만 한다 (CR-175, BIZ-149)', false, 'system', NOW()),
    ('widget.feedback-comment-max-chars', '500', '위젯 답 평가 한 줄 의견 최대 글자 수 (CR-175, BIZ-149)', false, 'system', NOW()),
    ('widget.follow-ups-enabled', 'true', '위젯 후속 질문과 대화 제목 만들기 사용 (CR-175, BIZ-151)', false, 'system', NOW()),
    ('widget.follow-ups-count', '3', '위젯 후속 질문 개수 (CR-175, BIZ-151)', false, 'system', NOW()),
    ('widget.follow-ups-model', '', '후속 질문과 제목을 만들 모델. 비면 그 연결 기본 모델 (CR-175, BIZ-151)', false, 'system', NOW()),
    ('widget.follow-ups-timeout-ms', '8000', '후속 질문 호출 시간 한도(밀리초) (CR-175, BIZ-151)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
