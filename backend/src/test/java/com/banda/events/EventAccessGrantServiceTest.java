package com.banda.events;

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
 * Write-side counterpart to {@link EventAccessServiceTest}: validates and persists the
 * group/individual internal-calendar access-scope grants an Section 7 event create requests.
 * Extracted from day one (unlike {@code SheetMusicService}, which grew to 11 constructor
 * params before this split was retrofitted) — copies
 * {@code SheetMusicAccessGrantServiceTest}'s exact shape per this PR's own task note.
 */
class EventAccessGrantServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private EventGroupAccessRepository eventGroupAccessRepository;
    private EventMusicianAccessRepository eventMusicianAccessRepository;
    private GroupRepository groupRepository;
    private UserAccountRepository userAccountRepository;
    private EventAccessGrantService accessGrantService;

    private Event event;

    @BeforeEach
    void setUp() {
        eventGroupAccessRepository = mock(EventGroupAccessRepository.class);
        eventMusicianAccessRepository = mock(EventMusicianAccessRepository.class);
        groupRepository = mock(GroupRepository.class);
        userAccountRepository = mock(UserAccountRepository.class);

        accessGrantService = new EventAccessGrantService(eventGroupAccessRepository,
                eventMusicianAccessRepository, groupRepository, userAccountRepository);

        event = new Event("Spring Concert", null, null, NOW, false, false, NOW);
    }

    // ---- group access scope ----

    @Test
    void appliesGroupAccessScopeForEveryGroupIdProvided() {
        Group group = new Group("Brass Section", null, NOW);
        ReflectionTestUtils.setField(group, "id", 5L);
        when(groupRepository.findById(5L)).thenReturn(Optional.of(group));

        accessGrantService.applyAccessScope(event, List.of(5L), null);

        ArgumentCaptor<EventGroupAccess> captor = ArgumentCaptor.forClass(EventGroupAccess.class);
        verify(eventGroupAccessRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getGroup()).isSameAs(group);
    }

    @Test
    void onAnUnknownGroupIdThrowsGroupNotFoundException() {
        when(groupRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessGrantService.applyAccessScope(event, List.of(999L), null))
                .isInstanceOf(GroupNotFoundException.class);

        verifyNoInteractions(eventGroupAccessRepository);
    }

    /**
     * Fix (WARNING — resilience+reliability): a duplicate group id in the same request used to
     * hit the {@code event_group_access} unique constraint on the second identical insert,
     * surfacing as an uncaught {@code DataIntegrityViolationException} mapped to a misleading
     * 503 by {@code GlobalExceptionHandler}. Deduping up front means exactly one grant row (and
     * one {@code saveAndFlush} call) per distinct group id, no matter how many times the caller
     * repeats it.
     */
    @Test
    void duplicateGroupIdsInTheSameRequestAreDedupedToASingleGrant() {
        Group group = new Group("Brass Section", null, NOW);
        ReflectionTestUtils.setField(group, "id", 5L);
        when(groupRepository.findById(5L)).thenReturn(Optional.of(group));

        accessGrantService.applyAccessScope(event, List.of(5L, 5L), null);

        verify(eventGroupAccessRepository).saveAndFlush(any());
    }

    // ---- individual musician access scope ----

    @Test
    void appliesIndividualAccessScopeForEveryMusicianIdProvided() {
        UserAccount musician = new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(musician, "id", 7L);
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(musician));

        accessGrantService.applyAccessScope(event, null, List.of(7L));

        ArgumentCaptor<EventMusicianAccess> captor = ArgumentCaptor.forClass(EventMusicianAccess.class);
        verify(eventMusicianAccessRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMusician()).isSameAs(musician);
    }

    @Test
    void onAnUnknownMusicianIdThrowsMusicianNotFoundException() {
        when(userAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessGrantService.applyAccessScope(event, null, List.of(999L)))
                .isInstanceOf(MusicianNotFoundException.class);
    }

    /** Symmetric to {@link #duplicateGroupIdsInTheSameRequestAreDedupedToASingleGrant} for the
     * {@code event_musician_access} unique constraint. */
    @Test
    void duplicateMusicianIdsInTheSameRequestAreDedupedToASingleGrant() {
        UserAccount musician = new UserAccount("musician-dup@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(musician, "id", 7L);
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(musician));

        accessGrantService.applyAccessScope(event, null, List.of(7L, 7L));

        verify(eventMusicianAccessRepository).saveAndFlush(any());
    }

    @Test
    void rejectsANonMusicianIdInIndividualScopeTheSameWayAsANonexistentId() {
        UserAccount notAMusician = new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
        ReflectionTestUtils.setField(notAMusician, "id", 8L);
        when(userAccountRepository.findById(8L)).thenReturn(Optional.of(notAMusician));

        assertThatThrownBy(() -> accessGrantService.applyAccessScope(event, null, List.of(8L)))
                .isInstanceOf(MusicianNotFoundException.class);

        verify(eventMusicianAccessRepository, never()).saveAndFlush(any());
    }

    // ---- no-ops ----

    @Test
    void nullGroupIdsAndMusicianIdsIsANoOp() {
        accessGrantService.applyAccessScope(event, null, null);

        verifyNoInteractions(eventGroupAccessRepository, eventMusicianAccessRepository);
    }
}
