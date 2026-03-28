import { useLayoutEffect } from "react";
import { cn } from "@/lib/utils";
import { useSetPageActions } from "./AppShell";

interface PageProps {
  /** 헤더 우측 액션 버튼 (동적 — context로 AppShell에 전달) */
  actions?: React.ReactNode;
  children: React.ReactNode;
  /** content 영역 패딩 제거 */
  noPadding?: boolean;
}

export function Page({ actions, children, noPadding }: PageProps) {
  const setActions = useSetPageActions();

  useLayoutEffect(() => {
    setActions(actions ?? null);
    return () => setActions(null);
  });

  return (
    <div className={cn("h-full", noPadding ? "" : "p-7")}>
      {children}
    </div>
  );
}
