package com.banda.groups;

/** No {@link Group} exists for the requested id. */
public class GroupNotFoundException extends RuntimeException {

    public GroupNotFoundException(Long groupId) {
        super("Group not found: " + groupId);
    }
}
