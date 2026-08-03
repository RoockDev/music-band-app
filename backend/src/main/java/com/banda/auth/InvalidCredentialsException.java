package com.banda.auth;

/** Wrong email, wrong password, or account not ACTIVE — kept indistinguishable to avoid
 * account enumeration. Never carries the submitted password. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
