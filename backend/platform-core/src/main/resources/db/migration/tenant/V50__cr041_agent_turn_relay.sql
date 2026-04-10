-- CR-041 후속: TURN 릴레이 주소 컬럼 추가
ALTER TABLE agent_registry
    ADD COLUMN IF NOT EXISTS turn_relay_address VARCHAR(255);

COMMENT ON COLUMN agent_registry.turn_relay_address
    IS 'TURN 릴레이 주소 (IP:port). 직접 연결 실패 시 폴백용.';
