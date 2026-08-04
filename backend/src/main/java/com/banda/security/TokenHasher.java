package com.banda.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hashes single-use tokens (activation/reset) before persistence, so a leaked database
 * dump alone can never be replayed to activate an account or reset a password. Shared
 * across features (auth now; future admin-driven user provisioning) — deliberately not
 * placed inside {@code com.banda.auth} to avoid a package dependency from users -> auth.
 */
public final class TokenHasher {

    private static final int RAW_TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TokenHasher() {
    }

    /**
     * Generates a new cryptographically random raw single-use token (256 bits of entropy,
     * URL-safe Base64 encoded — safe to embed directly in an emailed activation/reset
     * link). The caller is responsible for hashing it via {@link #sha256Hex} before
     * persistence and delivering the raw value to the user out-of-band; it is never stored
     * or logged in its raw form.
     */
    public static String generateRawToken() {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
