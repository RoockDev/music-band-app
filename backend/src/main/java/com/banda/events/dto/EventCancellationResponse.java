package com.banda.events.dto;

import com.banda.events.Event;
import com.banda.events.EventStatus;

import java.time.Instant;

/** Management-only cancellation result carrying the fresh optimistic-lock version. */
public record EventCancellationResponse(
        Long id,
        EventStatus status,
        Instant updatedAt,
        Long version
) {

    public static EventCancellationResponse from(Event event) {
        return new EventCancellationResponse(
                event.getId(), event.getStatus(), event.getUpdatedAt(), event.getVersion());
    }
}
