# T3-10 | CR-061 위젯 파일 업로드 (이미지/PDF Vision 첨부) 상세 설계서

**문서 번호**: T3-10
**관련 CR**: CR-061
**작성일**: 2026-04-24
**상태**: 📝 설계 완료, 구현 대기 (Sprint 54 예정, 5MD)
**원본 요구사항**: [docs/origins/원본_요구사항_CR061_파일업로드_20260424.md](origins/원본_요구사항_CR061_파일업로드_20260424.md)

---

## 1. 목적과 범위

### 1.1 목적
CR-058 위젯에 **이미지/PDF 첨부** 기능을 추가한다. 위젯 사용자는 상담 중 영수증·스크린샷·PDF 문서를 채팅창에 드래그앤드롭 또는 파일 선택으로 붙일 수 있고, LLM이 Vision 입력으로 직접 해석한다. RAG 인제스션은 **하지 않는다** — 해당 메시지 1회에만 컨텍스트로 투입된다.

### 1.2 범위 — 포함 (Sprint 54, 5MD)
- Phase 1 — `chat_attachments` 테이블(V58) + Entity/Repository (0.5MD)
- Phase 2 — `POST /api/v1/chat/attachments` + `DELETE /.../{id}` + 스토리지/MIME 검증 + GC (1.0MD)
- Phase 3 — ChatController `messages[].content[]` 에 `{type:"image"|"document", attachment_id}` 처리 + PDF 텍스트 추출 폴백 (1.0MD)
- Phase 4 — 위젯 FE 파일 선택/드래그앤드롭/칩 프리뷰 + chat-client 확장 (1.5MD)
- Phase 5 — 테스트(BE 단위+E2E) + API/운용 가이드 갱신 (1.0MD)

### 1.3 범위 — 제외
- **RAG 인제스션 연동** (세션 ad-hoc KnowledgeSource 자동 생성) — 별도 CR
- **음성(STT)** — CR-060
- **OCR / 이미지 내 텍스트 추출 후처리** — 프로바이더 Vision 능력에 위임
- **Office 문서(DOCX/XLSX/PPTX)** — `/api/v1/knowledge-sources/{id}/upload` 경로로 안내
- **멀티페이지 PDF UI 페이지 선택** — 전체 PDF를 그대로 투입
- **첨부 영구 저장** — 세션 TTL과 함께 GC

### 1.4 선행 의존성
- **CR-011** `StorageService` (로컬/S3 추상화) — 재사용
- **CR-058** 위젯 토큰 체계(`widget.allowed-scopes`) — `chat:upload` 추가
- **CR-045** Chat SSE (`/api/v1/chat/completions`) — messages content 확장
- **Sprint 15+** Python 사이드카 `parse_document` 도구 — PDF 텍스트 추출 폴백에 재사용
- **기존** `ContentBlock.Image` + `AnthropicAdapter` Vision 경로 — 그대로 사용

---

## 2. 아키텍처

### 2.1 흐름도

```
[위젯 FE]                [Aimbase BE]                  [Storage]           [LLM Adapter]
   │
   │ 1. 파일 선택/드롭
   │ (검증: 확장자·크기 — 클라 선제)
   │
   ├─ POST /chat/attachments ─────▶ AttachmentController
   │  multipart + session_id       ├─ scope=chat:upload 검증
   │                               ├─ MIME(magic number) 재검증
   │                               ├─ 크기 제한 BIZ-099
   │                               ├─ 세션당 개수 BIZ-100 확인
   │                               ├─ StorageService.save() ─────▶ [파일 저장]
   │                               ├─ PDF면 pages 메타 추출
   │                               └─ chat_attachments INSERT
   │ ◀─ { attachment_id, filename, media_type, pages? } ─────────
   │
   │ 2. 첨부 칩 UI 표시 (썸네일/파일명)
   │
   ├─ POST /chat/completions ─────▶ ChatController
   │  messages[0].content=[        ├─ attachment_id → 조회·소유권 검증
   │    {type:"text", text:"..."}, ├─ Adapter capability 판정:
   │    {type:"image",             │   ├─ Anthropic+image  → ContentBlock.Image (base64 로드)
   │       attachment_id:"..."},   │   ├─ Anthropic+pdf    → ContentBlock.Document (base64 로드)
   │    {type:"document",          │   ├─ OpenAI+image     → ContentBlock.Image
   │       attachment_id:"..."}    │   └─ 기타+pdf         → PdfTextExtractor 폴백
   │  ]                            │                          (Python parse_document)
   │                               │                          → ContentBlock.Text 앞에 주입
   │                               └─ LLM 호출
   │ ◀─ SSE 스트림 (기존과 동일)
   │
   │ 3. 세션 만료 (24h) or DELETE
   │                                  [배치] GcScheduler 매 5분
   │                                  └─ expires_at < now → StorageService.delete() + 레코드 삭제
```

### 2.2 왜 사전 업로드 방식(b)인가
- **크기**: 32MB PDF를 SSE 바디에 인라인하면 preflight·타임아웃·메모리 증가. 분리 업로드가 재시도, 멀티파일, 프로그레스 바 모두 유리.
- **재활용**: 같은 세션에서 같은 파일을 여러 메시지에 참조할 수 있음(드물지만 유효 패턴).
- **검증 타이밍**: 업로드 시 1회만 MIME/크기 검증, 메시지 전송 시엔 ID lookup만.

### 2.3 Scope 체계 (CR-058 확장)
`platform_settings.widget.allowed-scopes` 기본값에 `chat:upload` 추가:
```
기본: [chat:stream, chat:upload, workflow:subscribe, rag:read]
```
BFF에서 `/sessions/issue-widget-token` 호출 시 scope 배열에 `chat:upload` 포함해야 함. 미포함 시 업로드 API 403.

---

## 3. 데이터 모델

### 3.1 테이블 `chat_attachments` (tenant DB, Flyway V58)

```sql
CREATE TABLE chat_attachments (
    id            UUID PRIMARY KEY,
    session_id    VARCHAR(64) NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    media_type    VARCHAR(100) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    storage_path  VARCHAR(500) NOT NULL,
    pages         INTEGER,                      -- PDF만, 나머지 NULL
    checksum      VARCHAR(64) NOT NULL,         -- SHA-256 hex
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMP NOT NULL
);

CREATE INDEX idx_chat_attachments_session ON chat_attachments(session_id);
CREATE INDEX idx_chat_attachments_expires ON chat_attachments(expires_at);
```

**삭제 정책**:
- `expires_at`은 생성 시점 `+ session TTL(24h)`로 고정 — 세션 연장 시 위젯이 재업로드 권장
- GC 배치: `expires_at < now()` 레코드의 `storage_path`를 StorageService.delete() 호출 후 DB 삭제

### 3.2 JPA 엔티티 (개요)
```java
@Entity @Table(name = "chat_attachments")
public class ChatAttachmentEntity {
    @Id UUID id;
    String sessionId;
    String filename;
    String mediaType;
    Long sizeBytes;
    String storagePath;
    Integer pages;           // nullable
    String checksum;
    LocalDateTime createdAt;
    LocalDateTime expiresAt;
}
```

### 3.3 기존 모델 영향도
- `ContentBlock.Image` — 변경 없음, 그대로 사용
- `ContentBlock` — `Document` 레코드 신규 추가 (아래 §4.3)
- `ConversationMessageEntity` — 메시지 본문에 `attachment_ids: [...]` JSONB 배열 추가(선택, 감사/복원용)

---

## 4. API 설계

### 4.1 `POST /api/v1/chat/attachments`
**Scope**: `chat:upload`
**Content-Type**: `multipart/form-data`

**요청**:
```
file:        MultipartFile      (필수)
session_id:  String             (필수, 요청자가 권한 있는 세션)
```

**응답 201**:
```json
{
  "attachment_id": "a1b2c3d4-...",
  "filename": "receipt.pdf",
  "media_type": "application/pdf",
  "size_bytes": 1048576,
  "pages": 3,
  "expires_at": "2026-04-25T12:00:00Z"
}
```

**에러**:
- `400 FG-ATT-4001` — 지원하지 않는 MIME
- `400 FG-ATT-4002` — 크기 초과 (BIZ-099)
- `409 FG-ATT-4091` — 세션당 활성 첨부 초과 (BIZ-100)
- `403 FG-ATT-4031` — 세션 소유권 불일치
- `422 FG-ATT-4221` — MIME 검증 실패 (magic number 불일치)

### 4.2 `DELETE /api/v1/chat/attachments/{attachment_id}`
**Scope**: `chat:upload`

**응답 204** (소유권 OK, 삭제 완료)
**에러 403** — 다른 세션 소유

### 4.3 `POST /api/v1/chat/completions` — 확장

**messages[].content[]** 에 신규 블록 2종:
```json
{
  "role": "user",
  "content": [
    { "type": "text", "text": "이 영수증 금액을 정리해줘" },
    { "type": "image", "attachment_id": "a1b2..." },
    { "type": "document", "attachment_id": "b3c4..." }
  ]
}
```

**기존 호환**:
- 문자열 형태 `"content": "..."` 그대로 지원
- `{type:"image", data:"base64", mediaType:"..."}` (인라인) 도 호환 — 공개 API 문서에선 비공개 패스

**처리 규칙**:
1. `attachment_id` → `ChatAttachmentEntity` 조회 + `session_id` 일치 검증
2. LLM Adapter capability 확인 (`AdapterCapability.supportsPdf()` 신규)
3. 변환:
   - `image` + 이미지 지원 → `ContentBlock.Image.ofBase64(mediaType, base64)` (StorageService.load로 base64 변환)
   - `document` + PDF 지원(Anthropic) → `ContentBlock.Document.ofBase64(mediaType, base64)`
   - `document` + PDF 미지원 → `PdfTextExtractor.extract()` 호출 → 결과 텍스트를 기존 Text 블록 앞에 `"[첨부 문서: {filename}]\n{text}\n\n"`으로 prepend
4. LLM 호출 후 SSE 스트림은 변경 없음

---

## 5. 컴포넌트 설계

### 5.1 BE 신규 클래스

| 클래스 | 책임 | 경로 |
|--------|------|------|
| `ChatAttachmentEntity` | JPA 엔티티 | `com.platform.domain` |
| `ChatAttachmentRepository` | CRUD + `findBySessionId` + `findExpired` | `com.platform.repository` |
| `AttachmentController` | REST API 2종 | `com.platform.api` |
| `AttachmentService` | 저장·검증·GC 비즈니스 로직 | `com.platform.attachment` |
| `MimeValidator` | magic number + Content-Type 이중 검증 | `com.platform.attachment` |
| `PdfTextExtractor` | Python `parse_document` MCP 호출 래퍼 | `com.platform.attachment` |
| `AttachmentGcScheduler` | 매 5분 expired 정리 | `com.platform.attachment` |

### 5.2 BE 기존 클래스 확장

| 클래스 | 변경 |
|--------|------|
| `ContentBlock` | `Document(mediaType, data, url)` sealed 레코드 추가 |
| `AnthropicAdapter` | `toDocumentBlockParam(doc)` 추가 — Anthropic SDK의 document 블록 |
| `LlmAdapter` | `AdapterCapability capabilities()` 추가 (`supportsImage`, `supportsPdf`) |
| `ChatController` | `toUnifiedMessage` 에서 attachment_id 해석 + PDF 폴백 분기 |
| `WidgetTokenController` | 기본 scope에 `chat:upload` 포함 |
| `PlatformSettingsService` | `widget.allowed-scopes` 기본값 업데이트 |

### 5.3 FE 신규/변경

**신규 파일**:
- `packages/chat-widget-embed/src/attachment-client.ts` — POST /chat/attachments + DELETE
- `packages/chat-widget-embed/src/attachment-ui.ts` — 칩 프리뷰 렌더링

**변경**:
- `widget.ts` — 클립 버튼, 드래그앤드롭 이벤트 리스너, 칩 영역 렌더
- `chat-client.ts` — `sendMessage` 시 `attachment_ids` → content 블록 변환
- `types.ts` — `Attachment` 타입 export

### 5.4 MimeValidator (magic number)
```
image/png       → 89 50 4E 47
image/jpeg      → FF D8 FF
image/gif       → 47 49 46 38
image/webp      → RIFF....WEBP
application/pdf → 25 50 44 46 (% P D F)
```
확장자 위조 방지를 위해 파일 선두 바이트 직접 읽어 검증. Content-Type 헤더는 보조.

---

## 6. Sprint/Phase 상세

### Phase 1 — DB + 엔티티 (0.5MD)
- `V58__create_chat_attachments.sql` 작성 (tenant 경로)
- `ChatAttachmentEntity` + `ChatAttachmentRepository` (findBySessionId, findByExpiresAtBefore)
- LocalDevInitializer 영향 없음(기존 테넌트는 운용 가이드 § 2-5 수동 배포 스니펫 적용)

### Phase 2 — 업로드 API + GC (1.0MD)
- `AttachmentController` (POST/DELETE)
- `AttachmentService.save()`:
  1. scope 검증(Spring Security 어노테이션)
  2. 세션 소유권 검증
  3. `MimeValidator.validate()` (magic + Content-Type)
  4. 크기/개수 제한(BIZ-099, BIZ-100)
  5. PDF면 PyMuPDF로 pages 추출 (Python `parse_document` 호출, 메타만)
  6. `StorageService.save(tenantId, "widget-attachments", ...)`
  7. INSERT
- `AttachmentGcScheduler` — `@Scheduled(fixedDelay=300000)` 매 5분
- `@PreAuthorize("hasAuthority('SCOPE_chat:upload')")` 추가

### Phase 3 — ChatController 확장 (1.0MD)
- `ContentBlock.Document` 추가 + `AnthropicAdapter.toDocumentBlockParam()`
- `AdapterCapability` 인터페이스 + 각 어댑터 구현
- `ChatController.toUnifiedMessage()`:
  - attachment_id → entity 조회
  - capability 판정
  - `ContentBlock.Image/Document` 생성 또는 `PdfTextExtractor` 폴백
- `PdfTextExtractor`는 `MCPRagClient`에 `extractText(filePath)` 메서드 추가 (기존 `parse_document` 재사용)
- 감사 로그: attachment_id 포함

### Phase 4 — 위젯 FE (1.5MD)
- `attachment-client.ts` — fetch + FormData + AbortSignal + 프로그레스(업로드 바이트)
- `widget.ts` 증분:
  - 클립 버튼 `<button class="aimbase-attach-btn">` + 파일 다이얼로그 트리거
  - 드래그앤드롭: `dragenter/dragover/drop` 이벤트 + 오버레이 `<div class="aimbase-drop-overlay">`
  - 칩 영역 `<div class="aimbase-attachments">` — 썸네일(이미지)/파일아이콘+페이지수(PDF)/제거버튼
  - 전송 버튼 disabled 조건에 "업로드 진행 중" 추가
- `chat-client.ts` — `sendMessage({text, attachmentIds: [...]})` → content 블록 생성
- 에러 UX — 크기 초과 빨간 배지, MIME 거부 한 줄 알림

### Phase 5 — 테스트 + 가이드 (1.0MD)
- **BE 단위**: `AttachmentServiceTest` (MIME 검증, 크기·개수 제한, GC 동작, 소유권)
- **BE 통합**: `AttachmentControllerIntegrationTest` (multipart + 위젯 토큰 scope)
- **BE E2E**: Anthropic 모킹 + PDF 업로드 → `/chat/completions` → ContentBlock.Document 검증 / Ollama 모킹 → 텍스트 폴백 경로
- **FE E2E**: 드래그앤드롭 시뮬레이션 + 칩 렌더 + 전송
- 가이드: `aimbase-api-guide.md` § 18 신설(첨부 API), `aimbase-ops-guide.md` § 2-6 (GC 배치/스토리지 사용량 모니터링)

---

## 7. 보안 고려사항

| 위협 | 대응 |
|------|------|
| 확장자 위조(.pdf 이름의 exe) | Magic number 검증 필수 |
| 크로스 세션 참조 (타 세션 attachment_id 주입) | 세션 소유권 검증 |
| 크기 DoS (32MB × 대량 업로드) | BIZ-099 + BIZ-100 + Rate limit(Redis) 5req/min |
| 악성 PDF (임베디드 JS) | Aimbase는 렌더링하지 않음 — LLM이 텍스트만 추출. 위젯 FE도 썸네일만 표시 |
| 스토리지 고갈 | GC 배치 + 운용 대시보드 사용량 메트릭 |
| Origin 우회 | CR-058 CORS 화이트리스트 재사용(OPTIONS preflight 포함) |
| 다른 테넌트 접근 | TenantContext 기반 DataSource 라우팅(기존) |

**Rate limit**: 기존 Redis rate limiter에 `chat:upload` 키 추가 — 세션당 5req/min, 토큰당 20req/min.

---

## 8. 비즈니스 규칙 신규

- **BIZ-099**: 위젯 첨부 파일 단일 크기 — 이미지 10MB / PDF 32MB
- **BIZ-100**: 세션당 활성 첨부 최대 10개 (만료 전 기준)
- **BIZ-101**: 첨부는 세션 TTL(24h)과 동기화되어 자동 GC

---

## 9. 마이그레이션 전략 (CR-058 § 9-4 교훈)

기존 활성 테넌트는 Flyway 자동 재migrate가 없으므로:
1. 배포 후 **운용 가이드 § 2-5 스니펫**에 V58 수동 적용 절차 추가
2. 스니펫은 `WHERE NOT EXISTS` 가드 포함 — 중복 실행 안전
3. 신규 테넌트는 `TenantOnboardingService`에서 자동 적용

---

## 10. 리스크 & 오픈 이슈

| 리스크 | 대응 |
|--------|------|
| PDF 텍스트 추출 폴백 시 Python 사이드카 부하 | 기존 RAG 파이프라인과 같은 사이드카, 전용 타임아웃 30s |
| Anthropic document 블록 크기 상한(32MB)이 프로바이더 정책 변경될 수 있음 | BIZ-099를 `global_config` 값으로 소프트 코드 예정(Phase 2에서 `platform_settings.widget.attachment.*`) |
| 대량 트래픽 시 스토리지 비용 | GC 배치 + 용량 경고 메트릭 + 사용량 초과 시 테넌트 quota 추가(후속 CR) |
| 이미지 EXIF 개인정보 | 본 CR 범위 제외, 필요 시 후속 CR로 strip 도입 |

---

## 11. 관련 문서

- 원본 요구: `docs/origins/원본_요구사항_CR061_파일업로드_20260424.md`
- 선행: `docs/T3-9_CR-058_ChatWidget_설계서.md` § 9 범위 경계
- API 가이드: `docs/guides/aimbase-api-guide.md` — v2.6.0 § 18 신설
- 운용 가이드: `docs/guides/aimbase-ops-guide.md` — v2.5.0 § 2-6 신설
