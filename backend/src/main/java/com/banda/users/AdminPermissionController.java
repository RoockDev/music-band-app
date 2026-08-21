package com.banda.users;

import com.banda.security.AdminPermissionConflictException;
import com.banda.security.AdminPermissionManagementService;
import com.banda.security.Permission;
import com.banda.users.dto.AdminPermissionsResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** HTTP contract for explicit ADMIN permission grants. */
@RestController
@RequestMapping("/api/users/{userId}/permissions")
public class AdminPermissionController {

    private final AdminPermissionManagementService permissionManagementService;

    public AdminPermissionController(AdminPermissionManagementService permissionManagementService) {
        this.permissionManagementService = permissionManagementService;
    }

    @GetMapping
    public AdminPermissionsResponse get(@AuthenticationPrincipal UserAccount actor,
                                        @PathVariable Long userId) {
        return AdminPermissionsResponse.from(userId,
                permissionManagementService.getPermissions(actor, userId));
    }

    @PutMapping("/{permission}")
    public AdminPermissionsResponse grant(@AuthenticationPrincipal UserAccount actor,
                                          @PathVariable Long userId,
                                          @PathVariable Permission permission) {
        return AdminPermissionsResponse.from(userId,
                permissionManagementService.grant(actor, userId, permission));
    }

    @DeleteMapping("/{permission}")
    public AdminPermissionsResponse revoke(@AuthenticationPrincipal UserAccount actor,
                                           @PathVariable Long userId,
                                           @PathVariable Permission permission) {
        return AdminPermissionsResponse.from(userId,
                permissionManagementService.revoke(actor, userId, permission));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found"));
    }

    @ExceptionHandler(AdminPermissionConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(AdminPermissionConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
