package com.banda.sheetmusic;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SheetMusicRepository extends JpaRepository<SheetMusic, Long> {

    @EntityGraph(attributePaths = "collection")
    List<SheetMusic> findByActiveTrue();

    boolean existsByCollection(Collection collection);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "collection")
    @Query("select sheetMusic from SheetMusic sheetMusic where sheetMusic.id = :id")
    Optional<SheetMusic> findByIdForUpdate(Long id);
}
