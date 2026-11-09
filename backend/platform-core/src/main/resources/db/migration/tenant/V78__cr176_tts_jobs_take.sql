-- CR-176: 같은 글 다른 차례.
--
-- 같은 음색, 같은 글은 씨앗이 고정이라 늘 같은 소리다(BIZ-134). 사람이 애매하다고 한 소리만 글을 그대로 두고
-- 다시 만들 수 있게 차례를 두고, 같은 글 찾기(idx_tts_jobs_voice_text 로 좁힌 뒤)도 차례가 같을 때만 돌려준다.
-- 쌓인 행은 차례 0 이 되어 지금 소리 그대로 재사용된다.
-- V77 은 CR-175 몫이다. 테넌트 마이그레이션은 outOfOrder 로 돈다.

ALTER TABLE tts_jobs ADD COLUMN take INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tts_jobs ADD CONSTRAINT chk_tts_jobs_take CHECK (take >= 0);
