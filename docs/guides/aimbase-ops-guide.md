# Aimbase 운용 가이드

> **v2.0.0** | 2026-03-31 | Aimbase v4.3.0 기준

Aimbase 플랫폼을 운영하기 위한 관리자 가이드입니다.
소비앱 연동은 [aimbase-api-guide.md](aimbase-api-guide.md)를 참조하세요.

---

## 접속 정보

| 용도 | URL | 비고 |
|------|-----|------|
| **관리 UI** | `http://{서버IP}:3200` | React SPA (nginx 프록시) |
| **REST API** | `http://{서버IP}:8280/api/v1` | Spring Boot |
| **RAG Sidecar** | `http://{서버IP}:8281` | Python MCP Server (내부용) |

> 관리 UI에 접속하면 `/api/**` 요청이 BE로 자동 프록시됩니다. 소비앱에서 API를 직접 호출할 때만 8280 포트를 사용하세요.

---

## 대상 독자

| 역할 | 사용 범위 | 이 문서에서 다루는 내용 |
|------|----------|----------------------|
| **플랫폼 관리자** (SUPER_ADMIN) | Platform UI | 앱/테넌트 관리, 구독/과금, API Key 발급, 모니터링 |
| **테넌트 관리자** (ADMIN) | 테넌트 UI | Connection, 정책, 프롬프트, 스키마, 워크플로우, RAG, MCP 설정 |
| **소비앱 개발자** | — | 이 문서 대상 아님 → [aimbase-api-guide.md](aimbase-api-guide.md) |

---

## 1. 초기 세팅 플로우

새 서비스를 Aimbase에 온보딩할 때의 순서입니다.

```
[1] 앱 등록 → [2] 테넌트 생성 → [3] Connection 등록 → [4] 정책 세팅
     ↓                                                        ↓
[5] 지식소스 준비 → [6] RAG 인제스션 → [7] 프롬프트/스키마 등록
     ↓
[8] 워크플로우 구성 → [9] API Key 발급 → 소비앱 연동
```

---

## 2. 플랫폼 관리 (SUPER_ADMIN)

> 모든 `/api/v1/platform/**` 엔드포인트는 Master DB에서 동작합니다.

### 2-1. 앱 (App) 관리

앱은 테넌트의 상위 개념입니다 (3계층: Platform → App → Tenant). 하나의 앱이 여러 테넌트를 가집니다.

**UI 경로**: Platform > Apps

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /platform/apps` | status 필터 가능 |
| 생성 | `POST /platform/apps` | 앱 등록 + 자동 프로비저닝 |
| 상세 | `GET /platform/apps/{appId}` | 테넌트 수 포함 |
| 수정 | `PUT /platform/apps/{appId}` | 이름, 설명, 최대 테넌트 수 변경 |
| 정지 | `POST /platform/apps/{appId}/suspend` | 하위 테넌트 전체 정지 |
| 재활성화 | `POST /platform/apps/{appId}/activate` | 재활성화 |
| 삭제 | `DELETE /platform/apps/{appId}` | 테넌트 존재 시 실패 |
| 테넌트 목록 | `GET /platform/apps/{appId}/tenants` | 앱의 테넌트 조회 |

**생성 예시**:

```json
{
  "appId": "lexflow",
  "name": "LexFlow 법률 서비스",
  "description": "법률 AI SaaS",
  "ownerEmail": "admin@lexflow.com",
  "ownerPassword": "Admin1234!",
  "maxTenants": 100
}
```

### 2-2. 테넌트 관리

테넌트는 Database-per-Tenant 격리 단위입니다. 테넌트 생성 시 전용 DB 스키마가 자동 프로비저닝됩니다.

**UI 경로**: Platform > Tenants

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /platform/tenants` | status 필터 가능 |
| 생성 | `POST /platform/tenants` | DB 스키마 자동 생성 + Flyway 마이그레이션 |
| 상세 | `GET /platform/tenants/{id}` | 구독, 사용량 이력 포함 |
| 수정 | `PUT /platform/tenants/{id}` | 이름, 관리자 이메일 변경 |
| 정지 | `POST /platform/tenants/{id}/suspend` | 접근 차단 (데이터 보존) |
| 재활성화 | `POST /platform/tenants/{id}/activate` | 정지 해제 |
| 삭제 | `DELETE /platform/tenants/{id}` | DB 포함 완전 삭제 (비가역) |

**생성 예시**:

```json
{
  "tenantId": "lexflow_companya",
  "appId": "lexflow",
  "name": "LexFlow CompanyA",
  "adminEmail": "admin@companya.com",
  "initialAdminPassword": "Admin1234!",
  "plan": "standard"
}
```

> **주의**: `tenantId`는 생성 후 변경 불가. 소비앱에서 `X-Tenant-Id` 헤더로 사용하는 값이므로 신중히 결정하세요.

**앱 관리자의 셀프서비스 테넌트 생성** (CR-014):

```bash
# 앱 관리자(APP_ADMIN)가 자기 앱 하위에 테넌트 생성
POST /api/v1/apps/{appId}/tenants
```

### 2-3. 구독/과금 관리

**UI 경로**: Platform > Subscriptions

| 작업 | API | 설명 |
|------|-----|------|
| 구독 목록 | `GET /platform/subscriptions` | 전체 테넌트 플랜 현황 |
| 플랜 변경 | `PUT /platform/subscriptions/{tenantId}` | 플랜, 쿼터 변경 |

**쿼터 변경 예시**:

```json
{
  "plan": "enterprise",
  "llmMonthlyTokenQuota": 10000000,
  "maxConnections": 20,
  "maxKnowledgeSources": 50,
  "maxWorkflows": 100,
  "storageGb": 100
}
```

### 2-4. 사용량 모니터링

**UI 경로**: Platform > Monitoring

| 작업 | API | 설명 |
|------|-----|------|
| 대시보드 | `GET /platform/usage` | 전체 플랫폼 사용량 집계 |

### 2-5. 시스템 API Key 관리

소비앱이 JWT 없이 Aimbase API를 호출할 수 있도록 API Key를 발급합니다.

**UI 경로**: Platform > API Keys

| 작업 | API | 설명 |
|------|-----|------|
| 발급 | `POST /platform/api-keys` | domainApp + tenantId 바인딩 |
| 목록 | `GET /platform/api-keys` | tenantId 필터 가능 |
| 폐기 | `DELETE /platform/api-keys/{id}` | 즉시 비활성화 |
| 재발급 | `POST /platform/api-keys/{id}/regenerate` | 기존 키 폐기 → 동일 설정 신규 발급 |

> **주의**: 발급 시 반환되는 `apiKey` 값은 최초 1회만 조회 가능합니다. 즉시 안전한 곳에 저장하세요.

### 2-6. 공용 워크플로우 관리

모든 테넌트에서 `SUB_WORKFLOW` 스텝으로 참조 실행할 수 있는 공용 워크플로우입니다.

**UI 경로**: Platform > Workflows

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /platform/workflows` | category 필터 가능 |
| 상세 | `GET /platform/workflows/{id}` | 상세 조회 |
| 등록 | `POST /platform/workflows` | 공용 워크플로우 등록 |
| 수정 | `PUT /platform/workflows/{id}` | 수정 |
| 삭제 | `DELETE /platform/workflows/{id}` | 삭제 |
| 실행 | `POST /platform/workflows/{id}/run` | 현재 테넌트 컨텍스트에서 실행 |

### 2-6-1. 패스 모드 워크플로우

zip 업로드 대신 **로컬 경로를 직접 지정**하여 소스/문서를 분석하는 워크플로우입니다. zip_extract/temp_cleanup 스텝이 없어 더 단순하고 빠릅니다.

**기본 제공 템플릿**:

| ID | 이름 | 용도 | MCP 도구 |
|----|------|------|----------|
| `path-analysis` | 패스 기반 분석 | 범용 소스/문서 분석 (prompt로 지시) | O |
| `path-code-review` | 패스 기반 코드 리뷰 | 코드 품질/보안 리뷰 | X |
| `path-doc-generation` | 패스 기반 문서 생성 | 설계 문서 역생성 | X |
| `path-dsl-generation` | DSL 생성 및 등록 | 테스트 DSL 생성 → FlowGuard 등록 | O |

**실행 예시** — DSL 생성 및 FlowGuard 등록:

```bash
curl -X POST http://localhost:8280/api/v1/platform/workflows/path-dsl-generation/run \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: my-tenant" \
  -H "Authorization: Bearer {token}" \
  -d '{
    "local_path": "/Source/axopm"
  }'
```

**패스 모드 vs zip 모드**:

| 비교 항목 | zip 모드 | 패스 모드 |
|----------|----------|----------|
| 입력 | zip 파일 업로드 | 로컬 경로 지정 |
| 워크플로우 스텝 | 3단계 (압축해제→분석→정리) | 1단계 (분석) |
| 임시 파일 | 생성 후 정리 필요 | 없음 |
| MCP 도구 연동 | 별도 스텝 필요 | allowed_tools에 포함 |
| 적합 환경 | 원격 서버, 파일 전송 필요 시 | 로컬 개발, 동일 서버 |

> **채팅에서도 사용 가능**: Aimbase 채팅에서 "경로 /Source/axopm의 소스를 분석해서 FlowGuard에 등록해줘"라고 입력하면, 오케스트레이터가 ClaudeCodeTool을 호출하여 동일한 결과를 얻을 수 있습니다. 워크플로우 없이 대화형으로도 동작합니다.

#### MCP 도구 허용 설정

`allowed_tools`에 MCP 도구명을 추가하면 Claude Code가 외부 서비스와 연동할 수 있습니다.

```
레벨1: Read, Grep, Glob          — 읽기 전용 분석
레벨2: + Write                   — 파일 생성/수정
레벨3: + Bash                    — 셸 명령 실행
레벨4: + mcp__*                  — MCP 서버 도구 호출
```

**MCP 도구 지정 방식**:
- 와일드카드: `mcp__*` — 등록된 모든 MCP 도구 허용
- 서버 단위: `mcp__flowguard__*` — FlowGuard 서버 도구만 허용
- 개별 도구: `mcp__flowguard__create_step` — 특정 도구만 허용

### 2-7. Claude Code Agent 관리

Claude Code CLI 기반 에이전트 계정 풀을 관리합니다.

**UI 경로**: Platform > Claude Code

| 작업 | API | 설명 |
|------|-----|------|
| 계정 목록 | `GET /platform/agent-accounts` | 풀 상태 포함 |
| 등록 | `POST /platform/agent-accounts` | 에이전트 계정 등록 |
| 상세 | `GET /platform/agent-accounts/{id}` | 계정 상세 |
| 수정 | `PUT /platform/agent-accounts/{id}` | 설정 변경 |
| 삭제 | `DELETE /platform/agent-accounts/{id}` | 계정 제거 |
| 연결 테스트 | `POST /platform/agent-accounts/{id}/test` | 사이드카 연결 확인 |
| 토큰 업로드 | `POST /platform/agent-accounts/{id}/upload-token` | OAuth 토큰 배포 |
| 서킷 리셋 | `POST /platform/agent-accounts/{id}/reset-circuit` | 서킷 브레이커 리셋 |
| 할당 목록 | `GET /platform/agent-accounts/assignments` | 테넌트-계정 할당 |
| 할당 생성 | `POST /platform/agent-accounts/assignments` | 할당 추가 |
| 할당 삭제 | `DELETE /platform/agent-accounts/assignments/{id}` | 할당 제거 |

### 2-8. Claude Code 에러 패턴/서킷 관리

| 작업 | API | 설명 |
|------|-----|------|
| 에러 패턴 목록 | `GET /platform/claude-code/error-patterns` | 등록된 에러 패턴 |
| 에러 패턴 등록 | `POST /platform/claude-code/error-patterns` | 새 패턴 등록 |
| 에러 패턴 삭제 | `DELETE /platform/claude-code/error-patterns/{id}` | 패턴 제거 |
| 서킷 상태 | `GET /platform/claude-code/circuit-status` | 서킷 브레이커 상태 |
| 서킷 리셋 | `POST /platform/claude-code/circuit-reset` | 수동 CLOSED 전환 |

---

## 3. 테넌트 관리 (ADMIN)

테넌트 관리자가 Aimbase UI에서 수행하는 설정 작업입니다.

### 3-1. Connection (LLM 연결) 관리

LLM 프로바이더(Anthropic, OpenAI, Ollama 등) 및 외부 어댑터 연결을 등록합니다.

**UI 경로**: Connections

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /connections` | type 필터 가능 |
| 생성 | `POST /connections` | LLM/Write/Notification 어댑터 |
| 상세 | `GET /connections/{id}` | 설정 상세 |
| 수정 | `PUT /connections/{id}` | 키, 모델 변경 |
| 삭제 | `DELETE /connections/{id}` | 연결 제거 |
| 테스트 | `POST /connections/{id}/test` | 연결 상태 확인 |

> **권장**: Connection 생성 후 반드시 `test` API로 연결 상태를 확인하세요.

### 3-2. Connection Group (커넥션 그룹) 관리

여러 Connection을 그룹으로 묶어 폴백/로드밸런싱합니다 (CR-015).

**UI 경로**: Connection Groups

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /connection-groups` | adapter 필터 가능 |
| 생성 | `POST /connection-groups` | 그룹 생성 (strategy: failover/round_robin) |
| 상세 | `GET /connection-groups/{id}` | 멤버 상태 포함 |
| 수정 | `PUT /connection-groups/{id}` | 멤버/전략 변경 |
| 삭제 | `DELETE /connection-groups/{id}` | 그룹 제거 |
| 헬스체크 | `POST /connection-groups/{id}/test` | 전체 멤버 상태 확인 |

### 3-3. 정책 (Policy) 관리

요청에 대한 허용/거부/승인 규칙을 정의합니다. priority 내림차순으로 평가되며, 첫 DENY/REQUIRE_APPROVAL에서 중단됩니다.

**UI 경로**: Policies

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /policies` | domain 필터 가능 |
| 생성 | `POST /policies` | 규칙 정의 |
| 상세 | `GET /policies/{id}` | 규칙 상세 |
| 수정 | `PUT /policies/{id}` | 규칙 변경 |
| 삭제 | `DELETE /policies/{id}` | 규칙 제거 |
| 활성화 토글 | `POST /policies/{id}/activate` | 활성/비활성 전환 |
| 시뮬레이션 | `POST /policies/simulate` | 정책 적용 결과 사전 확인 |

> **팁**: 정책 변경 전 `simulate` API로 의도한 대로 동작하는지 사전 검증하세요.

### 3-4. 프롬프트 관리

프롬프트 템플릿을 버전 단위로 관리합니다.

**UI 경로**: Prompts

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /prompts` | 페이지네이션 |
| 생성 | `POST /prompts` | 새 프롬프트 버전 |
| 상세 | `GET /prompts/{id}/{version}` | 버전별 상세 |
| 수정 | `PUT /prompts/{id}/{version}` | 버전 수정 |
| 삭제 | `DELETE /prompts/{id}/{version}` | 버전 삭제 |
| 테스트 | `POST /prompts/{id}/{version}/test` | 변수 바인딩 렌더링 테스트 |

### 3-5. 스키마 관리

구조화된 출력(Structured Output)을 위한 JSON Schema를 버전 관리합니다.

**UI 경로**: Schemas

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /schemas` | 페이지네이션 |
| 생성 | `POST /schemas` | 새 스키마 버전 |
| 상세 | `GET /schemas/{id}/{version}` | 버전별 상세 |
| 삭제 | `DELETE /schemas/{id}/{version}` | 버전 삭제 |
| 검증 | `POST /schemas/{id}/{version}/validate` | 데이터 검증 테스트 |

### 3-6. 워크플로우 관리

DAG 기반 워크플로우를 설계하고 실행합니다. Workflow Studio(비주얼 에디터)에서 노드를 배치하거나, API로 직접 정의할 수 있습니다.

**UI 경로**: Workflows (목록) / Workflow Studio (편집)

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /workflows` | domain 필터, 내 워크플로우 필터 가능 |
| 생성 | `POST /workflows` | 새 워크플로우 |
| 상세 | `GET /workflows/{id}` | inputSchema 포함 |
| 수정 | `PUT /workflows/{id}` | 전체 교체 (full replace) |
| 삭제 | `DELETE /workflows/{id}` | 실행 이력 포함 삭제 |
| 실행 | `POST /workflows/{id}/run` | 비동기 DAG 실행 |
| 승인/거부 | `POST /workflows/runs/{runId}/approve` | HUMAN_INPUT 스텝 처리 |
| 실행 이력 | `GET /workflows/{id}/runs` | 실행 이력 목록 |
| 실행 결과 | `GET /workflows/{id}/runs/{runId}` | 개별 실행 결과 |

### 3-7. 지식소스 (Knowledge Source) 관리

RAG를 위한 지식소스를 등록하고 인제스션합니다.

**UI 경로**: Knowledge

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /knowledge-sources` | type 필터 가능 |
| 생성 | `POST /knowledge-sources` | 소스 생성 (file/api/url) |
| 상세 | `GET /knowledge-sources/{id}` | 소스 상세 |
| 수정 | `PUT /knowledge-sources/{id}` | 청킹/임베딩 설정 변경 |
| 삭제 | `DELETE /knowledge-sources/{id}` | 소스 + 임베딩 삭제 |
| 파일 업로드 | `POST /knowledge-sources/{id}/upload` | 파일 업로드 |
| 전체 인제스션 | `POST /knowledge-sources/{id}/sync` | 전체 재인제스션 |
| 텍스트 인제스션 | `POST /knowledge-sources/{id}/ingest-text` | 개별 문서 upsert |
| 문서 삭제 | `DELETE /knowledge-sources/{id}/documents/{docId}` | 개별 문서 임베딩 삭제 |
| 검색 | `POST /knowledge-sources/search` | 벡터 검색 |
| 인제스션 로그 | `GET /knowledge-sources/{id}/ingestion-logs` | 인제스션 이력 |

> **주의**: `sync`는 소스 전체를 재인제스션합니다. 증분 업데이트는 `ingest-text`를 사용하세요.

### 3-8. MCP 서버 관리

Model Context Protocol 서버를 등록하여 도구를 확장합니다.

**UI 경로**: MCP Servers

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /mcp-servers` | 등록된 서버 목록 |
| 등록 | `POST /mcp-servers` | 새 MCP 서버 등록 |
| 상세 | `GET /mcp-servers/{id}` | 서버 상세 |
| 수정 | `PUT /mcp-servers/{id}` | 설정 변경 |
| 삭제 | `DELETE /mcp-servers/{id}` | 서버 제거 |
| 도구 탐색 | `POST /mcp-servers/{id}/discover` | 서버에서 도구 자동 등록 |
| 연결 해제 | `POST /mcp-servers/{id}/disconnect` | 서버 연결 해제 |

### 3-9. LLM 라우팅 설정

요청 조건에 따라 어떤 LLM Connection을 사용할지 라우팅 규칙을 정의합니다.

**UI 경로**: Monitoring > Routing

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /routing` | 전체 라우팅 규칙 |
| 활성 규칙 | `GET /routing/active` | 현재 활성 규칙만 |
| 생성 | `POST /routing` | 새 라우팅 규칙 |
| 상세 | `GET /routing/{id}` | 규칙 상세 |
| 수정 | `PUT /routing/{id}` | 규칙 변경 |
| 삭제 | `DELETE /routing/{id}` | 규칙 제거 |

### 3-10. 검색 설정 (Retrieval Config)

RAG 검색 파이프라인의 동작을 세부 조정합니다.

**UI 경로**: Knowledge > Retrieval Config

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /retrieval-config` | 검색 설정 목록 |
| 생성 | `POST /retrieval-config` | 새 검색 설정 |
| 상세 | `GET /retrieval-config/{id}` | 설정 상세 |
| 수정 | `PUT /retrieval-config/{id}` | 설정 변경 |
| 삭제 | `DELETE /retrieval-config/{id}` | 설정 제거 |

### 3-11. RAG 평가 (Evaluation)

RAG 품질을 RAGAS 메트릭으로 평가하고, LLM 출력을 검증합니다.

**UI 경로**: RAG Evaluation

| 작업 | API | 설명 |
|------|-----|------|
| 상태 확인 | `GET /evaluations/status` | 평가 MCP 서버 가용 여부 |
| RAG 평가 | `POST /evaluations/rag` | 단건 RAG 품질 평가 |
| LLM 출력 평가 | `POST /evaluations/llm-output` | 환각/독성 평가 |
| 프롬프트 비교 | `POST /evaluations/prompt-comparison` | 프롬프트 회귀 테스트 |

### 3-12. 사용자/역할 관리

테넌트 내 사용자 계정과 역할을 관리합니다.

**UI 경로**: (Aimbase UI 로그인 후 관리)

**사용자**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /users` | 사용자 목록 |
| 생성 | `POST /users` | 사용자 추가 |
| 상세 | `GET /users/{id}` | 사용자 상세 |
| 수정 | `PUT /users/{id}` | 이름, 역할 변경 |
| 비활성화 | `DELETE /users/{id}` | 소프트 삭제 |
| 활성화 토글 | `PATCH /users/{id}/activate` | 활성/비활성 전환 |
| API Key 재발급 | `POST /users/{id}/api-key` | 사용자 API Key 재생성 |

**역할**:

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /roles` | 역할 목록 |
| 생성 | `POST /roles` | 역할 추가 |
| 상세 | `GET /roles/{id}` | 권한 포함 상세 |
| 수정 | `PUT /roles/{id}` | 역할/권한 변경 |
| 삭제 | `DELETE /roles/{id}` | 사용 중이면 실패 |

### 3-13. 프로젝트 관리

리소스(프롬프트, 스키마, 지식소스 등)를 프로젝트 단위로 묶어 관리합니다.

**UI 경로**: (프로젝트 선택 드롭다운)

| 작업 | API | 설명 |
|------|-----|------|
| 목록 | `GET /projects` | user 필터 가능 |
| 생성 | `POST /projects` | 프로젝트 생성 |
| 상세 | `GET /projects/{id}` | 멤버, 리소스 포함 |
| 수정 | `PUT /projects/{id}` | 프로젝트 정보 변경 |
| 삭제 | `DELETE /projects/{id}` | 프로젝트 삭제 |
| 멤버 관리 | `GET/POST/PUT/DELETE /projects/{id}/members` | 멤버 CRUD |
| 리소스 할당 | `GET/POST/DELETE /projects/{id}/resources` | 리소스 연결/해제 |

### 3-14. 관리 대시보드 / 모니터링

**UI 경로**: Dashboard / Monitoring

| 작업 | API | 설명 |
|------|-----|------|
| 대시보드 | `GET /admin/dashboard` | 비용, 토큰, 승인 대기, 활성 연결 현황 |
| 액션 로그 | `GET /admin/action-logs` | 액션 실행 이력 |
| 감사 로그 | `GET /admin/audit-logs` | 감사 이력 |
| 사용량 | `GET /admin/usage` | 사용량/비용 통계 |
| LLM 트레이스 | `GET /admin/traces` | session_id, model, 기간 필터 |
| 승인 대기 | `GET /admin/approvals` | 대기 중인 요청 |
| 승인 | `POST /admin/approvals/{id}/approve` | 요청 승인 |
| 거부 | `POST /admin/approvals/{id}/reject` | 요청 거부 |
| 비용 분석 | `GET /admin/cost-breakdown` | 모델별 비용 집계 |
| 비용 추이 | `GET /admin/cost-trend` | 일별/모델별 비용 추이 |

### 3-15. 확장 / 도구

| 작업 | API | 설명 |
|------|-----|------|
| 확장 목록 | `GET /extensions` | 로드된 확장과 도구 목록 |
| 도구 테스트 | `POST /actuator/tool-test/{toolName}` | 도구 직접 실행 (테스트용) |
| 도구 목록 | `GET /actuator/tool-test/list` | 사용 가능 도구 목록 |
| 모델 목록 | `GET /models` | 사용 가능 LLM 모델 목록 |

---

## 4. 운영 시나리오

### 시나리오 A: 새 소비앱 온보딩 (예: LexFlow)

```
1. [플랫폼 관리자] 앱 등록
   POST /platform/apps  { appId: "lexflow", name: "LexFlow", ... }

2. [플랫폼 관리자] 테넌트 생성
   POST /platform/tenants  { tenantId: "lexflow_companya", appId: "lexflow", ... }

3. [플랫폼 관리자] API Key 발급
   POST /platform/api-keys  { domainApp: "lexflow", tenantId: "lexflow_companya" }

4. [테넌트 관리자] LLM Connection 등록 + 테스트
   POST /connections  { adapter: "ANTHROPIC", config: { apiKey: "...", model: "..." } }
   POST /connections/{id}/test  → 연결 확인

5. [테넌트 관리자] 기본 정책 세팅
   POST /policies  { name: "토큰 제한", domain: "RATE_LIMIT", ... }
   POST /policies  { name: "민감정보 차단", domain: "SECURITY", ... }

6. [테넌트 관리자] 지식소스 + 인제스션
   POST /knowledge-sources  { name: "판례DB", type: "file", ... }
   POST /knowledge-sources/{id}/upload  → 파일 업로드
   POST /knowledge-sources/{id}/sync  → 인제스션

7. [소비앱 개발자] API Key로 연동 시작
   → aimbase-api-guide.md 참조
```

### 시나리오 B: Connection Group 폴백 구성

```
1. [테넌트 관리자] 개별 Connection 등록
   POST /connections  { id: "claude-primary", adapter: "ANTHROPIC", ... }
   POST /connections  { id: "gpt-fallback", adapter: "OPENAI", ... }

2. [테넌트 관리자] Connection Group 생성
   POST /connection-groups  {
     id: "llm-pool",
     adapter: "LLM",
     strategy: "failover",
     members: [
       { "connectionId": "claude-primary", "priority": 1 },
       { "connectionId": "gpt-fallback", "priority": 2 }
     ],
     isDefault: true
   }

3. [소비앱] chat 호출 시 connection_group_id 지정
   POST /chat/completions  { connection_group_id: "llm-pool", ... }
   → claude 장애 시 자동으로 gpt로 폴백
```

### 시나리오 C: RAG 품질 튜닝

```
1. [테넌트 관리자] RAG 평가 실행
   POST /evaluations/rag  { question: "...", answer: "...", contexts: [...] }
   → faithfulness, answer_relevancy 메트릭 확인

2. [테넌트 관리자] 품질이 낮으면 조치
   - 청킹 전략 변경: PUT /knowledge-sources/{id}  { chunkingConfig: { strategy: "contextual" } }
   - 검색 설정 조정: PUT /retrieval-config/{id}  { topK: 10 }
   - 재인제스션: POST /knowledge-sources/{id}/sync
```

### 시나리오 D: 테넌트 정지 / 재활성화

```
1. [플랫폼 관리자] 정지
   POST /platform/tenants/{id}/suspend
   → 해당 테넌트의 모든 API 호출 차단, 데이터는 보존

2. [플랫폼 관리자] 재활성화
   POST /platform/tenants/{id}/activate
   → 즉시 서비스 재개
```

### 시나리오 E: Claude Code Tool 원격 사이드카 구성

```
1. [인프라 담당자] 원격 서버에서 사이드카 이미지 빌드 & 실행
   → 아래 "5-3. 독립 사이드카 배포" 참조

2. [플랫폼 관리자] 계정 등록
   POST /platform/agent-accounts  { id: "remote-1", containerHost: "10.0.1.50", ... }

3. [플랫폼 관리자] 헬스체크 확인
   POST /platform/agent-accounts/remote-1/test

4. [플랫폼 관리자] 테넌트에 할당
   POST /platform/agent-accounts/assignments  { account_id: "remote-1", tenant_id: "dev" }

5. [테넌트 관리자] 워크플로우에서 claude_code 도구 사용
   → 자동으로 할당된 사이드카로 라우팅
```

---

## 5. Claude Code Tool 설치 · 운용

Claude Code Tool은 워크플로우의 TOOL_CALL Step에서 Claude Code CLI를 호출하여 파일 분석, 코드 검토, 문서 생성 등 에이전트 수준의 작업을 수행합니다.

### 5-1. 설치 구성 유형

| 유형 | 설명 | Claude CLI 설치 | 적합 환경 |
|------|------|-----------------|----------|
| **A. Docker 내장** | Aimbase API 컨테이너에 CLI 포함 | 자동 (Dockerfile) | 단일 서버, 개발/스테이징 |
| **B. 독립 사이드카** | 별도 컨테이너에서 CLI 실행 | 자동 (Dockerfile) | 분산 환경, 다중 계정 |
| **C. 로컬 직접 설치** | 호스트에 CLI 직접 설치 | **수동** | 베어메탈, 개발자 로컬 |

### 5-2. 유형 A — Docker 내장 (기본)

Aimbase API 이미지(`platform-core/Dockerfile`)에 Claude Code CLI가 포함되어 있습니다. `docker compose up`만으로 사용 가능합니다.

**활성화 조건** (`application.yml` 또는 환경변수):

```yaml
claude-code:
  enabled: true                          # CLAUDE_CODE_ENABLED=true
  executable: claude                     # CLI 경로 (기본: PATH에서 탐색)
  api-key: ${CLAUDE_CODE_API_KEY:}       # API Key (미설정 시 OAuth 토큰 사용)
  timeout-seconds: 300                   # 실행 타임아웃 (초)
  max-turns: 10                          # 에이전트 최대 턴 수
  working-directory: /data/workspace     # 기본 작업 디렉토리
```

**인증 방식 2가지** (택 1):

| 방식 | 설정 | 용도 |
|------|------|------|
| **OAuth 토큰** | 호스트에서 `claude login` 후 토큰 파일을 볼륨 마운트 | 개발/스테이징 |
| **API Key** | `CLAUDE_CODE_API_KEY` 환경변수 설정 | 프로덕션/AWS |

**OAuth 토큰 준비**:

```bash
# 1. 호스트에서 토큰 디렉토리 생성
mkdir -p ~/.claude/docker-auth

# 2. 해당 디렉토리에서 로그인 (토큰이 여기에 생성됨)
HOME=~/.claude/docker-auth claude login

# 3. docker-compose.yml에서 볼륨 마운트 (이미 설정됨)
# volumes:
#   - ~/.claude/docker-auth/.claude.json:/home/appuser/.claude.json
#   - ~/.claude/docker-auth/.claude:/home/appuser/.claude
```

### 5-3. 유형 B — 독립 사이드카 배포

Aimbase와 별도 서버(또는 별도 Docker 호스트)에 Claude CLI만 실행하는 경량 컨테이너입니다.

**사이드카 구성 파일** (`backend/claude-sidecar/`):

```
claude-sidecar/
├── Dockerfile     # node:18-alpine + Claude Code CLI
└── server.js      # HTTP 서버 (health, execute, upload-token)
```

**사이드카 엔드포인트**:

| 엔드포인트 | 메서드 | 용도 |
|---|---|---|
| `/health` | GET | 헬스체크 (`claude --version` 실행) |
| `/execute` | POST | CLI 명령 실행 (Aimbase → 사이드카) |
| `/upload-token` | POST | OAuth 토큰 런타임 배포 |

#### 단일 사이드카 배포

```bash
# 원격 서버에서 실행

# 1. 사이드카 소스 복사 (2개 파일만 필요)
scp -r backend/claude-sidecar/ user@remote:/opt/claude-sidecar/

# 2. 이미지 빌드
cd /opt/claude-sidecar
docker build -t claude-sidecar .

# 3. 컨테이너 실행
docker run -d \
  --name claude-agent-1 \
  --restart unless-stopped \
  -p 9100:9100 \
  -v /opt/claude-auth/.claude.json:/home/node/.claude.json \
  -v /opt/claude-auth/.claude:/home/node/.claude \
  -v /data/workspace:/data/workspace \
  -e PORT=9100 \
  claude-sidecar

# 4. 헬스체크 확인
curl http://localhost:9100/health
# → {"status":"ok","version":"1.x.x","pid":1}
```

#### 다중 계정 사이드카 (docker-compose)

```yaml
# docker-compose.sidecar.yml — 원격 서버에 배치
version: "3.8"
services:
  claude-agent-1:
    build: .
    ports:
      - "9100:9100"
    volumes:
      - ./accounts/account-1/.claude.json:/home/node/.claude.json
      - ./accounts/account-1/.claude:/home/node/.claude
      - /data/workspace:/data/workspace
    environment:
      PORT: "9100"
    restart: unless-stopped

  claude-agent-2:
    build: .
    ports:
      - "9101:9100"
    volumes:
      - ./accounts/account-2/.claude.json:/home/node/.claude.json
      - ./accounts/account-2/.claude:/home/node/.claude
      - /data/workspace:/data/workspace
    environment:
      PORT: "9100"
    restart: unless-stopped
```

```bash
# 계정별 OAuth 토큰 준비
mkdir -p accounts/account-1 accounts/account-2
HOME=$(pwd)/accounts/account-1 claude login   # 계정 1 로그인
HOME=$(pwd)/accounts/account-2 claude login   # 계정 2 로그인

# 기동
docker compose -f docker-compose.sidecar.yml up -d
```

### 5-4. 유형 C — 로컬 직접 설치 (Docker 없이)

```bash
# 1. Node.js 18+ 설치 확인
node --version  # v18 이상 필요

# 2. Claude Code CLI 설치
npm install -g @anthropic-ai/claude-code

# 3. 인증 (OAuth)
claude login

# 4. 설치 확인
claude --version

# 5. Aimbase 설정에서 활성화
# application.yml 또는 환경변수:
#   CLAUDE_CODE_ENABLED=true
#   CLAUDE_CODE_EXECUTABLE=claude  (또는 절대경로: /usr/local/bin/claude)
```

> 로컬 설치 시 Aimbase API 서버가 직접 `claude` 프로세스를 실행합니다 (ProcessBuilder). 사이드카 경로를 거치지 않습니다.

### 5-5. 에이전트 계정 풀 관리 (Platform Admin)

독립 사이드카를 등록하고 테넌트에 할당하는 관리 API입니다.

**UI 경로**: Platform > Agent Accounts (향후)

#### 계정 CRUD

| 작업 | API | 설명 |
|------|-----|------|
| 목록 (풀 상태 포함) | `GET /platform/agent-accounts` | 헬스, 서킷 상태, 동시실행 수 포함 |
| 등록 | `POST /platform/agent-accounts` | 사이드카 계정 등록 |
| 상세 | `GET /platform/agent-accounts/{id}` | 계정 상세 |
| 수정 | `PUT /platform/agent-accounts/{id}` | host, port, 동시실행 제한 등 변경 |
| 삭제 | `DELETE /platform/agent-accounts/{id}` | 계정 삭제 |
| 연결 테스트 | `POST /platform/agent-accounts/{id}/test` | `/health` 호출 |
| 토큰 배포 | `POST /platform/agent-accounts/{id}/upload-token` | OAuth 토큰 런타임 배포 |
| 서킷 리셋 | `POST /platform/agent-accounts/{id}/reset-circuit` | 서킷 브레이커 수동 리셋 |

**등록 예시**:

```json
{
  "id": "remote-agent-1",
  "name": "원격 Claude Agent #1",
  "agentType": "claude_code",
  "authType": "oauth",
  "containerHost": "10.0.1.50",
  "containerPort": 9100,
  "maxConcurrent": 1,
  "priority": 0,
  "config": {}
}
```

#### 테넌트 할당

| 작업 | API | 설명 |
|------|-----|------|
| 할당 목록 | `GET /platform/agent-accounts/assignments` | 전체 할당 조회 |
| 할당 생성 | `POST /platform/agent-accounts/assignments` | 테넌트/앱에 계정 할당 |
| 할당 삭제 | `DELETE /platform/agent-accounts/assignments/{id}` | 할당 해제 |

**할당 해소 우선순위**:

```
1. (tenantId + appId) → 가장 구체적
2. (tenantId만)
3. (appId만)
4. round_robin 풀에서 가용 계정
5. null → 로컬 ProcessBuilder 경로 (사이드카 미사용)
```

**할당 예시**:

```json
{
  "account_id": "remote-agent-1",
  "tenant_id": "lexflow_companya",
  "app_id": null,
  "assignment_type": "fixed",
  "priority": 0
}
```

`assignment_type`:
- `fixed` — 해당 테넌트 전용
- `round_robin` — 미할당 요청에 대해 라운드로빈 분배

### 5-6. 운영 모니터링

**자동 헬스체크**: 등록된 사이드카는 **30초 주기**로 `/health` 호출. 결과는 `health_status` 필드에 반영됩니다.

**서킷 브레이커**: 연속 3회 실패 시 OPEN → 5분 대기 → HALF_OPEN(1회 시도) → 성공 시 CLOSED. OPEN 상태에서는 해당 계정 사용을 건너뜁니다.

**풀 상태 조회 예시**:

```bash
curl http://localhost:8280/api/v1/platform/agent-accounts
```

```json
[
  {
    "accountId": "remote-agent-1",
    "name": "원격 Claude Agent #1",
    "agentType": "claude_code",
    "status": "active",
    "healthStatus": "healthy",
    "circuitState": "CLOSED",
    "currentConcurrency": 0,
    "maxConcurrent": 1,
    "lastHealthAt": "2026-03-31T10:00:30+09:00"
  }
]
```

### 5-7. 트러블슈팅

| 증상 | 원인 | 조치 |
|------|------|------|
| `CIRCUIT_OPEN` 에러 | 연속 3회 실패 | 사이드카 로그 확인 → 문제 해결 후 `POST /{id}/reset-circuit` |
| `FILE_NOT_FOUND` | input_file 경로 오류 | 사이드카의 `/data/workspace`에 파일 존재 확인 |
| `TIMEOUT` | 작업이 제한 시간 초과 | `CLAUDE_CODE_TIMEOUT` 증가 또는 `max_turns` 축소 |
| `EXIT_1` + "not logged in" | OAuth 토큰 만료/미설정 | `POST /{id}/upload-token`으로 재배포 또는 볼륨 마운트 확인 |
| 헬스 `unhealthy` | 사이드카 컨테이너 다운 | `docker logs claude-agent-1` 확인 → 재시작 |
| `BLOCKED_OPTION` | 차단된 CLI 옵션 사용 | `--dangerously-skip-permissions` 등은 보안상 차단됨 |

---

## 6. 운영 주의사항

### 보안
- LLM API Key는 Connection의 `config` 필드에 암호화 저장됨. UI에서 마스킹 표시
- 시스템 API Key는 발급 시 1회만 노출 — 분실 시 재발급 필요
- 테넌트 간 데이터 격리는 Database-per-Tenant로 보장. 교차 접근 불가

### 성능
- RAG 인제스션(`sync`)은 대용량 파일 시 수 분 소요 가능. 비동기 처리됨
- pgvector HNSW 인덱스는 대량 데이터 시 빌드 시간이 증가. 오프피크에 실행 권장
- 워크플로우 DAG 실행은 Virtual Threads 기반. 병렬 스텝 자동 분배

### 임베딩
- 기본 모델: BGE-M3 (로컬 실행)
- OpenAI text-embedding-3-small 선택 가능 (외부 API 호출)
- 모델 변경 시 해당 소스의 전체 재인제스션 필요

### 정책
- priority 숫자가 높을수록 먼저 평가
- 첫 DENY 또는 REQUIRE_APPROVAL 매칭 시 평가 중단
- 정책 변경 후 `simulate`로 반드시 검증

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| v2.1.0 | 2026-03-31 | Claude Code Tool 설치·운용 섹션 추가 (유형 A/B/C 설치, 사이드카 배포, 계정 풀 관리, 트러블슈팅) |
| v2.0.0 | 2026-03-31 | 전면 개편. 3계층(App→Tenant) 관리, Connection Group, 공용 워크플로우, Claude Code Agent, 대시보드/모니터링, 확장/도구 섹션 추가. 접속 정보, 운영 시나리오 4건 포함 |
| v1.0.0 | 2026-03-28 | 초판 작성 |
