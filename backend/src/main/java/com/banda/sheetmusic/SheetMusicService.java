package com.banda.sheetmusic;

import com.banda.audit.AuditService;
import com.banda.common.FileStorage;
import com.banda.common.FileDeletionQueue;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.dto.UploadSheetMusicRequest;
import com.banda.sheetmusic.dto.UpdateSheetMusicRequest;
import com.banda.users.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Section 5 (Sheet Music) upload use case: gated by {@link Permission#MANAGE_SHEET_MUSIC}
 * independent of the base ADMIN role (Sec.2/Sec.10), persists the {@code storageKey}
 * {@link FileStorage} returns, applies the requested group/individual access scope (delegated
 * to {@link SheetMusicAccessGrantService}), and audits (Sec.11) — the exact
 * "gate -> mutate -> audit" shape {@code GroupService}/{@code UserService} established, copied
 * here rather than reinvented.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link SheetMusicController}) MUST
 * resolve it from the authenticated principal, never from client-supplied request data —
 * the same contract every other gated service in this codebase documents.
 * The musician-facing {@link #list} remains access-scoped; {@link #listManaged} is a separate
 * {@code MANAGE_SHEET_MUSIC}-gated catalog so admins can manage uploads that are intentionally
 * scoped to other users or groups.
 */
@Service
@Transactional
public class SheetMusicService {

    private static final Logger log = LoggerFactory.getLogger(SheetMusicService.class);

    /**
     * Allow-list validated in {@link #upload} before the file ever reaches
     * {@link FileStorage}. Sheet music is realistically either a scanned/exported PDF or a
     * scanned image of a physical page — this is a deliberately minimal list for that use
     * case, not a generic file-upload allow-list; extend it only if a real scanning workflow
     * this app needs to support actually produces another format.
     */
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

    private final SheetMusicRepository sheetMusicRepository;
    private final CollectionRepository collectionRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final FileStorage fileStorage;
    private final FileDeletionQueue fileDeletionQueue;
    private final SheetMusicAccessService accessService;
    private final SheetMusicAccessGrantService accessGrantService;
    private final Clock clock;

    public SheetMusicService(SheetMusicRepository sheetMusicRepository,
                              CollectionRepository collectionRepository,
                              PermissionService permissionService,
                              AuditService auditService,
                              FileStorage fileStorage,
                              FileDeletionQueue fileDeletionQueue,
                              SheetMusicAccessService accessService,
                              SheetMusicAccessGrantService accessGrantService,
                              Clock clock) {
        this.sheetMusicRepository = sheetMusicRepository;
        this.collectionRepository = collectionRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.fileStorage = fileStorage;
        this.fileDeletionQueue = fileDeletionQueue;
        this.accessService = accessService;
        this.accessGrantService = accessGrantService;
        this.clock = clock;
    }

    /**
     * {@code fileContent}/{@code originalFilename}/{@code contentType} are passed separately
     * from {@code request} (rather than folded into the DTO) because they come from the
     * multipart file part, not the JSON-shaped metadata part — see
     * {@link SheetMusicController#upload}. {@code originalFilename} is stored only for
     * display/{@code Content-Disposition} purposes on download; {@link FileStorage#store}
     * itself never sees or uses it as the storage key.
     */
    public SheetMusic upload(UserAccount actor, UploadSheetMusicRequest request, String originalFilename,
                              String contentType, InputStream fileContent) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        requireAllowedContentType(contentType);
        requireMetadataLengths(request.title(), request.composer(), originalFilename, contentType);
        Collection collection = requireCollection(request.collectionId());
        boolean allScope = Boolean.TRUE.equals(request.allScope());
        SheetMusicAccessGrantService.ScopeTargets scope = accessGrantService.resolveScope(
                allScope, request.groupIds(), request.musicianIds());

        String storageKey;
        try {
            storageKey = fileStorage.store(fileContent);
        } catch (IOException e) {
            throw new SheetMusicStorageException(e);
        }

        AtomicBoolean cleanupScheduled = new AtomicBoolean(false);
        registerRollbackCleanup(storageKey, cleanupScheduled);

        try {
            Instant now = clock.instant();
            SheetMusic sheetMusic = new SheetMusic(request.title(), request.composer(), collection, storageKey,
                    originalFilename, contentType, allScope, now);
            SheetMusic saved = sheetMusicRepository.saveAndFlush(sheetMusic);
            accessGrantService.applyAccessScope(saved, scope);

            auditService.record(actor.getId(), "SHEET_MUSIC_UPLOADED", "SheetMusic", saved.getId(),
                    "title=" + request.title());
            log.info("Sheet music uploaded: {}", saved.getId());
            return saved;
        } catch (RuntimeException e) {
            scheduleOrphanCleanup(storageKey, cleanupScheduled);
            throw e;
        }
    }

    private void registerRollbackCleanup(String storageKey, AtomicBoolean cleanupScheduled) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    scheduleOrphanCleanup(storageKey, cleanupScheduled);
                }
            }
        });
    }

    private void scheduleOrphanCleanup(String storageKey, AtomicBoolean cleanupScheduled) {
        if (!cleanupScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            fileDeletionQueue.enqueueIndependent(storageKey);
        } catch (RuntimeException cleanupFailure) {
            cleanupScheduled.set(false);
            log.error("Failed to persist cleanup for orphaned file {} after a failed upload",
                    storageKey, cleanupFailure);
        }
    }

    public SheetMusic update(UserAccount actor, Long sheetMusicId, UpdateSheetMusicRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        SheetMusic sheetMusic = requireManagedSheetMusic(sheetMusicId);
        if (!Objects.equals(request.version(), sheetMusic.getVersion())) {
            throw new ConcurrentSheetMusicModificationException();
        }
        Collection collection = requireCollection(request.collectionId());
        SheetMusicAccessGrantService.ScopeTargets scope = accessGrantService.resolveScope(
                request.allScope(), request.groupIds(), request.musicianIds());
        List<Long> currentGroupIds = accessGrantService.groupIds(sheetMusic);
        List<Long> currentMusicianIds = accessGrantService.musicianIds(sheetMusic);
        boolean scopeChanged = sheetMusic.isAllScope() != request.allScope()
                || !currentGroupIds.equals(scope.groupIds())
                || !currentMusicianIds.equals(scope.musicianIds());
        boolean changed = !Objects.equals(sheetMusic.getTitle(), request.title())
                || !Objects.equals(sheetMusic.getComposer(), request.composer())
                || !Objects.equals(sheetMusic.getCollection().getId(), collection.getId())
                || scopeChanged;
        if (!changed) {
            return sheetMusic;
        }

        sheetMusic.setTitle(request.title());
        sheetMusic.setComposer(request.composer());
        sheetMusic.setCollection(collection);
        sheetMusic.setAllScope(request.allScope());
        sheetMusic.touch(clock.instant());
        if (scopeChanged) {
            accessGrantService.replaceAccessScope(sheetMusic, scope);
        }
        try {
            sheetMusicRepository.saveAndFlush(sheetMusic);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentSheetMusicModificationException();
        }
        auditService.record(actor.getId(), "SHEET_MUSIC_UPDATED", "SheetMusic", sheetMusicId,
                "title=" + request.title());
        return sheetMusic;
    }

    public void delete(UserAccount actor, Long sheetMusicId, Long version) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        SheetMusic sheetMusic = requireManagedSheetMusic(sheetMusicId);
        if (!Objects.equals(version, sheetMusic.getVersion())) {
            throw new ConcurrentSheetMusicModificationException();
        }
        String storageKey = sheetMusic.getStorageKey();
        accessGrantService.replaceAccessScope(sheetMusic,
                new SheetMusicAccessGrantService.ScopeTargets(List.of(), List.of()));
        sheetMusicRepository.delete(sheetMusic);
        sheetMusicRepository.flush();
        fileDeletionQueue.enqueue(storageKey);
        auditService.record(actor.getId(), "SHEET_MUSIC_DELETED", "SheetMusic", sheetMusicId);
    }

    @Transactional(readOnly = true)
    public List<SheetMusic> list(UserAccount actor) {
        return sheetMusicRepository.findByActiveTrue().stream()
                .filter(SheetMusic::isActive)
                .filter(sheetMusic -> accessService.canAccess(actor, sheetMusic))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SheetMusic> listManaged(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        return sheetMusicRepository.findByActiveTrue().stream()
                .filter(SheetMusic::isActive)
                .toList();
    }

    /**
     * Section 5 IDOR-block scenario (Sec.2/Sec.5), the core deliverable of task 6.3.
     * {@link SheetMusicNotFoundException} is thrown identically (same type, same message
     * shape) whether {@code sheetMusicId} doesn't exist, is inactive, or exists but
     * {@link SheetMusicAccessService#canAccess} denies it — a 404, never a 403, so an
     * unauthorized caller can never distinguish "doesn't exist" from "exists but you can't
     * have it" (design doc's "IDOR-safety" section). An inactive piece is checked BEFORE
     * even calling {@code canAccess} — it's treated as gone, the same way this codebase
     * treats a DEACTIVATED {@code UserAccount} at login, so {@code accessService} is never
     * even consulted for it (see the "never touches accessService" assertion this behavior
     * is tested against).
     *
     * <p><b>Accepted risk, not fixed here:</b> the identical 404 for "unknown id" vs.
     * "exists but denied" closes the response-shape side channel, but a sufficiently precise
     * timing side-channel (denied requests skip {@code fileStorage.retrieve} and the audit
     * write) could theoretically still distinguish the two. Closing that fully would require
     * constant-time access checks; not worth the added complexity given JWT-cookie auth
     * already gates entry to an authenticated session for this app's threat model.
     */
    @Transactional(readOnly = true)
    public DownloadResult download(UserAccount actor, Long sheetMusicId) {
        SheetMusic sheetMusic = sheetMusicRepository.findById(sheetMusicId)
                .filter(SheetMusic::isActive)
                .orElseThrow(() -> new SheetMusicNotFoundException(sheetMusicId));

        if (!accessService.canAccess(actor, sheetMusic)) {
            throw new SheetMusicNotFoundException(sheetMusicId);
        }

        byte[] content;
        try {
            content = fileStorage.retrieve(sheetMusic.getStorageKey());
        } catch (IOException e) {
            throw new SheetMusicStorageException(e);
        }

        auditService.record(actor.getId(), "SHEET_MUSIC_DOWNLOADED", "SheetMusic", sheetMusicId);
        log.info("Sheet music downloaded: {}", sheetMusicId);

        return new DownloadResult(content, sheetMusic.getContentType(), sheetMusic.getOriginalFilename());
    }

    /** {@code content} is fully in-memory (design doc: "loading the whole file into memory
     * for the response is acceptable" for this app's scale) rather than a live stream. */
    public record DownloadResult(byte[] content, String contentType, String filename) {
    }

    /**
     * Rejects any content-type not in {@link #ALLOWED_CONTENT_TYPES} with
     * {@link InvalidFileTypeException} (mapped to 400 by {@link SheetMusicController}) —
     * checked BEFORE {@link FileStorage#store} is ever called, so a rejected upload never
     * touches disk. A missing/blank content-type (a client that sent no {@code Content-Type}
     * on the file part at all) is rejected the same way: there is nothing to validate, so it
     * cannot be trusted either.
     */
    private void requireAllowedContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidFileTypeException(contentType);
        }
    }

    private Collection requireCollection(Long collectionId) {
        return collectionRepository.findById(collectionId)
                .orElseThrow(() -> new CollectionNotFoundException(collectionId));
    }

    private SheetMusic requireManagedSheetMusic(Long id) {
        return sheetMusicRepository.findByIdForUpdate(id)
                .filter(SheetMusic::isActive)
                .orElseThrow(() -> new SheetMusicNotFoundException(id));
    }

    private void requireMetadataLengths(String title, String composer, String originalFilename, String contentType) {
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new InvalidSheetMusicDataException("title must contain between 1 and 255 characters");
        }
        if (composer != null && composer.length() > 255) {
            throw new InvalidSheetMusicDataException("composer must contain at most 255 characters");
        }
        if (originalFilename != null && originalFilename.length() > 255) {
            throw new InvalidSheetMusicDataException("originalFilename must contain at most 255 characters");
        }
        if (contentType != null && contentType.length() > 255) {
            throw new InvalidSheetMusicDataException("contentType must contain at most 255 characters");
        }
    }
}
