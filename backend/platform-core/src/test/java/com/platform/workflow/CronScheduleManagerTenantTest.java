package com.platform.workflow;

import com.platform.domain.master.ScheduledJobEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.policy.AuditLogger;
import com.platform.repository.master.ScheduledJobRepository;
import com.platform.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-189: 예약 표는 마스터에 모든 테넌트 것이 함께 있다. 지우기와 켜고 끄기는 그 테넌트 것만 한다.
 */
class CronScheduleManagerTenantTest {

    private ScheduledJobRepository repository;
    private CronScheduleManager manager;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduledJobRepository.class);
        manager = new CronScheduleManager(repository, mock(TaskScheduler.class), mock(WorkflowEngine.class),
                mock(ToolRegistry.class), mock(AuditLogger.class), mock(PlatformMetrics.class));
        ScheduledJobEntity job = new ScheduledJobEntity();
        job.setId("job-1");
        job.setName("daily");
        job.setTenantId("tenant_a");
        when(repository.findById("job-1")).thenReturn(Optional.of(job));
    }

    @Test
    @DisplayName("자기 테넌트 예약은 지운다")
    void deletes_own_tenant_job() {
        manager.deleteJob("job-1", "tenant_a");

        verify(repository).delete(any(ScheduledJobEntity.class));
    }

    @Test
    @DisplayName("다른 테넌트 예약은 없는 것과 같은 답을 주고 지우지 않는다")
    void other_tenant_job_is_not_found_and_kept() {
        assertThatThrownBy(() -> manager.deleteJob("job-1", "tenant_b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 스케줄");

        verify(repository, never()).delete(any(ScheduledJobEntity.class));
    }

    @Test
    @DisplayName("테넌트가 없으면 지우지 않는다")
    void no_tenant_is_refused() {
        assertThatThrownBy(() -> manager.deleteJob("job-1", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).delete(any(ScheduledJobEntity.class));
    }

    @Test
    @DisplayName("다른 테넌트 예약은 켜고 끄지 않는다")
    void other_tenant_job_is_not_toggled() {
        assertThatThrownBy(() -> manager.toggleActive("job-1", false, "tenant_b"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any(ScheduledJobEntity.class));
    }
}
