package com.banda.security;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TokenHasher#generateRawToken()} is the raw-token half of the activation/reset
 * token mechanism used by {@code AuthService} (reset) and {@code UserService} (activation,
 * PR 5 — first admin-driven caller, exactly as this class's own Javadoc anticipated).
 */
class TokenHasherTest {

    @Test
    void generateRawTokenProducesANonBlankUrlSafeValue() {
        String token = TokenHasher.generateRawToken();

        assertThat(token).isNotBlank();
        // URL-safe Base64 alphabet only: letters, digits, '-' and '_' — never '+' or '/'
        // (a raw token may end up embedded in an emailed activation link).
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generateRawTokenProducesADifferentValueOnEachCall() {
        Set<String> tokens = new HashSet<>();
        IntStream.range(0, 50).forEach(i -> tokens.add(TokenHasher.generateRawToken()));

        // 50 independent SecureRandom draws colliding would indicate a broken generator,
        // not bad luck.
        assertThat(tokens).hasSize(50);
    }
}
