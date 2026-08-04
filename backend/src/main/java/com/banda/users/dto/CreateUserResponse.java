package com.banda.users.dto;

import com.banda.users.UserService;

/**
 * {@code activationToken} is the one-time raw value the admin must deliver to the new
 * user out-of-band (no {@code EmailSender} exists yet — see {@link UserService}'s class
 * Javadoc). It is returned exactly once, here, and never persisted or logged in raw form.
 */
public record CreateUserResponse(UserAccountResponse user, String activationToken) {

    public static CreateUserResponse from(UserService.CreateUserResult result) {
        return new CreateUserResponse(UserAccountResponse.from(result.user()), result.activationToken());
    }
}
