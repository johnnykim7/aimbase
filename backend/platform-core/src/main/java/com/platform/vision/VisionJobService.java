package com.platform.vision;

import com.platform.domain.VisionJobEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CR-137: 영상 판독 job 오케스트레이션 — 프레임 추출 → VLM 판독.
 *
 * <p>job 을 PENDING 으로 적재하고 즉시 반환한 뒤, 별도 스레드에서
 * ffmpeg 추출 + VLM 판독을 수행한다. 수십 초~수 분이 걸려 동기 응답이 불가능하다.
 *
 * <p><b>테넌트 전파 주의</b>: {@link TenantContext} 는 ThreadLocal 이라 워커 스레드로 자동 전파되지
 * 않는다. 빠뜨리면 다른 테넌트 DB 에 쓰거나 DataSource 를 못 찾는다(BIZ-003).
 * 제출 시점의 tenantId 를 캡처해 워커 첫 줄에서 복원하고 finally 에서 지운다.
 *
 * <p>상태 전이는 {@link VisionJobStore} 에 위임한다 — 같은 빈 내부 호출은
 * {@code @Transactional} 프록시를 타지 않기 때문이다.
 *
 * <p><b>판독은 {@link OrchestratorEngine#chat} 을 재사용한다.</b> 어댑터를 직접 부르면
 * 정책 평가·감사 로깅·비용 추적(BIZ-020)을 전부 우회하게 된다. 프레임을
 * {@link ContentBlock.Image} 로 조립해 넘기면 나머지는 기존 경로가 처리한다
 * (PDF PAGE_IMAGES 와 같은 구조 — CR-136 으로 openai_compatible 경로도 열려 있다).
 */
@Service
public class VisionJobService {

    private static final Logger log = LoggerFactory.getLogger(VisionJobService.class);

    /** BIZ-112: 프레임 기본 6장(cvilite 실증 기준), 조절 범위 1~20. */
    public static final int DEFAULT_FRAMES = 6;
    public static final int MIN_FRAMES = 1;
    public static final int MAX_FRAMES = 20;

    /**
     * 기본 판독 프롬프트.
     *
     * <p><b>수량을 묻지 않는다</b>(BIZ-115): VLM 은 라벨을 정확히 나열해놓고도 총계를 틀린다
     * (실측 — 박스 9개를 전부 나열하고 "총 7개"라고 답했고, 7B/32B 모두 동일했다).
     * 수량이 필요하면 호출부가 output_schema 로 배열을 받아 코드로 세야 한다.
     */
    private static final String DEFAULT_PROMPT = """
            이 이미지들은 하나의 영상에서 시간 순서대로 추출한 프레임입니다.
            영상에 무엇이 있고 어떤 상태인지 한국어로 설명하세요.
            보이는 라벨·문자·식별자가 있으면 빠짐없이 그대로 나열하세요.
            추측하지 말고 실제로 보이는 것만 기술하세요.""";

    private final VisionJobStore store;
    private final FrameExtractor frameExtractor;
    private final OrchestratorEngine orchestrator;
    private final ExecutorService executor;

    /** 프레임 추출 작업 디렉토리 루트. 원본·프레임 모두 여기 아래에 둔다. */
    @Value("${vision.work-dir:/data/aimbase/vision-jobs}")
    private String workDirRoot;

    public VisionJobService(VisionJobStore store,
                            FrameExtractor frameExtractor,
                            OrchestratorEngine orchestrator) {
        this.store = store;
        this.frameExtractor = frameExtractor;
        this.orchestrator = orchestrator;
        // VLM 이 직렬 처리(로컬 GPU 1대)라 BE 가 무한정 밀어넣어도 이득이 없다.
        // CR-133 전사 job 과 같은 판단.
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "vision-job");
            t.setDaemon(true);
            return t;
        });
    }

    /** 요청 프레임 수를 허용 범위로 클램프한다(BIZ-112). */
    public static int clampFrames(Integer requested) {
        if (requested == null) return DEFAULT_FRAMES;
        return Math.max(MIN_FRAMES, Math.min(MAX_FRAMES, requested));
    }

    /**
     * job 을 등록하고 즉시 job_id 를 반환한다. 추출·판독은 백그라운드에서 진행된다.
     *
     * <p>CR-141 하위호환: connectionGroupId 없이 부르던 기존 호출부용.
     */
    public VisionJobEntity submit(Path video, String mimeType, String filename, long sizeBytes,
                                  Integer frames, String prompt, String connectionId, String createdBy) {
        return submit(video, mimeType, filename, sizeBytes, frames, prompt, connectionId, null, createdBy);
    }

    /**
     * job 을 등록하고 즉시 job_id 를 반환한다. 추출·판독은 백그라운드에서 진행된다.
     *
     * @param video             업로드된 영상이 저장된 경로 (컨트롤러가 미리 저장)
     * @param frames            추출할 프레임 수 (null 이면 기본 6)
     * @param connectionId      판독에 쓸 커넥션. group 과 함께 오면 group 이 이긴다(오케스트레이터 우선순위)
     * @param connectionGroupId CR-141: 커넥션 그룹. 그룹 전략으로 커넥션을 고르고 실패 시 폴백한다.
     *                          맥이 꺼져 판독이 통째로 FAILED 되던 것을 다른 커넥션으로 넘긴다.
     */
    public VisionJobEntity submit(Path video, String mimeType, String filename, long sizeBytes,
                                  Integer frames, String prompt, String connectionId,
                                  String connectionGroupId, String createdBy) {
        int frameCount = clampFrames(frames);

        VisionJobEntity job = new VisionJobEntity();
        job.setJobId(UUID.randomUUID());
        job.setConnectionId(connectionId);
        job.setFilename(filename);
        job.setMimeType(mimeType);
        job.setSizeBytes(sizeBytes);
        job.setFrameCount(frameCount);          // 요청값 — 추출 후 실제값으로 갱신된다
        job.setSourcePath(video.toString());
        job.setPrompt(prompt);
        job.setStatus(VisionJobEntity.PENDING);
        job.setCreatedBy(createdBy);
        VisionJobEntity saved = store.save(job);

        String tenantId = TenantContext.getTenantId();
        UUID jobId = saved.getJobId();
        executor.submit(() -> runJob(jobId, tenantId, video, frameCount, prompt, connectionId, connectionGroupId));

        log.info("[CR-137] 영상 판독 job 등록: job_id={} file={} ({} bytes) frames={} tenant={}",
                jobId, filename, sizeBytes, frameCount, tenantId);
        return saved;
    }

    /** 워커 본체. 예외를 밖으로 던지지 않고 job 상태에 남긴다. */
    private void runJob(UUID jobId, String tenantId, Path video,
                        int frameCount, String prompt, String connectionId, String connectionGroupId) {
        // ThreadLocal 은 상속되지 않으므로 워커에서 직접 복원해야 한다(BIZ-003).
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        }
        long startedAt = System.currentTimeMillis();
        Path frameDir = Path.of(workDirRoot, jobId.toString(), "frames");
        try {
            store.markRunning(jobId);

            // 1) 프레임 추출
            double duration = frameExtractor.probeDuration(video);
            List<Path> frames = frameExtractor.extract(video, frameCount, frameDir);
            if (frames.isEmpty()) {
                throw new IllegalStateException(
                        "no frames could be extracted — file may be corrupted or not a video");
            }
            store.markFramesExtracted(jobId, frames.size(), duration, frameDir.toString());

            // 2) 프레임 → Vision 블록 (PDF PAGE_IMAGES 와 같은 형태)
            List<ContentBlock> blocks = new ArrayList<>(frames.size() + 1);
            blocks.add(new ContentBlock.Text(
                    (prompt == null || prompt.isBlank()) ? DEFAULT_PROMPT : prompt));
            for (Path f : frames) {
                byte[] bytes = Files.readAllBytes(f);
                blocks.add(ContentBlock.Image.ofBase64(
                        "image/jpeg", Base64.getEncoder().encodeToString(bytes)));
            }

            // 3) 판독 — orchestrator 경유(정책·감사·비용 추적 유지)
            ChatRequest req = new ChatRequest(
                    null,                                   // model — 커넥션 기본값 사용
                    null,                                   // sessionId — job 은 세션에 매이지 않는다
                    List.of(UnifiedMessage.ofUserContent(blocks)),
                    false,                                  // stream
                    false,                                  // actionsEnabled — 판독만, 도구 불필요
                    null,                                   // userId
                    null,                                   // ragSourceId
                    connectionId,
                    null,                                   // toolFilter
                    null,                                   // toolChoice
                    null,                                   // responseFormat
                    connectionGroupId,                      // CR-141: 그룹 지정 시 전략 선택 + 폴백
                    null);                                  // workingDirectory

            ChatResponse response = orchestrator.chat(req);
            String text = extractText(response);

            double elapsed = (System.currentTimeMillis() - startedAt) / 1000.0;
            store.markCompleted(jobId, text, null, elapsed);
            log.info("[CR-137] 영상 판독 완료: job_id={} frames={} {}초 소요",
                    jobId, frames.size(), String.format("%.1f", elapsed));

        } catch (Exception e) {
            log.warn("[CR-137] 영상 판독 실패: job_id={} — {}", jobId, e.getMessage(), e);
            try {
                store.markFailed(jobId, e.getMessage());
            } catch (Exception inner) {
                log.error("[CR-137] 실패 상태 기록마저 실패: job_id={}", jobId, inner);
            }
        } finally {
            // 프레임은 판독이 끝나면 쓸모가 없다. 원본(sourcePath)은 재판독·감사를 위해 남긴다(BIZ-114).
            FrameExtractor.deleteQuietly(frameDir);
            TenantContext.clear();
        }
    }

    /** ChatResponse 의 텍스트 블록만 이어붙인다. */
    private static String extractText(ChatResponse response) {
        if (response == null || response.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : response.content()) {
            if (b instanceof ContentBlock.Text t && t.text() != null) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    public Optional<VisionJobEntity> find(UUID jobId) {
        return store.find(jobId);
    }
}
