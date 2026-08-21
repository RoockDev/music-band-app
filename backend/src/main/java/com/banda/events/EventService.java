package com.banda.events;

import com.banda.audit.AuditService;
import com.banda.events.dto.CreateEventRequest;
import com.banda.events.dto.UpdateEventRequest;
import com.banda.groups.GroupRepository;
import com.banda.security.Permission;
import com.banda.security.PermissionService;
import com.banda.users.UserAccount;
import com.banda.users.UserAccountRepository;
import com.banda.users.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * <p><b>{@link #listManaged} — the admin management catalog:</b> an admin holding
 * {@code MANAGE_EVENTS} must be able to find events created for groups or musicians even
 * when the admin is not personally in that access scope. Keeping this as a separate,
 * permission-gated read avoids weakening the musician calendar's IDOR-safe scope rules.
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
    private final GroupRepository groupRepository;
    private final UserAccountRepository userAccountRepository;
    private final Clock clock;

    public EventService(EventRepository eventRepository,
                         PermissionService permissionService,
                         AuditService auditService,
                         EventAccessService accessService,
                         EventAccessGrantService accessGrantService,
                         GroupRepository groupRepository,
                         UserAccountRepository userAccountRepository,
                         Clock clock) {
        this.eventRepository = eventRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.accessService = accessService;
        this.accessGrantService = accessGrantService;
        this.groupRepository = groupRepository;
        this.userAccountRepository = userAccountRepository;
        this.clock = clock;
    }

    public Event create(UserAccount actor, CreateEventRequest request) {
        permissionService.requirePermission(actor, Permission.MANAGE_EVENTS);

        EventAccessGrantService.ResolvedEventScope scope = accessGrantService.resolveAccessScope(
                request.groupIds(), request.musicianIds());

        Instant now = clock.instant();
        Event event = new Event(request.title(), request.description(), request.location(), request.startsAt(),
                request.isPublic(), request.allScope(), now);
        Event saved = eventRepository.saveAndFlush(event);

        accessGrantService.synchronizeAccessScope(saved, scope);

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
    public List<ManagedEvent> listManaged(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_EVENTS);
        return eventRepository.findAll().stream()
                .map(event -> new ManagedEvent(event, accessGrantService.getAccessScope(event)))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventTargetCatalog listTargets(UserAccount actor) {
        permissionService.requirePermission(actor, Permission.MANAGE_EVENTS);
        return new EventTargetCatalog(groupRepository.findAll(), userAccountRepository.findAll().stream()
                .filter(account -> account.getRole() == UserRole.MUSICIAN)
                .toList());
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

        requireVersion(event.getVersion(), request.version());
        EventAccessGrantService.ResolvedEventScope resolvedScope = accessGrantService.resolveAccessScope(
                request.groupIds(), request.musicianIds());
        EventAccessScope beforeScope = accessGrantService.getAccessScope(event);
        EventAccessScope afterScope = resolvedScope.toAccessScope();
        boolean beforeAllScope = event.isAllScope();
        List<String> changedFields = changedFields(event, request, beforeScope, afterScope);
        if (changedFields.isEmpty()) {
            return event;
        }

        // Scope rows are synchronized before dirtying the versioned event entity. Bulk join-row
        // deletes may trigger an automatic JPA flush; keeping the event clean until afterwards
        // ensures every optimistic-lock failure is translated by saveWithOptimisticLockHandling.
        accessGrantService.synchronizeAccessScope(event, resolvedScope);

        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setLocation(request.location());
        event.setStartsAt(request.startsAt());
        event.setPublic(request.isPublic());
        event.setAllScope(request.allScope());
        event.touch(clock.instant());

        saveWithOptimisticLockHandling(event);

        auditService.record(actor.getId(), "EVENT_UPDATED", "Event", eventId,
                auditDetails(changedFields, beforeAllScope, event.isAllScope(), beforeScope, afterScope));
        log.info("Event updated: {}", eventId);

        return event;
    }

    public Event cancel(UserAccount actor, Long eventId, Long version) {
        permissionService.requirePermission(actor, Permission.MANAGE_EVENTS);
        Event event = requireEvent(eventId);

        if (event.getStatus() == EventStatus.CANCELLED) {
            return event;
        }
        requireVersion(event.getVersion(), version);

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

    private void requireVersion(Long current, Long requested) {
        if (!Objects.equals(current, requested)) {
            throw new ConcurrentEventModificationException();
        }
    }

    private List<String> changedFields(Event event, UpdateEventRequest request,
                                       EventAccessScope beforeScope, EventAccessScope afterScope) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(event.getTitle(), request.title())) changed.add("title");
        if (!Objects.equals(event.getDescription(), request.description())) changed.add("description");
        if (!Objects.equals(event.getLocation(), request.location())) changed.add("location");
        if (!Objects.equals(event.getStartsAt(), request.startsAt())) changed.add("startsAt");
        if (event.isPublic() != request.isPublic()) changed.add("public");
        if (event.isAllScope() != request.allScope()
                || !Objects.equals(beforeScope.groupIds(), afterScope.groupIds())
                || !Objects.equals(beforeScope.musicianIds(), afterScope.musicianIds())) {
            changed.add("scope");
        }
        return changed;
    }

    private String auditDetails(List<String> fields, boolean beforeAllScope, boolean afterAllScope,
                                EventAccessScope before, EventAccessScope after) {
        String details = "changed=" + String.join(",", fields)
                + ";beforeScope={all=" + beforeAllScope + ",groups=" + before.groupIds().size()
                + ",musicians=" + before.musicianIds().size() + "}"
                + ";afterScope={all=" + afterAllScope + ",groups=" + after.groupIds().size() + ",musicians="
                + after.musicianIds().size() + "}";
        return details.length() <= 255 ? details : details.substring(0, 255);
    }
}
