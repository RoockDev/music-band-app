package com.banda.events;

import com.banda.audit.AuditService;
import com.banda.events.dto.CreateEventRequest;
import com.banda.events.dto.UpdateEventRequest;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Section 7 (Calendar/Events) use cases: admin-driven CRUD over {@link Event} plus
 * non-destructive cancellation, gated by {@link Permission#MANAGE_EVENTS} independent of the
 * base ADMIN role (Sec.2/Sec.10), and audited on every mutation (Sec.11) — the exact
 * "gate -> mutate -> audit" shape {@code GroupService}/{@code SheetMusicService} established.
 *
 * <p>{@code actor} is trusted as-is: callers (currently {@link EventController}) MUST resolve
 * it from the authenticated principal, never from client-supplied request data.
 *
 * <p><b>{@link #list}/{@link #get} — the internal calendar (Sec.7 "Scoped access" scenario):
 * </b> unlike {@link #create}/{@link #edit}/{@link #cancel}, these are NOT gated by
 * {@code MANAGE_EVENTS} — any authenticated actor (MUSICIAN or ADMIN) may view their own
 * scoped calendar, mirroring {@code SheetMusicService#download}'s identical "any authenticated
 * user, per-resource authz in the service layer" contract. {@link #get} throws the same
 * {@link EventNotFoundException} whether the id doesn't exist or the actor simply cannot
 * access it (IDOR-safe 404, not 403 — see {@link EventNotFoundException}'s own Javadoc),
 * copying {@code SheetMusicService#download}'s exact pattern per this PR's own task note.
 * {@link #list} filters {@code eventRepository.findAll()} through
 * {@link EventAccessService#canAccess} rather than pushing the union logic into a repository
 * query — the same acceptable-at-this-scale tradeoff {@code SheetMusicAccessService} already
 * made (a handful of events/pieces, not a paginated firehose).
 *
 * <p><b>Cancellation as a non-destructive terminal state (Sec.7 "Cancellation" scenario):
 * </b> {@link #cancel} transitions {@link Event#getStatus()} to {@link EventStatus#CANCELLED}
 * in place — it never deletes the row, so {@link #list}/{@link #get} keep returning it to
 * every viewer who could already see it, now showing the cancelled state. There is
 * deliberately no hard-delete endpoint in this PR: cancellation IS the spec's own
 * lifecycle end-state for an event that will no longer happen, per Section 7's literal
 * wording ("cancellation as a distinct non-destructive state"). Idempotent: cancelling an
 * already-cancelled event is a silent no-op (no duplicate audit entry), mirroring
 * {@code UserService#deactivate}'s established idempotency rule.
 */
@Service
@Transactional
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final EventAccessService accessService;
    private final EventAccessGrantService accessGrantService;
    private final Clock clock;

    public EventService(EventRepository eventRepository,
                         PermissionService permissionService,
                         AuditService auditService,
                         EventAccessService accessService,
                         EventAccessGrantService accessGrantService,
                         Clock clock) {
        this.eventRepository = eventRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.accessService = accessService;
        this.accessGrantService = accessGrantService;
        this.clock = clock;
    }

    public Event create(UserAccount actor, CreateEventRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_EVENTS);

        Instant now = clock.instant();
        Event event = new Event(request.title(), request.description(), request.location(), request.startsAt(),
                request.isPublic(), request.allScope(), now);
        Event saved = eventRepository.saveAndFlush(event);

        accessGrantService.applyAccessScope(saved, request.groupIds(), request.musicianIds());

        auditService.record(actor.getId(), "EVENT_CREATED", "Event", saved.getId(), "title=" + request.title());
        log.info("Event created: {}", saved.getId());

        return saved;
    }

    @Transactional(readOnly = true)
    public List<Event> list(UserAccount actor) {
        return eventRepository.findAll().stream()
                .filter(event -> accessService.canAccess(actor, event))
                .toList();
    }

    @Transactional(readOnly = true)
    public Event get(UserAccount actor, Long eventId) {
        Event event = requireEvent(eventId);
        if (!accessService.canAccess(actor, event)) {
            throw new EventNotFoundException(eventId);
        }
        return event;
    }

    public Event edit(UserAccount actor, Long eventId, UpdateEventRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_EVENTS);
        Event event = requireEvent(eventId);

        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setLocation(request.location());
        event.setStartsAt(request.startsAt());
        event.setPublic(request.isPublic());
        event.setAllScope(request.allScope());
        event.touch(clock.instant());

        saveWithOptimisticLockHandling(event);

        auditService.record(actor.getId(), "EVENT_UPDATED", "Event", eventId, "title=" + request.title());
        log.info("Event updated: {}", eventId);

        return event;
    }

    public Event cancel(UserAccount actor, Long eventId) {
        permissionService.requirePermission(actor, Permission.MANAGE_EVENTS);
        Event event = requireEvent(eventId);

        if (event.getStatus() == EventStatus.CANCELLED) {
            return event;
        }

        event.cancel();
        event.touch(clock.instant());
        saveWithOptimisticLockHandling(event);

        auditService.record(actor.getId(), "EVENT_CANCELLED", "Event", eventId);
        log.info("Event cancelled: {}", eventId);

        return event;
    }

    private void saveWithOptimisticLockHandling(Event event) {
        try {
            // saveAndFlush (not save): forces the @Version check to happen NOW, mirroring
            // GroupService#edit's established optimistic-locking pattern.
            eventRepository.saveAndFlush(event);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentEventModificationException();
        }
    }

    private Event requireEvent(Long eventId) {
        return eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    }
}
