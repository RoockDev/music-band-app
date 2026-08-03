package com.banda.security;

/**
 * Thrown when an actor lacks the specific permission toggle required for a gated action —
 * independent of whether they hold the base ADMIN role (Sec.2 "Permission gate", Sec.10).
 *
 * <p>The exception message is diagnostic/log-oriented (it names the missing
 * {@link Permission} category) and must NOT be echoed verbatim in an HTTP response body —
 * doing so would leak the internal permission taxonomy to a partially-privileged caller. A
 * future exception handler (PR5+) should build a generic client-facing message (e.g.
 * "Forbidden") and use {@link #getPermission()} only for server-side logging.
 */
public class PermissionDeniedException extends RuntimeException {

    private final Permission permission;

    public PermissionDeniedException(Permission permission) {
        super("Missing required permission: " + permission);
        this.permission = permission;
    }

    public Permission getPermission() {
        return permission;
    }
}
