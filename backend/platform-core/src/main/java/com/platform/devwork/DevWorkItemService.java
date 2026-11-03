package com.platform.devwork;

import com.platform.domain.DevWorkItemChangeEntity;
import com.platform.domain.DevWorkItemEntity;
import com.platform.repository.DevWorkItemChangeRepository;
import com.platform.repository.DevWorkItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * CR-164: 개발 작업 상태 원천(PRD-350).
 *
 * <p>단계, 상태, 증빙 주소 중 하나라도 바뀔 때만 변경 한 줄을 남기고 {@link DevWorkItemStatusChanged} 를 낸다(BIZ-129).
 * 부르는 쪽이 같은 보고를 재시도해도 알림이 두 번 나가지 않게 하기 위해서다.
 */
@Service
public class DevWorkItemService {

    static final int MAX_WORK_ITEM_ID = 100;
    static final int MAX_FEATURE_NAME = 300;
    static final int MAX_STAGE = 50;
    static final int MAX_EVIDENCE_URL = 2000;
    private static final Set<String> EVIDENCE_SCHEMES = Set.of("http", "https");

    private final DevWorkItemRepository items;
    private final DevWorkItemChangeRepository changes;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    @Autowired
    public DevWorkItemService(DevWorkItemRepository items, DevWorkItemChangeRepository changes,
                              ApplicationEventPublisher publisher) {
        this(items, changes, publisher, Clock.systemUTC());
    }

    DevWorkItemService(DevWorkItemRepository items, DevWorkItemChangeRepository changes,
                       ApplicationEventPublisher publisher, Clock clock) {
        this.items = items;
        this.changes = changes;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** 상태 보고 한 번. featureName 은 없는 작업을 만들 때만 필수다. */
    public record Report(String workItemId, String featureName, String stage,
                         DevWorkStatus status, String evidenceUrl) {}

    /** change 는 바뀐 것이 없으면 null 이다. */
    public record Outcome(DevWorkItemEntity item, DevWorkItemChangeEntity change) {}

    public Outcome report(Report report, DevWorkChangeSource source, UUID workflowRunId) {
        String workItemId = requireText(report.workItemId(), "work_item_id", MAX_WORK_ITEM_ID);
        String stage = requireText(report.stage(), "stage", MAX_STAGE);
        if (report.status() == null) {
            throw invalid("status");
        }
        String featureName = optionalText(report.featureName(), "feature_name", MAX_FEATURE_NAME);
        String evidenceUrl = normalizeEvidenceUrl(report.evidenceUrl());

        OffsetDateTime now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        DevWorkItemEntity item = items.findById(workItemId).orElse(null);
        if (item == null) {
            if (featureName == null) {
                throw invalid("feature_name");
            }
            item = new DevWorkItemEntity();
            item.setWorkItemId(workItemId);
            item.setCreatedAt(now);
        } else if (stage.equals(item.getStage()) && report.status() == item.getStatus()
                && Objects.equals(evidenceUrl, item.getEvidenceUrl())) {
            if (featureName != null && !featureName.equals(item.getFeatureName())) {
                item.setFeatureName(featureName);
                item.setUpdatedAt(now);
                item = items.save(item);
            }
            return new Outcome(item, null);
        }

        if (featureName != null) {
            item.setFeatureName(featureName);
        }
        item.setStage(stage);
        item.setStatus(report.status());
        item.setEvidenceUrl(evidenceUrl);
        item.setStatusChangedAt(now);
        item.setUpdatedAt(now);
        if (workflowRunId != null) {
            item.setLastWorkflowRunId(workflowRunId);
        }
        DevWorkItemEntity savedItem = items.save(item);

        DevWorkItemChangeEntity change = new DevWorkItemChangeEntity();
        change.setWorkItemId(workItemId);
        change.setStage(stage);
        change.setStatus(report.status());
        change.setEvidenceUrl(evidenceUrl);
        change.setChangedAt(now);
        change.setSource(source);
        change.setWorkflowRunId(workflowRunId);
        DevWorkItemChangeEntity savedChange = changes.save(change);

        publisher.publishEvent(new DevWorkItemStatusChanged(
                workItemId, savedItem.getFeatureName(), stage, report.status(), evidenceUrl,
                now, savedChange.getId(), source, workflowRunId));
        return new Outcome(savedItem, savedChange);
    }

    public Page<DevWorkItemEntity> list(Pageable pageable) {
        return items.findAllByOrderByStatusChangedAtDesc(pageable);
    }

    public Optional<DevWorkItemEntity> find(String workItemId) {
        return items.findById(workItemId);
    }

    public List<DevWorkItemChangeEntity> recentChanges(String workItemId) {
        return changes.findTop100ByWorkItemIdOrderByChangedAtDesc(workItemId);
    }

    public static DevWorkStatus parseStatus(Object raw) {
        if (raw == null) {
            throw invalid("status");
        }
        try {
            return DevWorkStatus.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("status");
        }
    }

    static String requireText(String value, String field, int maxLength) {
        String text = optionalText(value, field, maxLength);
        if (text == null) {
            throw invalid(field);
        }
        return text;
    }

    static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() > maxLength) {
            throw invalid(field);
        }
        return text;
    }

    /** 빈 글은 없음이다. 있으면 http, https 의 절대 주소만 받는다. */
    static String normalizeEvidenceUrl(String value) {
        String url = optionalText(value, "evidence_url", MAX_EVIDENCE_URL);
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || !EVIDENCE_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null) {
                throw invalid("evidence_url");
            }
        } catch (IllegalArgumentException e) {
            throw invalid("evidence_url");
        }
        return url;
    }

    static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("dev_work_item_invalid:" + field);
    }
}
