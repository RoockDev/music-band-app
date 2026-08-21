package com.banda.sheetmusic;

import com.banda.audit.AuditService;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.dto.CreateCollectionRequest;
import com.banda.users.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Section 6 (Sheet Music Collections/Folders) minimal list/create use cases: gated by
 * {@link Permission#MANAGE_SHEET_MUSIC} independent of the base ADMIN role (Sec.2/Sec.10),
 * audited on mutation (Sec.11) — the exact "gate -> mutate -> audit" shape
 * {@code GroupService}/{@code UserService} established, copied here rather than reinvented.
 * Deliberately minimal (list and create only): before this, no {@code CollectionController} existed
 * at all, so this PR's own upload flow (which hard-requires an existing {@code collectionId})
 * had no way to be exercised end-to-end outside tests seeding a {@link Collection} directly
 * via {@link CollectionRepository}. Full CRUD (rename/delete-with-reassign, mirroring
 * {@code GroupService#delete}'s "delete in-use" 409 guard design decision #7 also names for
 * collections) is intentionally out of scope here — a follow-up PR is expected to add it.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link CollectionController}) MUST
 * resolve it from the authenticated principal, never from client-supplied request data —
 * the same contract every other gated service in this codebase documents.
 */
@Service
@Transactional
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    private final CollectionRepository collectionRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final Clock clock;

    public CollectionService(CollectionRepository collectionRepository, PermissionService permissionService,
                              AuditService auditService, Clock clock) {
        this.collectionRepository = collectionRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public Collection create(UserAccount actor, CreateCollectionRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);

        Instant now = clock.instant();
        Collection collection = new Collection(request.name(), request.description(), now);
        Collection saved = collectionRepository.saveAndFlush(collection);

        auditService.record(actor.getId(), "COLLECTION_CREATED", "Collection", saved.getId(), "name=" + request.name());
        log.info("Collection created: {}", saved.getId());

        return saved;
    }

    @Transactional(readOnly = true)
    public List<Collection> list(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        return collectionRepository.findAll();
    }
}
