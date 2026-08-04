package com.banda.users;

/** No {@link UserAccount} exists for the requested id. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long userId) {
        super("User not found: " + userId);
    }
}
