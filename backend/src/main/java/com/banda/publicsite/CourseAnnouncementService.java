package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.publicsite.dto.CreateCourseAnnouncementRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

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
}
