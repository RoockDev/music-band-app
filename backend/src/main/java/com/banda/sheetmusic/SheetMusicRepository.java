package com.banda.sheetmusic;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SheetMusicRepository extends JpaRepository<SheetMusic, Long> {

    @EntityGraph(attributePaths = "collection")
    List<SheetMusic> findByActiveTrue();

    boolean existsByCollection(Collection collection);
}
