import axios from "axios";

export const apiClient = axios.create({
  baseURL: "/api/v1",
  timeout: 15000,
  headers: {
    "Content-Type": "application/json",
    "X-Tenant-Id": "demo",
  },
});

apiClient.interceptors.response.use(
  (res) => res,
  (err) => {
    const message =
      err.response?.data?.error ?? err.message ?? "Unknown error";
    return Promise.reject(new Error(message));
  }
);
