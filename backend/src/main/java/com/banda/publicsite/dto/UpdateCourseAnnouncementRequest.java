package com.banda.publicsite.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateCourseAnnouncementRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String description,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        @NotNull @DecimalMin("0.0") BigDecimal price,
        @NotBlank @Size(max = 255) String instrument,
        @Min(0) int minimumAge,
        @NotNull @PositiveOrZero Long version
) {

    @AssertTrue(message = "endDate must not be before startDate")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
