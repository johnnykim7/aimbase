# Aimbase 영상·사진 판독(Vision) 연동 가이드

> 소비앱이 **현장에서 찍은 사진·영상을 AI에게 판독시킬 때** 어느 경로를 쓸지 고르고, 어떻게 붙이는지를 다룬다.
> 재고조사·입고검수처럼 "찍어서 판별"이 필요한 업무용이다.
> 엔진은 VLM(Vision Language Model) 이다. 기본은 사내 맥에서 도는 **로컬 Qwen2.5-VL** 이라 이미지가 외부로 나가지 않는다.

---

## 0. 먼저 — 어느 경로를 쓸 것인가

| | 경로 A: 사진 | 경로 B: 영상 |
|---|---|---|
| **용도** | 라벨 읽기, 상태 확인, 재고 대조 | 상품 검수(여러 각도), 개봉 과정 |
| **입력** | 이미지 1~N장 | 영상 파일 (서버가 프레임 추출) |
| **응답** | 동기 (30~50초) | 비동기 (job + 폴링, 40~90초) |
| **엔드포인트** | `POST /api/v1/chat/completions` | `POST /api/v1/vision-jobs` |
| **상태** | ✅ 운영 | ✅ 운영 |

**고르는 기준**

- 한 장면으로 판단이 되면 → **A**. 대부분 여기서 끝난다.
- **여러 각도·시간에 걸친 변화**를 봐야 하면 → **B**

> ⚠️ **B를 쉽게 고르지 말 것.** 영상은 프레임 N장을 넣는 것이고, 프레임이 늘면 그만큼 느려진다.
> 사진 3장으로 되는 일을 영상으로 하면 느리기만 하다. 실제로 현장 판단은 결정적 순간 1~3장이면 끝나는 경우가 많다.

---

## 1. 공통 — 인증

```
X-API-Key: <발급받은 키>
X-Tenant-Id: <테넌트 id>      # 키에 tenant_id 가 있으면 생략 가능
```

Base URL: `https://aimbase.banpoom.co.kr`

---

## 2. ★ 먼저 읽을 것 — VLM 이 못 하는 것

붙이기 전에 알아야 실패하지 않는다. 아래는 **실측 결과**(2026-08-06, Qwen2.5-VL 7B/32B 동일 조건).

창고 선반 사진(3단, 박스 **9개**, 라벨 SKU-A101~C302, 한 박스에 X 표시)으로 검증:

| 물어본 것 | 7B | 32B |
|---|---|---|
| 선반 단수 | ✅ 3단 | ✅ 3단 |
| **박스 개수** | ❌ **7개** | ❌ **7개** |
| SKU 라벨 9개 | ✅ 전부 정확 | ✅ 전부 정확 |
| 이상 있는 박스 | ✅ 지적 | ✅ 지적 |

### 2-1. 수량은 VLM 에게 묻지 마라

**두 모델 다 라벨 9개를 정확히 나열해놓고 "총 7개"라고 답했다.** 모델을 4배 키워도 안 고쳐진다.

**우회법 — 나열시키고 코드로 센다:**

```
❌ "박스가 몇 개야?"
✅ "보이는 SKU 라벨을 전부 JSON 배열로 나열해줘"
   → 앱에서 array.length
```

나열은 정확하므로 이 방법은 신뢰할 수 있다.

### 2-2. 잘 되는 것 / 안 되는 것

| | 신뢰도 |
|---|---|
| **읽기** — 라벨·SKU·바코드 번호·표기 문자 | ✅ 높음. 7B로도 충분 |
| **이상 감지(이진)** — 손상 있다/없다, 표시 있다/없다 | ✅ 쓸 만함 |
| **세기** — 개수, 수량 | ❌ 쓰지 말 것 (§2-1) |
| **정량 측정** — 몇 cm, 몇 % | ❌ 신뢰 불가 |
| **등급 판정** — A/B/C/D | ⚠️ 모델별 편차. §5 참조 |

### 2-3. 진짜 값은 "대조"에 있다

사진에서 **읽은 값**과 **WMS/OMS 데이터**를 비교하는 것 — 여기서 실제 업무 가치가 나온다.

```
사진 판독: "SKU-B203, 라벨 손상"
시스템:    "이 로케이션은 SKU-B201 20개여야 함"
→ 불일치 검출
```

VLM 단독으로는 못 하고, 앱이 판독 결과를 자기 데이터와 대조해야 한다.

---

## 3. 경로 A — 사진 (동기)

### 3-1. 요청

**형식은 OpenAI 스타일이다.** `image_url` + `data:` URI 를 쓴다.

```bash
curl -X POST https://aimbase.banpoom.co.kr/api/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $KEY" \
  -H "X-Tenant-Id: bp-wes" \
  -d '{
    "connection_id": "qwen-vl-32b-mac-001",
    "stream": false,
    "messages": [{
      "role": "user",
      "content": [
        {"type": "text", "text": "이 선반의 SKU 라벨을 전부 JSON 배열로 나열해줘. 설명 금지."},
        {"type": "image_url",
         "image_url": {"url": "data:image/jpeg;base64,/9j/4AAQ..."}}
      ]
    }]
  }'
```

이미지를 **여러 장** 넣으려면 `image_url` 파트를 여러 개 넣으면 된다.

### 3-2. 응답

```json
{
  "success": true,
  "data": {
    "id": "chatcmpl-...",
    "model": "qwen2.5-vl-7b-instruct",
    "session_id": "d84d76b9-...",
    "content": [{"type": "text", "text": "1. 선반 3단\n2. ..."}],
    "usage": {"input_tokens": 5811, "output_tokens": 114, "cost_usd": 0.0}
  }
}
```

판독 결과는 `data.content[0].text`. 로컬 모델이라 `cost_usd` 는 0 이다.

### 3-3. 브라우저에서 찍어 보내기

모바일 브라우저에서 카메라를 바로 띄우려면 `capture` 속성을 쓴다.

```html
<input type="file" accept="image/*" capture="environment" id="cam">
```

```js
document.getElementById('cam').onchange = async (e) => {
  const file = e.target.files[0];
  const b64 = await new Promise(r => {
    const fr = new FileReader();
    fr.onload = () => r(fr.result);      // "data:image/jpeg;base64,..." 통째로 온다
    fr.readAsDataURL(file);
  });

  const res = await fetch('https://aimbase.banpoom.co.kr/api/v1/chat/completions', {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'X-API-Key': KEY, 'X-Tenant-Id': TENANT},
    body: JSON.stringify({
      connection_id: 'qwen-vl-32b-mac-001',
      stream: false,
      messages: [{role: 'user', content: [
        {type: 'text', text: '보이는 SKU 라벨을 JSON 배열로만 출력.'},
        {type: 'image_url', image_url: {url: b64}}   // FileReader 결과 그대로
      ]}]
    })
  });
  const json = await res.json();
  console.log(json.data.content[0].text);
};
```

> 폰 사진은 4~12MB 다. 그대로 보내면 업로드가 느리다. `canvas` 로 긴 변 1024px 정도로 줄여 보내면
> 판독 품질은 그대로면서 훨씬 빠르다(§6-2 와 같은 이유).

### 3-4. 제한과 에러

| 상황 | 응답 |
|---|---|
| 이미지가 너무 커서 모델 컨텍스트 초과 | `400` (§7-1) |
| 인증 누락 | `403` |
| `X-Tenant-Id` 누락 | `400 X-Tenant-Id header is required` |
| 첨부 API(`/chat/attachments`)에 영상 업로드 | `400` — vision-jobs 로 안내 |

> ⚠️ **이 경로(`image_url` data URI)는 영상을 걸러주지 않는다.** 서버가 MIME 을 검사하지 않고
> 그대로 모델에 넘기므로, 영상 base64 를 넣으면 에러 없이 엉뚱한 결과가 나온다.
> **영상은 앱이 판단해서 §4 로 보내야 한다.** 파일 선택 시 `file.type.startsWith('video/')` 로 갈라라.

---

## 4. 경로 B — 영상 (비동기 job)

영상을 올리면 **서버가 ffmpeg 로 프레임 N장을 균등 간격으로 뽑아** VLM 에 태운다.
앱이 프레임을 뽑을 필요 없다.

### 4-1. 등록

```bash
curl -X POST https://aimbase.banpoom.co.kr/api/v1/vision-jobs \
  -H "X-API-Key: $KEY" \
  -H "X-Tenant-Id: bp-wes" \
  -F "file=@inspection.mp4;type=video/mp4" \
  -F "connection_id=qwen-vl-32b-mac-001" \
  -F "frames=6" \
  -F "prompt=반품 검수. JSON만 출력. {\"packaging_damaged\":bool,\"item_damaged\":bool,\"grade\":\"A|B|C|D\"}"
```

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `file` | ✅ | 영상 파일 (multipart). **최대 100MB**, MP4·MOV·WebM·AVI |
| `connection_id` | ⚠️ | 판독에 쓸 모델 (§5). **사실상 필수 — 아래 경고 참조** |
| `connection_group_id` | | 커넥션 그룹. 전략에 따라 고르고 **실패 시 폴백**한다. 주면 `connection_id` 보다 우선 |
| `frames` | | 뽑을 프레임 수. 기본 **6**, 범위 **1~20** (벗어나면 자동으로 잘림) |
| `prompt` | | 판독 지시문 (§6-3 — `prompt_key` 로 대체 가능) |

> ⚠️ **`connection_id` 를 반드시 지정하라.** 생략하면 판독 모델이 자동 선택되는데,
> 그 규칙이 **비전용이 아니다** — 테넌트에 CLI 커넥션(Claude CLI 러너)이 있으면 그쪽이 먼저 잡힌다
> (CR-075 자동 라우팅). 그러면 이미지가 VLM 이 아닌 CLI 경로로 흘러가 엉뚱한 결과가 나거나 실패한다.
> bp-wes 처럼 CLI 커넥션을 쓰는 테넌트는 **거의 확실히 이 함정에 걸린다.**

형식은 **매직넘버로 판정**한다(확장자·Content-Type 은 위조 가능). iOS 가 `.mov` 를
`video/mp4` 로 보내는 등 기기마다 표기가 달라, 영상은 `video/*` 계열이면 통과시킨다.

| 에러 | 원인 |
|---|---|
| `400` | 영상이 아님(이미지·PDF 는 §3 경로) / `file` 누락 |
| `413` | 100MB 초과 |
| `422` | 매직넘버와 Content-Type 계열 불일치 |

응답 (`202 Accepted`):

```json
{"success": true, "data": {
  "job_id": "95d474a0-9091-4e20-bda6-a9c2a63741cb",
  "status": "PENDING", "filename": "inspection.mp4",
  "size_bytes": 8096651, "frames": 6
}}
```

### 4-2. 폴링

```bash
curl https://aimbase.banpoom.co.kr/api/v1/vision-jobs/{job_id} \
  -H "X-API-Key: $KEY" -H "X-Tenant-Id: bp-wes"
```

```json
{"success": true, "data": {
  "job_id": "95d474a0-...",
  "status": "COMPLETED",
  "duration_sec": 15.95,
  "frame_count": 6,
  "result": "```json\n{\"packaging_damaged\": false, ... \"grade\": \"A\"}\n```",
  "elapsed_sec": 86.9,
  "error_message": null
}}
```

`status`: `PENDING` → `RUNNING` → `COMPLETED` | `FAILED`

> **`result` 는 문자열이다.** JSON 을 요청했어도 모델이 ` ```json ` 코드펜스로 감싸는 경우가 많으니
> 펜스를 벗겨내고 파싱할 것.

### 4-3. 폴링 주기

10~15초 간격을 권한다. 6프레임 기준 40~90초 걸리므로 1초 폴링은 낭비다.

### 4-4. 프레임 수 정하기

**프레임이 늘면 그만큼 느려진다.** 지연의 대부분이 이미지 처리 구간이라 거의 비례한다.

| frames | 쓸 때 |
|---|---|
| 3 | 빠른 확인. 대부분 이걸로 충분 |
| **6** | 기본값. 여러 각도가 필요할 때 |
| 9+ | 긴 영상에서 변화 추적. 느린 것 감수 |

### 4-5. 업로드한 영상은 얼마나 보관되나

원본 영상은 **24시간 보관 후 자동 삭제**된다. 재판독·이의제기 대응을 위해 남겨두는 것이고,
추출된 프레임은 판독 직후 지워진다. job 기록(판독 결과 텍스트)은 이력으로 계속 남는다.

→ **영상 원본을 오래 보관해야 하는 업무라면 앱이 따로 저장해야 한다.**

---

### 4-6. SDK 로 붙이기 (선택)

업로드 진행률·폴링 루프를 직접 짜기 싫으면 `@aimbase/vision-client` 를 쓴다.
**UI 는 없다** — 업로드 신뢰성과 폴링만 담당한다.

```ts
import { VisionClient } from '@aimbase/vision-client';

const client = new VisionClient({
  baseUrl: 'https://aimbase.banpoom.co.kr',
  apiKey: KEY,
  tenantId: 'bp-wes',
});

const job = await client.analyze(file, {
  connectionId: 'qwen-vl-32b-mac-001',
  frames: 6,
  prompt: '반품 검수. JSON만 출력.',
  onUploadProgress: (r) => setProgress(r),   // 0~1
  onProgress: (j) => setStatus(j.status),    // PENDING → RUNNING → COMPLETED
});
console.log(job.result);
```

`submit()` 과 `waitFor(jobId)` 로 나눠 쓰면 업로드 후 화면을 떠났다가 나중에 결과를 받을 수 있다.
상세는 `packages/vision-client/README.md`.

---

## 5. 모델 선택 — 커넥션

| connection_id | 모델 | 속도 | 정확도 |
|---|---|---|---|
| `qwen-vl-32b-mac-001` | Qwen2.5-VL 32B | 사진 30~50초 / 영상 6프레임 **87초** | 높음 |
| `qwen-vl-7b-mac-001` | Qwen2.5-VL 7B | 사진 ~34초 / 영상 ~40초 | 라벨 읽기는 동급, **등급 판정은 오판 있음** |

**실측 비교** — 같은 반품 영상(정답 A등급):

- 32B → **A** (정답)
- 7B → **C** (오판)

**고르는 기준**: 라벨·문자를 읽기만 하면 **7B**(2배 빠름). 상태·등급을 판단해야 하면 **32B**.

> 두 모델 모두 **사내 맥에서 도는 로컬 모델**이다. 이미지가 외부로 나가지 않고 비용이 0 이다.
> 대신 그 맥이 꺼져 있으면 판독이 안 된다(§7-3) — 그게 곤란하면 §5-1 참조.

### 5-1. 커넥션 그룹 — 하나 죽어도 판독은 되게

`connection_id` 를 하나 박아두면 그 커넥션이 죽는 순간 job 이 통째로 `FAILED` 된다.
**`connection_group_id`** 를 주면 그룹 전략으로 커넥션을 고르고 **실패 시 다른 커넥션으로 폴백**한다.

```bash
-F "connection_group_id=vision-pool"     # connection_id 대신
```

그룹은 운영 화면(Connections → 그룹)에서 만든다.

> ⚠️ **폴백이 늘 이득은 아니다.** 32B → 7B 로 떨어지면 판독은 되지만 **등급 판정이 틀릴 수 있다**
> (위 실측: 같은 영상에 32B=A, 7B=C). 그룹에 성격이 다른 모델을 섞을 때는
> "느려도 정확" vs "빨라도 오판" 중 무엇을 원하는지 정하고 넣어라.
> 라벨 읽기처럼 두 모델 결과가 같은 용도라면 폴백이 순수 이득이다.

---

## 6. 프롬프트 요령

### 6-1. JSON 을 강제하면 파싱이 쉬워진다

```
반품 검수. JSON만 출력. 설명·마크다운 금지.
{"packaging_damaged":bool,"item_damaged":bool,"stains_or_dirt":bool,"grade":"A|B|C|D"}
```

서술형으로 두면 마크다운 제목·불릿·"결론" 문단까지 길게 쓴다.

> **속도 기대는 하지 말 것.** 실측상 생성 구간은 전체의 4% 뿐이라(§7-2)
> 출력을 1/10 로 줄여도 총 시간은 거의 안 준다. JSON 의 이점은 **속도가 아니라 파싱 가능성**이다.

### 6-2. 판단 기준을 프롬프트에 적어라

VLM 은 "우리 회사의 B등급"을 모른다. 기준을 주지 않으면 모델이 제멋대로 해석한다.

```
등급 기준:
A = 미개봉·손상 없음
B = 개봉했으나 상품 정상
C = 경미한 손상 또는 오염
D = 재판매 불가
```

### 6-3. 프롬프트를 어디에 둘 것인가

판독 지시문은 **검수 기준이 바뀔 때마다 손대는 물건**이다. 코드에 상수로 박아두면
문구 한 줄 고치는 데 소비앱 배포가 필요해진다. aimbase 는 이걸 위해
**프롬프트 템플릿**(`prompt_templates`)을 테넌트별로 관리한다 — FE 관리 화면에서 수정하면
배포 없이 반영된다.

**방법 1 — `prompt_key` 로 넘긴다 (권장)**

원문 대신 키만 보내면 서버가 템플릿을 렌더해서 쓴다.

```bash
curl -X POST https://aimbase.banpoom.co.kr/api/v1/vision-jobs \
  -H "X-API-Key: $KEY" -H "X-Tenant-Id: bp-wes" \
  -F "file=@inspection.mp4;type=video/mp4" \
  -F "prompt_key=vision.return.grade" \
  -F 'prompt_vars={"category":"의류"}'      # 템플릿 변수(선택)
```

| 파라미터 | 설명 |
|---|---|
| `prompt_key` | 템플릿 키. `prompt` 와 함께 주면 **`prompt` 원문이 이긴다** |
| `prompt_vars` | 템플릿 변수 JSON. `{{category}}` 같은 자리를 채운다 |

없는 키를 주면 `400` 이다(조용히 빈 프롬프트로 돌지 않는다).

**방법 2 — 원문을 그대로 보낸다**

`prompt` 파라미터에 문자열을 실어 보낸다. 일회성 판독·실험용으로 편하다.

**템플릿은 이렇게 관리한다**

```bash
# 목록
GET  /api/v1/prompt-templates
# 렌더 결과 미리보기
POST /api/v1/prompt-templates/{key}/{version}/render
# 생성
POST /api/v1/prompt-templates
```

> 사진 경로(§3)는 `messages[].content[].text` 에 직접 싣는 OpenAI 형식이라 `prompt_key` 가 없다.
> 템플릿을 쓰려면 앱이 `/api/v1/prompt-templates/{key}/{version}/render` 로 문구를 받아
> `text` 에 넣으면 된다.

---

## 7. 자주 겪는 문제

### 7-1. `400: null` — 컨텍스트 초과

가장 흔한 실패다. 로그에는 이렇게 찍힌다:

```
request (16227 tokens) exceeds the available context size (8192 tokens)
```

**원인**: 고해상도 이미지를 많이 넣었다. 1080×1920 프레임 6장 = 16,227 토큰.

**해결**:
- 영상 경로는 서버가 자동으로 긴 변 768px 로 줄이므로 대개 문제없다
- 사진 경로는 **앱이 보내기 전에 줄여야 한다** (긴 변 1024px 권장)
- 그래도 나면 프레임 수·이미지 수를 줄인다

### 7-2. 왜 이렇게 느린가

실측 분해(32B, 6프레임):

```
이미지 처리(prompt eval):  81.7초  ← 96%
답변 생성(eval):            3.7초  ←  4%
```

**지연의 대부분은 이미지를 읽는 구간**이다. 따라서:

- ✅ 효과 있음: **프레임/이미지 수 줄이기**, **해상도 줄이기**, 7B 쓰기
- ❌ 효과 거의 없음: 출력 짧게 하기(4% 구간)

### 7-3. 판독이 아예 안 될 때

로컬 모델이 사내 맥에서 돌기 때문에, 그 맥이 꺼져 있거나 LM Studio 가 내려가 있으면 실패한다.
`FAILED` 가 계속 나면 플랫폼 담당자에게 모델 서버 상태를 확인 요청할 것.

---

## 8. 문의 전 확인할 것

1. `X-API-Key` / `X-Tenant-Id` 를 보냈는가
2. 사진 경로인데 영상을 올리지 않았는가 (→ `/api/v1/vision-jobs`)
3. 이미지가 너무 크지 않은가 (§7-1)
4. **수량을 물어보고 있지 않은가** (§2-1 — 이건 버그가 아니라 모델 한계다)
5. 타임아웃이 충분한가 (사진 120초+, 영상은 폴링)

---

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| 1.0.1 | 2026-08-06 | 코드 대조 후 정정·보강. ① **§3-4 정정** — "영상을 사진 경로에 올리면 400"은 사실이 아니다. `image_url`(data URI) 경로는 서버가 MIME 을 검사하지 않아 영상이 에러 없이 통과하고 엉뚱한 결과가 나온다. 400 을 내는 것은 `/chat/attachments` 경로이며, 영상 분기는 **앱이 `file.type` 으로 판단**해야 한다. ② §4-1 에 상한 100MB·지원 4포맷·프레임 범위 1~20·에러표(400/413/422) 추가. ③ §4-5 신설 — 원본 24h 보관 후 자동 삭제(장기 보관은 앱 책임). ④ §4-6 신설 — `@aimbase/vision-client` SDK 사용법 |
| 1.0.0 | 2026-08-06 | 신설. 사진(동기)·영상(비동기 job) 2경로 + VLM 한계·실측 데이터 (CR-136/CR-137) |
