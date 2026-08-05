package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.publicsite.dto.CreateVideoLinkRequest;
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
 * Section 8 (Public Site Content) minimal create use case for {@link VideoLink}: gated by
 * {@link Permission#MANAGE_CONTENT} independent of the base ADMIN role (Sec.2/Sec.10) and
 * audited on mutation (Sec.11) — the exact "gate -> mutate -> audit" shape
 * {@code NewsPostService}/{@code GroupService} established, copied here rather than
 * reinvented. Deliberately minimal (create only, no edit/delete) — mirrors
 * {@code CollectionService}'s own "deliberately minimal" precedent.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link VideoLinkController}) MUST
 * resolve it from the authenticated principal, never from client-supplied request data.
 */
@Service
@Transactional
public class VideoLinkService {

    private static final Logger log = LoggerFactory.getLogger(VideoLinkService.class);

    private final VideoLinkRepository videoLinkRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Clock clock;

    public VideoLinkService(VideoLinkRepository videoLinkRepository, PermissionService permissionService,
                             AuditService auditService, Clock clock) {
        this.videoLinkRepository = videoLinkRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public VideoLink create(UserAccount actor, CreateVideoLinkRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);

        Instant now = clock.instant();
        VideoLink videoLink = new VideoLink(request.title(), request.url(), now);
        VideoLink saved = videoLinkRepository.saveAndFlush(videoLink);

        auditService.record(actor.getId(), "VIDEO_LINK_CREATED", "VideoLink", saved.getId(), "title=" + request.title());
        log.info("Video link created: {}", saved.getId());

        return saved;
    }
}
