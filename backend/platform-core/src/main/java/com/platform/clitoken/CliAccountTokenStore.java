package com.platform.clitoken;

import com.platform.domain.master.CliAccountTokenEntity;
import com.platform.domain.master.CliTokenExpiryNoticeEntity;
import com.platform.repository.master.CliAccountTokenRepository;
import com.platform.repository.master.CliTokenExpiryNoticeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-196: Master DB 의 실행기 계정 토큰 상태(4.8-3)와 만료 알림 기록(4.8-2). 플랫폼 화면이 테넌트 DB 를 열지 않고
 * 모든 테넌트를 보게 하려고 Master 에 둔다(BIZ-024).
 */
@Component
public class CliAccountTokenStore {

    /** 실행기에 물은 결과 하나. reachable 이 false 면 나머지 값은 쓰지 않는다. */
    public record FolderState(String agentName, String configDir, List<String> connectionIds, boolean reachable,
                              String managedBy, boolean present, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {}

    private final CliAccountTokenRepository tokens;
    private final CliTokenExpiryNoticeRepository notices;

    public CliAccountTokenStore(CliAccountTokenRepository tokens, CliTokenExpiryNoticeRepository notices) {
        this.tokens = tokens;
        this.notices = notices;
    }

    /**
     * 그 테넌트의 줄을 states 대로 맞춘다. 꺼진 실행기는 앞서 확인한 값을 두고 reachable 과 checked_at 만 바꾼다.
     *
     * @param removeMissing true 면 states 에 없는 그 테넌트의 줄을 지운다. 연결이 지워진 폴더를 목록에서 걷어 내려고 둔다
     * @return states 순서대로 저장한 줄
     */
    @Transactional(transactionManager = "masterTransactionManager")
    public List<CliAccountTokenEntity> saveTenant(String tenantId, List<FolderState> states, boolean removeMissing,
                                                  OffsetDateTime now) {
        Map<String, CliAccountTokenEntity> existing = new HashMap<>();
        for (CliAccountTokenEntity e : tokens.findByTenantId(tenantId)) {
            existing.put(key(e.getAgentName(), e.getConfigDir()), e);
        }
        List<CliAccountTokenEntity> saved = new ArrayList<>();
        for (FolderState s : states) {
            CliAccountTokenEntity e = existing.remove(key(s.agentName(), s.configDir()));
            if (e == null) {
                e = new CliAccountTokenEntity();
                e.setTenantId(tenantId);
                e.setAgentName(s.agentName());
                e.setConfigDir(s.configDir());
            }
            if (s.reachable()) {
                e.setManagedBy(s.managedBy());
                e.setPresent(s.present());
                e.setIssuedAt(s.issuedAt());
                e.setExpiresAt(s.expiresAt());
            }
            e.setReachable(s.reachable());
            e.setConnectionIds(new ArrayList<>(s.connectionIds()));
            e.setCheckedAt(now);
            saved.add(tokens.save(e));
        }
        if (removeMissing && !existing.isEmpty()) {
            tokens.deleteAll(existing.values());
        }
        return saved;
    }

    @Transactional(transactionManager = "masterTransactionManager", readOnly = true)
    public List<CliAccountTokenEntity> findAll() {
        return tokens.findAll();
    }

    @Transactional(transactionManager = "masterTransactionManager", readOnly = true)
    public boolean noticeSent(String tenantId, String agentName, String configDir, OffsetDateTime expiresAt,
                              int thresholdDays) {
        return notices.existsByTenantIdAndAgentNameAndConfigDirAndExpiresAtAndThresholdDays(
                tenantId, agentName, configDir, expiresAt, thresholdDays);
    }

    @Transactional(transactionManager = "masterTransactionManager")
    public void recordNotice(String tenantId, String agentName, String configDir, OffsetDateTime expiresAt,
                             int thresholdDays, String channels, OffsetDateTime now) {
        CliTokenExpiryNoticeEntity n = new CliTokenExpiryNoticeEntity();
        n.setTenantId(tenantId);
        n.setAgentName(agentName);
        n.setConfigDir(configDir);
        n.setExpiresAt(expiresAt);
        n.setThresholdDays(thresholdDays);
        n.setChannels(channels);
        n.setSentAt(now);
        notices.save(n);
    }

    private static String key(String agentName, String configDir) {
        return agentName + "\n" + (configDir != null ? configDir : "");
    }
}
