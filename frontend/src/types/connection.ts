export interface Connection {
  id: string;
  name: string;
  adapter: string;
  type: string;
  config: Record<string, unknown>;
  status?: "connected" | "disconnected" | "error" | "warning";
  lastHealthCheckAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface ConnectionRequest {
  id: string;
  name: string;
  adapter: string;
  type: string;
  config: Record<string, unknown>;
}

export interface TestResult {
  ok: boolean;
  latencyMs: number;
  message?: string;
}
