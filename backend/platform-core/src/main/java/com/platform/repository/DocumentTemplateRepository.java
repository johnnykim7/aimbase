package com.platform.repository;

import com.platform.domain.DocumentTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplateEntity, String> {

    List<DocumentTemplateEntity> findByFormat(String format);

    List<DocumentTemplateEntity> findByTemplateType(String templateType);

    List<DocumentTemplateEntity> findByFormatAndTemplateType(String format, String templateType);
}
