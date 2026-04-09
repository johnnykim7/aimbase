package com.platform.session;

import com.platform.domain.ToolExecutionLogEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.repository.ToolExecutionLogRepository;
import com.platform.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-031 PRD-211: PostCompactRecoveryService 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class PostCompactRecoveryServiceTest {

    @Mock private ToolExecutionLogRepository toolExecutionLogRepository;
    @Mock private MemoryService memoryService;
    @Mock private ToolRegistry toolRegistry;

    private PostCompactRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new PostCompactRecoveryService(toolExecutionLogRepository, memoryService, toolRegistry);
    }

    @Test
    void recover_snipStrategy_returnsOriginalMessages() {
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hello"));

        PostCompactRecoveryService.RecoveryResult result =
                service.recover("sess-1", "user-1", messages, CompactionStrategy.SNIP);

        assertThat(result.messages()).isEqualTo(messages);
        assertThat(result.restoredFileCount()).isZero();
        assertThat(result.restoredMemoryCount()).isZero();
        verifyNoInteractions(toolExecutionLogRepository, memoryService, toolRegistry);
    }

    @Test
    void recover_nullSessionId_returnsOriginalMessages() {
        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hello"));

        PostCompactRecoveryService.RecoveryResult result =
                service.recover(null, "user-1", messages, CompactionStrategy.MICRO_COMPACT);

        assertThat(result.messages()).isEqualTo(messages);
        verifyNoInteractions(toolExecutionLogRepository);
    }

    @Test
    void recover_microCompact_restoresFilesAndMemory() {
        // 파일 참조 이력
        ToolExecutionLogEntity fileRef = new ToolExecutionLogEntity();
        fileRef.setToolName("file_read");
        fileRef.setInputSummary("src/Main.java");
        fileRef.setOutputSummary("public class Main { ... }");
        when(toolExecutionLogRepository.findRecentFileReferences("sess-1", 5))
                .thenReturn(List.of(fileRef));

        // 메모리
        when(memoryService.buildMemoryContext("sess-1", "user-1"))
                .thenReturn(List.of(
                        UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "[장기 기억] 사용자는 Java 개발자")));

        // 도구 스키마
        when(toolRegistry.getToolDefs()).thenReturn(List.of(
                new UnifiedToolDef("file_read", "파일 읽기", Map.of())));

        List<UnifiedMessage> compactedMessages = new ArrayList<>();
        compactedMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "시스템 프롬프트"));
        compactedMessages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "최근 질문"));

        PostCompactRecoveryService.RecoveryResult result =
                service.recover("sess-1", "user-1", compactedMessages, CompactionStrategy.MICRO_COMPACT);

        assertThat(result.restoredFileCount()).isEqualTo(1);
        assertThat(result.restoredMemoryCount()).isEqualTo(1);
        assertThat(result.totalTokensRestored()).isGreaterThan(0);
        // 복구 메시지가 SYSTEM 뒤에 삽입됨
        assertThat(result.messages().size()).isGreaterThan(compactedMessages.size());
    }

    @Test
    void recover_noFileReferences_stillRestoresMemoryAndTools() {
        when(toolExecutionLogRepository.findRecentFileReferences("sess-1", 5))
                .thenReturn(Collections.emptyList());
        when(memoryService.buildMemoryContext("sess-1", "user-1"))
                .thenReturn(List.of(
                        UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "[장기 기억] 중요 정보")));
        when(toolRegistry.getToolDefs()).thenReturn(Collections.emptyList());

        List<UnifiedMessage> compactedMessages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문"));

        PostCompactRecoveryService.RecoveryResult result =
                service.recover("sess-1", "user-1", compactedMessages, CompactionStrategy.AUTO_COMPACT);

        assertThat(result.restoredFileCount()).isZero();
        assertThat(result.restoredMemoryCount()).isEqualTo(1);
    }

    @Test
    void recover_exceptionInFileReferences_continuesWithMemory() {
        when(toolExecutionLogRepository.findRecentFileReferences(anyString(), anyInt()))
                .thenThrow(new RuntimeException("DB error"));
        when(memoryService.buildMemoryContext("sess-1", "user-1"))
                .thenReturn(List.of(
                        UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, "[장기 기억] 복구")));
        when(toolRegistry.getToolDefs()).thenReturn(Collections.emptyList());

        List<UnifiedMessage> messages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "질문"));

        PostCompactRecoveryService.RecoveryResult result =
                service.recover("sess-1", "user-1", messages, CompactionStrategy.SESSION_MEMORY);

        // 파일 참조 실패해도 메모리는 복구됨
        assertThat(result.restoredFileCount()).isZero();
        assertThat(result.restoredMemoryCount()).isEqualTo(1);
    }
}
