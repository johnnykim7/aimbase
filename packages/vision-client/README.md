# @aimbase/vision-client

현장에서 찍은 **영상을 올리면 서버가 프레임을 뽑아 VLM 으로 판독**해주는 Aimbase Vision API(CR-137) 클라이언트.

## 이 패키지가 하지 않는 것

**UI 를 제공하지 않는다.** 촬영 버튼·미리보기·결과 화면은 소비앱이 자기 화면톤에 맞춰 만든다.
촬영 자체는 브라우저 표준 한 줄이라 감쌀 알맹이가 없다:

```html
<input type="file" accept="video/*" capture="environment" />
```

앱마다 재구현되면 안 되는 것은 UI 가 아니라 **업로드 신뢰성**(대용량 파일, 모바일 네트워크 끊김,
폴링 루프)이고 이 SDK 는 그 부분만 담당한다.

## 설치

```bash
npm i @aimbase/vision-client
```

의존성 0. 브라우저 `fetch`/`XMLHttpRequest` 만 쓴다.

## 사용

```ts
import { VisionClient } from '@aimbase/vision-client';

const client = new VisionClient({
  baseUrl: 'https://aimbase.example.com',
  apiKey: 'plat-xxxx',
  tenantId: 'bp_wes',
});

// 업로드 + 판독 완료까지 한 번에
const job = await client.analyze(file, {
  connectionId: 'qwen-vl-7b-mac-001',
  frames: 6,
  prompt: '선반에 보이는 SKU 라벨을 모두 나열하세요.',
  onUploadProgress: (r) => setProgress(r),      // 0~1
  onProgress: (j) => setStatus(j.status),       // PENDING → RUNNING → COMPLETED
});

console.log(job.result);
```

`submit` 과 `waitFor` 를 나눠 쓸 수도 있다 — 업로드만 하고 화면을 떠났다가
나중에 `jobId` 로 결과를 다시 받는 경우:

```ts
const { jobId } = await client.submit(file, { connectionId: 'qwen-vl-7b-mac-001' });
// …
const job = await client.waitFor(jobId);
```

## 지원 포맷 / 제한

| 항목 | 값 |
|---|---|
| MIME | MP4 / MOV / WebM / AVI (매직넘버로 판정) |
| 최대 크기 | 100MB |
| 프레임 수 | 기본 6, 범위 1~20 |
| 원본 보존 | 24시간 (재판독·감사용) |

**사진 1장은 이 SDK 가 아니다.** 프레임 추출이 필요 없으므로 기존 채팅 첨부 경로
(`POST /api/v1/chat/attachments`)를 쓴다.

## ⚠️ 수량은 VLM 에 직접 묻지 말 것

VLM 은 **라벨을 정확히 나열해놓고도 총계를 틀린다.** 실측에서 박스 9개의 SKU 를 전부 정확히
읽어놓고 "총 7개"라고 답했고, 7B·32B 모두 동일했다 — 모델을 키워도 해결되지 않는 구조적 한계다.

```ts
// ❌ 하지 말 것
prompt: '박스가 몇 개입니까?'

// ✅ 나열시키고 코드로 센다
prompt: '보이는 SKU 라벨을 한 줄에 하나씩 모두 나열하세요.'
const count = job.result.split('\n').filter(Boolean).length;
```

## 에러

```ts
import { VisionError, VisionJobFailedError } from '@aimbase/vision-client';

try {
  const job = await client.analyze(file);
} catch (e) {
  if (e instanceof VisionJobFailedError) {
    // 서버에서 판독이 실패 (e.job.errorMessage)
  } else if (e instanceof VisionError) {
    // HTTP 오류 — e.status: 400 형식/비영상, 413 크기초과, 422 MIME 불일치
  }
}
```

## 참조

- API 명세: `docs/guides/aimbase-api-guide.md` § 23
- 설계서: `docs/T3-16_CR-137_영상프레임추출_설계서.md`
