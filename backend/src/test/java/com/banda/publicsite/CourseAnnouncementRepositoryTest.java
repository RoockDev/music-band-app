package com.banda.publicsite;

import com.banda.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Section 8 "Structured course" scenario data model: proves every structured field
 * (dates/price/instrument/minimum age) persists and reloads as its own distinct, typed value —
 * not folded into free text.
 *
 * <p>Uses a {@code "Repo Test *"}-prefixed title, deliberately distinct from
 * {@code CourseAnnouncementControllerIntegrationTest}'s own fixture titles — see
 * {@code AlbumRepositoryTest}'s class Javadoc for why a literal collision between a
 * repository-level fixture (no audit trail) and a controller-level one (audit-trail-asserting)
 * would silently break the controller test's own {@code findFirst()} lookup. */
class CourseAnnouncementRepositoryTest extends IntegrationTestBase {

    @Autowired
    private CourseAnnouncementRepository courseAnnouncementRepository;

    @Test
    void persistsAndReloadsEveryStructuredFieldDistinctly() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        CourseAnnouncement course = new CourseAnnouncement("Repo Test Violin Course", "A gentle introduction",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), new BigDecimal("120.00"), "Violin", 8, now);

        CourseAnnouncement saved = courseAnnouncementRepository.saveAndFlush(course);

        Optional<CourseAnnouncement> reloaded = courseAnnouncementRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(reloaded.get().getEndDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(reloaded.get().getPrice()).isEqualByComparingTo("120.00");
        assertThat(reloaded.get().getInstrument()).isEqualTo("Violin");
        assertThat(reloaded.get().getMinimumAge()).isEqualTo(8);
    }

    @Test
    void persistsWithANullEndDateForAnOpenEndedCourse() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        CourseAnnouncement course = new CourseAnnouncement("Ongoing Choir", null, LocalDate.of(2026, 3, 1), null,
                BigDecimal.ZERO, "Voice", 0, now);

        CourseAnnouncement saved = courseAnnouncementRepository.saveAndFlush(course);

        assertThat(courseAnnouncementRepository.findById(saved.getId()).orElseThrow().getEndDate()).isNull();
    }
}
