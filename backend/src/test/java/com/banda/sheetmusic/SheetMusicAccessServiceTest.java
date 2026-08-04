package com.banda.sheetmusic;

import com.banda.groups.Group;
import com.banda.groups.MusicianGroup;
import com.banda.groups.MusicianGroupRepository;
import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Section 5/6 core deliverable: the union (OR) access-scoping logic every download/browse
 * decision rests on — {@code allScope} OR individually scoped via
 * {@code sheet_musician_access} OR any of the actor's groups scoped via
 * {@code sheet_group_access} (design doc's "IDOR-safety + authenticated PDF serving"
 * section). Written cleanly per this PR's own task note: PR8 (events) is very likely to
 * copy this exact shape for its own group/individual/public scoping.
 */
class SheetMusicAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private SheetGroupAccessRepository sheetGroupAccessRepository;
    private SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private MusicianGroupRepository musicianGroupRepository;
    private SheetMusicAccessService accessService;

    private Collection collection;
    private UserAccount musician;

    @BeforeEach
    void setUp() {
        sheetGroupAccessRepository = mock(SheetGroupAccessRepository.class);
        sheetMusicianAccessRepository = mock(SheetMusicianAccessRepository.class);
        musicianGroupRepository = mock(MusicianGroupRepository.class);
        accessService = new SheetMusicAccessService(sheetGroupAccessRepository, sheetMusicianAccessRepository,
                musicianGroupRepository);

        collection = new Collection("Marches", null, NOW);
        musician = new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
    }

    private SheetMusic sheetMusic(boolean allScope) {
        return new SheetMusic("Piece", null, collection, "key", "file.pdf", "application/pdf", allScope, NOW);
    }

    @Test
    void allScopePieceIsAccessibleToAnyoneWithoutCheckingAnyOtherGrant() {
        SheetMusic piece = sheetMusic(true);

        boolean result = accessService.canAccess(musician, piece);

        assertThat(result).isTrue();
        verify(sheetMusicianAccessRepository, never()).existsBySheetMusicAndMusician(any(), any());
        verify(sheetGroupAccessRepository, never()).existsBySheetMusicAndGroupIn(any(), anyList());
    }

    @Test
    void nonAllScopePieceWithNoGrantAtAllIsDenied() {
        SheetMusic piece = sheetMusic(false);
        when(sheetMusicianAccessRepository.existsBySheetMusicAndMusician(piece, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(List.of());

        boolean result = accessService.canAccess(musician, piece);

        assertThat(result).isFalse();
        verify(sheetGroupAccessRepository, never()).existsBySheetMusicAndGroupIn(any(), anyList());
    }

    @Test
    void individuallyScopedMusicianIsGrantedAccessEvenWithoutAnyGroupMatch() {
        SheetMusic piece = sheetMusic(false);
        when(sheetMusicianAccessRepository.existsBySheetMusicAndMusician(piece, musician)).thenReturn(true);

        boolean result = accessService.canAccess(musician, piece);

        assertThat(result).isTrue();
        verify(musicianGroupRepository, never()).findByMusician(any());
    }

    @Test
    void musicianInAGroupScopedToThePieceIsGrantedAccessEvenWithoutAnyIndividualGrant() {
        SheetMusic piece = sheetMusic(false);
        Group group = new Group("Brass Section", null, NOW);
        when(sheetMusicianAccessRepository.existsBySheetMusicAndMusician(piece, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(List.of(new MusicianGroup(musician, group)));
        when(sheetGroupAccessRepository.existsBySheetMusicAndGroupIn(piece, List.of(group))).thenReturn(true);

        boolean result = accessService.canAccess(musician, piece);

        assertThat(result).isTrue();
    }

    @Test
    void musicianInGroupsNoneOfWhichAreScopedToThePieceIsDenied() {
        SheetMusic piece = sheetMusic(false);
        Group otherGroup = new Group("Choir", null, NOW);
        when(sheetMusicianAccessRepository.existsBySheetMusicAndMusician(piece, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(List.of(new MusicianGroup(musician, otherGroup)));
        when(sheetGroupAccessRepository.existsBySheetMusicAndGroupIn(piece, List.of(otherGroup))).thenReturn(false);

        boolean result = accessService.canAccess(musician, piece);

        assertThat(result).isFalse();
    }

    @Test
    void groupCheckQueriesAllOfTheActorsGroupsAtOnceNotOneByOne() {
        SheetMusic piece = sheetMusic(false);
        Group groupA = new Group("Group A", null, NOW);
        Group groupB = new Group("Group B", null, NOW);
        when(sheetMusicianAccessRepository.existsBySheetMusicAndMusician(piece, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(
                List.of(new MusicianGroup(musician, groupA), new MusicianGroup(musician, groupB)));
        when(sheetGroupAccessRepository.existsBySheetMusicAndGroupIn(eq(piece), eq(List.of(groupA, groupB))))
                .thenReturn(true);

        boolean result = accessService.canAccess(musician, piece);

        assertThat(result).isTrue();
        verify(sheetGroupAccessRepository).existsBySheetMusicAndGroupIn(eq(piece), eq(List.of(groupA, groupB)));
    }
}
