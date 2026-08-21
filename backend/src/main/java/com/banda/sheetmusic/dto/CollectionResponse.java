package com.banda.sheetmusic.dto;

import com.banda.sheetmusic.Collection;

import java.time.Instant;

/** Mirrors {@code com.banda.groups.dto.GroupResponse}'s exact shape. */
public record CollectionResponse(
        Long id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {

    public static CollectionResponse from(Collection collection) {
        return new CollectionResponse(collection.getId(), collection.getName(), collection.getDescription(),
                collection.getCreatedAt(), collection.getUpdatedAt(), collection.getVersion());
    }
}
