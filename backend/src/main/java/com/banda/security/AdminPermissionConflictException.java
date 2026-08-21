package com.banda.security;

/** A requested permission mutation would violate an administrative safety invariant. */
public class AdminPermissionConflictException extends RuntimeException {

    public AdminPermissionConflictException(String message) {
        super(message);
    }
}
