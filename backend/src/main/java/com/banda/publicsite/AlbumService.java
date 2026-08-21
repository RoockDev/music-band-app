package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.common.FileStorage;
import com.banda.common.FileDeletionQueue;
import com.banda.publicsite.dto.CreateAlbumRequest;
import com.banda.publicsite.dto.UpdateAlbumRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/**
 * Section 8 (Public Site Content) "Album view" scenario admin use cases: creating an
 * {@link Album} and uploading a {@link Photo} into it, gated by
 * {@link Permission#MANAGE_CONTENT} independent of the base ADMIN role (Sec.2/Sec.10) and
 * audited on every mutation (Sec.11) — the exact "gate -> mutate -> audit" shape
 * {@code SheetMusicService}/{@code GroupService} established, copied here rather than
 * reinvented. Deliberately minimal (create only, no edit/delete/reorder): mirrors
 * {@code CollectionService}'s own "deliberately minimal" precedent — this PR exists to close
 * the exact "not usable end-to-end" gap PR7 (sheet music) was flagged for, not to build full
 * album/photo management.
 *
 * <p>Photo storage reuses {@link FileStorage} (Section 5's abstraction) rather than building
 * a separate mechanism — {@link #addPhoto} follows the same store-then-persist-then-cleanup-
 * on-failure shape as {@code SheetMusicService#upload}, with cleanup additionally covering the
 * persist step itself (a narrow gap in the source pattern: {@code SheetMusicService#upload}'s
 * own {@code saveAndFlush} sits outside its try/cleanup block, so a persist failure there is
 * not itself cleaned up — not something fixed here, just not misrepresented as an exact match).
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link AlbumController}) MUST resolve
 * it from the authenticated principal, never from client-supplied request data.
 */
@Service
@Transactional
public class AlbumService {

    private static final Logger log = LoggerFactory.getLogger(AlbumService.class);

    /** Gallery photos are always plain images, never a PDF (unlike sheet music) — a
     * deliberately minimal allow-list for that use case. */
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg");

    private final AlbumRepository albumRepository;
    private final PhotoRepository photoRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final FileStorage fileStorage;
    private final FileDeletionQueue fileDeletionQueue;
    private final Clock clock;

    public AlbumService(AlbumRepository albumRepository, PhotoRepository photoRepository,
                         PermissionService permissionService, AuditService auditService,
                         FileStorage fileStorage, FileDeletionQueue fileDeletionQueue, Clock clock) {
        this.albumRepository = albumRepository;
        this.photoRepository = photoRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.fileStorage = fileStorage;
        this.fileDeletionQueue = fileDeletionQueue;
        this.clock = clock;
    }

    public Album createAlbum(UserAccount actor, CreateAlbumRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);

        Instant now = clock.instant();
        Album album = new Album(request.name(), request.description(), now);
        Album saved = albumRepository.saveAndFlush(album);

        auditService.record(actor.getId(), "ALBUM_CREATED", "Album", saved.getId(), "name=" + request.name());
        log.info("Album created: {}", saved.getId());

        return saved;
    }

    public Photo addPhoto(UserAccount actor, Long albumId, String contentType, InputStream fileContent, String caption) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        requireAllowedContentType(contentType);
        Album album = requireAlbum(albumId);

        String storageKey;
        try {
            storageKey = fileStorage.store(fileContent);
        } catch (IOException e) {
            throw new PhotoStorageException(e);
        }

        Photo saved;
        try {
            Photo photo = new Photo(album, caption, storageKey, contentType, clock.instant());
            saved = photoRepository.saveAndFlush(photo);
        } catch (RuntimeException e) {
            // Mirrors SheetMusicService#upload's cleanup: fileStorage.store already wrote real
            // bytes to disk, outside the DB transaction's control, so a failed persist here
            // must not leak an orphaned file that nothing references again.
            cleanupOrphanedFile(storageKey);
            throw e;
        }

        auditService.record(actor.getId(), "PHOTO_UPLOADED", "Photo", saved.getId(), "albumId=" + albumId);
        log.info("Photo uploaded: {}", saved.getId());

        return saved;
    }

    @Transactional(readOnly = true)
    public List<AlbumWithPhotos> list(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        return albumRepository.findAllByOrderByIdAsc().stream()
                .map(album -> new AlbumWithPhotos(album, photoRepository.findAllByAlbumOrderByIdAsc(album)))
                .toList();
    }

    public AlbumWithPhotos updateAlbum(UserAccount actor, Long albumId, UpdateAlbumRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        Album album = requireAlbum(albumId);
        requireVersion(album.getVersion(), request.version());
        if (Objects.equals(album.getName(), request.name())
                && Objects.equals(album.getDescription(), request.description())) {
            return new AlbumWithPhotos(album, photoRepository.findAllByAlbumOrderByIdAsc(album));
        }

        String beforeName = album.getName();
        album.update(request.name(), request.description(), clock.instant());
        try {
            albumRepository.saveAndFlush(album);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentContentModificationException();
        }
        auditService.record(actor.getId(), "ALBUM_UPDATED", "Album", albumId,
                truncate("beforeName=" + beforeName + ";afterName=" + request.name(), 255));
        log.info("Album updated: {}", albumId);
        return new AlbumWithPhotos(album, photoRepository.findAllByAlbumOrderByIdAsc(album));
    }

    public void deleteAlbum(UserAccount actor, Long albumId, Long version) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        Album album = requireAlbum(albumId);
        requireVersion(album.getVersion(), version);
        if (photoRepository.existsByAlbum(album)) {
            throw new AlbumInUseException();
        }
        try {
            albumRepository.delete(album);
            albumRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new AlbumInUseException();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentContentModificationException();
        }
        auditService.record(actor.getId(), "ALBUM_DELETED", "Album", albumId,
                "name=" + truncate(album.getName(), 220));
        log.info("Album deleted: {}", albumId);
    }

    public void deletePhoto(UserAccount actor, Long photoId) {
        permissionService.requirePermission(actor, Permission.MANAGE_CONTENT);
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> new PhotoNotFoundException(photoId));
        Long albumId = photo.getAlbum().getId();

        fileDeletionQueue.enqueue(photo.getStorageKey());
        photoRepository.delete(photo);
        photoRepository.flush();

        auditService.record(actor.getId(), "PHOTO_DELETED", "Photo", photoId, "albumId=" + albumId);
        log.info("Photo deleted: {}", photoId);
    }

    private void cleanupOrphanedFile(String storageKey) {
        try {
            fileStorage.delete(storageKey);
        } catch (IOException cleanupFailure) {
            log.warn("Failed to clean up orphaned file {} after a failed photo upload", storageKey, cleanupFailure);
        }
    }

    private void requireAllowedContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidPhotoFileTypeException(contentType);
        }
    }

    private Album requireAlbum(Long albumId) {
        return albumRepository.findById(albumId).orElseThrow(() -> new AlbumNotFoundException(albumId));
    }

    private void requireVersion(Long current, Long requested) {
        if (!Objects.equals(current, requested)) {
            throw new ConcurrentContentModificationException();
        }
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record AlbumWithPhotos(Album album, List<Photo> photos) {
    }
}
