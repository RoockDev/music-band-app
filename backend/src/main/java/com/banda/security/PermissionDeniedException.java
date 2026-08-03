package com.banda.security;

/** Thrown when an actor lacks the specific permission toggle required for a gated action —
 * independent of whether they hold the base ADMIN role (Sec.2 "Permission gate", Sec.10). */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(Permission permission) {
        super("Missing required permission: " + permission);
    }
}
