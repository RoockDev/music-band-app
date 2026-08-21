package com.banda.users.dto;

import com.banda.security.Permission;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Effective explicit permission grants for one ADMIN account. */
public record AdminPermissionsResponse(Long userId, List<Permission> permissions) {

    public static AdminPermissionsResponse from(Long userId, Set<Permission> permissions) {
        return new AdminPermissionsResponse(userId,
                permissions.stream().sorted(Comparator.comparing(Enum::name)).toList());
    }
}
