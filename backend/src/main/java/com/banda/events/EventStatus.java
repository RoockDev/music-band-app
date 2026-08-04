package com.banda.events;

/**
 * Section 7 (Calendar/Events): an {@link Event}'s lifecycle state. Cancellation is a distinct,
 * non-destructive terminal state (design doc/spec Sec.7 "Cancellation" scenario) — a cancelled
 * event is never deleted, only transitioned here, so its history/audit trail and its
 * visibility to scoped viewers are retained (they simply see the cancelled state instead).
 */
public enum EventStatus {
    SCHEDULED,
    CANCELLED
}
