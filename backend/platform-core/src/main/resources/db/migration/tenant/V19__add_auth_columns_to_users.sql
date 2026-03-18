-- Sprint 22: 인증/RBAC — users 테이블에 비밀번호, 리프레시 토큰 해시, 마지막 로그인 컬럼 추가
ALTER TABLE users ADD COLUMN password_hash VARCHAR(200);
ALTER TABLE users ADD COLUMN refresh_token_hash VARCHAR(200);
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ;
