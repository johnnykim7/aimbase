import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type { DomainAppConfig } from "../types/domain";

export const domainConfigsApi = {
  list: () =>
    apiClient.get<ApiResponse<DomainAppConfig[]>>("/domain-configs"),

  get: (domainApp: string) =>
    apiClient.get<ApiResponse<DomainAppConfig>>(`/domain-configs/${domainApp}`),

  create: (data: Omit<DomainAppConfig, "id" | "createdAt" | "updatedAt">) =>
    apiClient.post<ApiResponse<DomainAppConfig>>("/domain-configs", data),

  update: (domainApp: string, data: Partial<Omit<DomainAppConfig, "id" | "createdAt" | "updatedAt">>) =>
    apiClient.put<ApiResponse<DomainAppConfig>>(`/domain-configs/${domainApp}`, data),

  remove: (domainApp: string) =>
    apiClient.delete(`/domain-configs/${domainApp}`),
};
