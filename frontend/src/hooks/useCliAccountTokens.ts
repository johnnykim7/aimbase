import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { cliAccountTokensApi } from "../api/cliAccountTokens";
import { platformApi } from "../api/platform";
import type { AccountTokenRow, TokenRecipients } from "../types/cliAccountToken";
import { IMPERSONATION_FAILED } from "../components/connections/accountTokenState";

const LIST_KEY = ["cliAccountTokens"];
const PLATFORM_LIST_KEY = ["platformCliAccountTokens"];
const RECIPIENTS_KEY = ["cliTokenRecipients"];
const connectionKey = (id: string) => ["cliAccountToken", id];

export const useCliAccountTokens = () =>
  useQuery({
    queryKey: LIST_KEY,
    queryFn: () => cliAccountTokensApi.list().then((r) => r.data.data ?? []),
    retry: false,
  });

export const useConnectionAccountToken = (connectionId: string | null) =>
  useQuery({
    queryKey: connectionKey(connectionId ?? ""),
    queryFn: () => cliAccountTokensApi.getForConnection(connectionId as string).then((r) => r.data.data),
    enabled: !!connectionId,
    retry: false,
  });

export const usePutConnectionAccountToken = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ connectionId, token }: { connectionId: string; token: string }) =>
      cliAccountTokensApi.putForConnection(connectionId, token).then((r) => r.data.data),
    onSuccess: (row, { connectionId }) => {
      qc.setQueryData(connectionKey(connectionId), row);
      qc.invalidateQueries({ queryKey: LIST_KEY });
    },
  });
};

export const useTokenRecipients = () =>
  useQuery({
    queryKey: RECIPIENTS_KEY,
    queryFn: () => cliAccountTokensApi.getRecipients().then((r) => r.data.data),
    retry: false,
  });

export const useUpdateTokenRecipients = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: TokenRecipients) => cliAccountTokensApi.putRecipients(data).then((r) => r.data.data),
    onSuccess: (data) => qc.setQueryData(RECIPIENTS_KEY, data),
  });
};

export const usePlatformCliAccountTokens = () =>
  useQuery({
    queryKey: PLATFORM_LIST_KEY,
    queryFn: () => cliAccountTokensApi.platformList().then((r) => r.data.data ?? []),
    retry: false,
  });

/**
 * 플랫폼 화면의 넣기. 그 테넌트로 임퍼소네이션해 받은 토큰을 이 호출 한 번에만 쓰고 버린다(브라우저 저장소에 두지 않는다).
 * 성공하면 그 줄을 응답 값으로 바로 바꾼다.
 */
export const usePutPlatformAccountToken = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ row, token }: { row: AccountTokenRow; token: string }) => {
      let access: { access_token: string; tenant_id: string };
      try {
        access = await platformApi.impersonateTenant(row.tenant_id as string).then((r) => r.data.data);
      } catch (e) {
        throw new Error(`${IMPERSONATION_FAILED}${(e as Error).message}`);
      }
      return cliAccountTokensApi
        .putAsTenant(access.access_token, access.tenant_id, row.connection_ids[0], token)
        .then((r) => r.data.data);
    },
    onSuccess: (fresh, { row }) => {
      qc.setQueryData<AccountTokenRow[]>(PLATFORM_LIST_KEY, (rows) =>
        rows?.map((r) =>
          r.tenant_id === row.tenant_id && r.agent_name === row.agent_name && r.config_dir === row.config_dir
            ? { ...r, ...fresh, tenant_id: r.tenant_id, tenant_name: r.tenant_name }
            : r,
        ),
      );
      // 확인 시각은 서버가 Master 에 적은 값으로 다시 받는다.
      qc.invalidateQueries({ queryKey: PLATFORM_LIST_KEY });
    },
  });
};
