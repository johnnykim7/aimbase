-- CR-149: 워크플로우 스튜디오의 설명 칸을 저장한다. 플랫폼 공용 워크플로우에는 처음부터 있었다.
ALTER TABLE workflows ADD COLUMN IF NOT EXISTS description TEXT;
