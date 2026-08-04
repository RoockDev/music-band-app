package com.banda.events;

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
 * Section 7 (Calendar/Events) internal-calendar union (OR) access-scoping logic — the exact
 * shape {@code SheetMusicAccessService} (PR7) established and this PR was explicitly asked to
 * copy: {@code actor} can access {@code event} if EITHER {@link Event#isAllScope()} is true,
 * OR the actor is individually scoped via {@code event_musician_access}, OR any group the
 * actor belongs to is scoped via {@code event_group_access}. This is the INTERNAL calendar
 * scoping only — {@link Event#isPublic()} is a separate, unrelated flag consumed by the
 * unauthenticated public site (Phase 8), never checked here.
 */
class EventAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private EventGroupAccessRepository eventGroupAccessRepository;
    private EventMusicianAccessRepository eventMusicianAccessRepository;
    private MusicianGroupRepository musicianGroupRepository;
    private EventAccessService accessService;

    private UserAccount musician;

    @BeforeEach
    void setUp() {
        eventGroupAccessRepository = mock(EventGroupAccessRepository.class);
        eventMusicianAccessRepository = mock(EventMusicianAccessRepository.class);
        musicianGroupRepository = mock(MusicianGroupRepository.class);
        accessService = new EventAccessService(eventGroupAccessRepository, eventMusicianAccessRepository,
                musicianGroupRepository);

        musician = new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
    }

    private Event event(boolean allScope) {
        return new Event("Spring Concert", null, null, NOW, false, allScope, NOW);
    }

    @Test
    void allScopeEventIsAccessibleToAnyoneWithoutCheckingAnyOtherGrant() {
        Event event = event(true);

        boolean result = accessService.canAccess(musician, event);

        assertThat(result).isTrue();
        verify(eventMusicianAccessRepository, never()).existsByEventAndMusician(any(), any());
        verify(eventGroupAccessRepository, never()).existsByEventAndGroupIn(any(), anyList());
    }

    @Test
    void nonAllScopeEventWithNoGrantAtAllIsDenied() {
        Event event = event(false);
        when(eventMusicianAccessRepository.existsByEventAndMusician(event, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(List.of());

        boolean result = accessService.canAccess(musician, event);

        assertThat(result).isFalse();
        verify(eventGroupAccessRepository, never()).existsByEventAndGroupIn(any(), anyList());
    }

    @Test
    void individuallyScopedMusicianIsGrantedAccessEvenWithoutAnyGroupMatch() {
        Event event = event(false);
        when(eventMusicianAccessRepository.existsByEventAndMusician(event, musician)).thenReturn(true);

        boolean result = accessService.canAccess(musician, event);

        assertThat(result).isTrue();
        verify(musicianGroupRepository, never()).findByMusician(any());
    }

    @Test
    void musicianInAGroupScopedToTheEventIsGrantedAccessEvenWithoutAnyIndividualGrant() {
        Event event = event(false);
        Group group = new Group("Brass Section", null, NOW);
        when(eventMusicianAccessRepository.existsByEventAndMusician(event, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(List.of(new MusicianGroup(musician, group)));
        when(eventGroupAccessRepository.existsByEventAndGroupIn(event, List.of(group))).thenReturn(true);

        boolean result = accessService.canAccess(musician, event);

        assertThat(result).isTrue();
    }

    @Test
    void musicianInGroupsNoneOfWhichAreScopedToTheEventIsDenied() {
        Event event = event(false);
        Group otherGroup = new Group("Choir", null, NOW);
        when(eventMusicianAccessRepository.existsByEventAndMusician(event, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(List.of(new MusicianGroup(musician, otherGroup)));
        when(eventGroupAccessRepository.existsByEventAndGroupIn(event, List.of(otherGroup))).thenReturn(false);

        boolean result = accessService.canAccess(musician, event);

        assertThat(result).isFalse();
    }

    @Test
    void groupCheckQueriesAllOfTheActorsGroupsAtOnceNotOneByOne() {
        Event event = event(false);
        Group groupA = new Group("Group A", null, NOW);
        Group groupB = new Group("Group B", null, NOW);
        when(eventMusicianAccessRepository.existsByEventAndMusician(event, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(
                List.of(new MusicianGroup(musician, groupA), new MusicianGroup(musician, groupB)));
        when(eventGroupAccessRepository.existsByEventAndGroupIn(eq(event), eq(List.of(groupA, groupB))))
                .thenReturn(true);

        boolean result = accessService.canAccess(musician, event);

        assertThat(result).isTrue();
        verify(eventGroupAccessRepository).existsByEventAndGroupIn(eq(event), eq(List.of(groupA, groupB)));
    }

    /** Section 7's "isPublic is a different audience" design decision: the internal-calendar
     * union check must never treat {@code isPublic} as an access grant — a private,
     * non-allScope, non-scoped event stays denied even though it exists, and a public event
     * with no internal scope grant at all is likewise NOT automatically visible in the
     * internal calendar (public-site visibility, Phase 8, is a wholly separate concern). */
    @Test
    void isPublicFlagIsNeverConsultedByTheInternalCalendarAccessCheck() {
        Event publicButUnscopedEvent = new Event("Open Rehearsal", null, null, NOW, true, false, NOW);
        when(eventMusicianAccessRepository.existsByEventAndMusician(publicButUnscopedEvent, musician)).thenReturn(false);
        when(musicianGroupRepository.findByMusician(musician)).thenReturn(List.of());

        boolean result = accessService.canAccess(musician, publicButUnscopedEvent);

        assertThat(result).isFalse();
    }
}
