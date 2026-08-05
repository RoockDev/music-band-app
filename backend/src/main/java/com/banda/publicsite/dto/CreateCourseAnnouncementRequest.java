package com.banda.publicsite.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Section 8 "Structured course" scenario: dates/price/instrument/minimum-age are distinct,
 * validated fields — never folded into free text. */
public record CreateCourseAnnouncementRequest(
        @NotBlank String title,
        String description,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        @NotNull @DecimalMin(value = "0.0") BigDecimal price,
        @NotBlank String instrument,
        @Min(0) int minimumAge
) {
}
