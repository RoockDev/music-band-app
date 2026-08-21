package com.banda.events.dto;

import com.banda.events.Event;
import com.banda.events.EventStatus;
import com.banda.events.ManagedEvent;

import java.time.Instant;
import java.util.List;

/**
 * Management-only event representation. Target IDs and the optimistic-lock version are kept
 * out of {@link EventResponse}, which remains safe for scoped musician responses.
 */
public record AdminEventResponse(
        Long id,
        String title,
        String description,
        String location,
        Instant startsAt,
        boolean isPublic,
        boolean allScope,
        EventStatus status,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        List<Long> groupIds,
        List<Long> musicianIds
) {

    public static AdminEventResponse from(ManagedEvent managedEvent) {
        Event event = managedEvent.event();
        return new AdminEventResponse(event.getId(), event.getTitle(), event.getDescription(), event.getLocation(),
                event.getStartsAt(), event.isPublic(), event.isAllScope(), event.getStatus(), event.getCreatedAt(),
                event.getUpdatedAt(), event.getVersion(), managedEvent.scope().groupIds(),
                managedEvent.scope().musicianIds());
    }
}
