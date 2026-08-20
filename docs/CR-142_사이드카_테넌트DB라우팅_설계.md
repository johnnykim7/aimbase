# CR-142 — RAG 사이드카 테넌트 DB 라우팅 설계

- 발번: 2026-08-20
- 원본 요구사항: `docs/origins/원본_요구사항_CR142_사이드카_테넌트라우팅_20260820.md`
- 관련 규칙: BIZ-003 (Database-per-Tenant 격리)
- 상태: **설계 확정 / 코드 미구현**

---

## 1. 문제

RAG 사이드카는 테넌트 개념이 없다. DB 연결이 단일 env `DB_NAME` 하나로 고정되어 있어,
어느 테넌트의 요청이든 **같은 DB** 로 임베딩이 저장된다.

현재 `axopm_companyA` 만 파일 RAG 를 쓰고 있어 드러나지 않았을 뿐, `bp_wes`·`workmap` 이
파일 RAG 를 켜는 순간 BIZ-003 위반이 실데이터로 발생한다.

### 시급성 — 선행 조치가 위험을 활성화시켰다 ★

2026-08-20 이전에는 `.env` 의 `DB_NAME` 대소문자 오류로 **연결 자체가 실패**하고 있었다
(`FATAL: database "aimbase_axopm_companya" does not exist`). 즉 오염이 물리적으로 불가능했다.

당일 대소문자를 바로잡아 연결이 살아났으므로, **지금부터는 진짜로 섞인다.**
현재 오염 없음은 확인됨(`bp_wes` 0건, `workmap` 0건) — 이 두 테넌트가 파일 RAG 를
켜기 전까지가 안전 구간이다.

---

## 2. 실측 근거

| 항목 | 실측 |
|---|---|
| 사이드카 tenant 인지 | 0건 (`grep -ri tenant` 4건 전부 주석/기본값) |
| DB 연결 지점 | **단 1곳** — [db.py:15](../python-sidecar/src/rag_pipeline/db.py#L15) |
| DB명 출처 | [config.py:10](../python-sidecar/src/rag_pipeline/config.py#L10) 단일 env |
| BE 클라이언트 | 전 테넌트 공유 단일 인스턴스 — [MCPRagClient.java:63](../backend/platform-core/src/main/java/com/platform/rag/MCPRagClient.java#L63) |
| 도구 인자 tenant | 없음 |
| MCP 도구 총수 | 42개 (DB 접촉 8 직접 + `ingest_file` 등 간접) |

**연결 지점이 1곳이라는 사실이 이 설계의 핵심이다.** 42개 도구를 개별 수정하는 문제가 아니라
`get_connection()` 하나를 테넌트 인지형으로 바꾸는 문제다.

### 테넌트→DB명 정본은 이미 master DB 에 있다

```
 id             | db_name
 axopm_companyA | aimbase_axopm_companyA
 shopai-store-a | aimbase_shopai-store-a      ← 하이픈 포함
 bp_wes         | aimbase_bp_wes
 workmap        | aimbase_workmap
```

---

## 3. 설계 원칙 — DB명은 조립하지 말고 전달받는다 ★

사이드카가 `"aimbase_" + tenant_id` 로 **조립하면 안 된다.**

- 방금 터진 대소문자 장애가 정확히 그 조립 규칙에서 났다.
- `shopai-store-a` 처럼 하이픈 든 ID 가 있어 조립 규칙은 더 위험하다.
- 정본(`tenants.db_name`)이 master DB 에 이미 있다.

→ **BE 가 해석한 값을 그대로 넘긴다.** 규칙이 BE 한 곳에만 존재하게 만드는 것이 본 CR 의 본질.

---

## 4. 구현

### 4.1 사이드카 — 요청 스코프 DB 바인딩

```python
# rag_pipeline/db.py
import contextvars

_current_db: contextvars.ContextVar[str | None] = contextvars.ContextVar("tenant_db", default=None)

def bind_tenant_db(db_name: str | None):
    """도구 진입부에서 호출. 반환된 token 으로 finally 에서 reset."""
    return _current_db.set(_validate_db_name(db_name) if db_name else None)

def get_connection() -> psycopg.Connection:
    name = _current_db.get() or settings.DB_NAME      # 미지정 시 기존 동작 폴백
    conn = psycopg.connect(settings.db_url_for(name), autocommit=True)
    register_vector(conn)
    return conn
```

`config.py` 에 `db_url_for(db_name)` 추가(기존 `db_url` 은 `db_url_for(DB_NAME)` 로 위임).

**폴백을 반드시 남긴다.** 미지정이면 기존 `settings.DB_NAME` 으로 동작 → stateless 34개 도구와
기존 호출 경로가 안 깨지고, 점진 이행이 가능하다.

#### ContextVar 가 이 구조에서 실제로 동작하는가 — 검증함 ★

사이드카 MCP 도구는 `async def` 가 아니라 **sync `def`** 다. FastMCP 는 이를 threadpool 로 넘긴다.
`ContextVar` 는 async task 와 worker thread 간 전파 규칙이 달라 그냥 가정하면 위험하므로,
**운영 컨테이너에서 직접 확인했다**:

```
pairs: [('db_0','db_0'), ('db_1','db_1'), ('db_2','db_2'), ('db_3','db_3')]
ContextVar propagates to threadpool & isolates per-request: True
```

동시 4요청에서 값이 섞이지 않고 각 요청의 sync 함수 본문까지 정확히 전달됨을 확인
(`anyio.to_thread.run_sync` = FastMCP 의 sync 도구 실행 경로와 동일).

### 4.2 BE — 테넌트 DB 좌표 주입

```java
// MCPRagClient — DB 접촉 도구 호출 시
String tenantDb = tenantRegistry.getDbName(TenantContext.getTenantId());
input.put("tenant_db", tenantDb);
```

- 대상: `ingest_file`, `ingest_document`, 검색·시맨틱캐시·템플릿 계열
- 제외: 파싱/변환/OCR 등 stateless 도구 (DB 를 안 탐)
- `TenantContext` 가 비어있는 경로(스케줄러 등)가 있는지 확인 필요 —
  과거 CR-122 에서 스케줄러 `TenantContext` 누락 사고 이력 있음.

### 4.3 화이트리스트 가드 (보안)

넘어온 DB명을 그대로 신뢰하면 신뢰경계가 BE→사이드카 인자로 넓어진다.

```python
_DB_NAME_RE = re.compile(r"^aimbase_[A-Za-z0-9_-]+$")

def _validate_db_name(name: str) -> str:
    if not _DB_NAME_RE.fullmatch(name):
        raise ValueError(f"illegal tenant db: {name!r}")
    return name
```

`aimbase_master` 는 명시적으로 거부(테넌트 DB 가 아님).

---

## 5. 검토했지만 뺀 대안

| 대안 | 기각 사유 |
|---|---|
| 테넌트별 사이드카 컨테이너 분리 | 격리는 완벽하나 테넌트 7개 = 컨테이너 7개. BGE-M3 모델 메모리가 배수 증가 → 비현실적 |
| 사이드카가 master DB 조회해 자체 해석 | 사이드카에 master 접근권 부여 = 신뢰경계 확대. BE 가 이미 아는 값을 재조회할 이유 없음 |
| 커넥션 풀을 DB별로 사전 생성 | 테넌트 증가 시 풀 관리 복잡. 현재 `get_connection()` 은 요청마다 새 연결이라 우선 동일 방식 유지 |

---

## 6. 검증 계획

1. **단위** — `_validate_db_name` 허용/거부(`aimbase_master`, 경로문자, 빈값)
2. **동시성** — 서로 다른 `tenant_db` 로 동시 ingest → 각 DB 에만 적재되는지
   (`ContextVar` 격리 회귀 방지, 4.1 검증과 동일 형태)
3. **폴백** — `tenant_db` 미전달 시 기존 `DB_NAME` 으로 동작(기존 경로 무회귀)
4. **e2e** — `bp_wes` 테넌트로 파일 RAG 실행 →
   `aimbase_bp_wes.embeddings` 증가 **AND** `aimbase_axopm_companyA.embeddings` 불변
   (현재 각각 0 / 1 이므로 오염 여부가 명확히 드러남)

---

## 7. 배포

- 사이드카 변경 → `./deploy.sh rag`
- BE 변경 → `./deploy.sh be`
- 두 쪽이 함께 가야 함(BE 가 인자를 보내기 시작하는 시점 = 사이드카가 받을 수 있어야 함).
  단 4.1 폴백이 있으므로 **사이드카 먼저 → BE 나중** 순서면 무중단 이행 가능.
