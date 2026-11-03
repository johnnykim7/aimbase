package com.platform.devwork;

import com.platform.domain.DevWorkItemChangeEntity;
import com.platform.domain.DevWorkItemEntity;
import com.platform.repository.DevWorkItemChangeRepository;
import com.platform.repository.DevWorkItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("개발 작업 상태 원천 (CR-164, BIZ-129)")
class DevWorkItemServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:30:00.123456789Z");

    private DevWorkItemRepository items;
    private DevWorkItemChangeRepository changes;
    private ApplicationEventPublisher publisher;
    private DevWorkItemService service;

    @BeforeEach
    void setUp() {
        items = mock(DevWorkItemRepository.class);
        changes = mock(DevWorkItemChangeRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        when(items.findById(any())).thenReturn(Optional.empty());
        when(items.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(changes.save(any())).thenAnswer(inv -> {
            DevWorkItemChangeEntity c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        service = new DevWorkItemService(items, changes, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DevWorkItemEntity existing(String stage, DevWorkStatus status, String evidenceUrl) {
        DevWorkItemEntity e = new DevWorkItemEntity();
        e.setWorkItemId("W-1");
        e.setFeatureName("주문 취소 화면");
        e.setStage(stage);
        e.setStatus(status);
        e.setEvidenceUrl(evidenceUrl);
        return e;
    }

    @Test
    @DisplayName("TC-DW-101 없는 작업을 보고하면 항목, 변경 한 줄, 상태 변경 이벤트가 하나씩 생긴다")
    void createsItem() {
        DevWorkItemService.Outcome out = service.report(new DevWorkItemService.Report(
                "W-1", "주문 취소 화면", "기능", DevWorkStatus.IN_PROGRESS, null), DevWorkChangeSource.API, null);

        assertThat(out.item().getStatus()).isEqualTo(DevWorkStatus.IN_PROGRESS);
        assertThat(out.change()).isNotNull();
        assertThat(out.change().getSource()).isEqualTo(DevWorkChangeSource.API);
        verify(items, times(1)).save(any());
        verify(changes, times(1)).save(any());
        ArgumentCaptor<DevWorkItemStatusChanged> event = ArgumentCaptor.forClass(DevWorkItemStatusChanged.class);
        verify(publisher, times(1)).publishEvent(event.capture());
        assertThat(event.getValue().workItemId()).isEqualTo("W-1");
        assertThat(event.getValue().changeId()).isEqualTo(out.change().getId());
    }

    @Test
    @DisplayName("TC-DW-102 새 작업에 기능 이름이 없으면 막고 아무것도 남기지 않는다")
    void newItemNeedsFeatureName() {
        assertThatThrownBy(() -> service.report(new DevWorkItemService.Report(
                "W-1", " ", "기능", DevWorkStatus.IN_PROGRESS, null), DevWorkChangeSource.API, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dev_work_item_invalid:feature_name");
        verify(items, never()).save(any());
        verify(changes, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("TC-DW-103 단계, 상태, 증빙이 같으면 변경 줄도 이벤트도 없다")
    void sameReportIsNoop() {
        when(items.findById("W-1")).thenReturn(Optional.of(existing("기능", DevWorkStatus.IN_PROGRESS, "https://e.com/1")));

        DevWorkItemService.Outcome out = service.report(new DevWorkItemService.Report(
                "W-1", null, "기능", DevWorkStatus.IN_PROGRESS, "https://e.com/1"), DevWorkChangeSource.API, null);

        assertThat(out.change()).isNull();
        verify(changes, never()).save(any());
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("TC-DW-104 증빙 주소만 바뀌어도 변경 한 줄과 이벤트가 생긴다")
    void evidenceChangeCounts() {
        when(items.findById("W-1")).thenReturn(Optional.of(existing("기능", DevWorkStatus.IN_PROGRESS, null)));

        DevWorkItemService.Outcome out = service.report(new DevWorkItemService.Report(
                "W-1", null, "기능", DevWorkStatus.IN_PROGRESS, "https://e.com/2"), DevWorkChangeSource.API, null);

        assertThat(out.change()).isNotNull();
        assertThat(out.item().getEvidenceUrl()).isEqualTo("https://e.com/2");
        verify(publisher, times(1)).publishEvent(any(DevWorkItemStatusChanged.class));
    }

    @Test
    @DisplayName("TC-DW-105 칸 제한을 넘으면 그 칸 이름으로 막는다")
    void fieldLimits() {
        assertThatThrownBy(() -> service.report(new DevWorkItemService.Report(
                "W".repeat(101), "f", "기능", DevWorkStatus.IN_PROGRESS, null), DevWorkChangeSource.API, null))
                .hasMessage("dev_work_item_invalid:work_item_id");
        assertThatThrownBy(() -> service.report(new DevWorkItemService.Report(
                "W-1", "f", "s".repeat(51), DevWorkStatus.IN_PROGRESS, null), DevWorkChangeSource.API, null))
                .hasMessage("dev_work_item_invalid:stage");
        assertThatThrownBy(() -> DevWorkItemService.parseStatus("DONE"))
                .hasMessage("dev_work_item_invalid:status");
        assertThatThrownBy(() -> service.report(new DevWorkItemService.Report(
                "W-1", "f", "기능", DevWorkStatus.IN_PROGRESS, "ftp://e.com/1"), DevWorkChangeSource.API, null))
                .hasMessage("dev_work_item_invalid:evidence_url");
        assertThat(DevWorkItemService.parseStatus("completed")).isEqualTo(DevWorkStatus.COMPLETED);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("TC-DW-106 바뀐 시각은 밀리초로 자르고 이벤트에도 같은 값을 싣는다")
    void changedAtTruncatedToMillis() {
        DevWorkItemService.Outcome out = service.report(new DevWorkItemService.Report(
                "W-1", "f", "기능", DevWorkStatus.COMPLETED, null), DevWorkChangeSource.API, null);

        OffsetDateTime expected = OffsetDateTime.parse("2026-10-28T06:30:00.123Z");
        assertThat(out.change().getChangedAt()).isEqualTo(expected);
        assertThat(out.item().getStatusChangedAt()).isEqualTo(expected);
        ArgumentCaptor<DevWorkItemStatusChanged> event = ArgumentCaptor.forClass(DevWorkItemStatusChanged.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().changedAt()).isEqualTo(expected);
        assertThat(event.getValue().toWorkflowInput().get("reported_at")).isEqualTo("2026-10-28T06:30:00.123Z");
    }
}
