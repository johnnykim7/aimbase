package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.repository.ConversationMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 팀 공유 메모리 서비스 (PRD-201).
 *
 * TEAM scope 메모리에 대한 CRUD를 제공한다.
 * tenantId + teamId 기반으로 팀 내 공유 메모리를 관리한다.
 */
@Component
public class TeamMemoryService {

    private static final Logger log = LoggerFactory.getLogger(TeamMemoryService.class);

    private final ConversationMemoryRepository memoryRepository;

    public TeamMemoryService(ConversationMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /** 팀 메모리 저장 */
    public ConversationMemoryEntity save(String teamId, String userId,
                                          MemoryLayer layer, String content) {
        ConversationMemoryEntity entity = new ConversationMemoryEntity();
        entity.setTeamId(teamId);
        entity.setUserId(userId);
        entity.setMemoryType(layer.name());
        entity.setScope(MemoryScope.TEAM.name());
        entity.setContent(content);
        log.debug("팀 메모리 저장: teamId={}, layer={}", teamId, layer);
        return memoryRepository.save(entity);
    }

    /** 팀 메모리 — 계층별 조회 */
    public List<ConversationMemoryEntity> getByTeamAndLayer(String teamId, MemoryLayer layer) {
        return memoryRepository.findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                teamId, layer.name(), MemoryScope.TEAM.name());
    }

    /** 팀 메모리 — 전체 조회 */
    public List<ConversationMemoryEntity> getAllByTeam(String teamId) {
        return memoryRepository.findByTeamIdAndScopeOrderByCreatedAtDesc(
                teamId, MemoryScope.TEAM.name());
    }

    /** 팀 메모리 삭제 */
    public void delete(UUID id) {
        memoryRepository.deleteById(id);
    }
}
