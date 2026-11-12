package com.platform.session;

import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.llm.adapter.LLMAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationRewindServiceTest {

    @Mock private SessionStore sessionStore;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private ConnectionGroupSelector connectionGroupSelector;
    @Mock private LLMAdapter cliAdapter;
    @Mock private com.platform.repository.ChatGeneratedImageRepository imageRepository;

    private ConversationRewindService service;

    @BeforeEach
    void setUp() {
        service = new ConversationRewindService(sessionStore, cancellationRegistry,
                connectionAdapterFactory, connectionGroupSelector, imageRepository);
    }

    @Test
    @DisplayName("IT-09-004 2 답이_도는_세션은_되감기를_session_busy_로_거절한다")
    void busy_session_is_rejected_before_touching_history() {
        // CR-175 TC-CHT-003
        when(cancellationRegistry.isActive("s-1")).thenReturn(true);

        assertThatThrownBy(() -> service.rewind("s-1", 2, "conn-1", null))
                .isInstanceOf(RewindRejectedException.class)
                .extracting("reason").isEqualTo(RewindRejectedException.Reason.SESSION_BUSY);
        verify(sessionStore, never()).rewind(anyString(), anyInt());
    }

    @Test
    void cli_worker_is_closed_after_rewind_so_it_forgets_removed_turns() {
        when(cancellationRegistry.isActive("s-1")).thenReturn(false);
        when(connectionAdapterFactory.isCliConnection("cli-1")).thenReturn(true);
        when(connectionAdapterFactory.getAdapter("cli-1")).thenReturn(cliAdapter);

        service.rewind("s-1", 2, "cli-1", null);

        verify(sessionStore).rewind("s-1", 2);
        verify(cliAdapter).cleanupSession("s-1");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TC-CHT-106 1 되감은_뒤_되감긴_묶음의_그림에_지운_표시를_한다")
    void rewound_turn_images_are_soft_deleted() {
        when(cancellationRegistry.isActive("s-1")).thenReturn(false);

        service.rewind("s-1", 2, null, null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sessionStore, imageRepository);
        order.verify(sessionStore).rewind("s-1", 2);
        order.verify(imageRepository).softDeleteOrphanedBySessionId(
                org.mockito.ArgumentMatchers.eq("s-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TC-CHT-106 2 그림_지운_표시가_실패해도_되감기는_성공으로_끝난다")
    void image_soft_delete_failure_does_not_fail_rewind() {
        when(cancellationRegistry.isActive("s-1")).thenReturn(false);
        when(imageRepository.softDeleteOrphanedBySessionId(
                org.mockito.ArgumentMatchers.eq("s-1"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("db down"));

        service.rewind("s-1", 2, null, null);

        verify(sessionStore).rewind("s-1", 2);
    }

    @Test
    void every_member_of_connection_group_is_checked() {
        when(cancellationRegistry.isActive("s-1")).thenReturn(false);
        when(connectionGroupSelector.memberConnectionIds("grp")).thenReturn(List.of("api-1", "cli-2"));
        when(connectionAdapterFactory.isCliConnection("api-1")).thenReturn(false);
        when(connectionAdapterFactory.isCliConnection("cli-2")).thenReturn(true);
        when(connectionAdapterFactory.getAdapter("cli-2")).thenReturn(cliAdapter);

        service.rewind("s-1", 0, null, "grp");

        verify(cliAdapter).cleanupSession("s-1");
        verify(connectionAdapterFactory, never()).getAdapter("api-1");
    }
}
