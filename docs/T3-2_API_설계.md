# T3-2. API 설계서

> 설계 버전: 2.0 | 최종 수정: 2026-03-12 | 관련 CR: CR-002

> **프로젝트**: Aimbase
> **작성일**: 2026-03-10 (역설계)

---

## 공통 사항

### API 기본 규격
- **Base URL**: `/api/v1`
- **인증**: JWT Bearer Token (향후 완전 구현)
- **응답 래퍼**: `ApiResponse<T>` → `{success, data, error, pagination}`
- **페이지네이션**: `{page, size, totalElements, totalPages}`
- **에러 코드**: HTTP 상태 코드 (400, 401, 403, 404, 409, 500)
- **날짜 형식**: ISO 8601 (YYYY-MM-DDTHH:mm:ssZ)

### 공통 응답 구조
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "pagination": { "page": 0, "size": 20, "totalElements": 100, "totalPages": 5 }
}
```

---

## 채팅 (Chat)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| POST | `/chat/completions` | LLM 채팅 완료 (동기/SSE 스트리밍) | 🔒 |

---

## 연결 (Connections)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/connections` | 연결 목록 조회 (페이지네이션, type 필터) | 🔒 |
| POST | `/connections` | 연결 생성 | 🔒 |
| GET | `/connections/{id}` | 연결 상세 조회 | 🔒 |
| PUT | `/connections/{id}` | 연결 수정 | 🔒 |
| DELETE | `/connections/{id}` | 연결 삭제 | 🔒 |
| POST | `/connections/{id}/test` | 연결 헬스체크 | 🔒 |

---

## MCP 서버 (MCP Servers)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/mcp-servers` | MCP 서버 목록 조회 | 🔒 |
| POST | `/mcp-servers` | MCP 서버 등록 | 🔒 |
| GET | `/mcp-servers/{id}` | MCP 서버 상세 조회 | 🔒 |
| PUT | `/mcp-servers/{id}` | MCP 서버 수정 | 🔒 |
| DELETE | `/mcp-servers/{id}` | MCP 서버 삭제 | 🔒 |
| POST | `/mcp-servers/{id}/discover` | 도구 탐색 | 🔒 |
| POST | `/mcp-servers/{id}/disconnect` | 서버 연결 해제 | 🔒 |

---

## 스키마 (Schemas)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/schemas` | 스키마 목록 조회 | 🔒 |
| POST | `/schemas` | 스키마 생성 | 🔒 |
| GET | `/schemas/{id}/{version}` | 스키마 버전별 조회 | 🔒 |
| DELETE | `/schemas/{id}/{version}` | 스키마 삭제 | 🔒 |
| POST | `/schemas/{id}/{version}/validate` | 데이터 유효성 검증 | 🔒 |

---

## 정책 (Policies)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/policies` | 정책 목록 조회 (domain 필터) | 🔒 |
| POST | `/policies` | 정책 생성 | 🔒 |
| GET | `/policies/{id}` | 정책 상세 조회 | 🔒 |
| PUT | `/policies/{id}` | 정책 수정 | 🔒 |
| DELETE | `/policies/{id}` | 정책 삭제 | 🔒 |
| PATCH | `/policies/{id}/activate` | 정책 활성화/비활성화 (query: active) | 🔒 |
| POST | `/policies/simulate` | 정책 시뮬레이션 | 🔒 |

---

## 프롬프트 (Prompts)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/prompts` | 프롬프트 목록 조회 | 🔒 |
| POST | `/prompts` | 프롬프트 생성 | 🔒 |
| GET | `/prompts/{id}/{version}` | 프롬프트 버전별 조회 | 🔒 |
| PUT | `/prompts/{id}/{version}` | 프롬프트 수정 | 🔒 |
| DELETE | `/prompts/{id}/{version}` | 프롬프트 삭제 | 🔒 |
| POST | `/prompts/{id}/{version}/test` | 프롬프트 테스트 렌더링 | 🔒 |

---

## 라우팅 (Routing)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/routing` | 라우팅 설정 목록 조회 | 🔒 |
| GET | `/routing/active` | 활성 라우팅 규칙 조회 | 🔒 |
| POST | `/routing` | 라우팅 설정 생성 | 🔒 |
| GET | `/routing/{id}` | 라우팅 설정 상세 조회 | 🔒 |
| PUT | `/routing/{id}` | 라우팅 설정 수정 | 🔒 |
| DELETE | `/routing/{id}` | 라우팅 설정 삭제 | 🔒 |

---

## 워크플로우 (Workflows)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/workflows` | 워크플로우 목록 조회 (domain 필터) | 🔒 |
| POST | `/workflows` | 워크플로우 생성 | 🔒 |
| GET | `/workflows/{id}` | 워크플로우 상세 조회 | 🔒 |
| PUT | `/workflows/{id}` | 워크플로우 수정 | 🔒 |
| DELETE | `/workflows/{id}` | 워크플로우 삭제 | 🔒 |
| POST | `/workflows/{id}/run` | 워크플로우 실행 (비동기) | 🔒 |
| POST | `/workflows/runs/{runId}/approve` | 워크플로우 승인 처리 | 🔒 |
| GET | `/workflows/{id}/runs` | 실행 이력 조회 | 🔒 |
| GET | `/workflows/{id}/runs/{runId}` | 실행 상세 조회 | 🔒 |

---

## 지식소스 (Knowledge Sources)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/knowledge-sources` | 지식소스 목록 조회 (type 필터) | 🔒 |
| POST | `/knowledge-sources` | 지식소스 생성 | 🔒 |
| GET | `/knowledge-sources/{id}` | 지식소스 상세 조회 | 🔒 |
| PUT | `/knowledge-sources/{id}` | 지식소스 수정 | 🔒 |
| DELETE | `/knowledge-sources/{id}` | 지식소스 삭제 | 🔒 |
| POST | `/knowledge-sources/{id}/sync` | 인제스션 실행 | 🔒 |
| POST | `/knowledge-sources/search` | 벡터 검색 | 🔒 |
| GET | `/knowledge-sources/{id}/ingestion-logs` | 인제스션 로그 조회 | 🔒 |

---

## 검색 설정 (Retrieval Config)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/retrieval-config` | 검색 설정 목록 조회 | 🔒 |
| POST | `/retrieval-config` | 검색 설정 생성 | 🔒 |
| GET | `/retrieval-config/{id}` | 검색 설정 상세 조회 | 🔒 |
| PUT | `/retrieval-config/{id}` | 검색 설정 수정 | 🔒 |
| DELETE | `/retrieval-config/{id}` | 검색 설정 삭제 | 🔒 |

---

## 관리 (Admin)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/admin/dashboard` | 대시보드 통계 | 🔒 |
| GET | `/admin/action-logs` | 액션 로그 조회 | 🔒 |
| GET | `/admin/audit-logs` | 감사 로그 조회 | 🔒 |
| GET | `/admin/usage` | 사용량 통계 조회 | 🔒 |
| GET | `/admin/approvals` | 승인 대기 목록 | 🔒 |
| POST | `/admin/approvals/{id}/approve` | 승인 처리 | 🔒 |
| POST | `/admin/approvals/{id}/reject` | 거부 처리 | 🔒 |

---

## 사용자 (Users)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/users` | 사용자 목록 조회 | 🔒 |
| POST | `/users` | 사용자 생성 | 🔒 |
| GET | `/users/{id}` | 사용자 상세 조회 | 🔒 |
| PUT | `/users/{id}` | 사용자 수정 | 🔒 |
| DELETE | `/users/{id}` | 사용자 비활성화 | 🔒 |
| POST | `/users/{id}/api-key` | API 키 재생성 | 🔒 |

---

## 역할 (Roles)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/roles` | 역할 목록 조회 | 🔒 |
| POST | `/roles` | 역할 생성 | 🔒 |
| GET | `/roles/{id}` | 역할 상세 조회 | 🔒 |
| PUT | `/roles/{id}` | 역할 수정 | 🔒 |
| DELETE | `/roles/{id}` | 역할 삭제 | 🔒 |

---

## 모니터링 (Monitoring)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/models` | 모델 목록 조회 | 🔒 |
| GET | `/routing` | 활성 라우팅 설정 조회 | 🔒 |

---

## 플랫폼 관리 (Platform - Super Admin)

| 메서드 | 경로 | 설명 | 인증 |
|--------|------|------|------|
| GET | `/platform/tenants` | 테넌트 목록 조회 (status 필터) | 🔒 Super Admin |
| POST | `/platform/tenants` | 테넌트 생성 (자동 프로비저닝) | 🔒 Super Admin |
| GET | `/platform/tenants/{id}` | 테넌트 상세 조회 (구독+사용량 포함) | 🔒 Super Admin |
| PUT | `/platform/tenants/{id}` | 테넌트 수정 | 🔒 Super Admin |
| POST | `/platform/tenants/{id}/suspend` | 테넌트 일시정지 | 🔒 Super Admin |
| POST | `/platform/tenants/{id}/activate` | 테넌트 재활성화 | 🔒 Super Admin |
| DELETE | `/platform/tenants/{id}` | 테넌트 삭제 (디프로비저닝) | 🔒 Super Admin |
| GET | `/platform/subscriptions` | 구독 목록 조회 | 🔒 Super Admin |
| PUT | `/platform/subscriptions/{tenantId}` | 구독 수정 (쿼터 변경) | 🔒 Super Admin |
| GET | `/platform/usage` | 플랫폼 사용량 대시보드 | 🔒 Super Admin |

---

## Python MCP Server API (v2.0, CR-002)

> MCP 프로토콜 기반 도구(Tool) 정의. Spring MCP Client에서 직접 호출 (LLM 경유 불필요).
> Transport: stdio 또는 SSE (Docker 내부 통신)

### MCP Server 1: RAG Pipeline

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `ingest_document` | 문서 파싱(Unstructured) → 시맨틱 청킹 → 임베딩 → pgvector 저장 | PRD-052, PY-001, PY-002 |
| `search_hybrid` | 하이브리드 검색(BM25 + 벡터) + 리랭킹 | PRD-053, PY-003, PY-004 |
| `embed_texts` | 텍스트 배열을 임베딩 벡터로 변환 | PY-002 |
| `chunk_document` | 문서를 시맨틱 청크로 분할 (인제스션 미수행) | PY-001 |
| `rerank_results` | 검색 결과를 cross-encoder로 리랭킹 | PY-003 |
| `transform_query` | 쿼리 변환 (HyDE, Multi-Query) | PY-005 |

### MCP Server 2: Evaluation

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `evaluate_rag` | RAG 응답 품질 평가 (faithfulness, relevancy, context precision/recall) | PY-006 |
| `evaluate_llm_output` | LLM 출력 평가 (hallucination, toxicity, bias) | PY-007 |
| `compare_prompts` | 프롬프트 A/B 비교 회귀 테스트 | PY-008 |

### MCP Server 3: Safety

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `detect_pii` | PII 탐지 (한국어 포함 다국어) | PY-009 |
| `mask_pii` | PII 마스킹 처리 | PY-009 |
| `validate_output` | 출력 안전성/포맷 가드레일 검증 | PY-010 |

### MCP Server 4: Agent (향후)

| Tool 이름 | 설명 | 관련 기능ID |
|-----------|------|------------|
| `run_reasoning_chain` | 고급 추론 체인 실행 (reflection, plan_and_execute) | PY-011 |

---

## 작성 가이드

- **경로**: kebab-case 사용 (e.g., `/knowledge-sources`, `/mcp-servers`)
- **CRUD 패턴**: GET / → POST / → GET /{id} → PUT /{id} → DELETE /{id}
- **🔒 표시**: 인증 필요 엔드포인트
- **상세 스키마**: 데이터 모델(T3-1)에서 자동 도출, 별도 OpenAPI 스키마 미정의
- **Swagger**: `/swagger-ui.html`에서 자동 생성된 API 문서 확인 가능
