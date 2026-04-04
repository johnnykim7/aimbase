export interface ConnectionGroupMember {
  connection_id: string;
  connection_name?: string;
  priority: number;
  weight: number;
  status?: string;
  circuit_breaker_state?: string;
  usage_count?: number;
}

export interface ConnectionGroup {
  id: string;
  name: string;
  adapter: string;
  strategy: "PRIORITY" | "ROUND_ROBIN" | "LEAST_USED";
  members: ConnectionGroupMember[];
  is_default: boolean;
  is_active: boolean;
  created_at?: string;
  updated_at?: string;
}

export interface ConnectionGroupRequest {
  id: string;
  name: string;
  adapter: string;
  strategy: string;
  members: { connection_id: string; priority: number; weight: number }[];
  isDefault?: boolean;
}

export interface GroupTestResult {
  connection_id: string;
  ok: boolean;
  latencyMs?: number;
  error?: string;
}
