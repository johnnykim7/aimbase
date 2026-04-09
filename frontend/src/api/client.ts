import axios from "axios";
import { getProjectId } from "../store/projectContext";

const DEV_TOKEN = localStorage.getItem("access_token") ?? "";

export const apiClient = axios.create({
  baseURL: "/api/v1",
  timeout: 15000,
  headers: {
    "Content-Type": "application/json",
  },
});

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem("access_token") || DEV_TOKEN;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  // CR-027: 로그인 응답에서 저장한 tenant_id 동적 반영
  // 로그인/인증 요청에는 X-Tenant-Id를 보내지 않음 (이메일로 자동 resolve)
  const isAuthRequest = config.url?.startsWith("/auth/");
  const tenantId = localStorage.getItem("tenant_id");
  if (tenantId && !isAuthRequest) {
    config.headers["X-Tenant-Id"] = tenantId;
  }
  // CR-021: 프로젝트 스코핑 헤더
  const projectId = getProjectId();
  if (projectId) {
    config.headers["X-Project-Id"] = projectId;
  }
  return config;
});

apiClient.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      // 토큰 만료 시 로그인 페이지로
      if (window.location.pathname !== "/login") {
        localStorage.removeItem("access_token");
        localStorage.removeItem("tenant_id");
        window.location.href = "/login";
      }
    }
    const message =
      err.response?.data?.error ?? err.message ?? "Unknown error";
    return Promise.reject(new Error(message));
  }
);
