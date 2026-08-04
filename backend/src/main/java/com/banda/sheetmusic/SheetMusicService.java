package com.banda.sheetmusic;

import com.banda.audit.AuditService;
import com.banda.common.FileStorage;
import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.dto.UploadSheetMusicRequest;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Section 5 (Sheet Music) upload use case: gated by {@link Permission#MANAGE_SHEET_MUSIC}
 * independent of the base ADMIN role (Sec.2/Sec.10), persists the {@code storageKey}
 * {@link FileStorage} returns, applies the requested group/individual access scope, and
 * audits (Sec.11) — the exact "gate -> mutate -> audit" shape {@code GroupService}/
 * {@code UserService} established, copied here rather than reinvented.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link SheetMusicController}) MUST
 * resolve it from the authenticated principal, never from client-supplied request data —
 * the same contract every other gated service in this codebase documents.
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
    private final SheetGroupAccessRepository sheetGroupAccessRepository;
    private final SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private final UserAccountRepository userAccountRepository;
    private final GroupRepository groupRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final FileStorage fileStorage;
    private final SheetMusicAccessService accessService;
    private final Clock clock;

    public SheetMusicService(SheetMusicRepository sheetMusicRepository,
                              CollectionRepository collectionRepository,
                              SheetGroupAccessRepository sheetGroupAccessRepository,
                              SheetMusicianAccessRepository sheetMusicianAccessRepository,
                              UserAccountRepository userAccountRepository,
                              GroupRepository groupRepository,
                              PermissionService permissionService,
                              AuditService auditService,
                              FileStorage fileStorage,
                              SheetMusicAccessService accessService,
                              Clock clock) {
        this.sheetMusicRepository = sheetMusicRepository;
        this.collectionRepository = collectionRepository;
        this.sheetGroupAccessRepository = sheetGroupAccessRepository;
        this.sheetMusicianAccessRepository = sheetMusicianAccessRepository;
        this.userAccountRepository = userAccountRepository;
        this.groupRepository = groupRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.fileStorage = fileStorage;
        this.accessService = accessService;
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
        Collection collection = requireCollection(request.collectionId());

        String storageKey;
        try {
            storageKey = fileStorage.store(fileContent);
        } catch (IOException e) {
            throw new SheetMusicStorageException(e);
        }

        Instant now = clock.instant();
        SheetMusic sheetMusic = new SheetMusic(request.title(), request.composer(), collection, storageKey,
                originalFilename, contentType, Boolean.TRUE.equals(request.allScope()), now);
        SheetMusic saved = sheetMusicRepository.saveAndFlush(sheetMusic);

        try {
            applyAccessScope(saved, request.groupIds(), request.musicianIds());
        } catch (RuntimeException e) {
            // applyAccessScope can throw (e.g. GroupNotFoundException/MusicianNotFoundException
            // on an admin typo), which rolls back the DB transaction -- but fileStorage.store
            // above already wrote real bytes to disk, outside that transaction's control.
            // Without this cleanup, a failed upload would silently leak an orphaned file that
            // nothing ever references again (no update/delete endpoint exists to find it).
            cleanupOrphanedFile(storageKey);
            throw e;
        }

        auditService.record(actor.getId(), "SHEET_MUSIC_UPLOADED", "SheetMusic", saved.getId(),
                "title=" + request.title());
        log.info("Sheet music uploaded: {}", saved.getId());

        return saved;
    }

    private void cleanupOrphanedFile(String storageKey) {
        try {
            fileStorage.delete(storageKey);
        } catch (IOException cleanupFailure) {
            // Best-effort: the original failure (surfaced to the caller right after this)
            // must never be masked by a cleanup failure. Logged at WARN, not ERROR, since a
            // leaked file here is a disk-hygiene concern, not a correctness one -- the DB
            // transaction still rolled back cleanly.
            log.warn("Failed to clean up orphaned file {} after a failed upload", storageKey, cleanupFailure);
        }
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

    private void applyAccessScope(SheetMusic sheetMusic, List<Long> groupIds, List<Long> musicianIds) {
        if (groupIds != null) {
            for (Long groupId : groupIds) {
                Group group = requireGroup(groupId);
                sheetGroupAccessRepository.saveAndFlush(new SheetGroupAccess(sheetMusic, group));
            }
        }
        if (musicianIds != null) {
            for (Long musicianId : musicianIds) {
                UserAccount musician = requireMusician(musicianId);
                sheetMusicianAccessRepository.saveAndFlush(new SheetMusicianAccess(sheetMusic, musician));
            }
        }
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

    private Group requireGroup(Long groupId) {
        return groupRepository.findById(groupId).orElseThrow(() -> new GroupNotFoundException(groupId));
    }

    /** Mirrors {@code GroupService#requireMusician}: a non-musician id is treated identically
     * to a nonexistent one (404-style), not just a nonexistent id. */
    private UserAccount requireMusician(Long musicianId) {
        UserAccount account = userAccountRepository.findById(musicianId)
                .orElseThrow(() -> new MusicianNotFoundException(musicianId));
        if (account.getRole() != UserRole.MUSICIAN) {
            throw new MusicianNotFoundException(musicianId);
        }
        return account;
    }
}
