import axios from "axios";
import { apiClient } from "./client";
import type { ApiResponse } from "../types/api";
import type { AccountTokenRow, TokenRecipients } from "../types/cliAccountToken";

const tokenPath = (connectionId: string) => `/connections/${encodeURIComponent(connectionId)}/account-token`;

// 넣기는 실행기가 그 값으로 CLI 에 확인 질문을 던지고 답을 기다린다. 기다리는 한도는 서버의 연결 호출 시간 제한이
// 정하므로 화면 쪽 기본 제한(15초)에 끊기지 않게 제한을 두지 않는다.
const PUT_OPTIONS = { timeout: 0 };

/**
 * 플랫폼 화면이 그 테넌트로 임퍼소네이션해 받은 토큰으로 한 번 부를 때 쓴다. 공용 클라이언트는 저장해 둔 관리자 토큰을
 * 덮어 붙이고 401 이면 관리자를 로그아웃시키므로, 받은 토큰만 싣고 브라우저 저장소를 건드리지 않는 클라이언트를 따로 만든다.
 */
function impersonatedClient(accessToken: string, tenantId: string) {
  const client = axios.create({
    baseURL: "/api/v1",
    timeout: 0,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
      "X-Tenant-Id": tenantId,
    },
  });
  client.interceptors.response.use(
    (res) => res,
    (err) => Promise.reject(new Error(err.response?.data?.error ?? err.message ?? "Unknown error")),
  );
  return client;
}

export const cliAccountTokensApi = {
  list: () => apiClient.get<ApiResponse<AccountTokenRow[]>>("/cli-account-tokens"),

  getForConnection: (connectionId: string) => apiClient.get<ApiResponse<AccountTokenRow>>(tokenPath(connectionId)),

  putForConnection: (connectionId: string, token: string) =>
    apiClient.put<ApiResponse<AccountTokenRow>>(tokenPath(connectionId), { token }, PUT_OPTIONS),

  putAsTenant: (accessToken: string, tenantId: string, connectionId: string, token: string) =>
    impersonatedClient(accessToken, tenantId).put<ApiResponse<AccountTokenRow>>(tokenPath(connectionId), { token }),

  getRecipients: () => apiClient.get<ApiResponse<TokenRecipients>>("/cli-token-recipients"),

  putRecipients: (data: TokenRecipients) => apiClient.put<ApiResponse<TokenRecipients>>("/cli-token-recipients", data),

  platformList: () => apiClient.get<ApiResponse<AccountTokenRow[]>>("/platform/cli-account-tokens"),
};
