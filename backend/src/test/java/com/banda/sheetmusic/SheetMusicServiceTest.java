package com.banda.sheetmusic;

import com.banda.audit.AuditService;
import com.banda.common.FileStorage;
import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.dto.UploadSheetMusicRequest;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Section 5 upload use case: gated by {@link Permission#MANAGE_SHEET_MUSIC} (Sec.2/Sec.10),
 * persists {@code storageKey} from {@link FileStorage} (task 6.2's own test focus), applies
 * the group/individual access scope atomically, and audits (Sec.11) — the "gate -> mutate ->
 * audit" shape {@code GroupService}/{@code UserService} established.
 */
class SheetMusicServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private SheetMusicRepository sheetMusicRepository;
    private CollectionRepository collectionRepository;
    private SheetGroupAccessRepository sheetGroupAccessRepository;
    private SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private UserAccountRepository userAccountRepository;
    private GroupRepository groupRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private FileStorage fileStorage;
    private SheetMusicService sheetMusicService;

    private Collection collection;

    @BeforeEach
    void setUp() throws IOException {
        sheetMusicRepository = mock(SheetMusicRepository.class);
        collectionRepository = mock(CollectionRepository.class);
        sheetGroupAccessRepository = mock(SheetGroupAccessRepository.class);
        sheetMusicianAccessRepository = mock(SheetMusicianAccessRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);
        groupRepository = mock(GroupRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        fileStorage = mock(FileStorage.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        sheetMusicService = new SheetMusicService(sheetMusicRepository, collectionRepository,
                sheetGroupAccessRepository, sheetMusicianAccessRepository, userAccountRepository, groupRepository,
                permissionService, auditService, fileStorage, clock);

        collection = new Collection("Marches", null, NOW);
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(collection));
        when(sheetMusicRepository.saveAndFlush(any(SheetMusic.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fileStorage.store(any(InputStream.class))).thenReturn("generated-storage-key");
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    private InputStream fakeFileContent() {
        return new ByteArrayInputStream("fake pdf bytes".getBytes());
    }

    // ---- permission gate ----

    @Test
    void uploadChecksTheManageSheetMusicPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_SHEET_MUSIC))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", "Composer", 1L, false, null, null);

        assertThatThrownBy(() -> sheetMusicService.upload(actor, request, "file.pdf", "application/pdf", fakeFileContent()))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(sheetMusicRepository);
        verifyNoInteractions(fileStorage);
        verifyNoInteractions(auditService);
    }

    // ---- happy path: storageKey persisted (task 6.2's own test focus) ----

    @Test
    void uploadPersistsMetadataWithTheStorageKeyFromFileStorageAndWritesAnAuditRecord() throws IOException {
        UserAccount actor = adminActor();
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Radetzky March", "Johann Strauss I", 1L, true, null, null);

        SheetMusic saved = sheetMusicService.upload(actor, request, "radetzky.pdf", "application/pdf", fakeFileContent());

        ArgumentCaptor<SheetMusic> captor = ArgumentCaptor.forClass(SheetMusic.class);
        verify(sheetMusicRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStorageKey()).isEqualTo("generated-storage-key");
        assertThat(captor.getValue().getTitle()).isEqualTo("Radetzky March");
        assertThat(captor.getValue().getCollection()).isSameAs(collection);
        assertThat(captor.getValue().isAllScope()).isTrue();
        assertThat(saved.getStorageKey()).isEqualTo("generated-storage-key");
        verify(auditService).record(eq(actor.getId()), eq("SHEET_MUSIC_UPLOADED"), eq("SheetMusic"), any(), anyString());
    }

    /** A real HTML checkbox left unchecked submits no field at all, so
     * {@code request.allScope()} arrives as {@code null} (never a literal {@code "false"}) —
     * {@link UploadSheetMusicRequest#allScope} is boxed specifically so this doesn't blow up
     * the whole request, and must default to non-public just like an explicit {@code false}. */
    @Test
    void uploadTreatsANullAllScopeTheSameAsExplicitFalse() {
        UserAccount actor = adminActor();
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 1L, null, null, null);

        SheetMusic saved = sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent());

        assertThat(saved.isAllScope()).isFalse();
    }

    @Test
    void uploadOnAnUnknownCollectionThrowsCollectionNotFoundExceptionAndNeverTouchesFileStorage() {
        UserAccount actor = adminActor();
        when(collectionRepository.findById(404L)).thenReturn(Optional.empty());
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 404L, false, null, null);

        assertThatThrownBy(() -> sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent()))
                .isInstanceOf(CollectionNotFoundException.class);

        verifyNoInteractions(fileStorage);
        verifyNoInteractions(sheetMusicRepository);
    }

    // ---- access scope application ----

    @Test
    void uploadAppliesGroupAccessScopeForEveryGroupIdProvided() {
        UserAccount actor = adminActor();
        Group group = new Group("Brass Section", null, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(group, "id", 5L);
        when(groupRepository.findById(5L)).thenReturn(Optional.of(group));
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 1L, false, List.of(5L), null);

        sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent());

        ArgumentCaptor<SheetGroupAccess> captor = ArgumentCaptor.forClass(SheetGroupAccess.class);
        verify(sheetGroupAccessRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getGroup()).isSameAs(group);
    }

    @Test
    void uploadOnAnUnknownGroupIdInScopeThrowsGroupNotFoundException() {
        UserAccount actor = adminActor();
        when(groupRepository.findById(999L)).thenReturn(Optional.empty());
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 1L, false, List.of(999L), null);

        assertThatThrownBy(() -> sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent()))
                .isInstanceOf(GroupNotFoundException.class);
    }

    @Test
    void uploadAppliesIndividualAccessScopeForEveryMusicianIdProvided() {
        UserAccount actor = adminActor();
        UserAccount musician = new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(musician, "id", 7L);
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(musician));
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 1L, false, null, List.of(7L));

        sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent());

        ArgumentCaptor<SheetMusicianAccess> captor = ArgumentCaptor.forClass(SheetMusicianAccess.class);
        verify(sheetMusicianAccessRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMusician()).isSameAs(musician);
    }

    @Test
    void uploadOnAnUnknownMusicianIdInScopeThrowsMusicianNotFoundException() {
        UserAccount actor = adminActor();
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 1L, false, null, List.of(999L));

        assertThatThrownBy(() -> sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent()))
                .isInstanceOf(MusicianNotFoundException.class);
    }

    @Test
    void uploadRejectsANonMusicianIdInIndividualScopeTheSameWayAsANonexistentId() {
        UserAccount actor = adminActor();
        UserAccount notAMusician = adminActor();
        org.springframework.test.util.ReflectionTestUtils.setField(notAMusician, "id", 8L);
        when(userAccountRepository.findById(8L)).thenReturn(Optional.of(notAMusician));
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 1L, false, null, List.of(8L));

        assertThatThrownBy(() -> sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent()))
                .isInstanceOf(MusicianNotFoundException.class);

        verify(sheetMusicianAccessRepository, never()).saveAndFlush(any());
    }

    // ---- storage failure ----

    @Test
    void uploadWrapsAnIOExceptionFromFileStorageIntoSheetMusicStorageException() throws IOException {
        UserAccount actor = adminActor();
        when(fileStorage.store(any(InputStream.class))).thenThrow(new IOException("disk full"));
        UploadSheetMusicRequest request = new UploadSheetMusicRequest("Title", null, 1L, false, null, null);

        assertThatThrownBy(() -> sheetMusicService.upload(actor, request, "f.pdf", "application/pdf", fakeFileContent()))
                .isInstanceOf(SheetMusicStorageException.class);

        verifyNoInteractions(sheetMusicRepository);
        verifyNoInteractions(auditService);
    }
}
