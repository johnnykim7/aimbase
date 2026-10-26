-- CR-160: 음성 변환이 지어낸 글로 보이는 구간을 표시하는 기준값과 문구
-- 0.6 은 개발기 bp-office 녹음 측정에서 말이 있는 녹음 0.52 이하, 소리 없는 녹음 0.76 이상 사이에 둔 값이다.
-- 「감사합니다」처럼 실제 말에도 흔한 글은 문구에 넣지 않고 소리 없음 기준으로 거른다.
-- ON CONFLICT DO NOTHING: 운영에서 바꾼 값은 덮지 않는다.

INSERT INTO global_config (config_key, config_value, description, is_encrypted, updated_by, updated_at)
VALUES
    ('stt.suspect-no-speech-prob', '0.6', '변환 구간의 소리 없음 확률이 이 값 이상이고 글이 있으면 NO_SPEECH 로 표시한다. 비우면 표시하지 않는다 (CR-160)', false, 'system', NOW()),
    ('stt.suspect-phrases', '자막 제공,자막 by,한글자막,시청해 주셔서 감사합니다,구독과 좋아요,구독 좋아요,MBC 뉴스,다음 영상에서 만나요', '음성 변환이 알아듣지 못할 때 흔히 지어내는 문구. 쉼표로 나누고 띄어쓰기와 문장부호는 비교에서 뺀다. 구간 글에 담기면 KNOWN_PHRASE 로 표시한다. 비우면 표시하지 않는다 (CR-160)', false, 'system', NOW())
ON CONFLICT (config_key) DO NOTHING;
