import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { platformApi } from "../api/platform";
import type { SettingItem } from "../api/platform";

export const usePlatformSettings = (category?: string) =>
  useQuery({
    queryKey: ["platformSettings", { category }],
    queryFn: () =>
      platformApi.getSettings(category).then((r) => r.data.data as Record<string, SettingItem[]>),
    retry: false,
  });

export const useUpdatePlatformSettings = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (updates: { key: string; value: string }[]) =>
      platformApi.updateSettings(updates),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["platformSettings"] }),
  });
};

export const useEvictSettingsCache = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => platformApi.evictSettingsCache(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["platformSettings"] }),
  });
};
