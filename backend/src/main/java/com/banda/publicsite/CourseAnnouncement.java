package com.banda.publicsite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Section 8 (Public Site Content) "Structured course" scenario: dates/price/instrument/
 * minimum-age render as distinct fields (never folded into free text), per the spec's own
 * literal wording. {@link #endDate} is nullable — a course may run indefinitely/be
 * open-ended, but {@link #startDate} is always required.
 */
@Entity
@Table(name = "course_announcement")
public class CourseAnnouncement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private String instrument;

    @Column(name = "minimum_age", nullable = false)
    private int minimumAge;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock, kept for forward-compatibility with a future edit endpoint —
     * mirrors {@code Collection}/{@code Group}'s own {@code @Version} field. */
    @Version
    @Column(name = "version")
    private Long version;

    protected CourseAnnouncement() {
        // JPA
    }

    public CourseAnnouncement(String title, String description, LocalDate startDate, LocalDate endDate,
                               BigDecimal price, String instrument, int minimumAge, Instant now) {
        this.title = title;
        this.description = description;
        this.startDate = startDate;
        this.endDate = endDate;
        this.price = price;
        this.instrument = instrument;
        this.minimumAge = minimumAge;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getInstrument() {
        return instrument;
    }

    public int getMinimumAge() {
        return minimumAge;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
