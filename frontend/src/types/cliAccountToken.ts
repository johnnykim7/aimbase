// CR-196: 실행기 계정 폴더 하나의 1년 토큰 상태. 서버 응답 칸 이름을 그대로 쓴다.
export interface AccountTokenRow {
  tenant_id?: string;
  tenant_name?: string;
  agent_name: string;
  config_dir: string;
  managed_by: "tenant" | "platform";
  present: boolean;
  issued_at?: string;
  expires_at?: string;
  days_left?: number;
  reachable: boolean;
  connection_ids: string[];
  checked_at?: string;
}

export interface TokenRecipients {
  emails: string[];
  phones: string[];
}
