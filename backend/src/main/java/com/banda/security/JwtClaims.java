package com.banda.security;

/** Parsed, signature-verified, non-expired claims extracted from an access token. */
public record JwtClaims(Long userId, long tokenVersion, String role) {
}
