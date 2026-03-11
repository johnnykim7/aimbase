import { useQuery } from "@tanstack/react-query";
import { monitoringApi } from "../api/monitoring";

export const useModels = () =>
  useQuery({
    queryKey: ["models"],
    queryFn: () => monitoringApi.models().then((r) => r.data.data ?? []),
    retry: false,
  });

export const useActiveRouting = () =>
  useQuery({
    queryKey: ["activeRouting"],
    queryFn: () => monitoringApi.activeRouting().then((r) => r.data.data),
    retry: false,
  });
