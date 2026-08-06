package com.platform.vision;

import com.platform.domain.VisionJobEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * CR-137 VisionJobService — 프레임 추출 → VLM 판독 오케스트레이션.
 *
 * <p>중점 검증: 워커 스레드로의 {@link TenantContext} 전파(BIZ-003).
 * 빠뜨리면 다른 테넌트 DB 에 쓰거나 DataSource 를 못 찾는다.
 */
class VisionJobServiceTest {

    private VisionJobStore store;
    private FrameExtractor frameExtractor;
    private OrchestratorEngine orchestrator;
    private VisionJobService service;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        store = mock(VisionJobStore.class);
        frameExtractor = mock(FrameExtractor.class);
        orchestrator = mock(OrchestratorEngine.class);

        when(store.save(any(VisionJobEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new VisionJobService(store, frameExtractor, orchestrator);
        ReflectionTestUtils.setField(service, "workDirRoot", tmp.toString());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ── 프레임 수 클램프 (BIZ-112) ────────────────────────────

    @Test
    void clampFrames_defaultsToSix() {
        assertThat(VisionJobService.clampFrames(null)).isEqualTo(6);
    }

    @Test
    void clampFrames_enforcesRange() {
        assertThat(VisionJobService.clampFrames(0)).isEqualTo(1);
        assertThat(VisionJobService.clampFrames(-5)).isEqualTo(1);
        assertThat(VisionJobService.clampFrames(100)).isEqualTo(20);
        assertThat(VisionJobService.clampFrames(3)).isEqualTo(3);
    }

    // ── job 등록 ─────────────────────────────────────────────

    @Test
    void submit_persistsPendingAndReturnsImmediately(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("v.mp4");
        Files.writeString(video, "fake");
        stubSuccessfulRun(tmp, "판독 결과");

        VisionJobEntity job = service.submit(video, "video/mp4", "v.mp4", 1234L,
                6, null, "conn-1", "tester");

        assertThat(job.getJobId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(VisionJobEntity.PENDING);
        assertThat(job.getSizeBytes()).isEqualTo(1234L);
        assertThat(job.getFrameCount()).isEqualTo(6);
        assertThat(job.getSourcePath()).isEqualTo(video.toString());
    }

    /** ★ 핵심: 워커 스레드에서 TenantContext 가 복원되어야 한다(BIZ-003). */
    @Test
    void runJob_propagatesTenantContextToWorkerThread(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("v.mp4");
        Files.writeString(video, "fake");
        stubSuccessfulRun(tmp, "ok");

        AtomicReference<String> tenantSeenInWorker = new AtomicReference<>();
        // markRunning 은 워커 스레드 안에서 호출된다 — 그 시점의 테넌트를 포착한다.
        doAnswer(inv -> {
            tenantSeenInWorker.set(TenantContext.getTenantId());
            return null;
        }).when(store).markRunning(any(UUID.class));

        TenantContext.setTenantId("tenant-abc");
        service.submit(video, "video/mp4", "v.mp4", 10L, 2, null, "conn-1", "tester");

        awaitUntil(() -> tenantSeenInWorker.get() != null);
        assertThat(tenantSeenInWorker.get()).isEqualTo("tenant-abc");
    }

    @Test
    void runJob_completesWithVlmText(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("v.mp4");
        Files.writeString(video, "fake");
        stubSuccessfulRun(tmp, "선반에 박스가 보입니다");

        service.submit(video, "video/mp4", "v.mp4", 10L, 2, null, "conn-1", "tester");

        awaitUntil(() -> mockingDetails(store).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("markCompleted")));

        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(store).markCompleted(any(UUID.class), result.capture(), any(), anyDouble());
        assertThat(result.getValue()).isEqualTo("선반에 박스가 보입니다");
    }

    /** 프레임이 이미지 블록으로 조립되어 모델에 전달되어야 한다(CR-136 경로). */
    @Test
    void runJob_sendsFramesAsImageBlocks(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("v.mp4");
        Files.writeString(video, "fake");
        stubSuccessfulRun(tmp, "ok", 3);

        service.submit(video, "video/mp4", "v.mp4", 10L, 3, "직접 지시", "conn-1", "tester");

        awaitUntil(() -> !mockingDetails(orchestrator).getInvocations().isEmpty());

        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).chat(req.capture());

        List<ContentBlock> blocks = req.getValue().messages().get(0).content();
        // 텍스트 프롬프트 1개 + 이미지 3개
        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(0)).isInstanceOf(ContentBlock.Text.class);
        assertThat(((ContentBlock.Text) blocks.get(0)).text()).isEqualTo("직접 지시");
        assertThat(blocks.subList(1, 4)).allMatch(b -> b instanceof ContentBlock.Image);
        assertThat(req.getValue().connectionId()).isEqualTo("conn-1");
        // 판독만 하므로 도구 루프는 꺼져 있어야 한다
        assertThat(req.getValue().actionsEnabled()).isFalse();
    }

    /**
     * CR-141: connection_group_id 가 오케스트레이터까지 전달되어야 한다.
     * 여기서 끊기면 그룹 전략·폴백이 통째로 안 걸린다(맥이 꺼지면 그냥 FAILED).
     */
    @Test
    void runJob_passesConnectionGroupToOrchestrator(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("v.mp4");
        Files.writeString(video, "fake");
        stubSuccessfulRun(tmp, "ok", 2);

        service.submit(video, "video/mp4", "v.mp4", 10L, 2, "지시", null, "vision-pool", "tester");

        awaitUntil(() -> !mockingDetails(orchestrator).getInvocations().isEmpty());

        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).chat(req.capture());
        assertThat(req.getValue().connectionGroupId()).isEqualTo("vision-pool");
    }

    /** 그룹을 안 주던 기존 호출부는 그대로 동작해야 한다(8-arg 오버로드). */
    @Test
    void submit_withoutGroup_keepsNullGroup(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("v.mp4");
        Files.writeString(video, "fake");
        stubSuccessfulRun(tmp, "ok", 2);

        service.submit(video, "video/mp4", "v.mp4", 10L, 2, "지시", "conn-1", "tester");

        awaitUntil(() -> !mockingDetails(orchestrator).getInvocations().isEmpty());

        ArgumentCaptor<ChatRequest> req = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestrator).chat(req.capture());
        assertThat(req.getValue().connectionGroupId()).isNull();
        assertThat(req.getValue().connectionId()).isEqualTo("conn-1");
    }

    /** 프레임을 한 장도 못 뽑으면 FAILED 로 끝나야 한다(예외가 새어나가면 안 됨). */
    @Test
    void runJob_failsWhenNoFramesExtracted(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("broken.mp4");
        Files.writeString(video, "not a video");
        when(frameExtractor.probeDuration(any())).thenReturn(-1.0);
        when(frameExtractor.extract(any(), anyInt(), any())).thenReturn(List.of());

        service.submit(video, "video/mp4", "broken.mp4", 10L, 6, null, "conn-1", "tester");

        awaitUntil(() -> markFailedCalled());
        verify(store).markFailed(any(UUID.class), anyString());
        verify(orchestrator, never()).chat(any());
    }

    /** 모델 호출이 터져도 job 은 FAILED 로 정상 종료되어야 한다. */
    @Test
    void runJob_failsGracefullyWhenModelThrows(@TempDir Path tmp) throws Exception {
        Path video = tmp.resolve("v.mp4");
        Files.writeString(video, "fake");
        stubFrames(tmp, 2);
        when(orchestrator.chat(any())).thenThrow(new RuntimeException("model unavailable"));

        service.submit(video, "video/mp4", "v.mp4", 10L, 2, null, "conn-1", "tester");

        awaitUntil(() -> markFailedCalled());

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(store).markFailed(any(UUID.class), msg.capture());
        assertThat(msg.getValue()).contains("model unavailable");
    }

    // ── helpers ──────────────────────────────────────────────

    /**
     * 워커 스레드가 비동기로 도므로 조건이 참이 될 때까지 짧게 폴링한다.
     * (awaitility 를 쓰지 않는 이유: 이 테스트 하나 때문에 의존성을 추가하지 않는다)
     */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting", e);
            }
        }
        throw new AssertionError("condition not met within 5s");
    }

    private boolean markFailedCalled() {
        return mockingDetails(store).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("markFailed"));
    }

    private void stubSuccessfulRun(Path tmp, String vlmText) throws Exception {
        stubSuccessfulRun(tmp, vlmText, 2);
    }

    private void stubSuccessfulRun(Path tmp, String vlmText, int frameCount) throws Exception {
        stubFrames(tmp, frameCount);
        when(orchestrator.chat(any())).thenReturn(new ChatResponse(
                "resp-1", "qwen2.5-vl-7b-instruct", null,
                List.of(new ContentBlock.Text(vlmText)), null, null, 0.0));
    }

    /** 실제 파일이 있어야 Files.readAllBytes 가 동작하므로 더미 JPEG 을 만들어 둔다. */
    private void stubFrames(Path tmp, int count) throws Exception {
        Path dir = tmp.resolve("stub-frames");
        Files.createDirectories(dir);
        java.util.List<Path> frames = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path f = dir.resolve("f" + i + ".jpg");
            Files.write(f, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) i});
            frames.add(f);
        }
        when(frameExtractor.probeDuration(any())).thenReturn(12.0);
        when(frameExtractor.extract(any(), anyInt(), any())).thenReturn(frames);
    }

    private static double anyDouble() {
        return org.mockito.ArgumentMatchers.anyDouble();
    }
}
