package com.banda.publicsite;

import com.banda.audit.AuditService;
import com.banda.common.FileStorage;
import com.banda.publicsite.dto.CreateAlbumRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Section 8 (Public Site Content) "Album view" scenario: {@link AlbumService#createAlbum}/
 * {@link AlbumService#addPhoto} are gated by {@link Permission#MANAGE_CONTENT} independent of
 * the base ADMIN role (Sec.2/Sec.10) and audited on every mutation (Sec.11) — the exact
 * "gate -> mutate -> audit" shape {@code SheetMusicServiceTest} proves for
 * {@code SheetMusicService#upload}, including its orphaned-file cleanup contract, mirrored
 * here for {@link Photo}.
 */
class AlbumServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private AlbumRepository albumRepository;
    private PhotoRepository photoRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private FileStorage fileStorage;
    private AlbumService albumService;

    @BeforeEach
    void setUp() {
        albumRepository = mock(AlbumRepository.class);
        photoRepository = mock(PhotoRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        fileStorage = mock(FileStorage.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        albumService = new AlbumService(albumRepository, photoRepository, permissionService, auditService,
                fileStorage, clock);

        when(albumRepository.saveAndFlush(any(Album.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(photoRepository.saveAndFlush(any(Photo.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    private InputStream anyStream() {
        return new ByteArrayInputStream(new byte[] {1, 2, 3});
    }

    // ---- createAlbum() ----

    @Test
    void createAlbumChecksTheManageContentPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_CONTENT))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_CONTENT);

        assertThatThrownBy(() -> albumService.createAlbum(actor, new CreateAlbumRequest("Summer Tour", null)))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(albumRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createAlbumPersistsAnAlbumAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();

        Album created = albumService.createAlbum(actor, new CreateAlbumRequest("Summer Tour", "2026 tour photos"));

        ArgumentCaptor<Album> savedCaptor = ArgumentCaptor.forClass(Album.class);
        verify(albumRepository).saveAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getName()).isEqualTo("Summer Tour");
        assertThat(created.getName()).isEqualTo("Summer Tour");
        verify(auditService).record(eq(actor.getId()), eq("ALBUM_CREATED"), eq("Album"), any(), anyString());
    }

    // ---- addPhoto() ----

    @Test
    void addPhotoChecksTheManageContentPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_CONTENT))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_CONTENT);

        assertThatThrownBy(() -> albumService.addPhoto(actor, 1L, "image/png", anyStream(), null))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(albumRepository);
        verifyNoInteractions(fileStorage);
    }

    @Test
    void addPhotoRejectsAnUnsupportedContentTypeBeforeTouchingFileStorage() {
        UserAccount actor = adminActor();

        assertThatThrownBy(() -> albumService.addPhoto(actor, 1L, "application/pdf", anyStream(), null))
                .isInstanceOf(InvalidPhotoFileTypeException.class);

        verifyNoInteractions(fileStorage);
        verifyNoInteractions(albumRepository);
    }

    @Test
    void addPhotoOnAnUnknownAlbumThrowsAlbumNotFoundException() {
        UserAccount actor = adminActor();
        when(albumRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> albumService.addPhoto(actor, 404L, "image/png", anyStream(), null))
                .isInstanceOf(AlbumNotFoundException.class);

        verifyNoInteractions(fileStorage);
    }

    @Test
    void addPhotoStoresTheFileAndPersistsAPhotoAndWritesAnAuditRecord() throws IOException {
        UserAccount actor = adminActor();
        Album album = new Album("Summer Tour", null, NOW);
        when(albumRepository.findById(5L)).thenReturn(Optional.of(album));
        when(fileStorage.store(any())).thenReturn("generated-storage-key");

        Photo saved = albumService.addPhoto(actor, 5L, "image/jpeg", anyStream(), "On stage");

        ArgumentCaptor<Photo> savedCaptor = ArgumentCaptor.forClass(Photo.class);
        verify(photoRepository).saveAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStorageKey()).isEqualTo("generated-storage-key");
        assertThat(savedCaptor.getValue().getCaption()).isEqualTo("On stage");
        assertThat(savedCaptor.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(saved.getCaption()).isEqualTo("On stage");
        verify(auditService).record(eq(actor.getId()), eq("PHOTO_UPLOADED"), eq("Photo"), any(), anyString());
    }

    /** Mirrors {@code SheetMusicServiceTest}'s orphaned-file cleanup proof: a failed persist
     * after the file was already written to disk must delete that orphaned file rather than
     * leaking it. */
    @Test
    void addPhotoCleansUpTheOrphanedFileWhenPersistFailsAfterStoreSucceeds() throws IOException {
        UserAccount actor = adminActor();
        Album album = new Album("Summer Tour", null, NOW);
        when(albumRepository.findById(5L)).thenReturn(Optional.of(album));
        when(fileStorage.store(any())).thenReturn("orphaned-key");
        when(photoRepository.saveAndFlush(any(Photo.class))).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> albumService.addPhoto(actor, 5L, "image/png", anyStream(), null))
                .isInstanceOf(RuntimeException.class);

        verify(fileStorage).delete("orphaned-key");
        verifyNoInteractions(auditService);
    }
}
