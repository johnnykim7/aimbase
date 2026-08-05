# Aimbase 음성 인식(STT) 연동 가이드

> 소비앱이 음성을 텍스트로 바꾸려 할 때 **어느 경로를 쓸지 고르고, 어떻게 붙이는지**를 다룬다.
> 엔진은 세 경로 모두 Whisper 다. 차이는 **길이·응답 방식·비용**이다.

---

## 0. 먼저 — 어느 경로를 쓸 것인가

| | 경로 A: 짧은 발화 | 경로 B: 긴 녹음 | 경로 C: 실시간 |
|---|---|---|---|
| **용도** | 메모, 검색어, 명령 | 회의록, 통화 녹음 | 작업자 간 의사소통, 실시간 자막 |
| **길이** | ~60초 | 30분~2시간 | 제한 없음(말하는 동안) |
| **응답** | 동기 (2~4초) | 비동기 (job + 폴링) | 스트리밍 (계속) |
| **엔드포인트** | `POST /api/v1/chat/stt` | `POST /api/v1/transcribe-jobs` | `WS /stream` (사이드카 직결) |
| **입력** | 녹음 완료된 파일 | 이미 있는 파일 | 마이크 실시간 |
| **상태** | ✅ 운영 | ✅ 운영 | ⚠️ 미배포 (§4 참조) |

**고르는 기준**

- 사용자가 **버튼 누르고 말한 뒤 놓는다** → **A**
- 이미 만들어진 **파일이 있다** → **B**
- 말하는 **도중에** 텍스트가 필요하다 → **C**

> ⚠️ **C를 쉽게 고르지 말 것.** 실시간은 구현·운영 비용이 A/B보다 훨씬 크고, 정확도도 떨어진다(§4-5 실측).
> "녹음 끝나고 2초 뒤에 나와도 되는" 요구라면 A가 정답이다.

---

## 1. 공통 — 인증

모든 REST 호출은 API Key 를 쓴다.

```
X-API-Key: <발급받은 키>
X-Tenant-Id: <테넌트 id>      # 키에 tenant_id 가 있으면 생략 가능
```

Base URL: `https://aimbase.banpoom.co.kr`

> 위젯에서 호출하는 경우(경로 A)는 위젯 토큰 + `chat:stt` scope 로도 된다. § 2-3 참조.

---

## 2. 경로 A — 짧은 발화 (동기)

### 2-1. 요청

```bash
curl -X POST https://aimbase.banpoom.co.kr/api/v1/chat/stt \
  -H "X-API-Key: $KEY" \
  -H "X-Tenant-Id: bp_wes" \
  -F "file=@memo.webm" \
  -F "language=ko" \
  -F "session_id=sess-123"
```

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `file` | ✅ | 오디오 바이너리 |
| `language` | ❌ | `ko`/`en`/… 또는 `auto`(기본) |
| `session_id` | ❌ | Rate limit 단위. 없으면 `anonymous` 로 묶여 다른 사용자와 한도를 공유한다 |

### 2-2. 응답

```json
{
  "success": true,
  "data": { "text": "내일 오전 10시에 물류팀과 재고 실사 일정 조율하기",
            "language": "ko", "duration_sec": 6.88 }
}
```

### 2-3. 브라우저에서 녹음해 보내기

```js
const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
const rec = new MediaRecorder(stream, { mimeType: 'audio/webm' });
const chunks = [];
rec.ondataavailable = e => chunks.push(e.data);
rec.onstop = async () => {
  const fd = new FormData();
  fd.append('file', new Blob(chunks, { type: 'audio/webm' }), 'memo.webm');
  fd.append('language', 'ko');
  const r = await fetch(`${BASE}/api/v1/chat/stt`, {
    method: 'POST', headers: { 'X-API-Key': KEY, 'X-Tenant-Id': TENANT }, body: fd,
  });
  const { data } = await r.json();
  console.log(data.text);
};
rec.start();            // 버튼 누를 때
// rec.stop();          // 버튼 뗄 때
```

> **HTTPS 필수**: `getUserMedia` 는 secure context 에서만 동작한다(localhost 예외).

### 2-4. 제한과 에러

| 코드 | 원인 | 대응 |
|---|---|---|
| 400 `STT_FILE_TOO_LONG` | **60초 초과** | 경로 B 를 쓸 것 |
| 400 `STT_FILE_TOO_LARGE` | 25MB 초과 | 압축하거나 경로 B |
| 400 `STT_INVALID_MIME` | 허용 포맷 아님 | webm/mp4/mp3/wav/ogg 로 |
| 429 `STT_RATE_LIMITED` | 세션당 분당 10회 초과 | `session_id` 를 사용자별로 분리했는지 확인 |
| 503 `STT_PROVIDER_UNAVAILABLE` | STT 백엔드 불가 | §5 참조 |

> 60초 제한은 **의도적**이다. 위젯 마이크 입력을 보호하려고 건 값이므로, 긴 녹음 때문에 풀지 않는다.

---

## 3. 경로 B — 긴 녹음 (비동기 job)

1시간 회의는 전사에만 수 분이 걸려 동기 응답이 불가능하다. **등록 → 폴링** 구조를 쓴다.

### 3-1. 등록

```bash
curl -X POST https://aimbase.banpoom.co.kr/api/v1/transcribe-jobs \
  -H "X-API-Key: $KEY" -H "X-Tenant-Id: bp_wes" \
  -F "file=@meeting.m4a" \
  -F "language=ko"
```

```json
{ "success": true,
  "data": { "job_id": "524b89de-...", "status": "PENDING",
            "filename": "meeting.m4a", "size_bytes": 1128824 } }
```

HTTP **202** 로 즉시 돌아온다. 전사는 백그라운드에서 진행된다.

### 3-2. 폴링

```bash
curl "https://aimbase.banpoom.co.kr/api/v1/transcribe-jobs/$JOB_ID" \
  -H "X-API-Key: $KEY" -H "X-Tenant-Id: bp_wes"
```

```json
{ "success": true,
  "data": { "job_id": "524b89de-...", "status": "COMPLETED",
            "text": "자 그러면 회의를 시작하겠습니다 …",
            "language": "ko", "duration_sec": 3600.0, "elapsed_sec": 243.1,
            "created_at": "…", "started_at": "…", "finished_at": "…" } }
```

`status`: `PENDING` → `RUNNING` → `COMPLETED`, 실패 시 `FAILED`(+`error`).

**타임라인이 필요하면** `?include_segments=true` 로 `segments[{start,end,text}]` 를 함께 받는다.
1시간 회의면 수백 건이라 기본은 제외한다.

### 3-3. 폴링 주기

전사 소요는 대략 **오디오 길이 ÷ 14**(1시간 → 약 4분). 5~10초 간격이면 충분하다.

```js
async function waitForResult(jobId) {
  for (;;) {
    const r = await fetch(`${BASE}/api/v1/transcribe-jobs/${jobId}`, { headers });
    const { data } = await r.json();
    if (data.status === 'COMPLETED') return data.text;
    if (data.status === 'FAILED') throw new Error(data.error);
    await new Promise(s => setTimeout(s, 5000));
  }
}
```

### 3-4. 제한

| 항목 | 값 |
|---|---|
| 최대 파일 크기 | 1GB (413 반환) |
| 길이 제한 | 없음 |
| 동시 처리 | 2건 (초과분은 대기) |

---

## 4. 경로 C — 실시간 스트리밍 (WebSocket)

> ⚠️ **현재 사이드카 단독 동작이며 aimbase 연동 전이다.** 토큰 발급·테넌트 격리·결과 적재가 아직 없다.
> 소비앱에서 **먼저 체험해보는 용도**로만 쓰고, 운영 투입 전에 연동 범위를 협의할 것.

### 4-1. 연결

```
ws://<사이드카 호스트>:8291/stream?api_key=<키>&session_id=<식별자>
```

WebSocket 은 커스텀 헤더를 못 붙이는 클라이언트가 많아 **쿼리 파라미터**로 인증한다.
키가 틀리면 `close(1008)`.

### 4-2. 프로토콜

**보내기** — `MediaRecorder` 청크를 바이너리로 계속 전송. 빈 프레임(`b""`)은 "말 끝났다" 신호.

**받기** — JSON:

```json
{ "type": "transcript",
  "text":   "내일 오전 10시에 물류팀과",   // 확정 — 바뀌지 않음
  "delta":  "물류팀과",                    // 이번에 새로 확정된 부분
  "buffer": "재고 실사일정" }              // 미확정 — 다음 턴에 바뀔 수 있음
```

종료 시 `{ "type": "final", "text": "…" }`.

> **확정/미확정을 반드시 구분해 표시할 것.** 실시간 전사는 뒷말이 나오면 앞말 해석이 바뀐다.
> `text`는 검정, `buffer`는 회색으로 그리면 사용자가 "아직 확정 안 된 부분"을 인지한다.
> `buffer`를 확정처럼 보여주면 글자가 계속 바뀌어 읽기 힘들다.

### 4-3. 클라이언트 예시

```js
const ws = new WebSocket(`ws://HOST:8291/stream?api_key=${KEY}&session_id=${id}`);
ws.binaryType = 'arraybuffer';

ws.onopen = async () => {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const rec = new MediaRecorder(stream, { mimeType: 'audio/webm' });
  rec.ondataavailable = e => {
    if (e.data.size > 0 && ws.readyState === WebSocket.OPEN) ws.send(e.data);
  };
  rec.start(1000);          // 1초 timeslice
};

ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.type === 'transcript') render(m.text, m.buffer);   // 확정 + 회색 미확정
  if (m.type === 'final')      render(m.text, '');
};
```

**동작 확인용 페이지**: `http://<사이드카>:8291/stream-test` — 마이크 버튼이 있는 테스트 화면.

### 4-4. 동시 사용

연결마다 독립 세션이라 서로 섞이지 않는다(동시 2연결 검증 완료).
**동시 5명 이상은 아직 부하 측정 전이다.**

### 4-5. 정확도 — 실시간은 배치보다 떨어진다

같은 문장("내일 오전 10시에 물류팀과 재고 실사 일정 조율하기") 실측:

| 청크 | 결과 |
|---|---|
| 1초 | "…물류팀과 **최고**실사일정 조율아기! 해" |
| 2초 (기본) | "…물류팀과" |
| 3초 | "…물류팀과 **재고** 실사일정" |

청크가 짧을수록 반응이 빠르지만 문맥이 부족해 부정확하다. **지연↔정확도 트레이드오프**이며,
같은 파일을 경로 B로 처리하면 완벽하게 나온다. 정확도가 중요하면 실시간을 쓰지 말 것.

---

## 5. STT 백엔드 — 로컬 vs OpenAI

경로 A/B 는 백엔드를 고를 수 있다. **엔진은 양쪽 다 Whisper 이고 차이는 비용과 데이터 경계뿐이다.**

| | 로컬 (기본) | OpenAI |
|---|---|---|
| 비용 | **0원** | $0.006/분 |
| 오디오 | 외부로 나가지 않음 | OpenAI 로 전송 |
| 가용성 | 사이드카가 떠 있어야 함 | 항상 |

소비앱은 **신경 쓸 필요가 없다** — 플랫폼 설정(`stt.provider`)으로 정해지고 호출 방식은 동일하다.
로컬이 실패하면 OpenAI 로 자동 폴백한다(설정으로 끌 수 있음).

> 운영 참고: 폴백이 동작하려면 해당 테넌트에 OpenAI 커넥션이 등록돼 있어야 한다.
> 없으면 로컬 실패 시 `503 STT_PROVIDER_UNAVAILABLE` 이 그대로 나간다.

---

## 6. 오디오 포맷 권장

| 상황 | 권장 |
|---|---|
| 브라우저 녹음 | `audio/webm` (MediaRecorder 기본) |
| 모바일 앱 | `audio/mp4` (m4a) |
| 기존 파일 | mp3 / wav / ogg 모두 가능 |

내부적으로 16kHz 모노로 변환하므로 **고음질로 보낼 필요는 없다.** 파일만 커진다.

---

## 7. 자주 겪는 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| `getUserMedia` 가 undefined | HTTP 페이지 | HTTPS 로 접속 (localhost 예외) |
| 마이크 권한 거부 | 브라우저 설정 | 사이트 권한에서 마이크 허용 |
| 60초 넘는 파일이 400 | 경로 A 제한 | 경로 B(`/transcribe-jobs`) 사용 |
| job 이 `PENDING` 에서 안 변함 | 사이드카 미가동 | 운영팀에 사이드카 상태 확인 요청 |
| 실시간에서 같은 말 반복 | 무음 구간 환각 | 서버에서 필터링됨. 계속되면 보고 |
| 전사 결과가 빈 문자열 | 무음이거나 너무 짧음 | 녹음 길이·마이크 입력 확인 |

---

## 8. 문의 전 확인할 것

문제 보고 시 아래를 함께 주면 빠르다.

- 어느 경로(A/B/C)인지
- `job_id` (경로 B) 또는 `session_id`
- 요청한 오디오의 길이·포맷·크기
- 응답 본문 전문 (에러 코드 포함)

---

## 변경 이력

| 버전 | 날짜 | 변경 내용 |
|---|---|---|
| v1.0.0 | 2026-08-05 | 초판. 3경로(짧은 발화/긴 녹음/실시간) 선택 기준 + 연동 방법. CR-060/133/134/135 반영 |
