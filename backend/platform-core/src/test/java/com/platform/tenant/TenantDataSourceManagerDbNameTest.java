package com.platform.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-142: 테넌트 물리 DB 이름 조회 테스트.
 *
 * <p>RAG 사이드카는 테넌트 개념이 없어 단일 DB_NAME 에 고정되어 있었고, 그 결과 모든 테넌트의
 * 임베딩이 같은 DB 로 적재됐다(BIZ-003 위반). 라우팅 인자로 넘길 DB 이름을 여기서 해석한다.</p>
 *
 * <p>이름을 {@code "aimbase_" + tenantId} 로 조립하지 않는 것이 핵심이다 — 2026-08-20 에 그
 * 조립 규칙의 대소문자 불일치(<code>companya</code> vs <code>companyA</code>)로 사이드카 DB
 * 연결이 전면 실패한 이력이 있다. 아래 테스트가 대소문자·하이픈 보존을 고정한다.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantDataSourceManagerDbNameTest {

    private TenantDataSourceManager managerWith(String tenantId, String jdbcUrl) throws Exception {
        TenantDataSourceManager manager = new TenantDataSourceManager(null);
        HikariDataSource ds = mock(HikariDataSource.class);
        when(ds.getJdbcUrl()).thenReturn(jdbcUrl);

        Field f = TenantDataSourceManager.class.getDeclaredField("tenantDataSources");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, HikariDataSource> map = (Map<String, HikariDataSource>) f.get(manager);
        map.put(tenantId, ds);
        return manager;
    }

    @Test
    @DisplayName("대문자가 섞인 테넌트 DB 이름을 그대로 보존한다 (2026-08-20 장애 회귀 방지)")
    void preservesCase() throws Exception {
        TenantDataSourceManager m = managerWith(
                "axopm_companyA",
                "jdbc:postgresql://host.docker.internal:5432/aimbase_axopm_companyA");

        assertThat(m.getDbName("axopm_companyA")).isEqualTo("aimbase_axopm_companyA");
    }

    @Test
    @DisplayName("하이픈이 든 테넌트 DB 이름을 그대로 보존한다")
    void preservesHyphen() throws Exception {
        TenantDataSourceManager m = managerWith(
                "shopai-store-a",
                "jdbc:postgresql://host:5432/aimbase_shopai-store-a");

        assertThat(m.getDbName("shopai-store-a")).isEqualTo("aimbase_shopai-store-a");
    }

    @Test
    @DisplayName("JDBC URL 쿼리 파라미터를 DB 이름에서 제외한다")
    void stripsQueryParams() throws Exception {
        TenantDataSourceManager m = managerWith(
                "bp_wes",
                "jdbc:postgresql://host:5432/aimbase_bp_wes?ssl=true&connectTimeout=10");

        assertThat(m.getDbName("bp_wes")).isEqualTo("aimbase_bp_wes");
    }

    @Test
    @DisplayName("DataSource 가 없는 테넌트는 null 을 반환한다 (사이드카 기본 DB 폴백)")
    void returnsNullWhenAbsent() {
        TenantDataSourceManager m = new TenantDataSourceManager(null);

        assertThat(m.getDbName("nonexistent")).isNull();
    }

    @Test
    @DisplayName("DB 이름이 비어있는 URL 은 null 을 반환한다")
    void returnsNullOnMalformedUrl() throws Exception {
        TenantDataSourceManager m = managerWith("broken", "jdbc:postgresql://host:5432/");

        assertThat(m.getDbName("broken")).isNull();
    }

    @Test
    @DisplayName("tenantDataSources 맵은 동시 접근 가능한 구현이다")
    void mapIsConcurrent() throws Exception {
        TenantDataSourceManager m = new TenantDataSourceManager(null);
        Field f = TenantDataSourceManager.class.getDeclaredField("tenantDataSources");
        f.setAccessible(true);

        assertThat(f.get(m)).isInstanceOf(ConcurrentHashMap.class);
    }
}
