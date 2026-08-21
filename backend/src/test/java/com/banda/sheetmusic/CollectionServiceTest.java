package com.banda.sheetmusic;

import com.banda.audit.AuditService;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.sheetmusic.dto.CreateCollectionRequest;
import com.banda.sheetmusic.dto.UpdateCollectionRequest;
import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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
 * Section 6 minimal create use case: gated by {@link Permission#MANAGE_SHEET_MUSIC}
 * (Sec.2/Sec.10) and audited on mutation (Sec.11) — the "gate -> mutate -> audit" shape
 * {@code GroupService}/{@code UserService} established.
 */
class CollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private CollectionRepository collectionRepository;
    private PermissionService permissionService;
    private SheetMusicRepository sheetMusicRepository;
    private AuditService auditService;
    private CollectionService collectionService;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(CollectionRepository.class);
        sheetMusicRepository = mock(SheetMusicRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        collectionService = new CollectionService(collectionRepository, sheetMusicRepository, permissionService,
                auditService, clock);
        when(collectionRepository.saveAndFlush(any(Collection.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    @Test
    void createChecksTheManageSheetMusicPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_SHEET_MUSIC))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
        CreateCollectionRequest request = new CreateCollectionRequest("Marches", "Brass band marches");

        assertThatThrownBy(() -> collectionService.create(actor, request))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(collectionRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsTheCollectionAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        CreateCollectionRequest request = new CreateCollectionRequest("Marches", "Brass band marches");

        Collection saved = collectionService.create(actor, request);

        ArgumentCaptor<Collection> captor = ArgumentCaptor.forClass(Collection.class);
        verify(collectionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Marches");
        assertThat(captor.getValue().getDescription()).isEqualTo("Brass band marches");
        assertThat(saved.getName()).isEqualTo("Marches");
        verify(auditService).record(eq(actor.getId()), eq("COLLECTION_CREATED"), eq("Collection"), any(), anyString());
    }

    @Test
    void listRequiresManageSheetMusicPermissionBeforeReadingCollections() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_SHEET_MUSIC))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);

        assertThatThrownBy(() -> collectionService.list(actor))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(collectionRepository);
    }

    @Test
    void listReturnsAllCollectionsForAnAuthorizedAdministrator() {
        UserAccount actor = adminActor();
        Collection marches = new Collection("Marches", null, NOW);
        Collection concerts = new Collection("Concerts", null, NOW);
        when(collectionRepository.findAll()).thenReturn(List.of(marches, concerts));

        List<Collection> result = collectionService.list(actor);

        assertThat(result).containsExactly(marches, concerts);
        verify(permissionService).requirePermission(actor, Permission.MANAGE_SHEET_MUSIC);
    }

    @Test
    void updateRejectsAStaleVersionBeforeChangingState() {
        UserAccount actor = adminActor();
        Collection collection = new Collection("Marches", null, NOW);
        ReflectionTestUtils.setField(collection, "version", 2L);
        when(collectionRepository.findById(4L)).thenReturn(java.util.Optional.of(collection));

        assertThatThrownBy(() -> collectionService.update(actor, 4L,
                new UpdateCollectionRequest("Concerts", null, 1L)))
                .isInstanceOf(ConcurrentCollectionModificationException.class);

        assertThat(collection.getName()).isEqualTo("Marches");
        verifyNoInteractions(auditService);
    }

    @Test
    void updateNoOpDoesNotTouchOrAuditTheCollection() {
        UserAccount actor = adminActor();
        Collection collection = new Collection("Marches", "Brass", NOW.minusSeconds(60));
        ReflectionTestUtils.setField(collection, "version", 0L);
        when(collectionRepository.findById(4L)).thenReturn(java.util.Optional.of(collection));

        Collection result = collectionService.update(actor, 4L,
                new UpdateCollectionRequest("Marches", "Brass", 0L));

        assertThat(result.getUpdatedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(collectionRepository, org.mockito.Mockito.never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void deleteIsBlockedWhileTheCollectionContainsSheetMusic() {
        UserAccount actor = adminActor();
        Collection collection = new Collection("Marches", null, NOW);
        ReflectionTestUtils.setField(collection, "version", 0L);
        when(collectionRepository.findById(4L)).thenReturn(java.util.Optional.of(collection));
        when(sheetMusicRepository.existsByCollection(collection)).thenReturn(true);

        assertThatThrownBy(() -> collectionService.delete(actor, 4L, 0L))
                .isInstanceOf(CollectionInUseException.class);

        verify(collectionRepository, org.mockito.Mockito.never()).delete(any());
        verifyNoInteractions(auditService);
    }
}
