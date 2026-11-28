package com.platform.repository;

import java.util.List;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import com.platform.domain.WidgetUserPreferenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WidgetUserPreferenceRepository extends JpaRepository<WidgetUserPreferenceEntity, String> {

    /** CR-204 BIZ-293: 접두어로 시작하는 사람들의 선호 행 키. */
    @Query("SELECT p.userRef FROM WidgetUserPreferenceEntity p WHERE p.userRef LIKE :pattern ESCAPE '!'")
    List<String> findUserRefsByPrefix(@Param("pattern") String pattern);
}
