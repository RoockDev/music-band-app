package com.banda.events;

import com.banda.audit.AuditService;
import com.banda.events.dto.CreateEventRequest;
import com.banda.events.dto.UpdateEventRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionDeniedException;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

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
 * Section 7 (Calendar/Events) CRUD + non-destructive cancellation, gated by
 * {@link Permission#MANAGE_EVENTS} independent of the base ADMIN role (Sec.2/Sec.10) and
 * audited on every mutation (Sec.11) — the "gate -> mutate -> audit" shape
 * {@code GroupService}/{@code SheetMusicService} established. {@link #list}/{@link #get} are
 * the internal-calendar read path: never gated by {@code MANAGE_EVENTS} (any authenticated
 * actor may view their own scoped calendar, mirroring {@code SheetMusicService#download}),
 * filtered/gated instead by {@link EventAccessService#canAccess}.
 */
class EventServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private EventRepository eventRepository;
    private PermissionService permissionService;
    private AuditService auditService;
    private EventAccessService accessService;
    private EventAccessGrantService accessGrantService;
    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        permissionService = mock(PermissionService.class);
        auditService = mock(AuditService.class);
        accessService = mock(EventAccessService.class);
        accessGrantService = mock(EventAccessGrantService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        eventService = new EventService(eventRepository, permissionService, auditService,
                accessService, accessGrantService, clock);

        when(eventRepository.saveAndFlush(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private UserAccount adminActor() {
        return new UserAccount("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE, NOW);
    }

    private UserAccount musicianActor() {
        return new UserAccount("musician@example.com", UserRole.MUSICIAN, UserStatus.ACTIVE, NOW);
    }

    private Event persistedEvent(Long id, boolean isPublic, boolean allScope) {
        Event event = new Event("Event " + id, null, null, NOW, isPublic, allScope, NOW);
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    // ---- create() ----

    @Test
    void createChecksTheManageEventsPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_EVENTS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_EVENTS);
        CreateEventRequest request = new CreateEventRequest("Title", null, null, NOW, false, false, null, null);

        assertThatThrownBy(() -> eventService.create(actor, request))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(eventRepository);
        verifyNoInteractions(auditService);
    }

    @Test
    void createPersistsAScheduledEventWithBothVisibilityFlagsAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        CreateEventRequest request = new CreateEventRequest("Spring Concert", "Annual show", "Town Hall",
                NOW, true, false, null, null);

        Event created = eventService.create(actor, request);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Spring Concert");
        assertThat(captor.getValue().isPublic()).isTrue();
        assertThat(captor.getValue().isAllScope()).isFalse();
        assertThat(captor.getValue().getStatus()).isEqualTo(EventStatus.SCHEDULED);
        assertThat(created.getStatus()).isEqualTo(EventStatus.SCHEDULED);
        verify(auditService).record(eq(actor.getId()), eq("EVENT_CREATED"), eq("Event"), any(), anyString());
    }

    @Test
    void createDelegatesAccessScopeApplicationToAccessGrantServiceWithTheSavedEntityAndRequestedScope() {
        UserAccount actor = adminActor();
        CreateEventRequest request = new CreateEventRequest("Title", null, null, NOW, false, false,
                List.of(5L), List.of(7L));

        Event created = eventService.create(actor, request);

        verify(accessGrantService).applyAccessScope(created, List.of(5L), List.of(7L));
    }

    @Test
    void createOnAFailedAccessScopeApplicationPropagatesTheExceptionAndNeverAudits() {
        UserAccount actor = adminActor();
        doThrow(new GroupNotFoundException(999L)).when(accessGrantService)
                .applyAccessScope(any(Event.class), eq(List.of(999L)), any());
        CreateEventRequest request = new CreateEventRequest("Title", null, null, NOW, false, false,
                List.of(999L), null);

        assertThatThrownBy(() -> eventService.create(actor, request))
                .isInstanceOf(GroupNotFoundException.class);

        verifyNoInteractions(auditService);
    }

    // ---- list() / get() -- internal calendar read path (Sec.7 "Scoped access") ----

    @Test
    void listReturnsOnlyEventsTheActorCanAccessRegardlessOfRole() {
        UserAccount actor = musicianActor();
        Event visible = persistedEvent(1L, false, true);
        Event hidden = persistedEvent(2L, false, false);
        when(eventRepository.findAll()).thenReturn(List.of(visible, hidden));
        when(accessService.canAccess(actor, visible)).thenReturn(true);
        when(accessService.canAccess(actor, hidden)).thenReturn(false);

        List<Event> result = eventService.list(actor);

        assertThat(result).containsExactly(visible);
    }

    /** Section 7 "Cancellation" scenario: a cancelled event stays visible to scoped viewers
     * with its cancelled status shown -- it is never filtered out of the internal calendar. */
    @Test
    void listIncludesCancelledEventsTheActorCanAccess() {
        UserAccount actor = musicianActor();
        Event cancelled = persistedEvent(3L, false, true);
        cancelled.cancel();
        when(eventRepository.findAll()).thenReturn(List.of(cancelled));
        when(accessService.canAccess(actor, cancelled)).thenReturn(true);

        List<Event> result = eventService.list(actor);

        assertThat(result).containsExactly(cancelled);
        assertThat(result.get(0).getStatus()).isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    void listDoesNotRequireAnyManageEventsPermissionCheck() {
        UserAccount actor = musicianActor();
        when(eventRepository.findAll()).thenReturn(List.of());

        eventService.list(actor);

        verifyNoInteractions(permissionService);
    }

    @Test
    void getReturnsTheEventWhenAccessible() {
        UserAccount actor = musicianActor();
        Event event = persistedEvent(10L, false, true);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(accessService.canAccess(actor, event)).thenReturn(true);

        Event result = eventService.get(actor, 10L);

        assertThat(result).isSameAs(event);
    }

    @Test
    void getOnAnUnknownEventThrowsEventNotFoundException() {
        UserAccount actor = musicianActor();
        when(eventRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.get(actor, 404L))
                .isInstanceOf(EventNotFoundException.class);
    }

    /** The IDOR-block scenario (Sec.2/Sec.7): an event that DOES exist but the actor cannot
     * access must fail EXACTLY like a nonexistent id -- same exception type, same message. */
    @Test
    void getWhenAccessIsDeniedThrowsTheSameEventNotFoundExceptionAsAnUnknownId() {
        UserAccount actor = musicianActor();
        Event event = persistedEvent(20L, false, false);
        when(eventRepository.findById(20L)).thenReturn(Optional.of(event));
        when(accessService.canAccess(actor, event)).thenReturn(false);

        assertThatThrownBy(() -> eventService.get(actor, 20L))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessage(new EventNotFoundException(20L).getMessage());
    }

    // ---- edit() ----

    @Test
    void editChecksTheManageEventsPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_EVENTS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_EVENTS);
        UpdateEventRequest request = new UpdateEventRequest("New Title", null, null, NOW, false, false);

        assertThatThrownBy(() -> eventService.edit(actor, 1L, request))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(eventRepository);
    }

    @Test
    void editUpdatesFieldsAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        Event event = persistedEvent(1L, false, false);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        UpdateEventRequest request = new UpdateEventRequest("Renamed", "New desc", "New place", NOW, true, true);

        Event updated = eventService.edit(actor, 1L, request);

        assertThat(updated.getTitle()).isEqualTo("Renamed");
        assertThat(updated.getDescription()).isEqualTo("New desc");
        assertThat(updated.getLocation()).isEqualTo("New place");
        assertThat(updated.isPublic()).isTrue();
        assertThat(updated.isAllScope()).isTrue();
        verify(auditService).record(eq(actor.getId()), eq("EVENT_UPDATED"), eq("Event"), eq(1L), anyString());
    }

    @Test
    void editOfAnUnknownEventThrowsEventNotFoundException() {
        UserAccount actor = adminActor();
        when(eventRepository.findById(404L)).thenReturn(Optional.empty());
        UpdateEventRequest request = new UpdateEventRequest("Title", null, null, NOW, false, false);

        assertThatThrownBy(() -> eventService.edit(actor, 404L, request))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void editOnAnOptimisticLockConflictThrowsConcurrentEventModificationException() {
        UserAccount actor = adminActor();
        Event event = persistedEvent(1L, false, false);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(eventRepository.saveAndFlush(any(Event.class))).thenThrow(new ObjectOptimisticLockingFailureException(Event.class, 1L));
        UpdateEventRequest request = new UpdateEventRequest("Title", null, null, NOW, false, false);

        assertThatThrownBy(() -> eventService.edit(actor, 1L, request))
                .isInstanceOf(ConcurrentEventModificationException.class);

        verifyNoInteractions(auditService);
    }

    // ---- cancel() -- the core deliverable: non-destructive cancellation (Sec.7) ----

    @Test
    void cancelChecksTheManageEventsPermissionBeforeDoingAnythingElse() {
        UserAccount actor = adminActor();
        doThrow(new PermissionDeniedException(Permission.MANAGE_EVENTS))
                .when(permissionService).requirePermission(actor, Permission.MANAGE_EVENTS);

        assertThatThrownBy(() -> eventService.cancel(actor, 1L))
                .isInstanceOf(PermissionDeniedException.class);

        verifyNoInteractions(eventRepository);
    }

    @Test
    void cancelTransitionsAScheduledEventToCancelledWithoutDeletingItAndWritesAnAuditRecord() {
        UserAccount actor = adminActor();
        Event event = persistedEvent(1L, false, true);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));

        Event cancelled = eventService.cancel(actor, 1L);

        assertThat(cancelled.getStatus()).isEqualTo(EventStatus.CANCELLED);
        assertThat(cancelled.getId()).isEqualTo(1L);
        verify(eventRepository, never()).delete(any());
        verify(eventRepository, never()).deleteById(any());
        verify(auditService).record(eq(actor.getId()), eq("EVENT_CANCELLED"), eq("Event"), eq(1L));
    }

    /** Idempotent: cancelling an already-cancelled event is a silent no-op -- no duplicate
     * audit entry, mirroring {@code UserService#deactivate}'s established idempotency rule. */
    @Test
    void cancelOnAnAlreadyCancelledEventIsIdempotentAndWritesNoDuplicateAuditRecord() {
        UserAccount actor = adminActor();
        Event event = persistedEvent(1L, false, true);
        event.cancel();
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));

        Event result = eventService.cancel(actor, 1L);

        assertThat(result.getStatus()).isEqualTo(EventStatus.CANCELLED);
        verifyNoInteractions(auditService);
        verify(eventRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelOfAnUnknownEventThrowsEventNotFoundException() {
        UserAccount actor = adminActor();
        when(eventRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.cancel(actor, 404L))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void cancelOnAnOptimisticLockConflictThrowsConcurrentEventModificationException() {
        UserAccount actor = adminActor();
        Event event = persistedEvent(1L, false, true);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(eventRepository.saveAndFlush(any(Event.class))).thenThrow(new ObjectOptimisticLockingFailureException(Event.class, 1L));

        assertThatThrownBy(() -> eventService.cancel(actor, 1L))
                .isInstanceOf(ConcurrentEventModificationException.class);

        verifyNoInteractions(auditService);
    }
}
