package com.banda.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Applies a response-time floor to password-reset requests so the fast unknown-account
 * path cannot be distinguished from the normal active-account path with a single sample.
 */
@Component
final class PasswordResetResponseTimer {

    private final long minimumNanos;
    private final LongSupplier nanoTime;
    private final LongConsumer parkNanos;

    @Autowired
    PasswordResetResponseTimer(
            @Value("${app.auth.password-reset-min-response-time:PT1S}") Duration minimumResponseTime) {
        this(minimumResponseTime, System::nanoTime, LockSupport::parkNanos);
    }

    PasswordResetResponseTimer(Duration minimumResponseTime, LongSupplier nanoTime, LongConsumer parkNanos) {
        if (minimumResponseTime.isNegative()) {
            throw new IllegalArgumentException("Password-reset minimum response time must not be negative");
        }
        this.minimumNanos = minimumResponseTime.toNanos();
        this.nanoTime = nanoTime;
        this.parkNanos = parkNanos;
    }

    void run(Runnable action) {
        long startedAt = nanoTime.getAsLong();
        try {
            action.run();
        } finally {
            waitForMinimumDuration(startedAt);
        }
    }

    private void waitForMinimumDuration(long startedAt) {
        long remaining;
        while ((remaining = minimumNanos - (nanoTime.getAsLong() - startedAt)) > 0) {
            parkNanos.accept(remaining);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }
}
