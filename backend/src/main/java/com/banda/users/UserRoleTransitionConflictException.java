package com.banda.users;

public class UserRoleTransitionConflictException extends RuntimeException {

    public UserRoleTransitionConflictException(long groupMemberships, long eventGrants, long sheetMusicGrants) {
        super("Musician cannot be promoted while dependencies remain: groupMemberships=" + groupMemberships
                + ", eventGrants=" + eventGrants + ", sheetMusicGrants=" + sheetMusicGrants
                + ". Remove these relationships before changing the role");
    }
}
