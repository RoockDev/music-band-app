package com.banda.auth.dto;

import com.banda.users.UserAccount;

public record CurrentUserResponse(Long id, String email, String role) {

    public static CurrentUserResponse from(UserAccount user) {
        return new CurrentUserResponse(user.getId(), user.getEmail(), user.getRole().name());
    }
}
