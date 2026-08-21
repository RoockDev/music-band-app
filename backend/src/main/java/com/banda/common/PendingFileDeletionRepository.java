package com.banda.common;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PendingFileDeletionRepository extends JpaRepository<PendingFileDeletion, Long> {

    List<PendingFileDeletion> findAllByOrderByCreatedAtAscIdAsc();

    Optional<PendingFileDeletion> findByStorageKey(String storageKey);
}
