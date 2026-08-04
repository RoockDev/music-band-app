package com.banda.users.dto;

import com.banda.users.UserAccount;
import com.banda.users.UserRole;
import com.banda.users.UserStatus;

import java.time.Instant;

/** Never exposes {@code passwordHash}, {@code tokenVersion}, or the JPA {@code version}. */
public record UserAccountResponse(
        Long id,
        String email,
        UserRole role,
        UserStatus status,
        boolean minor,
        String guardianContact,
        boolean consentOnFile,
        Instant createdAt,
        Instant updatedAt
) {

    public static UserAccountResponse from(UserAccount user) {
        return new UserAccountResponse(user.getId(), user.getEmail(), user.getRole(), user.getStatus(),
                user.isMinor(), user.getGuardianContact(), user.isConsentOnFile(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
