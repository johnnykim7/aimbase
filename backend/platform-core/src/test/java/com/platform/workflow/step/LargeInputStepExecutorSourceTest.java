package com.platform.workflow.step;

import com.platform.attachment.AttachmentService;
import com.platform.config.WorkspaceProperties;
import com.platform.largeinput.*;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.router.ModelRouter;
import com.platform.rag.MCPRagClient;
import com.platform.repository.LargeInputJobRepository;
import com.platform.workflow.StepContext;
import com.platform.workflow.analysis.AnalysisActionRegistry;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CR-120: source_file 분기(작업장 경로 vs attachment_id) + whitelist 방어선 단위 테스트.
 *
 * <p>LargeInputStepExecutor 는 의존성이 많아 private loadSource 를 리플렉션으로 직접 호출한다
 * (전체 execute 통합은 e2e 영역). WorkspaceProperties 만 실제 객체로 두고 나머지는 mock.
 */
class LargeInputStepExecutorSourceTest {

    @TempDir
    Path workspace;

    private WorkspaceProperties workspaceProperties;
    private LargeInputStepExecutor executor;
    private PdfSourceLoader pdfLoader;

    @BeforeEach
    void setUp() {
        workspaceProperties = new WorkspaceProperties();
        workspaceProperties.setBase(workspace.toString());
        workspaceProperties.setWhitelistRoots(List.of(workspace.toString()));

        MCPRagClient ragClient = mock(MCPRagClient.class);
        pdfLoader = new PdfSourceLoader(ragClient); // pageCount 실패해도 totalPages=null 로 동작

        executor = new LargeInputStepExecutor(
                mock(AttachmentService.class),
                List.of(pdfLoader),
                mock(LargeInputDecomposerService.class),
                mock(HierarchicalReduceService.class),
                mock(CoverageVerifier.class),
                mock(AnalysisActionRegistry.class),
                mock(LargeInputJobRepository.class),
                mock(ConnectionAdapterFactory.class),
                mock(ModelRouter.class),
                ragClient,
                mock(com.platform.largeinput.PopplerPdfRenderer.class),
                workspaceProperties,
                mock(com.platform.workflow.event.WorkflowRunEventRecorder.class),
                new com.platform.agent.ActiveCliWorkerRegistry(),          // CR-121
                new com.platform.workflow.WorkflowCancellationRegistry()); // CR-121
    }

    private LargeInputSource invokeLoadSource(Map<String, Object> config) throws Exception {
        Method m = LargeInputStepExecutor.class.getDeclaredMethod(
                "loadSource", WorkflowStep.class, Map.class, StepContext.class);
        m.setAccessible(true);
        WorkflowStep step = new WorkflowStep("s1", "s1", WorkflowStep.StepType.LARGE_INPUT, config, null, null, null, null);
        StepContext ctx = new StepContext("run1", "wf1", "sess1", Map.of(), Map.of());
        try {
            return (LargeInputSource) m.invoke(executor, step, config, ctx);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("작업장 상대경로 PDF — whitelist 안이면 BE 가 직접 읽어 LargeInputSource 로 적재")
    void loadsWorkspaceRelativePdf() throws Exception {
        Path attachments = Files.createDirectories(workspace.resolve("attachments"));
        Files.write(attachments.resolve("notice.pdf"), "%PDF-1.4 fake".getBytes());

        LargeInputSource src = invokeLoadSource(Map.of("source_file", "attachments/notice.pdf"));

        assertThat(src).isNotNull();
        assertThat(src.mimeType()).isEqualTo("application/pdf");
        assertThat(src.bytes()).isNotNull();
        assertThat(new String(src.bytes())).startsWith("%PDF");
        assertThat(src.sourceId()).endsWith("notice.pdf");
    }

    @Test
    @DisplayName("whitelist 밖 경로(../etc/passwd) — 거부")
    void rejectsPathOutsideWhitelist() {
        assertThatThrownBy(() -> invokeLoadSource(Map.of("source_file", "../../../etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitelist");
    }

    @Test
    @DisplayName("작업장 안이지만 파일 없음 — not found 거부")
    void rejectsMissingFile() {
        assertThatThrownBy(() -> invokeLoadSource(Map.of("source_file", "attachments/missing.pdf")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("인라인 input — 텍스트 소스로 래핑(작업장/attachment 미사용)")
    void wrapsInlineInput() throws Exception {
        LargeInputSource src = invokeLoadSource(Map.of("input", "hello world inline text"));
        assertThat(src.isInlineText()).isTrue();
        assertThat(src.inlineText()).isEqualTo("hello world inline text");
        assertThat(src.mimeType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("source_file/input 둘 다 없음 — 거부")
    void rejectsNoSource() {
        assertThatThrownBy(() -> invokeLoadSource(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }
}
