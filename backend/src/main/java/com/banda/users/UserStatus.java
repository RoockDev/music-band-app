package com.banda.users;

public enum UserStatus {
    /** Created by an admin, not yet activated via the emailed activation token. */
    PENDING,
    /** Activated and allowed to log in. */
    ACTIVE,
    /** Disabled by an admin; login is blocked but history is retained. */
    DEACTIVATED
}
