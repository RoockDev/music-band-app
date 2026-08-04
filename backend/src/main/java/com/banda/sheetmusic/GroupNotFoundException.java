package com.banda.sheetmusic;

/**
 * No {@code com.banda.groups.Group} exists for a group id an upload's group-access-scope
 * list referenced. Kept local to this feature package rather than reusing
 * {@code com.banda.groups.GroupNotFoundException}, following the exact "by feature" package
 * boundary rationale {@code com.banda.groups.MusicianNotFoundException} already established
 * (design decision #8): this feature's own controller should not couple its error handling
 * to another feature's exception type.
 */
public class GroupNotFoundException extends RuntimeException {

    public GroupNotFoundException(Long groupId) {
        super("Group not found: " + groupId);
    }
}
