package com.banda.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetResponseTimerTest {

    @Test
    void waitsOnlyForTheTimeRemainingAfterTheAction() {
        AtomicLong now = new AtomicLong(100);
        AtomicLong parkedFor = new AtomicLong();
        PasswordResetResponseTimer timer = new PasswordResetResponseTimer(
                Duration.ofNanos(100), now::get, nanos -> {
                    parkedFor.addAndGet(nanos);
                    now.addAndGet(nanos);
                });

        timer.run(() -> now.addAndGet(30));

        assertThat(parkedFor).hasValue(70);
        assertThat(now).hasValue(200);
    }

    @Test
    void doesNotWaitWhenTheActionAlreadyExceededTheMinimum() {
        AtomicLong now = new AtomicLong();
        AtomicLong parkedFor = new AtomicLong();
        PasswordResetResponseTimer timer = new PasswordResetResponseTimer(
                Duration.ofNanos(100), now::get, parkedFor::addAndGet);

        timer.run(() -> now.addAndGet(101));

        assertThat(parkedFor).hasValue(0);
    }

    @Test
    void enforcesTheMinimumAndThenPropagatesActionFailures() {
        AtomicLong now = new AtomicLong();
        AtomicLong parkedFor = new AtomicLong();
        PasswordResetResponseTimer timer = new PasswordResetResponseTimer(
                Duration.ofNanos(100), now::get, nanos -> {
                    parkedFor.addAndGet(nanos);
                    now.addAndGet(nanos);
                });

        assertThatThrownBy(() -> timer.run(() -> {
            now.addAndGet(40);
            throw new IllegalStateException("failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(parkedFor).hasValue(60);
    }
}
