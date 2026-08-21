package com.banda.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Section 7 (Calendar/Events): a single Concert/Event entity carrying TWO independent
 * visibility concerns, per the design's explicit split:
 *
 * <ul>
 *   <li>{@link #isPublic}: whether this event appears on the unauthenticated PUBLIC site
 *       listing (Sec.8, built in Phase 8/PR9 — this PR only persists the flag).</li>
 *   <li>{@link #allScope} (+ {@code event_group_access}/{@code event_musician_access}, not
 *       modeled here directly; see {@link EventGroupAccess}/{@link EventMusicianAccess}):
 *       whether/who among authenticated musicians sees this event in the INTERNAL calendar,
 *       via the same union (OR) semantics {@link EventAccessService#canAccess} implements —
 *       the exact shape {@code SheetMusic#isAllScope()}/{@code SheetMusicAccessService}
 *       (PR7) established.</li>
 * </ul>
 *
 * <p>These two flags are orthogonal: a private (not public) event can still be fully visible
 * internally via {@code allScope}/group/individual grants, and a public event has no bearing
 * on internal-calendar visibility at all — {@link EventAccessService#canAccess} never
 * consults {@link #isPublic}.
 *
 * <p>{@link #status}: see {@link EventStatus}'s own Javadoc for the non-destructive
 * cancellation rationale.
 */
@Entity
@Table(name = "event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Column
    private String location;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @Column(name = "all_scope", nullable = false)
    private boolean allScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock — guards concurrent {@link EventService#edit}/{@link EventService#cancel}
     * from silently clobbering another admin's in-flight update, the same pattern
     * {@code Group}/{@code SheetMusic}'s own {@code @Version} fields established. */
    @Version
    @Column(name = "version")
    private Long version;

    protected Event() {
        // JPA
    }

    public Event(String title, String description, String location, Instant startsAt,
                 boolean isPublic, boolean allScope, Instant now) {
        this.title = title;
        this.description = description;
        this.location = location;
        this.startsAt = startsAt;
        this.isPublic = isPublic;
        this.allScope = allScope;
        this.status = EventStatus.SCHEDULED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public boolean isAllScope() {
        return allScope;
    }

    /** Global scope is updated atomically with group and musician grants by {@link EventService}. */
    public void setAllScope(boolean allScope) {
        this.allScope = allScope;
    }

    public EventStatus getStatus() {
        return status;
    }

    /** Section 7 "Cancellation" scenario: transitions to {@link EventStatus#CANCELLED} in
     * place — never removes the row. See {@link EventService#cancel} for the idempotency
     * contract around this call. */
    public void cancel() {
        this.status = EventStatus.CANCELLED;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public Long getVersion() {
        return version;
    }
}
