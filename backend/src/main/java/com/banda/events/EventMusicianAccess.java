package com.banda.events;

import com.banda.users.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One musician's individually scoped access to one {@link Event} — the
 * {@code event_musician_access} explicit join table (Section 7). Per the union (OR) semantics
 * {@link EventAccessService#canAccess} implements, this is an access grant independent of
 * (and additive to) any group-based grant via {@link EventGroupAccess}. Copies
 * {@code SheetMusicianAccess}'s exact shape, per this PR's own task note.
 */
@Entity
@Table(name = "event_musician_access",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_musician_access_event_musician",
                columnNames = {"event_id", "musician_id"}))
public class EventMusicianAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "musician_id", nullable = false)
    private UserAccount musician;

    protected EventMusicianAccess() {
        // JPA
    }

    public EventMusicianAccess(Event event, UserAccount musician) {
        this.event = event;
        this.musician = musician;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public UserAccount getMusician() {
        return musician;
    }
}
