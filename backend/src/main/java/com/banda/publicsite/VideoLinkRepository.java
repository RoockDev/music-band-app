package com.banda.publicsite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoLinkRepository extends JpaRepository<VideoLink, Long> {

    List<VideoLink> findAllByOrderByCreatedAtDesc();
}
