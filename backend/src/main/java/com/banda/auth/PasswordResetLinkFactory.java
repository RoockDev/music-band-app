package com.banda.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Builds reset links from an explicitly configured, validated frontend origin. */
@Component
class PasswordResetLinkFactory {

    private static final Set<String> LOCAL_HTTP_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    private final URI frontendOrigin;

    PasswordResetLinkFactory(@Value("${app.frontend.base-url}") String configuredBaseUrl) {
        this.frontendOrigin = requireSafeOrigin(configuredBaseUrl);
    }

    String create(String rawToken) {
        return UriComponentsBuilder.fromUri(frontendOrigin)
                .path("/restablecer")
                .queryParam("token", rawToken)
                .build()
                .encode()
                .toUriString();
    }

    private static URI requireSafeOrigin(String configuredBaseUrl) {
        URI candidate;
        try {
            candidate = URI.create(configuredBaseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("app.frontend.base-url must be a valid absolute URL", e);
        }

        String scheme = candidate.getScheme() == null ? "" : candidate.getScheme().toLowerCase(Locale.ROOT);
        String host = candidate.getHost();
        boolean localHttp = "http".equals(scheme) && host != null
                && LOCAL_HTTP_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        boolean safeScheme = "https".equals(scheme) || localHttp;
        boolean originOnly = host != null
                && candidate.getUserInfo() == null
                && (candidate.getPath().isEmpty() || "/".equals(candidate.getPath()))
                && candidate.getQuery() == null
                && candidate.getFragment() == null;

        if (!safeScheme || !originOnly) {
            throw new IllegalArgumentException(
                    "app.frontend.base-url must be an HTTPS origin (HTTP is allowed only for loopback development)");
        }

        try {
            return new URI(scheme, null, host, candidate.getPort(), null, null, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("app.frontend.base-url must be a valid frontend origin", e);
        }
    }
}
