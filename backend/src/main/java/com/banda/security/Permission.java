package com.banda.security;

/**
 * Section 2 (Authorization/RBAC) / Section 10 (Admin Panel Permission-Gated Actions):
 * the individually toggleable action categories an ADMIN account may additionally be
 * granted, on top of the base ADMIN role. Holding the ADMIN role alone never implies any
 * of these — see {@link PermissionService#requirePermission}.
 *
 * <p>Categories line up with the mutating action surfaces later phases gate: user/musician
 * management (PR 5), groups (PR 6), sheet music (PR 7), and events (PR 8), plus public site
 * content (PR 9). The contact form (PR 10) has no gated admin mutation per spec Section 9
 * (public submission + notify-all-admins only), so it has no corresponding category here.
 *
 * <p>{@link #MANAGE_ADMIN_ROLES} is deliberately separate from {@link #MANAGE_USERS}:
 * {@code MANAGE_USERS} gates ordinary user/musician profile CRUD, never the power to mint
 * new admins or change an existing account's role. Any actor changing a target's {@code role}
 * (via {@code UserService#create}/{@code #edit}) MUST additionally hold this permission —
 * see {@code UserService} for the exact gate.
 */
public enum Permission {
    MANAGE_USERS,
    MANAGE_ADMIN_ROLES,
    MANAGE_GROUPS,
    MANAGE_SHEET_MUSIC,
    MANAGE_EVENTS,
    MANAGE_CONTENT
}
