package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.publicsite.dto.CreateCourseAnnouncementRequest;
import com.banda.publicsite.dto.UpdateCourseAnnouncementRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Section 8 (Public Site Content) "Structured course" scenario: {@link CourseAnnouncementService#create}
 * persists dates/price/instrument/minimum-age as their own distinct, typed fields — never
 * folded into free text — and follows the gate/audit shape {@code NewsPostServiceTest} proves.
 */
class CourseAnnouncementServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private CourseAnnouncementRepository courseAnnouncementRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private CourseAnnouncementService courseAnnouncementService;

    @BeforeEach
    void setUp() {
        courseAnnouncementRepository = mock(CourseAnnouncementRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        courseAnnouncementService = new CourseAnnouncementService(courseAnnouncementRepository, permissionService,
                auditService, clock);

        when(courseAnnouncementRepository.saveAndFlush(any(CourseAnnouncement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    private CreateCourseAnnouncementRequest violinCourseRequest() {
        return new CreateCourseAnnouncementRequest("Beginner Violin", "A gentle introduction",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), new BigDecimal("120.00"), "Violin", 8);
    }

    @Test
    void createChecksTheManageContentPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_CONTENT))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_CONTENT);

        assertThatThrownBy(() -> courseAnnouncementService.create(actor, violinCourseRequest()))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(courseAnnouncementRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsEveryStructuredFieldDistinctlyAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();

        CourseAnnouncement created = courseAnnouncementService.create(actor, violinCourseRequest());

        ArgumentCaptor<CourseAnnouncement> savedCaptor = ArgumentCaptor.forClass(CourseAnnouncement.class);
        verify(courseAnnouncementRepository).saveAndFlush(savedCaptor.capture());
        CourseAnnouncement saved = savedCaptor.getValue();
        assertThat(saved.getTitle()).isEqualTo("Beginner Violin");
        assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(saved.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(saved.getPrice()).isEqualByComparingTo("120.00");
        assertThat(saved.getInstrument()).isEqualTo("Violin");
        assertThat(saved.getMinimumAge()).isEqualTo(8);
        assertThat(created.getInstrument()).isEqualTo("Violin");
        verify(auditService).record(eq(actor.getId()), eq("COURSE_ANNOUNCEMENT_CREATED"), eq("CourseAnnouncement"),
                any(), anyString());
    }

    @Test
    void createWithANullEndDateSucceedsForAnOpenEndedCourse() {
        UserAccount actor = adminActor();
        CreateCourseAnnouncementRequest request = new CreateCourseAnnouncementRequest("Ongoing Choir", null,
                LocalDate.of(2026, 3, 1), null, BigDecimal.ZERO, "Voice", 0);

        CourseAnnouncement created = courseAnnouncementService.create(actor, request);

        assertThat(created.getEndDate()).isNull();
    }

    @Test
    void updatePersistsAllStructuredFieldsWithOptimisticVersion() {
        UserAccount actor = adminActor();
        CourseAnnouncement course = new CourseAnnouncement("Old", null, LocalDate.of(2026, 9, 1), null,
                BigDecimal.ZERO, "Voice", 0, NOW.minusSeconds(60));
        ReflectionTestUtils.setField(course, "version", 1L);
        when(courseAnnouncementRepository.findById(5L)).thenReturn(java.util.Optional.of(course));
        UpdateCourseAnnouncementRequest request = new UpdateCourseAnnouncementRequest("Choir", "Weekly",
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1), new BigDecimal("50.00"), "Voice", 12, 1L);

        CourseAnnouncement updated = courseAnnouncementService.update(actor, 5L, request);

        assertThat(updated.getTitle()).isEqualTo("Choir");
        assertThat(updated.getEndDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(updated.getPrice()).isEqualByComparingTo("50.00");
        assertThat(updated.getMinimumAge()).isEqualTo(12);
        verify(courseAnnouncementRepository).saveAndFlush(course);
        verify(auditService).record(eq(actor.getId()), eq("COURSE_ANNOUNCEMENT_UPDATED"),
                eq("CourseAnnouncement"), eq(5L), anyString());
    }
}
