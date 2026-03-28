/**
 * CR-021: 프로젝트 컨텍스트 — 선택된 프로젝트 ID를 전역 관리.
 * localStorage에 저장하여 새로고침 후에도 유지.
 * apiClient 인터셉터에서 X-Project-Id 헤더로 자동 주입.
 */

const STORAGE_KEY = "aimbase_project_id";

let listeners: Array<() => void> = [];

export function getProjectId(): string | null {
  return localStorage.getItem(STORAGE_KEY);
}

export function setProjectId(id: string | null) {
  if (id) {
    localStorage.setItem(STORAGE_KEY, id);
  } else {
    localStorage.removeItem(STORAGE_KEY);
  }
  listeners.forEach((fn) => fn());
}

export function subscribe(listener: () => void) {
  listeners.push(listener);
  return () => {
    listeners = listeners.filter((fn) => fn !== listener);
  };
}
