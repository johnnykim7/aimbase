package com.platform.session;

import com.platform.domain.ResponseCacheEntity;
import com.platform.rag.EmbeddingService;
import com.platform.repository.ResponseCacheRepository;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 임베딩을 쓸 수 없는 서버에서는 의미 비교를 건너뛰고, 같은 질문 찾기만 한다.
 */
class ResponseCacheServiceTest {

    private ResponseCacheRepository repository;
    private EmbeddingService embeddingService;
    private ResponseCacheService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResponseCacheRepository.class);
        embeddingService = mock(EmbeddingService.class);
        service = new ResponseCacheService(repository, embeddingService,
                mock(TenantDataSourceManager.class), mock(TransactionTemplate.class));

        when(repository.findByCacheKeyAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.empty());
        when(repository.findByCacheKey(anyString())).thenReturn(Optional.empty());
        when(repository.findSemanticallySimlar(any(), anyDouble())).thenReturn(Optional.empty());
        when(repository.save(any(ResponseCacheEntity.class))).thenAnswer(inv -> {
            ResponseCacheEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    void 임베딩이_없으면_조회에서_의미_비교를_건너뛴다() {
        when(embeddingService.isAvailable()).thenReturn(false);

        Optional<String> hit = service.get("model", null, "주문 상태 알려줘");

        assertThat(hit).isEmpty();
        verify(embeddingService, never()).embed(anyString());
        verify(repository, never()).findSemanticallySimlar(any(), anyDouble());
    }

    @Test
    void 임베딩이_없으면_저장에서_임베딩을_만들지_않는다() {
        when(embeddingService.isAvailable()).thenReturn(false);

        service.put("model", null, "주문 상태 알려줘", "답", 10);

        verify(repository).save(any(ResponseCacheEntity.class));
        verify(embeddingService, after(300).never()).embed(anyString());
        verify(repository, never()).updateEmbedding(any(), any());
    }

    @Test
    void 임베딩이_있으면_조회와_저장에서_임베딩을_쓴다() {
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingService.vectorToString(any())).thenReturn("[0.1]");

        service.get("model", null, "주문 상태 알려줘");
        service.put("model", null, "주문 상태 알려줘", "답", 10);

        verify(repository).findSemanticallySimlar("[0.1]", 0.95);
        verify(repository, timeout(2000)).updateEmbedding(any(UUID.class), any());
    }
}
