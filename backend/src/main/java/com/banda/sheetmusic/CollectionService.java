package com.banda.sheetmusic;

import com.banda.audit.AuditService;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.dto.CreateCollectionRequest;
import com.banda.sheetmusic.dto.UpdateCollectionRequest;
import com.banda.users.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
    private final SheetMusicRepository sheetMusicRepository;
    private final Clock clock;

    public CollectionService(CollectionRepository collectionRepository, SheetMusicRepository sheetMusicRepository,
                             PermissionService permissionService, AuditService auditService, Clock clock) {
        this.collectionRepository = collectionRepository;
        this.sheetMusicRepository = sheetMusicRepository;
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

    @Transactional(readOnly = true)
    public Collection get(UserAccount actor, Long id) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        return requireCollection(id);
    }

    public Collection update(UserAccount actor, Long id, UpdateCollectionRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        Collection collection = requireCollection(id);
        requireVersion(collection.getVersion(), request.version());
        if (Objects.equals(collection.getName(), request.name())
                && Objects.equals(collection.getDescription(), request.description())) {
            return collection;
        }

        String beforeName = collection.getName();
        collection.update(request.name(), request.description(), clock.instant());
        try {
            collectionRepository.saveAndFlush(collection);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentCollectionModificationException();
        }
        auditService.record(actor.getId(), "COLLECTION_UPDATED", "Collection", id,
                truncate("beforeName=" + beforeName + ";afterName=" + request.name(), 255));
        log.info("Collection updated: {}", id);
        return collection;
    }

    public void delete(UserAccount actor, Long id, Long version) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        Collection collection = requireCollection(id);
        requireVersion(collection.getVersion(), version);
        if (sheetMusicRepository.existsByCollection(collection)) {
            throw new CollectionInUseException();
        }
        try {
            collectionRepository.delete(collection);
            collectionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new CollectionInUseException();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentCollectionModificationException();
        }
        auditService.record(actor.getId(), "COLLECTION_DELETED", "Collection", id,
                "name=" + truncate(collection.getName(), 220));
        log.info("Collection deleted: {}", id);
    }

    private Collection requireCollection(Long id) {
        return collectionRepository.findById(id).orElseThrow(() -> new CollectionNotFoundException(id));
    }

    private void requireVersion(Long current, Long requested) {
        if (!Objects.equals(current, requested)) {
            throw new ConcurrentCollectionModificationException();
        }
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
