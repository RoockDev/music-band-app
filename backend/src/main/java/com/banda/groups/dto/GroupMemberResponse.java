package com.banda.groups.dto;

import com.banda.users.UserAccount;
import com.banda.users.UserRole;

/**
 * Minimal, {@code Permission#MANAGE_GROUPS}-scoped view of a group member: {@code id},
 * {@code email}, {@code role} only. Deliberately does NOT reuse
 * {@code com.banda.users.dto.UserAccountResponse} (which also serializes {@code status},
 * {@code minor}, {@code guardianContact}, {@code consentOnFile}): everywhere else in the
 * codebase that richer DTO sits behind {@code Permission#MANAGE_USERS}, and reusing it here
 * would let any actor holding only {@code MANAGE_GROUPS} (never {@code MANAGE_USERS}) read
 * {@code MANAGE_USERS}-domain PII — including guardian contact info for minors — through
 * {@code GET /api/groups/{id}/musicians}.
 */
public record GroupMemberResponse(
        Long id,
        String email,
        UserRole role
) {

    public static GroupMemberResponse from(UserAccount member) {
        return new GroupMemberResponse(member.getId(), member.getEmail(), member.getRole());
    }
}
