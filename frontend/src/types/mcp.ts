export interface MCPToolDef {
  name: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
}

export interface MCPServer {
  id: string;
  name: string;
  transport: "http" | "stdio" | "sse";
  config: Record<string, unknown>;
  status?: "connected" | "disconnected" | "error";
  autoStart?: boolean;
  toolsCache?: MCPToolDef[];
  discoveredTools?: MCPToolDef[];
  toolCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface MCPServerRequest {
  id: string;
  name: string;
  transport: string;
  config: Record<string, unknown>;
  autoStart?: boolean;
}

export interface DiscoverResult {
  serverId: string;
  toolCount: number;
  tools: MCPToolDef[];
}
