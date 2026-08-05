package com.banda.publicsite.dto;

import com.banda.publicsite.CourseAnnouncement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CourseAnnouncementResponse(
        Long id,
        String title,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal price,
        String instrument,
        int minimumAge,
        Instant createdAt,
        Instant updatedAt
) {

    public static CourseAnnouncementResponse from(CourseAnnouncement course) {
        return new CourseAnnouncementResponse(course.getId(), course.getTitle(), course.getDescription(),
                course.getStartDate(), course.getEndDate(), course.getPrice(), course.getInstrument(),
                course.getMinimumAge(), course.getCreatedAt(), course.getUpdatedAt());
    }
}
