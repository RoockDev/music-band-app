package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.publicsite.dto.CreateNewsPostRequest;
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
 * Section 8 (Public Site Content) minimal create use case for {@link NewsPost}: gated by
 * {@link Permission#MANAGE_CONTENT} independent of the base ADMIN role (Sec.2/Sec.10) and
 * audited on mutation (Sec.11) — the exact "gate -> mutate -> audit" shape
 * {@code GroupService}/{@code CollectionService} established, copied here rather than
 * reinvented. Deliberately minimal (create only, no edit/delete): the spec names no
 * draft/publish workflow or content-management lifecycle beyond "expose public news" — a
 * follow-up PR can add edit/delete if the product later needs it, mirroring
 * {@code CollectionService}'s own "deliberately minimal" precedent.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link NewsController}) MUST resolve
 * it from the authenticated principal, never from client-supplied request data — the same
 * contract every other gated service in this codebase documents.
 */
@Service
@Transactional
public class NewsPostService {

    private static final Logger log = LoggerFactory.getLogger(NewsPostService.class);

    private final NewsPostRepository newsPostRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Clock clock;

    public NewsPostService(NewsPostRepository newsPostRepository, PermissionService permissionService,
                            AuditService auditService, Clock clock) {
        this.newsPostRepository = newsPostRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public NewsPost create(UserAccount actor, CreateNewsPostRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);

        Instant now = clock.instant();
        NewsPost newsPost = new NewsPost(request.title(), request.body(), now);
        NewsPost saved = newsPostRepository.saveAndFlush(newsPost);

        auditService.record(actor.getId(), "NEWS_POST_CREATED", "NewsPost", saved.getId(), "title=" + request.title());
        log.info("News post created: {}", saved.getId());

        return saved;
    }
}
