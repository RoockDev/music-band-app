package com.banda.publicsite.dto;

import com.banda.events.Event;
import com.banda.events.EventStatus;

import java.time.Instant;

/**
 * Section 8 (Public Site Content) "Public visibility" scenario response shape. Deliberately
 * NOT {@code com.banda.events.dto.EventResponse}: that DTO exposes {@code isPublic}/
 * {@code allScope}, internal-calendar-only concepts a public visitor has no business seeing
 * (and {@code allScope} in particular would be a meaningless/confusing field on a response
 * meant for an unauthenticated audience). This is a separate, intentionally narrower shape for
 * a separate audience.
 */
public record PublicEventResponse(
        Long id,
        String title,
        String description,
        String location,
        Instant startsAt,
        EventStatus status
) {

    public static PublicEventResponse from(Event event) {
        return new PublicEventResponse(event.getId(), event.getTitle(), event.getDescription(),
                event.getLocation(), event.getStartsAt(), event.getStatus());
    }
}
