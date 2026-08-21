package com.banda.groups.dto;

import com.banda.groups.Group;

import java.time.Instant;

public record GroupResponse(
        Long id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {

    public static GroupResponse from(Group group) {
        return new GroupResponse(group.getId(), group.getName(), group.getDescription(),
                group.getCreatedAt(), group.getUpdatedAt(), group.getVersion());
    }
}
