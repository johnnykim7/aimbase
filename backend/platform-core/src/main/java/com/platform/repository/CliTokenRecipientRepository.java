package com.platform.repository;

import com.platform.domain.CliTokenRecipientEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CliTokenRecipientRepository extends JpaRepository<CliTokenRecipientEntity, Long> {
}
