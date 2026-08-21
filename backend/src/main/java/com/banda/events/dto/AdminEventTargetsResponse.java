package com.banda.events.dto;

import com.banda.events.EventTargetCatalog;

import java.util.List;

/** Readable target choices authorized by the event-management permission. */
public record AdminEventTargetsResponse(List<Target> groups, List<Target> musicians) {

    public record Target(Long id, String label) {
    }

    public static AdminEventTargetsResponse from(EventTargetCatalog catalog) {
        List<Target> groups = catalog.groups().stream()
                .map(group -> new Target(group.getId(), group.getName()))
                .toList();
        List<Target> musicians = catalog.musicians().stream()
                .map(musician -> new Target(musician.getId(), musician.getEmail()))
                .toList();
        return new AdminEventTargetsResponse(groups, musicians);
    }
}
