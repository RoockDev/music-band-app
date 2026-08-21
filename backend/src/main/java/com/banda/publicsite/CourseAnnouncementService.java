package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.publicsite.dto.CreateCourseAnnouncementRequest;
import com.banda.publicsite.dto.UpdateCourseAnnouncementRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Section 8 (Public Site Content) "Structured course" scenario admin use case: gated by
 * {@link Permission#MANAGE_CONTENT} independent of the base ADMIN role (Sec.2/Sec.10) and
 * audited on mutation (Sec.11) — the exact "gate -> mutate -> audit" shape
 * {@code NewsPostService}/{@code GroupService} established, copied here rather than
 * reinvented. Deliberately minimal (create only, no edit/delete) — mirrors
 * {@code CollectionService}'s own "deliberately minimal" precedent.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link CourseAnnouncementController})
 * MUST resolve it from the authenticated principal, never from client-supplied request data.
 */
@Service
@Transactional
public class CourseAnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(CourseAnnouncementService.class);

    private final CourseAnnouncementRepository courseAnnouncementRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Clock clock;

    public CourseAnnouncementService(CourseAnnouncementRepository courseAnnouncementRepository,
                                      PermissionService permissionService, AuditService auditService, Clock clock) {
        this.courseAnnouncementRepository = courseAnnouncementRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public CourseAnnouncement create(UserAccount actor, CreateCourseAnnouncementRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);

        Instant now = clock.instant();
        CourseAnnouncement course = new CourseAnnouncement(request.title(), request.description(),
                request.startDate(), request.endDate(), request.price(), request.instrument(),
                request.minimumAge(), now);
        CourseAnnouncement saved = courseAnnouncementRepository.saveAndFlush(course);

        auditService.record(actor.getId(), "COURSE_ANNOUNCEMENT_CREATED", "CourseAnnouncement", saved.getId(),
                "title=" + request.title());
        log.info("Course announcement created: {}", saved.getId());

        return saved;
    }

    @Transactional(readOnly = true)
    public List<CourseAnnouncement> list(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        return courseAnnouncementRepository.findAllByOrderByStartDateAsc();
    }

    public CourseAnnouncement update(UserAccount actor, Long id, UpdateCourseAnnouncementRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        CourseAnnouncement course = requireCourse(id);
        requireVersion(course.getVersion(), request.version());
        if (sameState(course, request)) {
            return course;
        }

        String before = course.getTitle() + "@" + course.getStartDate();
        course.update(request.title(), request.description(), request.startDate(), request.endDate(), request.price(),
                request.instrument(), request.minimumAge(), clock.instant());
        saveWithOptimisticLockHandling(course);
        String after = request.title() + "@" + request.startDate();
        auditService.record(actor.getId(), "COURSE_ANNOUNCEMENT_UPDATED", "CourseAnnouncement", id,
                truncate("before=" + before + ";after=" + after, 255));
        log.info("Course announcement updated: {}", id);
        return course;
    }

    public void delete(UserAccount actor, Long id, Long version) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        CourseAnnouncement course = requireCourse(id);
        requireVersion(course.getVersion(), version);
        try {
            courseAnnouncementRepository.delete(course);
            courseAnnouncementRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentContentModificationException();
        }
        auditService.record(actor.getId(), "COURSE_ANNOUNCEMENT_DELETED", "CourseAnnouncement", id,
                "title=" + truncate(course.getTitle(), 220));
        log.info("Course announcement deleted: {}", id);
    }

    private CourseAnnouncement requireCourse(Long id) {
        return courseAnnouncementRepository.findById(id)
                .orElseThrow(() -> new ContentNotFoundException("Course announcement", id));
    }

    private boolean sameState(CourseAnnouncement course, UpdateCourseAnnouncementRequest request) {
        return Objects.equals(course.getTitle(), request.title())
                && Objects.equals(course.getDescription(), request.description())
                && Objects.equals(course.getStartDate(), request.startDate())
                && Objects.equals(course.getEndDate(), request.endDate())
                && course.getPrice().compareTo(request.price()) == 0
                && Objects.equals(course.getInstrument(), request.instrument())
                && course.getMinimumAge() == request.minimumAge();
    }

    private void saveWithOptimisticLockHandling(CourseAnnouncement course) {
        try {
            courseAnnouncementRepository.saveAndFlush(course);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentContentModificationException();
        }
    }

    private void requireVersion(Long current, Long requested) {
        if (!Objects.equals(current, requested)) {
            throw new ConcurrentContentModificationException();
        }
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
