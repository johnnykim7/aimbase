-- CR-177 BIZ-161: 아쉬워요 이유에 영어 이름(labels.en)을 더한다.
-- 관리자가 이미 고친 값은 덮지 않도록 V74 처음 값 그대로일 때만 바꾼다.
UPDATE global_config
SET config_value = '[{"code":"WRONG_INFO","label":"틀린 정보","labels":{"en":"Incorrect information"}},{"code":"NO_LOOKUP","label":"조회를 안 함","labels":{"en":"Didn''t look it up"}},{"code":"TOO_LONG","label":"너무 김","labels":{"en":"Too long"}},{"code":"OFF_TOPIC","label":"질문과 다름","labels":{"en":"Not what I asked"}},{"code":"OTHER","label":"기타","labels":{"en":"Other"}}]',
    description = '위젯 답 평가의 아쉬워요 이유. 저장은 code 만 한다. labels 는 화면 언어별 이름 (CR-175 BIZ-149, CR-177 BIZ-161)',
    updated_by = 'system',
    updated_at = NOW()
WHERE config_key = 'widget.feedback-reasons'
  AND config_value = '[{"code":"WRONG_INFO","label":"틀린 정보"},{"code":"NO_LOOKUP","label":"조회를 안 함"},{"code":"TOO_LONG","label":"너무 김"},{"code":"OFF_TOPIC","label":"질문과 다름"},{"code":"OTHER","label":"기타"}]';
