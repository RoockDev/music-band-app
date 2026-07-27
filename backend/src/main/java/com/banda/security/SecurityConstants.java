package com.banda.security;

public final class SecurityConstants {

    /** httpOnly+Secure+SameSite cookie carrying the JWT. Never readable from JS. */
    public static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";

    private SecurityConstants() {
    }
}
