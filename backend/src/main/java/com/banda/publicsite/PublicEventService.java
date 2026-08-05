package com.banda.publicsite;

import com.banda.events.Event;
import com.banda.events.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Section 8 (Public Site Content) "Public visibility" scenario: the unauthenticated public
 * events listing. PR8 (events) deliberately persisted {@code Event#isPublic} but deferred
 * building this endpoint to this PR — see {@code Event}'s own class Javadoc.
 *
 * <p><b>Deliberately decoupled from the internal calendar.</b> This service depends ONLY on
 * {@link EventRepository} — never {@code EventAccessService}, {@code EventService}, or
 * {@code PermissionService}. It is a SEPARATE audience/endpoint from the authenticated
 * internal-calendar listing ({@code EventController#list}/{@code EventService#list}, gated by
 * {@code EventAccessService#canAccess}'s union group/individual scoping), not an extension of
 * it: a private event (regardless of any {@code allScope}/group/individual grant) is
 * completely absent here, and conversely a public event's internal-calendar scoping plays no
 * role in whether it appears here — {@link EventRepository#findByIsPublicTrueOrderByStartsAtAsc}
 * is a plain {@code isPublic}-only filter, full stop.
 */
@Service
@Transactional(readOnly = true)
public class PublicEventService {

    private final EventRepository eventRepository;

    public PublicEventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<Event> listPublicEvents() {
        return eventRepository.findByIsPublicTrueOrderByStartsAtAsc();
    }
}
