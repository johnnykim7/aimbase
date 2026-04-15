import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";

export interface WorkspaceItem {
  name: string;
  path: string;
  modifiedAt: string;
}

export const workspacesApi = {
  list: () => apiClient.get<ApiResponse<WorkspaceItem[]>>("/workspaces"),
};
