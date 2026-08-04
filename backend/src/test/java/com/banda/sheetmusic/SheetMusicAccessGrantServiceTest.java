package com.banda.sheetmusic;

import com.banda.groups.Group;
import com.banda.groups.GroupRepository;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Write-side counterpart to {@link SheetMusicAccessServiceTest}: validates and persists the
 * group/individual access-scope grants a Section 5 upload requests. Extracted out of
 * {@code SheetMusicServiceTest} together with {@link SheetMusicAccessGrantService} itself —
 * see that class's own Javadoc for the "why" of the split.
 */
class SheetMusicAccessGrantServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private SheetGroupAccessRepository sheetGroupAccessRepository;
    private SheetMusicianAccessRepository sheetMusicianAccessRepository;
    private GroupRepository groupRepository;
    private UserAccountRepository userAccountRepository;
    private SheetMusicAccessGrantService accessGrantService;

    private SheetMusic sheetMusic;

    @BeforeEach
    void setUp() {
        sheetGroupAccessRepository = mock(SheetGroupAccessRepository.class);
        sheetMusicianAccessRepository = mock(SheetMusicianAccessRepository.class);
        groupRepository = mock(GroupRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);

        accessGrantService = new SheetMusicAccessGrantService(sheetGroupAccessRepository,
                sheetMusicianAccessRepository, groupRepository, userAccountRepository);

        Collection collection = new Collection("Marches", null, NOW);
        sheetMusic = new SheetMusic("Title", null, collection, "storage-key",
                "f.pdf", "application/pdf", false, NOW);
    }

    // ---- group access scope ----

    @Test
    void appliesGroupAccessScopeForEveryGroupIdProvided() {
        Group group = new Group("Brass Section", null, NOW);
        ReflectionTestUtils.setField(group, "id", 5L);
        when(groupRepository.findById(5L)).thenReturn(Optional.of(group));

        accessGrantService.applyAccessScope(sheetMusic, List.of(5L), null);

        ArgumentCaptor<SheetGroupAccess> captor = ArgumentCaptor.forClass(SheetGroupAccess.class);
        verify(sheetGroupAccessRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getGroup()).isSameAs(group);
    }

    @Test
    void onAnUnknownGroupIdThrowsGroupNotFoundException() {
        when(groupRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessGrantService.applyAccessScope(sheetMusic, List.of(999L), null))
                .isInstanceOf(GroupNotFoundException.class);

        verifyNoInteractions(sheetGroupAccessRepository);
    }

    // ---- individual musician access scope ----

    @Test
    void appliesIndividualAccessScopeForEveryMusicianIdProvided() {
        UserAccount musician = new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(musician, "id", 7L);
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(musician));

        accessGrantService.applyAccessScope(sheetMusic, null, List.of(7L));

        ArgumentCaptor<SheetMusicianAccess> captor = ArgumentCaptor.forClass(SheetMusicianAccess.class);
        verify(sheetMusicianAccessRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMusician()).isSameAs(musician);
    }

    @Test
    void onAnUnknownMusicianIdThrowsMusicianNotFoundException() {
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessGrantService.applyAccessScope(sheetMusic, null, List.of(999L)))
                .isInstanceOf(MusicianNotFoundException.class);
    }

    @Test
    void rejectsANonMusicianIdInIndividualScopeTheSameWayAsANonexistentId() {
        UserAccount notAMusician = new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(notAMusician, "id", 8L);
        when(userAccountRepository.findById(8L)).thenReturn(Optional.of(notAMusician));

        assertThatThrownBy(() -> accessGrantService.applyAccessScope(sheetMusic, null, List.of(8L)))
                .isInstanceOf(MusicianNotFoundException.class);

        verify(sheetMusicianAccessRepository, never()).saveAndFlush(any());
    }

    // ---- no-ops ----

    @Test
    void nullGroupIdsAndMusicianIdsIsANoOp() {
        accessGrantService.applyAccessScope(sheetMusic, null, null);

        verifyNoInteractions(sheetGroupAccessRepository, sheetMusicianAccessRepository);
    }
}
