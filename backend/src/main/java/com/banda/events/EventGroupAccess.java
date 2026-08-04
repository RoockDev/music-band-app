package com.banda.events;

import com.banda.groups.Group;
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
 * One {@link Group}'s scoped access to one {@link Event} — the {@code event_group_access}
 * explicit join table (Section 7, design doc's "Access joins (explicit, NOT polymorphic)").
 * Every musician in this group can see the event in the internal calendar, per the union (OR)
 * semantics {@link EventAccessService#canAccess} implements. Copies
 * {@code SheetGroupAccess}'s exact shape, per this PR's own task note.
 */
@Entity
@Table(name = "event_group_access",
        uniqueConstraints = @UniqueConstraint(name = "uk_event_group_access_event_group",
                columnNames = {"event_id", "group_id"}))
public class EventGroupAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    protected EventGroupAccess() {
        // JPA
    }

    public EventGroupAccess(Event event, Group group) {
        this.event = event;
        this.group = group;
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Group getGroup() {
        return group;
    }
}
