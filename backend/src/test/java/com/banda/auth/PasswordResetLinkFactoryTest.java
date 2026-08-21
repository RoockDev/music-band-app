package com.banda.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetLinkFactoryTest {

    @Test
    void buildsResetPathFromConfiguredHttpsOrigin() {
        PasswordResetLinkFactory factory = new PasswordResetLinkFactory("https://music.example:8443");

        assertThat(factory.create("url_safe-token"))
                .isEqualTo("https://music.example:8443/restablecer?token=url_safe-token");
    }

    @Test
    void permitsPlainHttpOnlyForLoopbackDevelopment() {
        assertThat(new PasswordResetLinkFactory("http://localhost:4200").create("token"))
                .isEqualTo("http://localhost:4200/restablecer?token=token");
        assertThatThrownBy(() -> new PasswordResetLinkFactory("http://music.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCredentialsPathsQueriesAndFragmentsInConfiguredOrigin() {
        assertThatThrownBy(() -> new PasswordResetLinkFactory("https://user@music.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetLinkFactory("https://music.example/app"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetLinkFactory("https://music.example?redirect=evil"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetLinkFactory("https://music.example#fragment"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
