package com.banda.events.dto;

import com.banda.events.Event;
import com.banda.events.EventStatus;

import java.time.Instant;

public record EventResponse(
        Long id,
        String title,
        String description,
        String location,
        Instant startsAt,
        boolean isPublic,
        boolean allScope,
        EventStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.getId(), event.getTitle(), event.getDescription(), event.getLocation(),
                event.getStartsAt(), event.isPublic(), event.isAllScope(), event.getStatus(),
                event.getCreatedAt(), event.getUpdatedAt());
    }
}
