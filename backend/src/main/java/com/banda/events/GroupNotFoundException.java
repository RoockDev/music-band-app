package com.banda.events;

/**
 * No {@code com.banda.groups.Group} exists for a group id an event's group-access-scope list
 * referenced. Kept local to this feature package rather than reusing
 * {@code com.banda.groups.GroupNotFoundException}, following the exact "by feature" package
 * boundary rationale {@code com.banda.sheetmusic.GroupNotFoundException} already established
 * (design decision #8).
 */
public class GroupNotFoundException extends RuntimeException {

    public GroupNotFoundException(Long groupId) {
        super("Group not found: " + groupId);
    }
}
