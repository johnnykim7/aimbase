export const STRATEGIES = [
  { id: "PRIORITY", label: "Priority", desc: "우선순위 고정 - 장애 시 다음 커넥션으로 전환" },
  { id: "ROUND_ROBIN", label: "Round Robin", desc: "순환 분산 - 요청마다 다음 커넥션 사용" },
  { id: "LEAST_USED", label: "Least Used", desc: "사용량 기반 - 호출 수 가장 적은 커넥션 선택" },
];

export const strategyLabel = (id: string) => STRATEGIES.find((s) => s.id === id)?.label ?? id;
