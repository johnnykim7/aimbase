package com.platform.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-161 TC-CG-124: 목록, 생성, 수정 응답의 그룹 필드 이름은 상세 응답과 같다. */
class ConnectionGroupEntityJsonTest {

    @Test
    void tc_cg_124_listFieldsMatchDetailNames() {
        ConnectionGroupEntity group = new ConnectionGroupEntity();
        group.setId("grp-1");
        group.setIsActive(false);
        group.setIsDefault(true);

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(group);

        assertThat(json.get("is_active").asBoolean()).isFalse();
        assertThat(json.get("is_default").asBoolean()).isTrue();
        assertThat(json.has("created_at")).isTrue();
        assertThat(json.has("updated_at")).isTrue();
        assertThat(json.has("isActive")).isFalse();
        assertThat(json.has("isDefault")).isFalse();
    }
}
