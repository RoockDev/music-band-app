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

        applyAccessScope(saved, request.groupIds(), request.musicianIds());

        auditService.record(actor.getId(), "SHEET_MUSIC_UPLOADED", "SheetMusic", saved.getId(),
                "title=" + request.title());
        log.info("Sheet music uploaded: {}", saved.getId());

        return saved;
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
