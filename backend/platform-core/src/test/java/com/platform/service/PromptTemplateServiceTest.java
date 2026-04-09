package com.platform.service;

import com.platform.domain.PromptTemplateEntity;
import com.platform.domain.PromptTemplateEntityId;
import com.platform.repository.PromptTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CR-036: PromptTemplateService 단위 테스트.
 */
class PromptTemplateServiceTest {

    private PromptTemplateRepository repository;
    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(PromptTemplateRepository.class);
        service = new PromptTemplateService(repository);
    }

    // ─── getTemplate ───────────────────────────────────────────

    @Nested
    @DisplayName("getTemplate()")
    class GetTemplate {

        @Test
        @DisplayName("DB 조회 성공 → 캐시 저장 → 두 번째 호출 시 캐시 히트")
        void dbHit_thenCacheHit() {
            PromptTemplateEntity entity = createEntity("system.greeting", 1,
                    "orchestration", "Hello {{user}}!");

            when(repository.findActiveByKey("system.greeting"))
                    .thenReturn(Optional.of(entity));

            // 첫 호출 — DB 조회
            String first = service.getTemplate("system.greeting");
            assertThat(first).isEqualTo("Hello {{user}}!");

            // 두 번째 호출 — 캐시 히트, DB 재호출 없어야 함
            String second = service.getTemplate("system.greeting");
            assertThat(second).isEqualTo("Hello {{user}}!");

            verify(repository, times(1)).findActiveByKey("system.greeting");
        }

        @Test
        @DisplayName("DB에 없을 때 resources/prompts/*.txt 파일 폴백")
        void dbMiss_fileFallback() {
            when(repository.findActiveByKey("test.fallback"))
                    .thenReturn(Optional.empty());

            String result = service.getTemplate("test.fallback");
            assertThat(result).isNotNull();
            assertThat(result).contains("file fallback template");
        }

        @Test
        @DisplayName("DB와 파일 모두 없을 때 null 반환")
        void dbMiss_fileMiss_returnsNull() {
            when(repository.findActiveByKey("nonexistent.key.xyz"))
                    .thenReturn(Optional.empty());

            String result = service.getTemplate("nonexistent.key.xyz");
            assertThat(result).isNull();
        }
    }

    // ─── render ────────────────────────────────────────────────

    @Nested
    @DisplayName("render()")
    class Render {

        @Test
        @DisplayName("변수 치환 정상 동작")
        void variableSubstitution() {
            PromptTemplateEntity entity = createEntity("greet", 1,
                    "test", "Hello {{name}}, welcome to {{place}}!");
            when(repository.findActiveByKey("greet"))
                    .thenReturn(Optional.of(entity));

            String result = service.render("greet",
                    Map.of("name", "Alice", "place", "Aimbase"));

            assertThat(result).isEqualTo("Hello Alice, welcome to Aimbase!");
        }

        @Test
        @DisplayName("존재하지 않는 변수는 {{variable}} 그대로 유지")
        void missingVariable_keptAsIs() {
            PromptTemplateEntity entity = createEntity("partial", 1,
                    "test", "Hi {{name}}, your id is {{id}}.");
            when(repository.findActiveByKey("partial"))
                    .thenReturn(Optional.of(entity));

            String result = service.render("partial",
                    Map.of("name", "Bob"));

            assertThat(result).isEqualTo("Hi Bob, your id is {{id}}.");
        }
    }

    // ─── getTemplateOrFallback ─────────────────────────────────

    @Test
    @DisplayName("getTemplateOrFallback: DB 없으면 fallback 반환")
    void getTemplateOrFallback_returnsFallback() {
        when(repository.findActiveByKey("missing.key.abc"))
                .thenReturn(Optional.empty());

        String result = service.getTemplateOrFallback("missing.key.abc",
                "default fallback text");
        assertThat(result).isEqualTo("default fallback text");
    }

    @Test
    @DisplayName("getTemplateOrFallback: DB 있으면 DB 값 반환")
    void getTemplateOrFallback_returnsDbValue() {
        PromptTemplateEntity entity = createEntity("exists", 1,
                "test", "DB template");
        when(repository.findActiveByKey("exists"))
                .thenReturn(Optional.of(entity));

        String result = service.getTemplateOrFallback("exists",
                "should not use this");
        assertThat(result).isEqualTo("DB template");
    }

    // ─── bulkLoad ──────────────────────────────────────────────

    @Test
    @DisplayName("bulkLoad: 카테고리별 필터링 동작")
    void bulkLoad_byCategory() {
        PromptTemplateEntity e1 = createEntity("orch.sys", 1, "orchestration", "sys prompt");
        PromptTemplateEntity e2 = createEntity("orch.user", 1, "orchestration", "user prompt");

        when(repository.findByCategoryAndIsActiveTrue("orchestration"))
                .thenReturn(List.of(e1, e2));

        Map<String, String> result = service.bulkLoad("orchestration");
        assertThat(result).hasSize(2);
        assertThat(result).containsEntry("orch.sys", "sys prompt");
        assertThat(result).containsEntry("orch.user", "user prompt");
    }

    @Test
    @DisplayName("bulkLoad: null 카테고리 시 전체 조회")
    void bulkLoad_allWhenNull() {
        PromptTemplateEntity e1 = createEntity("a.key", 1, "cat1", "t1");
        when(repository.findByIsActiveTrue()).thenReturn(List.of(e1));

        Map<String, String> result = service.bulkLoad(null);
        assertThat(result).hasSize(1);
        verify(repository).findByIsActiveTrue();
        verify(repository, never()).findByCategoryAndIsActiveTrue(any());
    }

    // ─── invalidateCache ───────────────────────────────────────

    @Test
    @DisplayName("invalidateCache: 캐시 무효화 후 DB 재조회")
    void invalidateCache_forcesDbReload() {
        PromptTemplateEntity entity = createEntity("cached.key", 1,
                "test", "original");
        when(repository.findActiveByKey("cached.key"))
                .thenReturn(Optional.of(entity));

        // 첫 호출 → 캐시 저장
        service.getTemplate("cached.key");

        // 캐시 무효화
        service.invalidateCache("cached.key");

        // DB 값 변경 시뮬레이션
        PromptTemplateEntity updated = createEntity("cached.key", 2,
                "test", "updated");
        when(repository.findActiveByKey("cached.key"))
                .thenReturn(Optional.of(updated));

        // 재호출 → DB 재조회
        String result = service.getTemplate("cached.key");
        assertThat(result).isEqualTo("updated");
        verify(repository, times(2)).findActiveByKey("cached.key");
    }

    // ─── renderTemplate (public method) ────────────────────────

    @Nested
    @DisplayName("renderTemplate()")
    class RenderTemplate {

        @Test
        @DisplayName("다중 변수 치환")
        void multipleVariables() {
            String result = service.renderTemplate(
                    "{{a}} + {{b}} = {{c}}",
                    Map.of("a", "1", "b", "2", "c", "3"));
            assertThat(result).isEqualTo("1 + 2 = 3");
        }

        @Test
        @DisplayName("null 템플릿 → null 반환")
        void nullTemplate_returnsNull() {
            String result = service.renderTemplate(null, Map.of("x", "y"));
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("null 변수 맵 → 템플릿 그대로 반환")
        void nullVariables_returnsTemplate() {
            String result = service.renderTemplate("Hello {{name}}", null);
            assertThat(result).isEqualTo("Hello {{name}}");
        }

        @Test
        @DisplayName("빈 변수 맵 → 템플릿 그대로 반환")
        void emptyVariables_returnsTemplate() {
            String result = service.renderTemplate("Hello {{name}}", Map.of());
            assertThat(result).isEqualTo("Hello {{name}}");
        }

        @Test
        @DisplayName("변수 값이 숫자/객체일 때 toString 변환")
        void objectValues_toString() {
            String result = service.renderTemplate(
                    "count: {{n}}, flag: {{f}}",
                    Map.of("n", 42, "f", true));
            assertThat(result).isEqualTo("count: 42, flag: true");
        }

        @Test
        @DisplayName("같은 변수 여러 번 등장")
        void repeatedVariable() {
            String result = service.renderTemplate(
                    "{{x}} and {{x}} again",
                    Map.of("x", "val"));
            assertThat(result).isEqualTo("val and val again");
        }
    }

    // ─── PromptTemplateEntity.toMap() ──────────────────────────

    @Nested
    @DisplayName("PromptTemplateEntity")
    class EntityTest {

        @Test
        @DisplayName("toMap() 정상 동작")
        void toMap_containsAllFields() {
            PromptTemplateEntity entity = createEntity("my.key", 3,
                    "orchestration", "template text");
            entity.setName("My Prompt");
            entity.setDescription("A description");
            entity.setLanguage("ko");
            entity.setActive(true);
            entity.setSystem(true);
            entity.setCreatedBy("admin");

            Map<String, Object> map = entity.toMap();

            assertThat(map).containsEntry("key", "my.key");
            assertThat(map).containsEntry("version", 3);
            assertThat(map).containsEntry("category", "orchestration");
            assertThat(map).containsEntry("name", "My Prompt");
            assertThat(map).containsEntry("description", "A description");
            assertThat(map).containsEntry("template", "template text");
            assertThat(map).containsEntry("language", "ko");
            assertThat(map).containsEntry("is_active", true);
            assertThat(map).containsEntry("is_system", true);
            assertThat(map).containsEntry("created_by", "admin");
            assertThat(map).containsKey("created_at");
            assertThat(map).containsKey("updated_at");
        }

        @Test
        @DisplayName("toMap() pk가 null이면 key/version null")
        void toMap_nullPk() {
            PromptTemplateEntity entity = new PromptTemplateEntity();
            entity.setCategory("test");
            entity.setName("test");
            entity.setTemplate("t");

            Map<String, Object> map = entity.toMap();
            assertThat(map.get("key")).isNull();
            assertThat(map.get("version")).isNull();
        }
    }

    // ─── PromptTemplateEntityId equals/hashCode ────────────────

    @Nested
    @DisplayName("PromptTemplateEntityId")
    class EntityIdTest {

        @Test
        @DisplayName("동일 key/version → equals true")
        void sameKeyVersion_equal() {
            PromptTemplateEntityId id1 = new PromptTemplateEntityId("k1", 1);
            PromptTemplateEntityId id2 = new PromptTemplateEntityId("k1", 1);
            assertThat(id1).isEqualTo(id2);
            assertThat(id1.hashCode()).isEqualTo(id2.hashCode());
        }

        @Test
        @DisplayName("다른 key → equals false")
        void differentKey_notEqual() {
            PromptTemplateEntityId id1 = new PromptTemplateEntityId("k1", 1);
            PromptTemplateEntityId id2 = new PromptTemplateEntityId("k2", 1);
            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("다른 version → equals false")
        void differentVersion_notEqual() {
            PromptTemplateEntityId id1 = new PromptTemplateEntityId("k1", 1);
            PromptTemplateEntityId id2 = new PromptTemplateEntityId("k1", 2);
            assertThat(id1).isNotEqualTo(id2);
        }

        @Test
        @DisplayName("자기 자신과 equals → true")
        void sameInstance_equal() {
            PromptTemplateEntityId id = new PromptTemplateEntityId("k", 1);
            assertThat(id).isEqualTo(id);
        }

        @Test
        @DisplayName("null과 비교 → false")
        void nullComparison_notEqual() {
            PromptTemplateEntityId id = new PromptTemplateEntityId("k", 1);
            assertThat(id).isNotEqualTo(null);
        }
    }

    // ─── Helper ────────────────────────────────────────────────

    private PromptTemplateEntity createEntity(String key, int version,
                                               String category, String template) {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setPk(new PromptTemplateEntityId(key, version));
        entity.setCategory(category);
        entity.setName(key);
        entity.setTemplate(template);
        entity.setActive(true);
        return entity;
    }
}
