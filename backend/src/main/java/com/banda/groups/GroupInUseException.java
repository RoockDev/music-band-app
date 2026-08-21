package com.banda.groups;

/**
 * Section 4 "Delete in-use group" scenario: an admin attempted to delete a {@link Group}
 * that still has musician members. Per design decision #7 ("Block (409 Conflict), require
 * empty/reassign first"), the delete is rejected outright — no silent cascade, no orphaned
 * {@code musician_group} rows. The admin must {@link GroupService#unassignMusician} every
 * member first, then retry the delete.
 */
public class GroupInUseException extends RuntimeException {

    public GroupInUseException(long musicianMemberships, long eventGrants, long sheetMusicGrants) {
        super("Group cannot be deleted while dependencies remain: musicianMemberships=" + musicianMemberships
                + ", eventGrants=" + eventGrants + ", sheetMusicGrants=" + sheetMusicGrants
                + ". Remove memberships and resource grants before retrying");
    }

    public GroupInUseException() {
        this(0, 0, 0);
    }
}
