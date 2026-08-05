package com.banda.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Section 8 (Public Site Content) "Public visibility" scenario: events flagged
     * {@code isPublic}, for the unauthenticated public listing
     * ({@code com.banda.publicsite.PublicEventService}). Deliberately a plain
     * {@code isPublic}-only filter, independent of {@link Event#isAllScope()} or any
     * {@code event_group_access}/{@code event_musician_access} grant — see {@link Event}'s own
     * class Javadoc for why these two visibility concerns are orthogonal. This method must
     * never be consumed by the internal-calendar path ({@code EventService}/
     * {@code EventAccessService}), which has its own separate, actor-scoped access logic.
     */
    List<Event> findByIsPublicTrueOrderByStartsAtAsc();
}
