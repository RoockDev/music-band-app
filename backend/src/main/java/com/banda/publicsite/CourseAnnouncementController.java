package com.banda.publicsite;

import com.banda.publicsite.dto.CourseAnnouncementResponse;
import com.banda.publicsite.dto.CreateCourseAnnouncementRequest;
import com.banda.users.UserAccount;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Section 8 (Public Site Content) admin panel surface for course announcements — minimal
 * create-only, see {@link CourseAnnouncementService}'s own Javadoc for why. The
 * unauthenticated public read side lives entirely separately in
 * {@code PublicContentController} under {@code /api/public/**}, never this path.
 * {@code PermissionDeniedException} is handled globally by {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/courses")
public class CourseAnnouncementController {

    private final CourseAnnouncementService courseAnnouncementService;

    public CourseAnnouncementController(CourseAnnouncementService courseAnnouncementService) {
        this.courseAnnouncementService = courseAnnouncementService;
    }

    @PostMapping
    public ResponseEntity<CourseAnnouncementResponse> create(@AuthenticationPrincipal UserAccount actor,
                                                               @Valid @RequestBody CreateCourseAnnouncementRequest request) {
        CourseAnnouncement created = courseAnnouncementService.create(actor, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(CourseAnnouncementResponse.from(created));
    }
}
