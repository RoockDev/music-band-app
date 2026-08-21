package com.banda.common;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingFileDeletionRepository extends JpaRepository<PendingFileDeletion, Long> {

    List<PendingFileDeletion> findAllByOrderByCreatedAtAscIdAsc();
}
