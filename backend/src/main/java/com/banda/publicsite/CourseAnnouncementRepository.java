package com.banda.publicsite;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseAnnouncementRepository extends JpaRepository<CourseAnnouncement, Long> {

    List<CourseAnnouncement> findAllByOrderByStartDateAsc();
}
