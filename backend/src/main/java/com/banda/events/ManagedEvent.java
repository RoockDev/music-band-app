package com.banda.events;

/** Event plus its complete administrative access scope. */
public record ManagedEvent(Event event, EventAccessScope scope) {
}
