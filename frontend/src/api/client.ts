import axios from "axios";

export const apiClient = axios.create({
  baseURL: "/api/v1",
  timeout: 15000,
  headers: {
    "Content-Type": "application/json",
  },
});

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem("access_token");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  // 로그인/인증 요청에는 X-Tenant-Id를 보내지 않음 (이메일로 자동 resolve)
  const isAuthRequest = config.url?.startsWith("/auth/");
  const tenantId = localStorage.getItem("tenant_id");
  if (tenantId && !isAuthRequest) {
    config.headers["X-Tenant-Id"] = tenantId;
  }
  return config;
});

apiClient.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      if (window.location.pathname !== "/login") {
        localStorage.removeItem("access_token");
        localStorage.removeItem("tenant_id");
        window.location.href = "/login";
      }
    }
    const message =
      err.response?.data?.message ?? err.response?.data?.error ?? err.message ?? "Unknown error";
    return Promise.reject(new Error(message));
  }
);
